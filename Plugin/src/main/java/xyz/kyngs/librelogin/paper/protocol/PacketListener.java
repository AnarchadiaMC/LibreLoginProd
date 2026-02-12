/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package xyz.kyngs.librelogin.paper.protocol;

import java.util.List;
import java.util.Set;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;

import xyz.kyngs.librelogin.paper.PaperLibreLogin;
import xyz.kyngs.librelogin.paper.PaperListeners;

public class PacketListener extends PacketListenerAbstract {

    private final PaperListeners delegate;
    private final PaperLibreLogin plugin;

    // All packet types that should be blocked for unauthenticated players in limbo.
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

    public PacketListener(PaperListeners delegate, PaperLibreLogin plugin) {
        super(PacketListenerPriority.HIGHEST);
        this.delegate = delegate;
        this.plugin = plugin;
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

        // Only check data-leaking packets
        if (!BLOCKED_PACKETS.contains(event.getPacketType())) {
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

        // Get the player - if not available (shouldn't happen in PLAY state), let packets through
        Player player = Bukkit.getPlayer(uuid);
        if (player == null) {
            return;
        }

        // Only block packets if the player is in a limbo world
        World world = player.getWorld();
        if (world == null) {
            return;
        }

        List<String> limboWorlds = plugin.getConfiguration()
                .get(xyz.kyngs.librelogin.common.config.ConfigurationKeys.LIMBO);
        if (limboWorlds.contains(world.getName())) {
            event.setCancelled(true);
        }
    }
}
