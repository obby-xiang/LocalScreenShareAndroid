package com.obby.android.localscreenshare.identity.tls;

import androidx.annotation.NonNull;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the self-signed X.509 certificate that carries the identity public key.
 *
 * <p>The certificate is a container, not the identity: its subject public key info is copied
 * verbatim from {@link PublicKey#getEncoded()}, so the fingerprint derived from the certificate
 * equals the one derived from the identity key. Serial number, validity, subject and extensions
 * are therefore free to change without changing what peers pin.</p>
 *
 * <p>The DER encoding is written by hand because BouncyCastle would be a new dependency and
 * {@code sun.security.x509} is absent on Android. The signature is produced with
 * {@code SHA256withECDSA} and no explicit provider, so Android signs through the keystore bridge
 * and the JVM signs with its default provider. The private key only has to implement
 * {@link Signature#initSign}; it is never read as bytes.</p>
 *
 * <p>Fields are fixed by design: {@code CN=lss.local} with a matching dNSName SAN (the client
 * verifies that name), validity 2020-01-01 through 2049-12-31 (a wide window because device
 * clocks are unreliable, and 2049 is the last year RFC 5280 allows UTCTime), key usage
 * {@code digitalSignature}, extended key usage {@code serverAuth} and {@code clientAuth}, and
 * {@code basicConstraints CA=false}. The class is pure JVM: no Android type, no I/O.</p>
 */
public final class SelfSignedCertificateEncoder {

    /**
     * Signature algorithm of the certificate and of the key it certifies.
     */
    public static final String SIGNATURE_ALGORITHM = "SHA256withECDSA";

    // ecdsa-with-SHA256; parameters are absent, as RFC 5758 requires for this algorithm.
    private static final String OID_ECDSA_WITH_SHA256 = "1.2.840.10045.4.3.2";

    private static final String OID_COMMON_NAME = "2.5.4.3";

    private static final String OID_KEY_USAGE = "2.5.29.15";

    private static final String OID_SUBJECT_ALT_NAME = "2.5.29.17";

    private static final String OID_BASIC_CONSTRAINTS = "2.5.29.19";

    private static final String OID_EXTENDED_KEY_USAGE = "2.5.29.37";

    private static final String OID_SERVER_AUTH = "1.3.6.1.5.5.7.3.1";

    private static final String OID_CLIENT_AUTH = "1.3.6.1.5.5.7.3.2";

    // UTCTime bounds; the upper bound stays below 2050 so the field remains a UTCTime.
    private static final String NOT_BEFORE = "200101000000Z";

    private static final String NOT_AFTER = "491231235959Z";

    // Bytes of the SPKI digest used as the serial number.
    private static final int SERIAL_LENGTH = 16;

    private static final int TAG_BOOLEAN = 0x01;

    private static final int TAG_INTEGER = 0x02;

    private static final int TAG_BIT_STRING = 0x03;

    private static final int TAG_OCTET_STRING = 0x04;

    private static final int TAG_UTF8_STRING = 0x0C;

    private static final int TAG_IA5_STRING = 0x16;

    private static final int TAG_UTC_TIME = 0x17;

    private static final int TAG_SEQUENCE = 0x30;

    private static final int TAG_SET = 0x31;

    private static final int TAG_CONTEXT_0_EXPLICIT = 0xA0;

    private static final int TAG_CONTEXT_3_EXPLICIT = 0xA3;

    private static final int TAG_CONTEXT_2_PRIMITIVE = 0x82;

    private SelfSignedCertificateEncoder() {
    }

    /**
     * Returns the DER encoding of a self-signed v3 certificate for {@code publicKey}.
     *
     * @param publicKey key whose SPKI is embedded and whose certificate is produced
     * @param signer    private key of the same pair; used only for the signature
     * @throws GeneralSecurityException if the key cannot be read or the signature fails
     * @throws IllegalArgumentException if an argument is null or the public key has no encoding
     */
    @NonNull
    public static byte[] encode(@NonNull final PublicKey publicKey,
                                @NonNull final PrivateKey signer)
        throws GeneralSecurityException {
        return encode(publicKey, signer, NOT_BEFORE, NOT_AFTER);
    }

    /**
     * Returns a certificate with explicit UTCTime bounds.
     *
     * <p>Production always uses the fixed window; the overload exists so a test can present an
     * already expired certificate and prove that validity is not part of the trust decision.</p>
     *
     * @param notBefore lower bound as {@code YYMMDDHHMMSSZ}
     * @param notAfter  upper bound as {@code YYMMDDHHMMSSZ}
     */
    @NonNull
    public static byte[] encode(@NonNull final PublicKey publicKey, @NonNull final PrivateKey signer,
                         @NonNull final String notBefore, @NonNull final String notAfter)
        throws GeneralSecurityException {
        if (publicKey == null) {
            throw new IllegalArgumentException("publicKey must not be null");
        }
        if (signer == null) {
            throw new IllegalArgumentException("signer must not be null");
        }
        final byte[] spki = publicKey.getEncoded();
        if (spki == null || spki.length == 0) {
            throw new IllegalArgumentException("public key has no X.509 encoding");
        }

        final byte[] tbsCertificate = tbsCertificate(spki, notBefore, notAfter);
        final byte[] signature = sign(tbsCertificate, signer);
        return sequence(tbsCertificate, sequence(oid(OID_ECDSA_WITH_SHA256)),
            bitString(signature, 0));
    }

    @NonNull
    private static byte[] tbsCertificate(@NonNull final byte[] spki,
                                         @NonNull final String notBefore,
                                         @NonNull final String notAfter)
        throws GeneralSecurityException {
        final byte[] name = name();
        final byte[] extensions = sequence(
            extension(OID_KEY_USAGE, true, bitString(new byte[]{(byte) 0x80}, 7)),
            extension(OID_EXTENDED_KEY_USAGE, false,
                sequence(oid(OID_SERVER_AUTH), oid(OID_CLIENT_AUTH))),
            extension(OID_BASIC_CONSTRAINTS, true,
                sequence(tagged(TAG_BOOLEAN, new byte[]{0x00}))),
            extension(OID_SUBJECT_ALT_NAME, false,
                sequence(tagged(TAG_CONTEXT_2_PRIMITIVE,
                    TlsNames.AUTHORITY.getBytes(StandardCharsets.US_ASCII)))));
        return sequence(
            // version [0] EXPLICIT INTEGER 2, i.e. v3.
            tagged(TAG_CONTEXT_0_EXPLICIT, integer(new byte[]{2})),
            integer(serialNumber(spki)),
            sequence(oid(OID_ECDSA_WITH_SHA256)),
            name,
            sequence(utcTime(notBefore), utcTime(notAfter)),
            name,
            spki,
            tagged(TAG_CONTEXT_3_EXPLICIT, extensions)
        );
    }

    /**
     * Returns a stable positive serial number derived from the SPKI digest.
     */
    @NonNull
    static byte[] serialNumber(@NonNull final byte[] spki) throws GeneralSecurityException {
        final byte[] digest = MessageDigest.getInstance("SHA-256").digest(spki);
        final byte[] serial = new byte[SERIAL_LENGTH];
        System.arraycopy(digest, 0, serial, 0, SERIAL_LENGTH);
        // DER integers are signed: clear the sign bit and keep the value non-zero.
        serial[0] &= 0x7F;
        boolean isZero = true;
        for (final byte value : serial) {
            if (value != 0) {
                isZero = false;
                break;
            }
        }
        if (isZero) {
            serial[SERIAL_LENGTH - 1] = 1;
        }
        return serial;
    }

    @NonNull
    private static byte[] name() {
        return sequence(set(sequence(oid(OID_COMMON_NAME),
            utf8String(TlsNames.AUTHORITY))));
    }

    @NonNull
    private static byte[] extension(@NonNull final String oid, final boolean critical,
                                    @NonNull final byte[] value) {
        return critical
            ? sequence(oid(oid), tagged(TAG_BOOLEAN, new byte[]{(byte) 0xFF}), octetString(value))
            : sequence(oid(oid), octetString(value));
    }

    @NonNull
    private static byte[] sign(@NonNull final byte[] data, @NonNull final PrivateKey signer)
        throws GeneralSecurityException {
        final Signature signature = Signature.getInstance(SIGNATURE_ALGORITHM);
        signature.initSign(signer);
        signature.update(data);
        // JCA encodes ECDSA signatures as the DER SEQUENCE of r and s already.
        return signature.sign();
    }

    @NonNull
    private static byte[] integer(@NonNull final byte[] magnitude) {
        int offset = 0;
        while (offset < magnitude.length - 1 && magnitude[offset] == 0) {
            offset++;
        }
        final boolean isNegative = (magnitude[offset] & 0x80) != 0;
        final byte[] value = new byte[magnitude.length - offset + (isNegative ? 1 : 0)];
        System.arraycopy(magnitude, offset, value, isNegative ? 1 : 0, magnitude.length - offset);
        return tagged(TAG_INTEGER, value);
    }

    @NonNull
    private static byte[] bitString(@NonNull final byte[] data, final int unusedBits) {
        final byte[] value = new byte[data.length + 1];
        value[0] = (byte) unusedBits;
        System.arraycopy(data, 0, value, 1, data.length);
        return tagged(TAG_BIT_STRING, value);
    }

    @NonNull
    private static byte[] octetString(@NonNull final byte[] data) {
        return tagged(TAG_OCTET_STRING, data);
    }

    @NonNull
    private static byte[] utf8String(@NonNull final String value) {
        return tagged(TAG_UTF8_STRING, value.getBytes(StandardCharsets.UTF_8));
    }

    @NonNull
    private static byte[] utcTime(@NonNull final String value) {
        return tagged(TAG_UTC_TIME, value.getBytes(StandardCharsets.US_ASCII));
    }

    @NonNull
    private static byte[] oid(@NonNull final String value) {
        final String[] parts = value.split("\\.");
        final List<Integer> encoded = new ArrayList<>(parts.length + 2);
        encoded.add(Integer.parseInt(parts[0]) * 40 + Integer.parseInt(parts[1]));
        for (int i = 2; i < parts.length; i++) {
            int arc = Integer.parseInt(parts[i]);
            final int[] groups = new int[5];
            int count = 0;
            do {
                groups[count++] = arc & 0x7F;
                arc >>>= 7;
            } while (arc != 0);
            for (int j = count - 1; j >= 0; j--) {
                encoded.add(j == 0 ? groups[j] : groups[j] | 0x80);
            }
        }
        final byte[] bytes = new byte[encoded.size()];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (int) encoded.get(i);
        }
        return tagged(0x06, bytes);
    }

    @NonNull
    private static byte[] sequence(@NonNull final byte[]... items) {
        return tagged(TAG_SEQUENCE, concat(items));
    }

    @NonNull
    private static byte[] set(@NonNull final byte[]... items) {
        return tagged(TAG_SET, concat(items));
    }

    @NonNull
    private static byte[] concat(@NonNull final byte[]... items) {
        int length = 0;
        for (final byte[] item : items) {
            length += item.length;
        }
        final byte[] value = new byte[length];
        int offset = 0;
        for (final byte[] item : items) {
            System.arraycopy(item, 0, value, offset, item.length);
            offset += item.length;
        }
        return value;
    }

    @NonNull
    private static byte[] tagged(final int tag, @NonNull final byte[] value) {
        final byte[] length = length(value.length);
        final byte[] encoded = new byte[1 + length.length + value.length];
        encoded[0] = (byte) tag;
        System.arraycopy(length, 0, encoded, 1, length.length);
        System.arraycopy(value, 0, encoded, 1 + length.length, value.length);
        return encoded;
    }

    @NonNull
    private static byte[] length(final int value) {
        if (value < 0x80) {
            return new byte[]{(byte) value};
        }
        int size = 1;
        while ((value >>> (size * 8)) != 0) {
            size++;
        }
        final byte[] encoded = new byte[size + 1];
        encoded[0] = (byte) (0x80 | size);
        for (int i = 0; i < size; i++) {
            encoded[size - i] = (byte) (value >>> (i * 8));
        }
        return encoded;
    }
}
