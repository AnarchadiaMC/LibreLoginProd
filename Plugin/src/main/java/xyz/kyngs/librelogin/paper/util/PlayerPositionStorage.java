/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package xyz.kyngs.librelogin.paper.util;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.FileReader;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.Nullable;
import xyz.kyngs.librelogin.api.database.connector.SQLDatabaseConnector;
import xyz.kyngs.librelogin.common.database.provider.LibreLoginMySQLDatabaseProvider;
import xyz.kyngs.librelogin.paper.PaperLibreLogin;

/**
 * Persistently stores and manages players' last valid gameplay locations, bed/anchor respawn points,
 * and death state using the server's existing SQL database and a bounded in-memory Caffeine cache.
 *
 * Designed for high concurrency:
 * - Event handlers and main-thread operations perform zero synchronous database queries.
 * - Writes are queued and flushed asynchronously in batches using database-native upserts.
 * - Positions are pre-loaded into cache during AsyncPlayerPreLoginEvent.
 */
public class PlayerPositionStorage {

    private final PaperLibreLogin plugin;
    private final Cache<UUID, Optional<StoredPosition>> cache;
    private final ConcurrentHashMap<UUID, StoredPosition> dirtyQueue = new ConcurrentHashMap<>();
    private final AtomicBoolean flushScheduled = new AtomicBoolean(false);
    private final BukkitTask heartbeatTask;
    private String upsertSql;

    public record StoredPosition(
            String world,
            double x,
            double y,
            double z,
            float yaw,
            float pitch,
            boolean isDead,
            @Nullable String bedWorld,
            @Nullable Integer bedX,
            @Nullable Integer bedY,
            @Nullable Integer bedZ,
            @Nullable Float bedYaw) {

        public static StoredPosition fromJson(JsonObject obj) {
            String world = obj.has("world") ? obj.get("world").getAsString() : "world";
            double x = obj.has("x") ? obj.get("x").getAsDouble() : 0.0;
            double y = obj.has("y") ? obj.get("y").getAsDouble() : 64.0;
            double z = obj.has("z") ? obj.get("z").getAsDouble() : 0.0;
            float yaw = obj.has("yaw") ? obj.get("yaw").getAsFloat() : 0.0f;
            float pitch = obj.has("pitch") ? obj.get("pitch").getAsFloat() : 0.0f;
            boolean isDead = obj.has("isDead") && obj.get("isDead").getAsBoolean();

            String bedWorld = obj.has("bedWorld") ? obj.get("bedWorld").getAsString() : null;
            Integer bedX = obj.has("bedX") ? obj.get("bedX").getAsInt() : null;
            Integer bedY = obj.has("bedY") ? obj.get("bedY").getAsInt() : null;
            Integer bedZ = obj.has("bedZ") ? obj.get("bedZ").getAsInt() : null;
            Float bedYaw = obj.has("bedYaw") ? obj.get("bedYaw").getAsFloat() : null;

            return new StoredPosition(
                    world, x, y, z, yaw, pitch, isDead, bedWorld, bedX, bedY, bedZ, bedYaw);
        }
    }

    public PlayerPositionStorage(PaperLibreLogin plugin) {
        this.plugin = plugin;
        this.cache = Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterAccess(30, TimeUnit.MINUTES)
                .build();

        initTable();
        initUpsertSql();
        migrateFromFlatFile();

        BukkitTask task = null;
        try {
            task = Bukkit.getScheduler().runTaskTimerAsynchronously(
                    plugin.getBootstrap(), this::flushBatchAsync, 100L, 100L); // Flush every 5 seconds (100 ticks)
        } catch (Throwable ignored) {
        }
        this.heartbeatTask = task;
    }

    @Nullable
    private SQLDatabaseConnector getConnector() {
        return plugin.getSQLDatabaseConnector();
    }

    private void initTable() {
        var connector = getConnector();
        if (connector == null) {
            plugin.getLogger().warn("SQLDatabaseConnector is not available, skipping position table initialization.");
            return;
        }
        try {
            connector.runQuery(connection -> {
                try (var statement = connection.createStatement()) {
                    statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS librelogin_positions (
                            uuid VARCHAR(255) NOT NULL PRIMARY KEY,
                            world VARCHAR(255) NOT NULL,
                            x DOUBLE PRECISION NOT NULL,
                            y DOUBLE PRECISION NOT NULL,
                            z DOUBLE PRECISION NOT NULL,
                            yaw REAL NOT NULL,
                            pitch REAL NOT NULL,
                            is_dead BOOLEAN NOT NULL DEFAULT FALSE,
                            bed_world VARCHAR(255) NULL DEFAULT NULL,
                            bed_x INT NULL DEFAULT NULL,
                            bed_y INT NULL DEFAULT NULL,
                            bed_z INT NULL DEFAULT NULL,
                            bed_yaw REAL NULL DEFAULT NULL
                        )
                    """);
                }
            });
            plugin.getLogger().debug("Initialized SQL table 'librelogin_positions'.");
        } catch (Exception e) {
            plugin.getLogger().error("Failed to initialize librelogin_positions table: " + e.getMessage());
        }
    }

    private void initUpsertSql() {
        if (plugin.getDatabaseProvider() instanceof LibreLoginMySQLDatabaseProvider) {
            upsertSql = """
                INSERT INTO librelogin_positions (
                    uuid, world, x, y, z, yaw, pitch, is_dead,
                    bed_world, bed_x, bed_y, bed_z, bed_yaw
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    world = VALUES(world),
                    x = VALUES(x),
                    y = VALUES(y),
                    z = VALUES(z),
                    yaw = VALUES(yaw),
                    pitch = VALUES(pitch),
                    is_dead = VALUES(is_dead),
                    bed_world = COALESCE(VALUES(bed_world), librelogin_positions.bed_world),
                    bed_x = COALESCE(VALUES(bed_x), librelogin_positions.bed_x),
                    bed_y = COALESCE(VALUES(bed_y), librelogin_positions.bed_y),
                    bed_z = COALESCE(VALUES(bed_z), librelogin_positions.bed_z),
                    bed_yaw = COALESCE(VALUES(bed_yaw), librelogin_positions.bed_yaw)
            """;
        } else {
            // PostgreSQL and SQLite both support standard ON CONFLICT (uuid) DO UPDATE SET
            upsertSql = """
                INSERT INTO librelogin_positions (
                    uuid, world, x, y, z, yaw, pitch, is_dead,
                    bed_world, bed_x, bed_y, bed_z, bed_yaw
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (uuid) DO UPDATE SET
                    world = EXCLUDED.world,
                    x = EXCLUDED.x,
                    y = EXCLUDED.y,
                    z = EXCLUDED.z,
                    yaw = EXCLUDED.yaw,
                    pitch = EXCLUDED.pitch,
                    is_dead = EXCLUDED.is_dead,
                    bed_world = COALESCE(EXCLUDED.bed_world, librelogin_positions.bed_world),
                    bed_x = COALESCE(EXCLUDED.bed_x, librelogin_positions.bed_x),
                    bed_y = COALESCE(EXCLUDED.bed_y, librelogin_positions.bed_y),
                    bed_z = COALESCE(EXCLUDED.bed_z, librelogin_positions.bed_z),
                    bed_yaw = COALESCE(EXCLUDED.bed_yaw, librelogin_positions.bed_yaw)
            """;
        }
    }

    private void migrateFromFlatFile() {
        File oldFile = new File(plugin.getDataFolder(), "player_positions.json");
        if (!oldFile.exists()) {
            return;
        }
        var connector = getConnector();
        if (connector == null) {
            return;
        }

        plugin.getLogger().info("Found legacy player_positions.json, migrating data to SQL database...");
        try (var reader = new FileReader(oldFile)) {
            var rootElement = JsonParser.parseReader(reader);
            if (rootElement != null && rootElement.isJsonObject()) {
                var rootObj = rootElement.getAsJsonObject();
                int count = 0;
                for (Map.Entry<String, JsonElement> entry : rootObj.entrySet()) {
                    try {
                        UUID uuid = UUID.fromString(entry.getKey());
                        if (entry.getValue().isJsonObject()) {
                            var pos = StoredPosition.fromJson(entry.getValue().getAsJsonObject());
                            cache.put(uuid, Optional.of(pos));
                            dirtyQueue.put(uuid, pos);
                            count++;
                        }
                    } catch (Exception e) {
                        plugin.getLogger().warn("Failed to migrate legacy entry " + entry.getKey() + ": " + e.getMessage());
                    }
                }
                flushBatchSync();
                plugin.getLogger().info("Successfully migrated " + count + " player positions from flat file to SQL database.");
            }
        } catch (Exception e) {
            plugin.getLogger().warn("Failed to read legacy player_positions.json during migration: " + e.getMessage());
        }

        File migratedFile = new File(plugin.getDataFolder(), "player_positions.json.migrated");
        if (!oldFile.renameTo(migratedFile)) {
            oldFile.delete();
        }
    }

    /**
     * Loads a player's position from the in-memory cache.
     * If not present in cache:
     * - On asynchronous threads (e.g. AsyncPlayerPreLoginEvent), queries the SQL database and caches result.
     * - On the primary Minecraft thread, returns null immediately to prevent thread blocking.
     */
    @Nullable
    public StoredPosition loadPosition(UUID uuid) {
        var cached = cache.getIfPresent(uuid);
        if (cached != null) {
            return cached.orElse(null);
        }

        // CRITICAL: NEVER perform synchronous DB queries on the main thread!
        if (Bukkit.isPrimaryThread()) {
            return null;
        }

        return loadPositionFromDb(uuid);
    }

    @Nullable
    private StoredPosition loadPositionFromDb(UUID uuid) {
        var connector = getConnector();
        if (connector == null) {
            return null;
        }

        try {
            StoredPosition loaded = connector.runQuery(connection -> {
                try (var ps = connection.prepareStatement(
                        "SELECT world, x, y, z, yaw, pitch, is_dead, bed_world, bed_x, bed_y, bed_z, bed_yaw "
                                + "FROM librelogin_positions WHERE uuid = ?")) {
                    ps.setString(1, uuid.toString());
                    try (var rs = ps.executeQuery()) {
                        if (rs.next()) {
                            String world = rs.getString("world");
                            double x = rs.getDouble("x");
                            double y = rs.getDouble("y");
                            double z = rs.getDouble("z");
                            float yaw = rs.getFloat("yaw");
                            float pitch = rs.getFloat("pitch");
                            boolean isDead = rs.getBoolean("is_dead");
                            String bedWorld = rs.getString("bed_world");
                            Integer bedX = rs.getObject("bed_x") != null ? rs.getInt("bed_x") : null;
                            Integer bedY = rs.getObject("bed_y") != null ? rs.getInt("bed_y") : null;
                            Integer bedZ = rs.getObject("bed_z") != null ? rs.getInt("bed_z") : null;
                            Float bedYaw = rs.getObject("bed_yaw") != null ? rs.getFloat("bed_yaw") : null;

                            return new StoredPosition(
                                    world, x, y, z, yaw, pitch, isDead, bedWorld, bedX, bedY, bedZ, bedYaw);
                        }
                    }
                }
                return null;
            });

            cache.put(uuid, Optional.ofNullable(loaded));
            return loaded;
        } catch (Exception e) {
            plugin.getLogger().error("Failed to load position from SQL for UUID " + uuid + ": " + e.getMessage());
            return null;
        }
    }

    private void bindPosition(PreparedStatement ps, UUID uuid, StoredPosition pos) throws SQLException {
        ps.setString(1, uuid.toString());
        ps.setString(2, pos.world());
        ps.setDouble(3, pos.x());
        ps.setDouble(4, pos.y());
        ps.setDouble(5, pos.z());
        ps.setFloat(6, pos.yaw());
        ps.setFloat(7, pos.pitch());
        ps.setBoolean(8, pos.isDead());
        if (pos.bedWorld() != null) {
            ps.setString(9, pos.bedWorld());
        } else {
            ps.setNull(9, Types.VARCHAR);
        }
        if (pos.bedX() != null) {
            ps.setInt(10, pos.bedX());
        } else {
            ps.setNull(10, Types.INTEGER);
        }
        if (pos.bedY() != null) {
            ps.setInt(11, pos.bedY());
        } else {
            ps.setNull(11, Types.INTEGER);
        }
        if (pos.bedZ() != null) {
            ps.setInt(12, pos.bedZ());
        } else {
            ps.setNull(12, Types.INTEGER);
        }
        if (pos.bedYaw() != null) {
            ps.setFloat(13, pos.bedYaw());
        } else {
            ps.setNull(13, Types.REAL);
        }
    }

    private void scheduleBatchFlush() {
        if (flushScheduled.compareAndSet(false, true)) {
            if (plugin.getBootstrap().isEnabled()) {
                Bukkit.getScheduler().runTaskLaterAsynchronously(plugin.getBootstrap(), this::flushBatchAsync, 20L); // 1-second debounce
            } else {
                flushBatchSync();
            }
        }
    }

    private void flushBatchAsync() {
        flushScheduled.set(false);
        flushBatchSync();
    }

    /**
     * Executes batched native upserts for all entries currently in dirtyQueue using a single connection.
     */
    public synchronized void flushBatchSync() {
        var connector = getConnector();
        if (connector == null || dirtyQueue.isEmpty() || upsertSql == null) {
            return;
        }

        Map<UUID, StoredPosition> toFlush = new HashMap<>(dirtyQueue);
        if (toFlush.isEmpty()) {
            return;
        }

        try {
            connector.runQuery(connection -> {
                try (var ps = connection.prepareStatement(upsertSql)) {
                    int count = 0;
                    for (var entry : toFlush.entrySet()) {
                        bindPosition(ps, entry.getKey(), entry.getValue());
                        ps.addBatch();
                        count++;
                        if (count % 500 == 0) {
                            ps.executeBatch();
                        }
                    }
                    if (count % 500 != 0) {
                        ps.executeBatch();
                    }
                }
            });

            for (var entry : toFlush.entrySet()) {
                dirtyQueue.remove(entry.getKey(), entry.getValue());
            }
        } catch (Exception e) {
            plugin.getLogger().error("Failed to batch flush player positions to SQL: " + e.getMessage());
        }
    }

    /**
     * Flushes all remaining dirty positions synchronously. Called during plugin shutdown.
     */
    public void saveSync() {
        if (heartbeatTask != null) {
            heartbeatTask.cancel();
        }
        if (dirtyQueue.isEmpty()) {
            return;
        }
        plugin.getLogger().info("Flushing " + dirtyQueue.size() + " pending player positions to SQL database...");
        flushBatchSync();
    }

    @Nullable
    private World resolveWorld(@Nullable String worldName) {
        if (worldName == null || worldName.isBlank()) {
            return null;
        }
        World world = Bukkit.getWorld(worldName);
        if (world != null) {
            return world;
        }
        for (World w : Bukkit.getWorlds()) {
            if (w.getName().equalsIgnoreCase(worldName)) {
                return w;
            }
        }
        return null;
    }

    /**
     * Checks if a World corresponds to a valid gameplay world (neither limbo nor lobby).
     */
    public boolean isValidGameplayWorld(@Nullable World world) {
        if (world == null) {
            return false;
        }
        var serverHandler = plugin.getServerHandler();
        if (serverHandler != null) {
            if (serverHandler.getLimboServers().contains(world)
                    || serverHandler.getLobbyServers().containsValue(world)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Checks if a world name corresponds to a valid gameplay world (neither limbo nor lobby).
     */
    public boolean isValidGameplayWorldName(@Nullable String worldName) {
        if (worldName == null || worldName.isBlank()) {
            return false;
        }
        World world = resolveWorld(worldName);
        if (world != null) {
            return isValidGameplayWorld(world);
        }
        var limboWorlds = plugin.getConfiguration().get(xyz.kyngs.librelogin.common.config.ConfigurationKeys.LIMBO);
        if (limboWorlds != null && limboWorlds.contains(worldName)) {
            return false;
        }
        var lobbyWorlds = plugin.getConfiguration().get(xyz.kyngs.librelogin.common.config.ConfigurationKeys.LOBBY);
        if (lobbyWorlds != null && lobbyWorlds.values().contains(worldName)) {
            return false;
        }
        return true;
    }

    /**
     * Records the player's last valid survival/gameplay location.
     * Updates in-memory cache immediately and queues a batched asynchronous upsert.
     */
    public void saveLastValidLocation(UUID uuid, Location location) {
        if (location == null || location.getWorld() == null) {
            return;
        }
        if (!isValidGameplayWorld(location.getWorld())) {
            return;
        }

        var existing = loadPosition(uuid);
        String bedWorld = existing != null ? existing.bedWorld() : null;
        Integer bedX = existing != null ? existing.bedX() : null;
        Integer bedY = existing != null ? existing.bedY() : null;
        Integer bedZ = existing != null ? existing.bedZ() : null;
        Float bedYaw = existing != null ? existing.bedYaw() : null;

        var newPos = new StoredPosition(
                location.getWorld().getName(),
                location.getX(),
                location.getY(),
                location.getZ(),
                location.getYaw(),
                location.getPitch(),
                false,
                bedWorld,
                bedX,
                bedY,
                bedZ,
                bedYaw);

        cache.put(uuid, Optional.of(newPos));
        dirtyQueue.put(uuid, newPos);
        scheduleBatchFlush();
    }

    /**
     * Returns the player's last stored valid location from cache, or null if unavailable or world not loaded.
     */
    @Nullable
    public Location getLastValidLocation(UUID uuid) {
        var stored = loadPosition(uuid);
        if (stored == null || stored.world() == null) {
            return null;
        }
        World world = resolveWorld(stored.world());
        if (world == null || !isValidGameplayWorld(world)) {
            return null;
        }
        return new Location(world, stored.x(), stored.y(), stored.z(), stored.yaw(), stored.pitch());
    }

    /**
     * Sets the player's dead status and optionally records their bed/anchor respawn location.
     */
    public void setDead(UUID uuid, boolean isDead, @Nullable Location bedLocation) {
        var existing = loadPosition(uuid);
        String world = existing != null ? existing.world() : "world";
        double x = existing != null ? existing.x() : 0.0;
        double y = existing != null ? existing.y() : 64.0;
        double z = existing != null ? existing.z() : 0.0;
        float yaw = existing != null ? existing.yaw() : 0.0f;
        float pitch = existing != null ? existing.pitch() : 0.0f;

        String bedWorld = existing != null ? existing.bedWorld() : null;
        Integer bedX = existing != null ? existing.bedX() : null;
        Integer bedY = existing != null ? existing.bedY() : null;
        Integer bedZ = existing != null ? existing.bedZ() : null;
        Float bedYaw = existing != null ? existing.bedYaw() : null;

        if (bedLocation != null && bedLocation.getWorld() != null) {
            bedWorld = bedLocation.getWorld().getName();
            bedX = bedLocation.getBlockX();
            bedY = bedLocation.getBlockY();
            bedZ = bedLocation.getBlockZ();
            bedYaw = bedLocation.getYaw();
        }

        var newPos = new StoredPosition(
                world, x, y, z, yaw, pitch, isDead, bedWorld, bedX, bedY, bedZ, bedYaw);
        cache.put(uuid, Optional.of(newPos));
        dirtyQueue.put(uuid, newPos);
        scheduleBatchFlush();
    }

    public boolean isDead(UUID uuid) {
        var stored = loadPosition(uuid);
        return stored != null && stored.isDead();
    }

    public void clearDead(UUID uuid) {
        var existing = loadPosition(uuid);
        if (existing != null && existing.isDead()) {
            var newPos = new StoredPosition(
                    existing.world(),
                    existing.x(),
                    existing.y(),
                    existing.z(),
                    existing.yaw(),
                    existing.pitch(),
                    false,
                    existing.bedWorld(),
                    existing.bedX(),
                    existing.bedY(),
                    existing.bedZ(),
                    existing.bedYaw());
            cache.put(uuid, Optional.of(newPos));
            dirtyQueue.put(uuid, newPos);
            scheduleBatchFlush();
        }
    }

    @Nullable
    public Location getBedSpawn(UUID uuid) {
        var stored = loadPosition(uuid);
        if (stored == null || stored.bedWorld() == null || stored.bedX() == null || stored.bedY() == null || stored.bedZ() == null) {
            return null;
        }
        World world = resolveWorld(stored.bedWorld());
        if (world == null) {
            return null;
        }
        float yaw = stored.bedYaw() != null ? stored.bedYaw() : 0.0f;
        return new Location(world, stored.bedX() + 0.5, stored.bedY(), stored.bedZ() + 0.5, yaw, 0.0f);
    }
}
