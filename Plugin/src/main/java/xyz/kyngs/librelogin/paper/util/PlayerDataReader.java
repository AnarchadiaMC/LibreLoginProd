/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package xyz.kyngs.librelogin.paper.util;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.zip.GZIPInputStream;

/** Utility class to read player position data from Minecraft's NBT player.dat files. */
public class PlayerDataReader {

    private static final byte TAG_END = 0;
    private static final byte TAG_BYTE = 1;
    private static final byte TAG_SHORT = 2;
    private static final byte TAG_INT = 3;
    private static final byte TAG_LONG = 4;
    private static final byte TAG_FLOAT = 5;
    private static final byte TAG_DOUBLE = 6;
    private static final byte TAG_BYTE_ARRAY = 7;
    private static final byte TAG_STRING = 8;
    private static final byte TAG_LIST = 9;
    private static final byte TAG_COMPOUND = 10;
    private static final byte TAG_INT_ARRAY = 11;
    private static final byte TAG_LONG_ARRAY = 12;

    /**
     * Reads player position from world/playerdata/{uuid}.dat
     *
     * @param worldFolder Path to the world folder (e.g., server's main world)
     * @param playerUuid Player's UUID
     * @return PlayerPosition containing location data, or null if not found
     */
    public static PlayerPosition readPlayerPosition(Path worldFolder, UUID playerUuid) {
        Path playerDataFile =
                worldFolder.resolve("playerdata").resolve(playerUuid.toString() + ".dat");

        if (!Files.exists(playerDataFile)) {
            return null;
        }

        try (DataInputStream dis =
                new DataInputStream(new GZIPInputStream(Files.newInputStream(playerDataFile)))) {

            // Read root compound tag
            byte tagType = dis.readByte();
            if (tagType != TAG_COMPOUND) {
                return null;
            }

            // Skip root tag name
            skipString(dis);

            // Parse the compound to find Pos, Rotation, and Dimension
            return parseRootCompound(dis);

        } catch (IOException e) {
            return null;
        }
    }

    private static PlayerPosition parseRootCompound(DataInputStream dis) throws IOException {
        double[] pos = null;
        float[] rotation = null;
        String dimension = null;
        Float health = null;
        Integer spawnX = null;
        Integer spawnY = null;
        Integer spawnZ = null;
        String spawnDimension = null;
        Float spawnAngle = null;
        Boolean spawnForced = null;

        while (true) {
            byte tagType = dis.readByte();
            if (tagType == TAG_END) {
                break;
            }

            String tagName = readString(dis);

            if (tagName.equals("Pos") && tagType == TAG_LIST) {
                pos = readDoubleList(dis);
            } else if (tagName.equals("Rotation") && tagType == TAG_LIST) {
                rotation = readFloatList(dis);
            } else if (tagName.equals("Dimension") && tagType == TAG_STRING) {
                dimension = readString(dis);
            } else if (tagName.equals("Health") && tagType == TAG_FLOAT) {
                health = dis.readFloat();
            } else if (tagName.equals("Health") && tagType == TAG_SHORT) {
                health = (float) dis.readShort();
            } else if (tagName.equals("SpawnX") && tagType == TAG_INT) {
                spawnX = dis.readInt();
            } else if (tagName.equals("SpawnY") && tagType == TAG_INT) {
                spawnY = dis.readInt();
            } else if (tagName.equals("SpawnZ") && tagType == TAG_INT) {
                spawnZ = dis.readInt();
            } else if (tagName.equals("SpawnDimension") && tagType == TAG_STRING) {
                spawnDimension = readString(dis);
            } else if (tagName.equals("SpawnAngle") && tagType == TAG_FLOAT) {
                spawnAngle = dis.readFloat();
            } else if (tagName.equals("SpawnForced") && tagType == TAG_BYTE) {
                spawnForced = dis.readByte() != 0;
            } else {
                // Skip this tag
                skipTag(dis, tagType);
            }
        }

        if (pos != null && pos.length >= 3) {
            float yaw = (rotation != null && rotation.length >= 1) ? rotation[0] : 0f;
            float pitch = (rotation != null && rotation.length >= 2) ? rotation[1] : 0f;
            String dim = (dimension != null) ? dimension : "minecraft:overworld";
            float h = (health != null) ? health : 20.0f;

            return new PlayerPosition(
                    pos[0], pos[1], pos[2], yaw, pitch, dim, h,
                    spawnX, spawnY, spawnZ, spawnDimension, spawnAngle, spawnForced);
        }

        return null;
    }

    private static void skipRemainingCompound(DataInputStream dis) throws IOException {
        while (true) {
            byte tagType = dis.readByte();
            if (tagType == TAG_END) {
                break;
            }
            skipString(dis); // tag name
            skipTag(dis, tagType);
        }
    }

    private static double[] readDoubleList(DataInputStream dis) throws IOException {
        byte listType = dis.readByte();
        int length = dis.readInt();

        if (listType != TAG_DOUBLE) {
            // Skip the list contents
            for (int i = 0; i < length; i++) {
                skipTag(dis, listType);
            }
            return null;
        }

        double[] result = new double[length];
        for (int i = 0; i < length; i++) {
            result[i] = dis.readDouble();
        }
        return result;
    }

    private static float[] readFloatList(DataInputStream dis) throws IOException {
        byte listType = dis.readByte();
        int length = dis.readInt();

        if (listType != TAG_FLOAT) {
            // Skip the list contents
            for (int i = 0; i < length; i++) {
                skipTag(dis, listType);
            }
            return null;
        }

        float[] result = new float[length];
        for (int i = 0; i < length; i++) {
            result[i] = dis.readFloat();
        }
        return result;
    }

    private static String readString(DataInputStream dis) throws IOException {
        int length = dis.readUnsignedShort();
        byte[] bytes = new byte[length];
        dis.readFully(bytes);
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static void skipString(DataInputStream dis) throws IOException {
        int length = dis.readUnsignedShort();
        skipFully(dis, length);
    }

    private static void skipFully(DataInputStream dis, long bytes) throws IOException {
        long remaining = bytes;
        while (remaining > 0) {
            long skipped = dis.skip(remaining);
            if (skipped <= 0) {
                // Read and discard if skip doesn't work
                dis.readByte();
                remaining--;
            } else {
                remaining -= skipped;
            }
        }
    }

    private static void skipTag(DataInputStream dis, byte tagType) throws IOException {
        switch (tagType) {
            case TAG_BYTE -> dis.readByte();
            case TAG_SHORT -> dis.readShort();
            case TAG_INT -> dis.readInt();
            case TAG_LONG -> dis.readLong();
            case TAG_FLOAT -> dis.readFloat();
            case TAG_DOUBLE -> dis.readDouble();
            case TAG_BYTE_ARRAY -> {
                int len = dis.readInt();
                skipFully(dis, len);
            }
            case TAG_STRING -> skipString(dis);
            case TAG_LIST -> {
                byte listType = dis.readByte();
                int length = dis.readInt();
                for (int i = 0; i < length; i++) {
                    skipTag(dis, listType);
                }
            }
            case TAG_COMPOUND -> {
                while (true) {
                    byte innerType = dis.readByte();
                    if (innerType == TAG_END) {
                        break;
                    }
                    skipString(dis); // tag name
                    skipTag(dis, innerType);
                }
            }
            case TAG_INT_ARRAY -> {
                int len = dis.readInt();
                skipFully(dis, (long) len * 4);
            }
            case TAG_LONG_ARRAY -> {
                int len = dis.readInt();
                skipFully(dis, (long) len * 8);
            }
        }
    }

    /** Record holding player position data extracted from player.dat */
    public record PlayerPosition(
            double x,
            double y,
            double z,
            float yaw,
            float pitch,
            String dimension,
            float health,
            Integer spawnX,
            Integer spawnY,
            Integer spawnZ,
            String spawnDimension,
            Float spawnAngle,
            Boolean spawnForced) {

        public boolean isDead() {
            return health <= 0.0f;
        }

        public boolean hasRespawnPoint() {
            return spawnX != null && spawnY != null && spawnZ != null;
        }

        public String getSpawnWorldName() {
            return parseWorldName(spawnDimension != null ? spawnDimension : "minecraft:overworld");
        }

        /**
         * Gets the world name from the dimension string. Converts "minecraft:overworld" to "world",
         * "minecraft:the_nether" to "world_nether", etc. For custom dimensions, returns the
         * dimension key as-is.
         */
        public String getWorldName() {
            return parseWorldName(dimension);
        }

        private static String parseWorldName(String dim) {
            if (dim == null) {
                return "world";
            }

            return switch (dim) {
                case "minecraft:overworld" -> "world";
                case "minecraft:the_nether" -> "world_nether";
                case "minecraft:the_end" -> "world_the_end";
                default -> {
                    // For custom dimensions, try to extract just the key part
                    if (dim.contains(":")) {
                        yield dim.substring(dim.indexOf(':') + 1);
                    }
                    yield dim;
                }
            };
        }
    }
}
