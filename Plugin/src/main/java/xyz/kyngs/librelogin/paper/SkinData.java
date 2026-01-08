/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package xyz.kyngs.librelogin.paper;

/**
 * Holds skin texture data from Mojang session verification.
 *
 * @param value The base64 encoded skin texture value
 * @param signature The signature of the skin texture
 */
public record SkinData(String value, String signature) {}
