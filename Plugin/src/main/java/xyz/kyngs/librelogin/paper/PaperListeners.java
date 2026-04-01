/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package xyz.kyngs.librelogin.paper;

import static xyz.kyngs.librelogin.paper.protocol.ProtocolUtil.getServerVersion;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.util.reflection.Reflection;
import com.github.retrooper.packetevents.wrapper.login.client.WrapperLoginClientEncryptionResponse;
import com.github.retrooper.packetevents.wrapper.login.client.WrapperLoginClientLoginStart;
import com.github.retrooper.packetevents.wrapper.login.server.WrapperLoginServerDisconnect;
import com.github.retrooper.packetevents.wrapper.login.server.WrapperLoginServerEncryptionRequest;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.retrooper.packetevents.util.SpigotReflectionUtil;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.*;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.crypto.*;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.spigotmc.event.player.PlayerSpawnLocationEvent;
import xyz.kyngs.librelogin.api.database.User;
import xyz.kyngs.librelogin.common.AuthenticLibreLogin;
import xyz.kyngs.librelogin.common.config.ConfigurationKeys;
import xyz.kyngs.librelogin.common.config.MessageKeys;
import xyz.kyngs.librelogin.common.listener.AuthenticListeners;
import xyz.kyngs.librelogin.common.util.GeneralUtil;
import xyz.kyngs.librelogin.paper.protocol.ClientPublicKey;
import xyz.kyngs.librelogin.paper.protocol.EncryptionUtil;
import xyz.kyngs.librelogin.paper.protocol.ProtocolUtil;
import xyz.kyngs.librelogin.paper.util.PlayerDataReader;

/**
 * Coordinates Paper-specific login handling, including spawn routing, Floodgate interop, and the
 * encrypted premium authentication handshake.
 */
public class PaperListeners extends AuthenticListeners<PaperLibreLogin, Player, World>
        implements Listener {

    private static final String LEGACY_ENCRYPTION_CLASS_NAME = "MinecraftEncryption";
    private static final Class<?> LEGACY_ENCRYPTION_CLASS;
    private static Method encryptionBootstrapMethod;
    private static Method cipherFactoryMethod;
    private static final boolean REQUIRES_LEGACY_ENCRYPTION_CLASS
            = getServerVersion().isOlderThan(ServerVersion.V_1_21_11);

    static {
        if (REQUIRES_LEGACY_ENCRYPTION_CLASS) {
            try {
                LEGACY_ENCRYPTION_CLASS =
                        Class.forName("net.minecraft.util." + LEGACY_ENCRYPTION_CLASS_NAME);
            } catch (ClassNotFoundException e) {
                throw new RuntimeException(e);
            }
        } else {
            LEGACY_ENCRYPTION_CLASS = null;
        }
    }

    // Runtime detection for AsyncPlayerSpawnLocationEvent availability
    private static final boolean HAS_ASYNC_SPAWN_EVENT;

    static {
        boolean hasAsyncEvent = false;
        try {
            Class.forName("io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent");
            hasAsyncEvent = true;
        } catch (ClassNotFoundException ignored) {
        }
        HAS_ASYNC_SPAWN_EVENT = hasAsyncEvent;
    }

    private final KeyPair keyPair = EncryptionUtil.createKeyPair();
    private final Random entropySource = new SecureRandom();
    private final Cache<String, EncryptionData> pendingEncryptionSessions
            = Caffeine.newBuilder().expireAfterWrite(2, TimeUnit.MINUTES).build();
    private final FloodgateHelper floodgateSupport;
    private final Cache<UUID, String> addressCache;
    private final Cache<UUID, User> preloadedUserCache;
    private final Cache<UUID, Location> deferredSpawnCache;
    private final Cache<UUID, SkinData> verifiedSkinCache;

    public PaperListeners(PaperLibreLogin plugin) {
        super(plugin);

        floodgateSupport = this.plugin.floodgateEnabled() ? new FloodgateHelper() : null;

        addressCache = Caffeine.newBuilder().expireAfterWrite(2, TimeUnit.MINUTES).build();

        preloadedUserCache = Caffeine.newBuilder().expireAfterWrite(2, TimeUnit.MINUTES).build();

        deferredSpawnCache = Caffeine.newBuilder().expireAfterWrite(2, TimeUnit.MINUTES).build();

        verifiedSkinCache = Caffeine.newBuilder().expireAfterWrite(2, TimeUnit.MINUTES).build();
    }

    /**
     * Exposes the cached post-authentication spawn destination for players currently passing
     * through limbo.
     *
     * @return cached destination map keyed by player UUID
     */
    public Cache<UUID, Location> getSpawnLocationCache() {
        return deferredSpawnCache;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        // checking is done here instead of in AsyncPlayerSpawnLocationEvent's handler
        // cuz there is no (Player) object available in that event
        var player = event.getPlayer();
        if (player.getHealth() == 0) {
            player.setHealth(player.getMaxHealth());
        }
        GeneralUtil.runAsync(() -> onPlayerDisconnect(event.getPlayer()));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPostLogin(PlayerLoginEvent event) {
        addressCache.put(event.getPlayer().getUniqueId(), event.getAddress().getHostAddress());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        var data = preloadedUserCache.getIfPresent(event.getPlayer().getUniqueId());
        if (data == null && !plugin.fromFloodgate(event.getPlayer().getName())) {
            event.getPlayer().kick(Component.text("Internal error, please try again later."));
            return;
        }
        preloadedUserCache.invalidate(event.getPlayer().getUniqueId());
        onPostLogin(event.getPlayer(), data);
        plugin.stashMountForLimbo(event.getPlayer());
        Bukkit.getScheduler()
                .runTask(plugin.getBootstrap(), () -> plugin.stashMountForLimbo(event.getPlayer()));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (plugin.fromFloodgate(event.getName())) {
            return;
        }

        var user = plugin.getDatabaseProvider().getByName(event.getName());

        // For premium players, use their Mojang UUID (premiumUUID) to preserve player data
        UUID profileUuid = user.getPremiumUUID() != null ? user.getPremiumUUID() : user.getUuid();
        var newProfile = Bukkit.createProfileExact(profileUuid, event.getName());

        // Apply skin if available (from previous session verification)
        var skin = verifiedSkinCache.getIfPresent(profileUuid);
        if (skin != null && skin.value() != null) {
            try {
                // Set the full texture property with signature - this is required for
                // other players to see the skin. Without the signature, only the local
                // player sees their skin because clients don't trust unsigned textures.
                var property
                        = new com.destroystokyo.paper.profile.ProfileProperty(
                                "textures", skin.value(), skin.signature());
                newProfile.setProperty(property);
                plugin.getLogger()
                        .debug(
                                "Applied skin property for "
                                + event.getName()
                                + " (has signature: "
                                + (skin.signature() != null)
                                + ")");
            } catch (Exception e) {
                plugin.getLogger()
                        .warn(
                                "Failed to apply skin for "
                                + event.getName()
                                + ": "
                                + e.getMessage());
            }
            verifiedSkinCache.invalidate(profileUuid);
        }

        event.setPlayerProfile(newProfile);

        preloadedUserCache.put(profileUuid, user);
    }

    // Fallback handler for older Paper/Purpur versions without AsyncPlayerSpawnLocationEvent
    // Only executes when the async variant is NOT available
    @EventHandler(priority = EventPriority.HIGHEST)
    public void chooseWorld(PlayerSpawnLocationEvent event) {
        // Skip if async event is available - it will handle this instead
        if (HAS_ASYNC_SPAWN_EVENT) {
            return;
        }
        handleSpawnLocationPublic(
                event.getPlayer().getUniqueId(), event.getSpawnLocation(), event::setSpawnLocation);
    }

    /**
     * Shared implementation for spawn location handling. Works with both
     * PlayerSpawnLocationEvent and AsyncPlayerSpawnLocationEvent. Made public
     * to allow access from AsyncSpawnLocationListener.
     *
     * @param puuid Player's UUID
     * @param currentSpawn The current spawn location from the event
     * @param setSpawn Consumer to set the new spawn location
     */
    public void handleSpawnLocationPublic(
            UUID puuid, Location currentSpawn, java.util.function.Consumer<Location> setSpawn) {
        var cachedAddress = addressCache.getIfPresent(puuid);
        if (cachedAddress == null) {
            Bukkit.getScheduler()
                    .runTask(
                            plugin.getBootstrap(),
                            ()
                            -> Bukkit.getPlayer(puuid)
                                    .kick(
                                            Component.text(
                                                    "Internal error, please try again"
                                                    + " later.")));
            return;
        }

        var preloadedUser = preloadedUserCache.getIfPresent(puuid);
        var destination = chooseServer(puuid, cachedAddress, preloadedUser);
        addressCache.invalidate(puuid);
        deferredSpawnCache.invalidate(puuid);
        if (destination.value() == null) {
            Bukkit.getScheduler()
                    .runTask(
                            plugin.getBootstrap(),
                            ()
                            -> Bukkit.getPlayer(puuid)
                                    .kick(
                                            plugin.getMessages()
                                                    .getMessage(
                                                            "kick-no-"
                                                            + (destination.key()
                                                            ? "lobby"
                                                            : "limbo"))));
        } else {
            // Try to read player's last position from player.dat
            Location playerDataLocation = null;
            try {
                // Get the primary world folder to read playerdata
                var primaryWorld = Bukkit.getWorlds().get(0);
                Path worldFolder = primaryWorld.getWorldFolder().toPath();

                // Use the database UUID, not the connection UUID, for player data lookup
                // This is critical for cracked players whose connection UUID differs from
                // the UUID stored in the database and used for their player.dat file
                UUID playerDataUuid = (preloadedUser != null) ? preloadedUser.getUuid() : puuid;
                var playerPosition = PlayerDataReader.readPlayerPosition(worldFolder, playerDataUuid);
                if (playerPosition != null) {
                    String targetWorldName = playerPosition.getWorldName();
                    World targetWorld = Bukkit.getWorld(targetWorldName);

                    // Fall back to dimension key lookup if standard name doesn't work
                    if (targetWorld == null && playerPosition.dimension() != null) {
                        for (World w : Bukkit.getWorlds()) {
                            if (w.getKey().toString().equals(playerPosition.dimension())
                                    || w.getName().equals(playerPosition.dimension())) {
                                targetWorld = w;
                                break;
                            }
                        }
                    }

                    if (targetWorld != null) {
                        // Check if the saved location is in a limbo world - if so, don't use it
                        // This prevents players who disconnected in limbo from being stuck there
                        if (plugin.getConfiguration()
                                .get(ConfigurationKeys.LIMBO)
                                .contains(targetWorld.getName())) {
                            plugin.getLogger()
                                    .debug(
                                            "Player "
                                            + puuid
                                            + " has saved location in limbo world "
                                            + targetWorld.getName()
                                            + ", ignoring");
                        } else {
                            playerDataLocation
                                    = new Location(
                                            targetWorld,
                                            playerPosition.x(),
                                            playerPosition.y(),
                                            playerPosition.z(),
                                            playerPosition.yaw(),
                                            playerPosition.pitch());
                        }
                    }
                }
            } catch (Exception e) {
                plugin.getLogger()
                        .debug("Could not read player.dat for " + puuid + ": " + e.getMessage());
            }

            // Determine spawn location logic
            // world.key() is true if it's a Lobby (Authenticated/Auto-Login), false if Limbo (Needs
            // Auth)
            if (destination.key()) {
                // User is already authenticated (Auto-Login) or going to Lobby
                // Spawn them directly at their last known location
                if (playerDataLocation != null) {
                    setSpawn.accept(playerDataLocation);
                } else {
                    setSpawn.accept(destination.value().getSpawnLocation());
                }
            } else {
                // User needs to authenticate (Limbo)
                // Cache their destination for after authentication
                if (playerDataLocation != null) {
                    deferredSpawnCache.put(puuid, playerDataLocation);
                } else {
                    // Check if the current spawn location is valid (not a limbo world)
                    if (!plugin.getConfiguration()
                            .get(ConfigurationKeys.LIMBO)
                            .contains(currentSpawn.getWorld().getName())) {
                        deferredSpawnCache.put(puuid, currentSpawn);
                    } else {
                        // Fallback to lobby spawn if we can't find a safe previous location
                        var fallbackLobby
                                = plugin.getServerHandler()
                                        .chooseLobbyServer(null, null, false, true);
                        if (fallbackLobby != null) {
                            deferredSpawnCache.put(puuid, fallbackLobby.getSpawnLocation());
                        }
                    }
                }
                setSpawn.accept(destination.value().getSpawnLocation());
            }
        }
    }

    /* Commented out when migrating to PacketEvents
    //Unused, might be useful in the future
    public void setUUID(Player player, String username) {
        var profile = plugin.getDatabaseProvider().getByName(username);

        try {
            var network = getNetworkManager(player);

            var clazz = network.getClass();
            var accessor = Accessors.getFieldAccessorOrNull(clazz, "spoofedUUID", UUID.class);
            accessor.set(network, profile.getUuid());
        } catch (Exception e) {
            e.printStackTrace();
            kickPlayer("Internal error", player);
        }
    }*/
    /**
     * Processes intercepted login packets on LibreLogin's async executor.
     *
     * @param event cloned packet event
     */
    public void processPacketAsync(PacketReceiveEvent event) {
        var user = event.getUser();
        var packetType = event.getPacketType();

        plugin.getLogger()
                .debug(
                        "Packet received "
                        + packetType
                        + " from "
                        + user.getName()
                        + " ("
                        + user.getAddress().toString()
                        + ")");

        if (packetType == PacketType.Login.Client.LOGIN_START) {
            // Check for Floodgate player BEFORE parsing packet to avoid IndexOutOfBoundsException.
            // Geyser/Bedrock clients may send packets in a format that packetevents can't parse
            // correctly based on the server version (e.g., missing UUID for 1.20.2+ servers).
            if (plugin.floodgateEnabled()) {
                var floodgatePlayer = floodgateSupport.findFloodgatePlayer(event.getChannel());
                if (floodgatePlayer != null) {
                    String username = floodgatePlayer.getCorrectUsername();
                    plugin.getLogger()
                            .debug(
                                    "Detected Floodgate player before packet parsing: "
                                    + username
                                    + " (XUID: "
                                    + floodgatePlayer.getXuid()
                                    + ")");

                    if (Bukkit.getPlayer(username) != null) {
                        kickPlayer(
                                plugin.getMessages()
                                        .getMessage(MessageKeys.KICK_ALREADY_CONNECTED.key()),
                                user);
                        return;
                    }

                    // Floodgate player - handle without parsing the packet
                    // The UUID will be set by Floodgate
                    forwardSyntheticLoginStart(
                            username, null, event.getChannel(), UUID.randomUUID());
                    return;
                }
            }

            WrapperLoginClientLoginStart packet;
            try {
                packet = new WrapperLoginClientLoginStart(event);
            } catch (IndexOutOfBoundsException e) {
                // Geyser/Bedrock clients may send packets with a different format
                // that packetevents can't parse correctly based on the server version.
                // Let Floodgate handle these connections instead.
                plugin.getLogger()
                        .debug(
                                "Could not parse login start packet (likely Geyser/Bedrock client):"
                                + " "
                                + e.getMessage());
                return;
            }
            var sessionKey = user.getAddress().toString();

            pendingEncryptionSessions.invalidate(sessionKey);

            if (plugin.floodgateEnabled()) {
                var success = floodgateSupport.applyLoginWorkaround(event, packet);
                // don't continue execution if the player was kicked by Floodgate
                if (!success) {
                    return;
                }
            }
            var username = packet.getUsername();

            Optional<ClientPublicKey> clientKey;

            if (getServerVersion().isNewerThanOrEquals(ServerVersion.V_1_19_3)) {
                clientKey = Optional.empty();
            } else {
                var signature = packet.getSignatureData();

                clientKey
                        = signature.map(
                                data -> {
                                    var expires = data.getTimestamp();
                                    var key = data.getPublicKey();
                                    var signatureData = data.getSignature();

                                    return new ClientPublicKey(expires, key, signatureData);
                                });
            }

            if (Bukkit.getPlayer(username) != null) {
                kickPlayer(
                        plugin.getMessages().getMessage(MessageKeys.KICK_ALREADY_CONNECTED.key()),
                        user);
                return;
            }

            if (plugin.fromFloodgate(username)) {
                // Floodgate player, do not handle, only retransmit the packet. The UUID will be set
                // by Floodgate
                forwardSyntheticLoginStart(
                        username, clientKey.orElse(null), event.getChannel(), UUID.randomUUID());
                return;
            }
            var preLoginResult = onPreLogin(username, user.getAddress().getAddress());
            switch (preLoginResult.state()) {
                case DENIED -> {
                    assert preLoginResult.message() != null;
                    kickPlayer(preLoginResult.message(), user);
                }
                case FORCE_ONLINE -> {
                    byte[] token;
                    try {
                        token = EncryptionUtil.createNonce(entropySource);

                        var newPacket
                                = new WrapperLoginServerEncryptionRequest(
                                        "", keyPair.getPublic(), token);

                        pendingEncryptionSessions.put(
                                sessionKey,
                                new EncryptionData(
                                        username,
                                        token,
                                        clientKey.orElse(null),
                                        preLoginResult.user().getUuid(),
                                        false));

                        PacketEvents.getAPI()
                                .getProtocolManager()
                                .sendPacket(event.getChannel(), newPacket);
                    } catch (Exception e) {
                        plugin.getLogger()
                                .error(
                                        "Failed to send encryption begin packet for player "
                                        + username
                                        + "! Kicking player.");
                        e.printStackTrace();
                        kickPlayer("Internal error", user);
                    }
                }
                default -> {
                    // Encrypt the connection even for offline/cracked players
                    // to protect against MITM attacks
                    byte[] offlineToken;
                    try {
                        offlineToken = EncryptionUtil.createNonce(entropySource);

                        var offlineEncPacket
                                = new WrapperLoginServerEncryptionRequest(
                                        "", keyPair.getPublic(), offlineToken);
                        // Critical: tell the client NOT to verify with Mojang
                        // Without this, cracked clients try session auth, fail, and disconnect
                        offlineEncPacket.setShouldAuthenticate(false);

                        pendingEncryptionSessions.put(
                                sessionKey,
                                new EncryptionData(
                                        username,
                                        offlineToken,
                                        clientKey.orElse(null),
                                        UUID.randomUUID(),
                                        true));

                        PacketEvents.getAPI()
                                .getProtocolManager()
                                .sendPacket(event.getChannel(), offlineEncPacket);
                    } catch (Exception e) {
                        plugin.getLogger()
                                .error(
                                        "Failed to send encryption packet for offline player "
                                        + username
                                        + "! Kicking player.");
                        e.printStackTrace();
                        kickPlayer("Internal error", user);
                    }
                }
            }
        } else {
            var packet = new WrapperLoginClientEncryptionResponse(event);
            var sharedSecret = packet.getEncryptedSharedSecret();

            var data = pendingEncryptionSessions.getIfPresent(user.getAddress().toString());

            if (data == null) {
                kickPlayer("Illegal encryption state", user);
                return;
            }

            var expectedToken = data.token().clone();

            if (!isNonceResponseValid(packet, data.publicKey(), expectedToken)) {
                kickPlayer("Invalid nonce", user);
                return;
            }

            // Verify session
            var privateKey = keyPair.getPrivate();

            SecretKey loginKey;

            try {
                loginKey = EncryptionUtil.decryptSharedSecret(privateKey, sharedSecret);
            } catch (GeneralSecurityException securityEx) {
                kickPlayer("Cannot decrypt shared secret", user);
                return;
            }

            try {
                if (!installConnectionEncryption(loginKey, user, event.getChannel())) {
                    return;
                }
            } catch (Exception e) {
                kickPlayer("Cannot decrypt shared secret", user);
                return;
            }

            var username = data.username();

            if (data.offlinePlayer()) {
                // Offline player - encryption is enabled, skip Mojang session verification
                plugin.getLogger()
                        .debug("Encryption enabled for offline player " + username);
                forwardSyntheticLoginStart(
                        username, data.publicKey(), event.getChannel(), data.uuid());
            } else {
                // Premium player - verify session with Mojang
                var serverId
                        = EncryptionUtil.computeServerIdHash(
                                "", loginKey, keyPair.getPublic());
                var address = user.getAddress();

                try {
                    var skinResult
                            = verifySessionAndCaptureSkin(
                                    username,
                                    serverId,
                                    address.getAddress(),
                                    data.uuid());
                    if (skinResult != null) {
                        forwardSyntheticLoginStart(
                                username,
                                data.publicKey(),
                                event.getChannel(),
                                data.uuid());
                    } else {
                        kickPlayer("Invalid session", user);
                    }
                } catch (IOException e) {
                    if (e instanceof SocketTimeoutException) {
                        plugin.getLogger()
                                .warn(
                                        "Session verification timed out (5 seconds) for "
                                        + username);
                    }
                    kickPlayer("Cannot verify session", user);
                }
            }
        }
    }

    public void onPacketReceive(PacketReceiveEvent event) {
        event.setCancelled(true);

        var copy = event.clone();

        AuthenticLibreLogin.EXECUTOR.execute(
                () -> {
                    try {
                        processPacketAsync(copy);
                    } finally {
                        copy.cleanUp();
                    }
                });
    }

    /**
     * Replays a synthetic login-start packet so the server continues its own login pipeline after
     * LibreLogin has completed its interception work.
     *
     * @param username final username to forward to the server
     * @param clientKey optional client public key bundle
     * @param channel login channel
     * @param uuid UUID that should be attached to the replayed packet when supported by the server
     */
    private void forwardSyntheticLoginStart(
            String username, ClientPublicKey clientKey, Object channel, UUID uuid) {
        WrapperLoginClientLoginStart forwardedPacket;
        if (getServerVersion().isNewerThanOrEquals(ServerVersion.V_1_20)) {
            forwardedPacket
                    = new WrapperLoginClientLoginStart(
                            getServerVersion().toClientVersion(),
                            username,
                            clientKey == null ? null : clientKey.toSignatureData(),
                            uuid);
        } else if (getServerVersion().isNewerThanOrEquals(ServerVersion.V_1_19)) {
            forwardedPacket
                    = new WrapperLoginClientLoginStart(
                            getServerVersion().toClientVersion(),
                            username,
                            clientKey == null ? null : clientKey.toSignatureData());
        } else {
            forwardedPacket
                    = new WrapperLoginClientLoginStart(
                            getServerVersion().toClientVersion(), username);
        }
        PacketEvents.getAPI().getProtocolManager().receivePacketSilently(channel, forwardedPacket);
    }

    /**
     * Verifies a premium session with Mojang and stores any returned texture payload for the
     * upcoming Bukkit profile creation step.
     *
     * @param username player username
     * @param serverHash computed session hash
     * @param hostIp connecting address
     * @param playerUuid premium UUID to use as the skin cache key
     * @return skin data when verification succeeds, a blank skin marker when verification succeeds
     *     without textures, or {@code null} when session verification fails
     * @throws IOException when the session server request fails
     */
    public SkinData verifySessionAndCaptureSkin(
            String username, String serverHash, InetAddress hostIp, UUID playerUuid)
            throws IOException {
        var connection = openSessionServerConnection(username, serverHash, hostIp);
        int responseCode = connection.getResponseCode();

        if (responseCode == 204) {
            connection.disconnect();
            return null;
        }

        SkinData skinData = null;
        try (BufferedReader reader
                = new BufferedReader(
                        new InputStreamReader(
                                connection.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }

            if (response.length() > 0) {
                JsonObject json = JsonParser.parseString(response.toString()).getAsJsonObject();
                if (json.has("properties")) {
                    var properties = json.getAsJsonArray("properties");
                    for (var prop : properties) {
                        var propObj = prop.getAsJsonObject();
                        if ("textures".equals(propObj.get("name").getAsString())) {
                            String value = propObj.get("value").getAsString();
                            String signature
                                    = propObj.has("signature")
                                    ? propObj.get("signature").getAsString()
                                    : null;
                            skinData = new SkinData(value, signature);

                            // Cache the skin for application in onPreLogin
                            if (playerUuid != null) {
                                verifiedSkinCache.put(playerUuid, skinData);
                            }
                            break;
                        }
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().debug("Failed to parse skin data: " + e.getMessage());
        }

        connection.disconnect();
        return skinData != null ? skinData : new SkinData(null, null);
    }

    /**
     * Verifies whether Mojang reports a matching joined session for the supplied player.
     *
     * @param username player username
     * @param serverHash computed session hash
     * @param hostIp connecting address
     * @return {@code true} when Mojang confirms the session
     * @throws IOException when the session server request fails
     */
    public boolean verifyJoinedSession(String username, String serverHash, InetAddress hostIp)
            throws IOException {
        var connection = openSessionServerConnection(username, serverHash, hostIp);
        int responseCode = connection.getResponseCode();
        connection.disconnect();
        return responseCode != 204;
    }

    /**
     * Enables Minecraft's packet encryption on the network manager backing the current login
     * channel.
     *
     * @param sharedSecret negotiated login secret
     * @param user PacketEvents login user
     * @param channel login channel
     * @return {@code true} when encryption was installed successfully
     * @throws IllegalArgumentException when the underlying reflection bootstrap receives invalid
     *     arguments
     */
    private boolean installConnectionEncryption(
            SecretKey loginKey,
            com.github.retrooper.packetevents.protocol.player.User user,
            Object channel)
            throws IllegalArgumentException {
        if (encryptionBootstrapMethod == null) {
            Class<?> networkManagerClass
                    = SpigotReflectionUtil.getNetworkManagers().get(0).getClass();

            encryptionBootstrapMethod
                    = Reflection.getMethod(networkManagerClass, "setupEncryption", SecretKey.class);

            if (encryptionBootstrapMethod == null) {
                encryptionBootstrapMethod
                        = Reflection.getMethod(
                                networkManagerClass, "setEncryptionKey", SecretKey.class);
            }

            if (encryptionBootstrapMethod == null) {
                encryptionBootstrapMethod
                        = Reflection.getMethod(
                                networkManagerClass,
                                "setEncryptionKey",
                                Cipher.class,
                                Cipher.class);
                cipherFactoryMethod =
                        Reflection.getMethod(LEGACY_ENCRYPTION_CLASS, "a", int.class, Key.class);
            }
        }

        try {
            Object networkManager = ProtocolUtil.findNetworkManager(channel);

            if (cipherFactoryMethod == null) {
                encryptionBootstrapMethod.invoke(networkManager, loginKey);
            } else {
                Object decryptionCipher =
                        cipherFactoryMethod.invoke(null, Cipher.DECRYPT_MODE, loginKey);
                Object encryptionCipher =
                        cipherFactoryMethod.invoke(null, Cipher.ENCRYPT_MODE, loginKey);
                encryptionBootstrapMethod.invoke(
                        networkManager, decryptionCipher, encryptionCipher);
            }
        } catch (Exception ex) {
            kickPlayer("Couldn't enable encryption", user);
            ex.printStackTrace();
            return false;
        }

        return true;
    }

    private void kickPlayer(
            String reason, com.github.retrooper.packetevents.protocol.player.User player) {
        kickPlayer(Component.text(reason), player);
    }

    private void kickPlayer(
            Component reason, com.github.retrooper.packetevents.protocol.player.User player) {
        // Cannot use Player#kick(Component) because it doesn't work in the login state
        var kickPacket = new WrapperLoginServerDisconnect(reason);
        try {
            // send kick packet at login state
            PacketEvents.getAPI().getProtocolManager().sendPacket(player.getChannel(), kickPacket);
        } finally {
            // tell the server that we want to close the connection
            player.closeConnection();
        }
    }

    /**
     * Validates the nonce challenge returned in the client's encryption response.
     *
     * @param packet encryption response packet
     * @param clientPublicKey optional client public key bundle
     * @param expectedToken original nonce sent to the client
     * @return {@code true} when the response matches the expected nonce
     */
    private boolean isNonceResponseValid(
            WrapperLoginClientEncryptionResponse packet,
            ClientPublicKey clientPublicKey,
            byte[] expectedToken) {
        try {
            if (getServerVersion().isNewerThanOrEquals(ServerVersion.V_1_19)
                    && !getServerVersion().isNewerThanOrEquals(ServerVersion.V_1_19_3)) {
                if (clientPublicKey == null) {
                    return EncryptionUtil.isNonceValid(
                            expectedToken,
                            keyPair.getPrivate(),
                            packet.getEncryptedVerifyToken().get());
                } else {
                    PublicKey publicKey = clientPublicKey.key();
                    var optSignature = packet.getSaltSignature();
                    if (optSignature.isEmpty()) {
                        return false;
                    }
                    var signature = optSignature.get();

                    return EncryptionUtil.isSignedNonceValid(
                            expectedToken,
                            publicKey,
                            signature.getSalt(),
                            signature.getSignature());
                }
            } else {
                byte[] nonce = packet.getEncryptedVerifyToken().get();
                return EncryptionUtil.isNonceValid(expectedToken, keyPair.getPrivate(), nonce);
            }
        } catch (NoSuchAlgorithmException
                | InvalidKeyException
                | SignatureException
                | NoSuchPaddingException
                | IllegalBlockSizeException
                | BadPaddingException signatureEx) {
            return false;
        }
    }

    /**
     * Opens a request to Mojang's session server for the supplied login attempt.
     *
     * @param username player username
     * @param serverHash computed session hash
     * @param hostIp connecting address
     * @return configured HTTP connection
     * @throws IOException when the request cannot be created
     */
    private HttpURLConnection openSessionServerConnection(
            String username, String serverHash, InetAddress hostIp) throws IOException {
        String sessionUrl;
        if (hostIp instanceof Inet6Address
                || plugin.getConfiguration().get(ConfigurationKeys.ALLOW_PROXY_CONNECTIONS)) {
            sessionUrl =
                    String.format(
                            "https://sessionserver.mojang.com/session/minecraft/hasJoined?username=%s&serverId=%s",
                            username, serverHash);
        } else {
            var encodedIp = URLEncoder.encode(hostIp.getHostAddress(), StandardCharsets.UTF_8);
            sessionUrl =
                    String.format(
                            "https://sessionserver.mojang.com/session/minecraft/hasJoined?username=%s&serverId=%s&ip=%s",
                            username, serverHash, encodedIp);
        }

        var connection = (HttpURLConnection) new URL(sessionUrl).openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        connection.connect();
        return connection;
    }
}
