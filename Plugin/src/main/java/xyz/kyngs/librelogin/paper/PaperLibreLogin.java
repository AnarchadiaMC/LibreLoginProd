/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package xyz.kyngs.librelogin.paper;

import static xyz.kyngs.librelogin.common.config.ConfigurationKeys.DEBUG;
import static xyz.kyngs.librelogin.paper.protocol.ProtocolUtil.getServerVersion;

import co.aikar.commands.BukkitCommandIssuer;
import co.aikar.commands.CommandIssuer;
import co.aikar.commands.CommandManager;
import co.aikar.commands.PaperCommandManager;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import java.io.File;
import java.io.InputStream;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.audience.Audience;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.CustomChart;
import org.bstats.charts.SimplePie;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.AbstractHorse;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntitySnapshot;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pig;
import org.bukkit.util.Vector;
import xyz.kyngs.librelogin.api.Logger;
import xyz.kyngs.librelogin.api.database.User;
import xyz.kyngs.librelogin.api.database.connector.SQLDatabaseConnector;
import xyz.kyngs.librelogin.api.event.exception.EventCancelledException;
import xyz.kyngs.librelogin.common.AuthenticLibreLogin;
import xyz.kyngs.librelogin.common.SLF4JLogger;
import xyz.kyngs.librelogin.common.database.AuthenticDatabaseProvider;
import xyz.kyngs.librelogin.common.image.AuthenticImageProjector;
import xyz.kyngs.librelogin.common.util.CancellableTask;
import xyz.kyngs.librelogin.paper.protocol.PacketListener;
import xyz.kyngs.librelogin.paper.util.PlayerPositionStorage;

public class PaperLibreLogin extends AuthenticLibreLogin<Player, World> {

    private final PaperBootstrap bootstrap;
    private PaperListeners listeners;
    private PacketListener packetListener;
    private PlayerPositionStorage positionStorage;
    private final Map<UUID, StashedMount> stashedMounts = new ConcurrentHashMap<>();
    private boolean started;

    private record StashedMount(EntitySnapshot snapshot, String typeName) {}

    public PlayerPositionStorage getPositionStorage() {
        return positionStorage;
    }

    public SQLDatabaseConnector getSQLDatabaseConnector() {
        if (getDatabaseProvider() instanceof AuthenticDatabaseProvider<?> authProvider
                && authProvider.getConnector() instanceof SQLDatabaseConnector sqlConnector) {
            return sqlConnector;
        }
        return null;
    }

    public PaperLibreLogin(PaperBootstrap bootstrap) {
        this.bootstrap = bootstrap;
        this.started = false;

        PacketEvents.setAPI(SpigotPacketEventsBuilder.build(bootstrap));

        PacketEvents.getAPI()
                .getSettings()
                //                .debug(true)
                .checkForUpdates(false)
                .bStats(false);

        PacketEvents.getAPI().load();
    }

    public PaperBootstrap getBootstrap() {
        return bootstrap;
    }

    private boolean isStashableMount(Entity entity) {
        return entity instanceof AbstractHorse || entity instanceof Pig;
    }

    private Entity findMountToStash(Player player) {
        var directVehicle = player.getVehicle();
        if (isStashableMount(directVehicle)) {
            return directVehicle;
        }

        if (stashedMounts.containsKey(player.getUniqueId())) {
            return null;
        }

        var playerLocation = player.getLocation();
        return player.getNearbyEntities(2.0, 2.0, 2.0).stream()
                .filter(this::isStashableMount)
                .filter(
                        entity ->
                                entity.getPassengers().isEmpty()
                                        || entity.getPassengers().contains(player))
                .min(
                        Comparator.comparingDouble(
                                entity ->
                                        entity.getLocation().distanceSquared(playerLocation)))
                .orElse(null);
    }

    public void stashMountForLimbo(Player player) {
        if (player == null
                || !player.isOnline()
                || !getServerHandler().getLimboServers().contains(player.getWorld())
                || (getAuthorizationProvider().isAuthorized(player)
                && !getAuthorizationProvider().isAwaiting2FA(player))) {
            return;
        }

        var mount = findMountToStash(player);
        if (mount == null || !mount.isValid()) {
            return;
        }

        boolean hadPlayerPassenger = mount.getPassengers().contains(player);
        if (hadPlayerPassenger) {
            player.leaveVehicle();
            mount.removePassenger(player);
        }

        var snapshot = mount.createSnapshot();
        if (snapshot == null) {
            if (hadPlayerPassenger && player.isOnline()) {
                mount.addPassenger(player);
            }
            getLogger()
                    .warn(
                            "Failed to snapshot mount "
                            + mount.getType()
                            + " for "
                            + player.getName()
                            + ", leaving it in place.");
            return;
        }

        stashedMounts.put(
                player.getUniqueId(), new StashedMount(snapshot, mount.getType().toString()));
        mount.remove();
    }

    private void restoreStashedMount(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }

        var stashedMount = stashedMounts.get(player.getUniqueId());
        if (stashedMount == null) {
            return;
        }

        try {
            var restoredMount = stashedMount.snapshot().createEntity(player.getLocation());
            restoredMount.setVelocity(new Vector());
            if (!restoredMount.addPassenger(player)) {
                Bukkit.getScheduler()
                        .runTask(
                                bootstrap,
                                () -> {
                                    if (player.isOnline() && restoredMount.isValid()) {
                                        restoredMount.addPassenger(player);
                                    }
                                });
            }
            stashedMounts.remove(player.getUniqueId(), stashedMount);
        } catch (Exception e) {
            getLogger()
                    .warn(
                            "Failed to restore stashed mount "
                            + stashedMount.typeName()
                            + " for "
                            + player.getName(),
                            e);
        }
    }

    @Override
    public InputStream getResourceAsStream(String name) {
        return bootstrap.getResource(name);
    }

    @Override
    public File getDataFolder() {
        return bootstrap.getDataFolder();
    }

    @Override
    public String getVersion() {
        return bootstrap.getDescription().getVersion();
    }

    @Override
    public boolean isPresent(UUID uuid) {
        return Bukkit.getPlayer(uuid) != null;
    }

    @Override
    public boolean multiProxyEnabled() {
        return false;
    }

    @Override
    public Player getPlayerForUUID(UUID uuid) {
        return Bukkit.getPlayer(uuid);
    }

    @Override
    protected PaperPlatformHandle providePlatformHandle() {
        return new PaperPlatformHandle(this);
    }

    @Override
    protected Logger provideLogger() {
        return new SLF4JLogger(bootstrap.getSLF4JLogger(), () -> getConfiguration().get(DEBUG));
    }

    @Override
    public CommandManager<?, ?, ?, ?, ?, ?> provideManager() {
        return new PaperCommandManager(bootstrap);
    }

    @Override
    protected boolean mainThread() {
        return Bukkit.isPrimaryThread() && started;
    }

    @Override
    public Player getPlayerFromIssuer(CommandIssuer issuer) {
        var bukkitIssuer = (BukkitCommandIssuer) issuer;

        return bukkitIssuer.getPlayer();
    }

    @Override
    protected void disable() {
        PacketEvents.getAPI().terminate();
        if (positionStorage != null) {
            positionStorage.saveSync();
        }
        if (getDatabaseProvider() == null) {
            return; // Not initialized
        }
        super.disable();
    }

    @Override
    protected void enable() {

        logger = provideLogger();

        if (Bukkit.getOnlineMode()) {
            getLogger()
                    .error(
                            "!!!The server is running in online mode! LibreLogin won't start unless"
                            + " you set it to false!!!");
            disable();
            return;
        }

        boolean isBehindProxy;
        if (getServerVersion().isNewerThanOrEquals(ServerVersion.V_1_21_4)) {
            isBehindProxy = Bukkit.getServer().getServerConfig().isProxyEnabled();
        } else {
            isBehindProxy
                    = Bukkit.spigot().getSpigotConfig().getBoolean("settings.bungeecord")
                    || Bukkit.spigot()
                            .getPaperConfig()
                            .getBoolean("settings.velocity-support.enabled");
        }

        if (isBehindProxy) {
            getLogger().error("!!!This server is running under a proxy, LibreLogin won't start!!!");
            getLogger()
                    .error(
                            "If you want to use LibreLogin under a proxy, place it on the proxy and"
                            + " remove it from the server.");
            disable();
            return;
        }

        try {
            super.enable();
        } catch (ShutdownException e) {
            return;
        }

        var provider = getEventProvider();

        provider.subscribe(
                provider.getTypes().authenticated,
                event -> {
                    var player = event.getPlayer();
                    if (player == null) {
                        return;
                    }
                    player.setInvisible(false);
                    // Resync inventory to client - packets may have been blocked in limbo
                    player.updateInventory();
                });

        positionStorage = new PlayerPositionStorage(this);

        listeners = new PaperListeners(this);

        Bukkit.getPluginManager().registerEvents(listeners, bootstrap);
        Bukkit.getPluginManager().registerEvents(new Blockers(this), bootstrap);

        // Try to register AsyncPlayerSpawnLocationEvent listener for newer Paper versions
        // Falls back to PlayerSpawnLocationEvent in PaperListeners for older versions
        try {
            Class.forName("io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent");
            Bukkit.getPluginManager()
                    .registerEvents(new AsyncSpawnLocationListener(listeners), bootstrap);
            getLogger().debug("Registered AsyncPlayerSpawnLocationEvent listener");
        } catch (ClassNotFoundException e) {
            getLogger()
                    .debug(
                            "AsyncPlayerSpawnLocationEvent not available, using"
                            + " PlayerSpawnLocationEvent fallback");
        }

        packetListener = new PacketListener(listeners, this);
        PacketEvents.getAPI().getEventManager().registerListener(packetListener);

        started = true;
    }

    @Override
    public void authorize(Player player, User user, Audience audience) {
        try {

            var location = listeners.getSpawnLocationCache().getIfPresent(player.getUniqueId());
            boolean wasDead = listeners.isDeadPendingAuth(player.getUniqueId())
                    || (positionStorage != null && positionStorage.isDead(player.getUniqueId()));
            boolean isNewPlayer = (location == null) && !wasDead;
            UUID dataUuid = (user != null) ? user.getUuid() : player.getUniqueId();

            // Safety check: NEVER teleport to a limbo world after authentication
            // This is defense-in-depth in case the caching logic fails or cache has stale data
            if (location != null) {
                var limboWorlds
                        = getConfiguration()
                                .get(xyz.kyngs.librelogin.common.config.ConfigurationKeys.LIMBO);
                if (limboWorlds.contains(location.getWorld().getName())) {
                    getLogger()
                            .warn(
                                    "Safety check triggered: Cached location for "
                                    + player.getName()
                                    + " was in limbo world "
                                    + location.getWorld().getName()
                                    + ". Forcing fallback lookup.");
                    listeners.getSpawnLocationCache().invalidate(player.getUniqueId());
                    location = null; // Force fallback to persistent storage or lobby
                }
            }

            // If location is null (or was invalidated from limbo), check persistent storage before defaulting to lobby
            if (location == null && positionStorage != null) {
                location = positionStorage.getLastValidLocation(dataUuid);
                if (location != null) {
                    getLogger().debug("Restored location for authenticated player " + player.getName() + " from persistent storage: " + location);
                }
            }

            if (location == null) {
                var world = getServerHandler().chooseLobbyServer(user, player, true, false);

                if (world == null) {
                    getPlatformHandle().kick(player, getMessages().getMessage("kick-no-lobby"));
                    return;
                }

                location = world.getSpawnLocation();
            } else {
                isNewPlayer = false; // Has cached or stored location, not a new player
                listeners.getSpawnLocationCache().invalidate(player.getUniqueId());
            }

            // If player was dead on disconnect, they are respawning, so they are not a new player
            if (wasDead) {
                isNewPlayer = false;
                player.setHealth(player.getMaxHealth());
                player.setFoodLevel(20);
                player.setFireTicks(0);
                if (positionStorage != null) {
                    positionStorage.clearDead(dataUuid);
                }
                listeners.clearDeadPendingAuth(player.getUniqueId());
            }

            var finalLocation = location;
            var runNewPlayerRtp
                    = isNewPlayer
                    && getConfiguration()
                            .get(xyz.kyngs.librelogin.common.config.ConfigurationKeys.NEW_PLAYER_RTP_ENABLED);

            PaperUtil.runSyncAndWait(
                    () ->
                            player.teleportAsync(finalLocation)
                                    .whenComplete(
                                            (teleported, throwable) ->
                                                    Bukkit.getScheduler()
                                                            .runTask(
                                                                    bootstrap,
                                                                    () -> {
                                                                        if (throwable != null) {
                                                                            getLogger()
                                                                                    .warn(
                                                                                            "Failed to teleport authenticated player "
                                                                                            + player.getName(),
                                                                                            throwable);
                                                                            return;
                                                                        }

                                                                        if (!Boolean.TRUE.equals(
                                                                                teleported)) {
                                                                            getLogger()
                                                                                    .warn(
                                                                                            "Teleport for authenticated player "
                                                                                            + player.getName()
                                                                                            + " returned false.");
                                                                            return;
                                                                        }

                                                                        if (!player.isOnline()) {
                                                                            return;
                                                                        }

                                                                        if (wasDead) {
                                                                            player.setHealth(player.getMaxHealth());
                                                                            player.setFoodLevel(20);
                                                                            player.setFireTicks(0);
                                                                        }

                                                                        restoreStashedMount(player);

                                                                        if (!runNewPlayerRtp) {
                                                                            return;
                                                                        }

                                                                        var command
                                                                                = getConfiguration()
                                                                                        .get(xyz.kyngs.librelogin.common.config.ConfigurationKeys.NEW_PLAYER_RTP_COMMAND)
                                                                                        .replace(
                                                                                                "{player}",
                                                                                                player.getName());
                                                                        getLogger()
                                                                                .info(
                                                                                        "Executing RTP command for new player "
                                                                                        + player.getName());
                                                                        Bukkit.dispatchCommand(
                                                                                Bukkit.getConsoleSender(),
                                                                                command);
                                                                    })),
                    this);

        } catch (EventCancelledException ignored) {
        }
    }

    @Override
    public CancellableTask delay(Runnable runnable, long delayInMillis) {
        var task
                = Bukkit.getScheduler()
                        .runTaskLaterAsynchronously(bootstrap, runnable, delayInMillis / 50);
        return task::cancel;
    }

    @Override
    public CancellableTask repeat(Runnable runnable, long delayInMillis, long repeatInMillis) {
        var task
                = Bukkit.getScheduler()
                        .runTaskTimerAsynchronously(
                                bootstrap, runnable, delayInMillis / 50, repeatInMillis / 50);
        return task::cancel;
    }

    @Override
    public boolean pluginPresent(String pluginName) {
        return Bukkit.getPluginManager().isPluginEnabled(pluginName);
    }

    @Override
    protected AuthenticImageProjector<Player, World> provideImageProjector() {
        return null;
    }

    @Override
    protected void initMetrics(CustomChart... charts) {
        var metrics = new Metrics(bootstrap, Constants.BSTATS_ID);

        for (var chart : charts) {
            metrics.addCustomChart(chart);
        }

        var isVelocity = new SimplePie("is_velocity", () -> "Paper");

        metrics.addCustomChart(isVelocity);
    }

    @Override
    protected void shutdownProxy(int code) {
        bootstrap.disable();
        bootstrap.getServer().shutdown();
        throw new ShutdownException();
    }

    @Override
    public Audience getAudienceFromIssuer(CommandIssuer issuer) {
        return ((BukkitCommandIssuer) issuer).getIssuer();
    }
}
