/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package xyz.kyngs.librelogin.paper;

import xyz.kyngs.librelogin.paper.protocol.ClientPublicKey;

import java.util.UUID;

public record EncryptionData(String username, byte[] token, ClientPublicKey publicKey, UUID uuid) {}
