/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package xyz.kyngs.librelogin.paper;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.bukkit.plugin.Plugin;

/**
 * Utility class to generate the End sky datapack for limbo worlds. This copies
 * the bundled datapack from plugin resources to the world's datapacks folder so
 * that the limbo world has the End dimension skybox effect.
 */
public class LimboDatapackGenerator {

    private static final String DATAPACK_NAME = "librelogin_end_sky";
    private static final String[] DATAPACK_FILES = {
        "pack.mcmeta",
        "data/minecraft/dimension_type/overworld.json"
    };

    /**
     * Generates the End sky datapack in the specified world folder. This must
     * be called BEFORE Bukkit.createWorld() for the datapack to take effect.
     *
     * @param worldName The name of the world (also the folder name)
     * @param plugin The plugin instance for accessing resources
     */
    public static void generateDatapack(String worldName, Plugin plugin) {
        File worldFolder = new File(worldName);
        File datapacksFolder = new File(worldFolder, "datapacks");
        File datapackFolder = new File(datapacksFolder, DATAPACK_NAME);

        // Create directory structure
        if (!datapackFolder.exists()) {
            datapackFolder.mkdirs();
        }

        // Copy each datapack file from resources
        for (String filePath : DATAPACK_FILES) {
            copyResourceFile(plugin, filePath, datapackFolder);
        }

        plugin.getLogger().info("Generated End sky datapack for limbo world: " + worldName);
    }

    private static void copyResourceFile(Plugin plugin, String resourcePath, File datapackFolder) {
        String fullResourcePath = DATAPACK_NAME + "/" + resourcePath;

        try (InputStream is = plugin.getResource(fullResourcePath)) {
            if (is == null) {
                plugin.getLogger().warning("Could not find resource: " + fullResourcePath);
                return;
            }

            File targetFile = new File(datapackFolder, resourcePath);

            // Create parent directories if needed
            File parentDir = targetFile.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }

            // Copy the file
            Files.copy(is, targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to copy datapack file " + resourcePath + ": " + e.getMessage());
        }
    }
}
