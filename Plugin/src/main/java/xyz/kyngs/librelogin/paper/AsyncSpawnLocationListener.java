/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package xyz.kyngs.librelogin.paper;

import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Separate listener for AsyncPlayerSpawnLocationEvent. This class is isolated to prevent
 * ClassNotFoundException on servers that don't have this event (e.g., older Purpur versions).
 */
public class AsyncSpawnLocationListener implements Listener {

    private final PaperListeners paperListeners;

    public AsyncSpawnLocationListener(PaperListeners paperListeners) {
        this.paperListeners = paperListeners;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void chooseWorldAsync(AsyncPlayerSpawnLocationEvent event) {
        paperListeners.handleSpawnLocationPublic(
                event.getConnection().getProfile().getId(),
                event.getSpawnLocation(),
                event::setSpawnLocation);
    }
}
