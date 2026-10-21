package com.obby.android.localscreenshare.trust;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Converts between the canonical fingerprint and the form shown to a person.
 *
 * <p>The stored and compared form stays {@code base64url(SHA-256(SPKI))}; the display form is
 * uppercase hexadecimal in eight groups of eight characters, which is easier to read aloud and to
 * compare character by character. Conversion happens only at the edge (dialogs, notifications,
 * settings); every decision uses the canonical string.</p>
 *
 * <p>Pure functions, no Android type. The display is never truncated: a shortened fingerprint
 * would weaken the only manual check the trust-on-first-use model has.</p>
 */
public final class FingerprintFormatter {

    /**
     * Number of hexadecimal characters in a SHA-256 fingerprint.
     */
    public static final int HEX_LENGTH = 64;

    /**
     * Characters between two display groups.
     */
    public static final int GROUP_LENGTH = 8;

    private static final String BASE64_URL_ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

    private static final char[] HEX_DIGITS = "0123456789ABCDEF".toCharArray();

    private FingerprintFormatter() {
    }

    /**
     * Returns the grouped uppercase hexadecimal form, or an empty string when the input is not a
     * canonical fingerprint.
     */
    @NonNull
    public static String toDisplay(@Nullable final String fingerprint) {
        final byte[] bytes = decode(fingerprint);
        if (bytes == null) {
            return "";
        }
        final StringBuilder display = new StringBuilder(HEX_LENGTH + HEX_LENGTH / GROUP_LENGTH);
        final int bytesPerGroup = GROUP_LENGTH / 2;
        for (int i = 0; i < bytes.length; i++) {
            if (i > 0 && i % bytesPerGroup == 0) {
                display.append(' ');
            }
            final int value = bytes[i] & 0xFF;
            display.append(HEX_DIGITS[value >>> 4]).append(HEX_DIGITS[value & 0x0F]);
        }
        return display.toString();
    }

    /**
     * Returns the canonical fingerprint of a grouped or ungrouped hexadecimal form, or null when
     * the input is not 64 hexadecimal characters.
     */
    @Nullable
    public static String toCanonical(@Nullable final String display) {
        if (display == null) {
            return null;
        }
        final StringBuilder hex = new StringBuilder(HEX_LENGTH);
        for (int i = 0; i < display.length(); i++) {
            final char value = display.charAt(i);
            if (value == ' ' || value == '\t' || value == '\n' || value == '\r') {
                continue;
            }
            hex.append(value);
        }
        if (hex.length() != HEX_LENGTH) {
            return null;
        }
        final byte[] bytes = new byte[HEX_LENGTH / 2];
        for (int i = 0; i < bytes.length; i++) {
            final int high = Character.digit(hex.charAt(i * 2), 16);
            final int low = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (high < 0 || low < 0) {
                return null;
            }
            bytes[i] = (byte) ((high << 4) | low);
        }
        return encodeBase64Url(bytes);
    }

    /**
     * Returns whether the value is already a canonical fingerprint.
     */
    public static boolean isCanonical(@Nullable final String fingerprint) {
        return decode(fingerprint) != null;
    }

    @Nullable
    private static byte[] decode(@Nullable final String fingerprint) {
        if (fingerprint == null || fingerprint.length() != 43) {
            return null;
        }
        final byte[] bytes = new byte[32];
        int buffer = 0;
        int bits = 0;
        int index = 0;
        for (int i = 0; i < fingerprint.length(); i++) {
            final int value = BASE64_URL_ALPHABET.indexOf(fingerprint.charAt(i));
            if (value < 0) {
                return null;
            }
            buffer = (buffer << 6) | value;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                if (index >= bytes.length) {
                    return null;
                }
                bytes[index++] = (byte) ((buffer >> bits) & 0xFF);
            }
        }
        // 43 characters carry 258 bits; the last two padding bits must be zero.
        return index == bytes.length && bits == 2 && (buffer & 0x03) == 0 ? bytes : null;
    }

    @NonNull
    private static String encodeBase64Url(@NonNull final byte[] data) {
        final StringBuilder encoded = new StringBuilder(43);
        int buffer = 0;
        int bits = 0;
        for (final byte value : data) {
            buffer = (buffer << 8) | (value & 0xFF);
            bits += 8;
            while (bits >= 6) {
                bits -= 6;
                encoded.append(BASE64_URL_ALPHABET.charAt((buffer >> bits) & 0x3F));
            }
        }
        if (bits > 0) {
            encoded.append(BASE64_URL_ALPHABET.charAt((buffer << (6 - bits)) & 0x3F));
        }
        return encoded.toString();
    }
}
