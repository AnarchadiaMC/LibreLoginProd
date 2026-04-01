/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package xyz.kyngs.librelogin.paper;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.wrapper.login.client.WrapperLoginClientLoginStart;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.util.AttributeKey;
import org.geysermc.floodgate.api.player.FloodgatePlayer;

/**
 * Bridges the parts of Floodgate's login pipeline that are not triggered when LibreLogin handles
 * the incoming login packet first.
 */
public class FloodgateHelper {
    private static final AttributeKey<String> FLOODGATE_KICK_MESSAGE =
            AttributeKey.valueOf("floodgate-kick-message");
    private static final AttributeKey<FloodgatePlayer> FLOODGATE_PLAYER_ATTRIBUTE =
            AttributeKey.valueOf("floodgate-player");
    private static final String FLOODGATE_HANDLER_NAME = "floodgate_data_handler";

    /**
     * Applies the Floodgate login adjustments that would normally be handled by its packet hook.
     *
     * @param packetEvent intercepted login packet event
     * @param loginStart mutable login-start packet wrapper
     * @return {@code true} when login handling may continue, otherwise {@code false}
     */
    protected boolean applyLoginWorkaround(
            PacketReceiveEvent packetEvent, WrapperLoginClientLoginStart loginStart) {
        FloodgatePlayer floodgatePlayer = findFloodgatePlayer(packetEvent.getChannel());
        if (floodgatePlayer == null) {
            return true;
        }

        Channel loginChannel = (Channel) packetEvent.getChannel();
        String kickMessage = loginChannel.attr(FLOODGATE_KICK_MESSAGE).get();
        if (kickMessage != null) {
            packetEvent.getUser().closeConnection();
            return false;
        }

        loginStart.setUsername(floodgatePlayer.getCorrectUsername());

        ChannelHandler pipelineHandler = loginChannel.pipeline().get(FLOODGATE_HANDLER_NAME);
        if (pipelineHandler != null) {
            loginChannel.pipeline().remove(pipelineHandler);
        }

        return true;
    }

    /**
     * Retrieves the Floodgate player object already attached to a channel, if present.
     *
     * @param channel Netty login channel
     * @return associated Floodgate player, or {@code null} when the connection is not managed by
     *     Floodgate
     */
    public FloodgatePlayer findFloodgatePlayer(Object channel) {
        return ((Channel) channel).attr(FLOODGATE_PLAYER_ATTRIBUTE).get();
    }
}
