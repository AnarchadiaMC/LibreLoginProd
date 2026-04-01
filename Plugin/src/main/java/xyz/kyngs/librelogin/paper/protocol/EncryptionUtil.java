/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package xyz.kyngs.librelogin.paper.protocol;

import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import com.google.common.io.Resources;
import com.google.common.primitives.Longs;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Random;
import java.util.UUID;
import javax.crypto.*;
import javax.crypto.spec.SecretKeySpec;
import xyz.kyngs.librelogin.paper.PaperBootstrap;

/**
 * Contains low-level helpers for the Minecraft login encryption handshake.
 */
public final class EncryptionUtil {
    public static final int NONCE_LENGTH_BYTES = 4;
    public static final String ASYMMETRIC_KEY_ALGORITHM = "RSA";

    private static final int LOGIN_KEY_SIZE_BITS = 1_024;
    private static final int MIME_LINE_LENGTH = 76;
    private static final int LONG_BYTES = 8;
    private static final int UUID_BYTES = 2 * LONG_BYTES;
    private static final PublicKey SESSION_SERVICE_PUBLIC_KEY;
    private static final Base64.Encoder MIME_KEY_ENCODER =
            Base64.getMimeEncoder(MIME_LINE_LENGTH, "\n".getBytes(StandardCharsets.UTF_8));

    static {
        try {
            SESSION_SERVICE_PUBLIC_KEY = readSessionServiceKey();
        } catch (IOException | NoSuchAlgorithmException | InvalidKeySpecException ex) {
            throw new RuntimeException("Failed to load Mojang session key", ex);
        }
    }

    private EncryptionUtil() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Creates the RSA key pair used during the login handshake.
     *
     * @return freshly generated key pair
     */
    public static KeyPair createKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(ASYMMETRIC_KEY_ALGORITHM);
            generator.initialize(LOGIN_KEY_SIZE_BITS);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    /**
     * Creates the random nonce sent to the client in the encryption request.
     *
     * @param randomSource entropy source
     * @return handshake nonce
     */
    public static byte[] createNonce(Random randomSource) {
        byte[] nonce = new byte[NONCE_LENGTH_BYTES];
        randomSource.nextBytes(nonce);
        return nonce;
    }

    /**
     * Computes the server id hash sent to Mojang's session server.
     *
     * @param sessionId login session id
     * @param sharedSecret negotiated secret
     * @param publicKey server public key
     * @return hexadecimal server id hash
     */
    public static String computeServerIdHash(
            String sessionId, SecretKey sharedSecret, PublicKey publicKey) {
        return new BigInteger(createServerIdDigest(sessionId, publicKey, sharedSecret)).toString(16);
    }

    /**
     * Decrypts the AES shared secret returned by the client.
     *
     * @param privateKey server private key
     * @param encryptedSharedSecret encrypted secret bytes
     * @return decrypted AES key
     */
    public static SecretKey decryptSharedSecret(PrivateKey privateKey, byte[] encryptedSharedSecret)
            throws NoSuchPaddingException,
                    IllegalBlockSizeException,
                    NoSuchAlgorithmException,
                    BadPaddingException,
                    InvalidKeyException {
        return new SecretKeySpec(decryptPayload(privateKey, encryptedSharedSecret), "AES");
    }

    /**
     * Verifies Mojang's signature over the optional client public key payload.
     *
     * @param clientKey client public key bundle
     * @param verificationTime current verification timestamp
     * @param premiumId premium UUID associated with the session, when known
     * @return {@code true} when the signed payload is valid
     */
    public static boolean isClientKeyValid(
            ClientPublicKey clientKey, Instant verificationTime, UUID premiumId)
            throws NoSuchAlgorithmException, InvalidKeyException, SignatureException {
        if (clientKey.expired(verificationTime)) {
            return false;
        }

        Signature verifier = Signature.getInstance("SHA1withRSA");
        verifier.initVerify(SESSION_SERVICE_PUBLIC_KEY);
        verifier.update(serializeSignedClientKey(clientKey, premiumId));
        return verifier.verify(clientKey.signature());
    }

    /**
     * Verifies the encrypted nonce returned by the client on legacy protocol versions.
     *
     * @param expectedNonce nonce originally sent to the client
     * @param decryptionKey server private key
     * @param encryptedNonce encrypted nonce response
     * @return {@code true} when the decrypted response matches the expected nonce
     */
    public static boolean isNonceValid(
            byte[] expectedNonce, PrivateKey decryptionKey, byte[] encryptedNonce)
            throws NoSuchPaddingException,
                    IllegalBlockSizeException,
                    NoSuchAlgorithmException,
                    BadPaddingException,
                    InvalidKeyException {
        return Arrays.equals(expectedNonce, decryptPayload(decryptionKey, encryptedNonce));
    }

    /**
     * Verifies the signed nonce format used by Minecraft 1.19 through 1.19.2 when a client public
     * key is present.
     *
     * @param nonce expected nonce
     * @param clientKey client public key
     * @param signatureSalt salt bundled with the signed payload
     * @param signature signature bytes received from the client
     * @return {@code true} when the signature matches the supplied nonce and salt
     */
    public static boolean isSignedNonceValid(
            byte[] nonce, PublicKey clientKey, long signatureSalt, byte[] signature)
            throws NoSuchAlgorithmException, InvalidKeyException, SignatureException {
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(clientKey);
        verifier.update(nonce);
        verifier.update(Longs.toByteArray(signatureSalt));
        return verifier.verify(signature);
    }

    private static byte[] serializeSignedClientKey(ClientPublicKey clientPublicKey, UUID ownerUuid) {
        if (ownerUuid == null) {
            long expiresAt = clientPublicKey.expire().toEpochMilli();
            String encodedKey = MIME_KEY_ENCODER.encodeToString(clientPublicKey.key().getEncoded());
            return (expiresAt
                            + "-----BEGIN RSA PUBLIC KEY-----\n"
                            + encodedKey
                            + "\n-----END RSA PUBLIC KEY-----\n")
                    .getBytes(StandardCharsets.US_ASCII);
        }

        byte[] encodedKey = clientPublicKey.key().getEncoded();
        return ByteBuffer.allocate(encodedKey.length + UUID_BYTES + LONG_BYTES)
                .putLong(ownerUuid.getMostSignificantBits())
                .putLong(ownerUuid.getLeastSignificantBits())
                .putLong(clientPublicKey.expire().toEpochMilli())
                .put(encodedKey)
                .array();
    }

    private static PublicKey readSessionServiceKey()
            throws IOException, NoSuchAlgorithmException, InvalidKeySpecException {
        var keyResource =
                PaperBootstrap.class.getClassLoader().getResource("yggdrasil_session_pubkey.der");
        var encodedKey = Resources.toByteArray(keyResource);
        var keySpec = new X509EncodedKeySpec(encodedKey);
        return KeyFactory.getInstance("RSA").generatePublic(keySpec);
    }

    private static byte[] decryptPayload(PrivateKey privateKey, byte[] encryptedPayload)
            throws NoSuchPaddingException,
                    NoSuchAlgorithmException,
                    InvalidKeyException,
                    IllegalBlockSizeException,
                    BadPaddingException {
        Cipher cipher = Cipher.getInstance(privateKey.getAlgorithm());
        cipher.init(Cipher.DECRYPT_MODE, privateKey);
        return cipher.doFinal(encryptedPayload);
    }

    private static byte[] createServerIdDigest(
            String sessionId, PublicKey publicKey, SecretKey sharedSecret) {
        @SuppressWarnings("deprecation")
        Hasher hasher = Hashing.sha1().newHasher();
        hasher.putBytes(sessionId.getBytes(StandardCharsets.ISO_8859_1));
        hasher.putBytes(sharedSecret.getEncoded());
        hasher.putBytes(publicKey.getEncoded());
        return hasher.hash().asBytes();
    }
}
