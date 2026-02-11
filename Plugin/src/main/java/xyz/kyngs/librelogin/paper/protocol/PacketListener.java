/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package xyz.kyngs.librelogin.paper.protocol;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;

import xyz.kyngs.librelogin.paper.PaperListeners;

public class PacketListener extends PacketListenerAbstract {

    private final PaperListeners delegate;

    // Track authorized player UUIDs - players NOT in this set will have data packets blocked.
    // This avoids relying on event.getPlayer() instanceof Player which doesn't work during early join.
    private final Set<UUID> authorizedPlayers = ConcurrentHashMap.newKeySet();

    // All packet types that should be blocked for unauthenticated players.
    // This prevents leaking inventory data before login.
    // Note: Chunk/block and entity packets cannot be blocked as they are required
    // for the client to complete the login sequence (loading terrain).
    // Entity data is safe because limbo is a void world with invisible players.
    private static final Set<PacketTypeCommon> BLOCKED_PACKETS = Set.of(
            PacketType.Play.Server.WINDOW_ITEMS,
            PacketType.Play.Server.SET_SLOT,
            PacketType.Play.Server.OPEN_WINDOW,
            PacketType.Play.Server.ENTITY_EQUIPMENT
    );

    public PacketListener(PaperListeners delegate) {
        super(PacketListenerPriority.HIGHEST);
        this.delegate = delegate;
    }

    /**
     * Mark a player as authorized so their inventory packets are no longer
     * blocked.
     */
    public void markAuthorized(UUID uuid) {
        authorizedPlayers.add(uuid);
    }

    /**
     * Remove a player from the authorized set (e.g. on disconnect).
     */
    public void markUnauthorized(UUID uuid) {
        authorizedPlayers.remove(uuid);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.isCancelled()) {
            return;
        }
        if (event.getPacketType() != PacketType.Login.Client.LOGIN_START
                && event.getPacketType() != PacketType.Login.Client.ENCRYPTION_RESPONSE) {
            return;
        }

        delegate.onPacketReceive(event);
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (event.isCancelled()) {
            return;
        }

        var user = event.getUser();
        if (user == null) {
            return;
        }

        var uuid = user.getUUID();
        if (uuid == null) {
            return;
        }

        // If the player is already authorized, let all packets through
        if (authorizedPlayers.contains(uuid)) {
            return;
        }

        // Block data-leaking packets for unauthenticated players
        if (BLOCKED_PACKETS.contains(event.getPacketType())) {
            event.setCancelled(true);
        }
    }
}
