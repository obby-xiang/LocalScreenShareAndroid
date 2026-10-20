package com.obby.android.localscreenshare.support;

import androidx.annotation.NonNull;

import java.util.Locale;

/**
 * Encoder and decoder for Crockford's base32 variant.
 *
 * <p>The alphabet is {@code 0-9A-HJKMNP-TV-Z}: letters {@code I}, {@code L}, {@code O} and
 * {@code U} are excluded so a hand-typed id cannot be confused. Bytes are packed as a bit stream,
 * five bits per symbol, most significant bit first, and the trailing bits of the last symbol are
 * zero. Encoding is unpadded and uppercase; decoding accepts lowercase, hyphens and the visually
 * ambiguous {@code I}/{@code L} (read as {@code 1}) and {@code O} (read as {@code 0}).</p>
 *
 * <p>The class carries no state and performs no I/O, so it is used for the device identity on the
 * JVM and on the device alike.</p>
 */
public final class Base32Crockford {

    /** Encoding alphabet; the index of a character is its five-bit value. */
    public static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

    /** Radix of the encoding. */
    public static final int RADIX = 32;

    private static final int BITS_PER_SYMBOL = 5;

    private static final int BITS_PER_BYTE = 8;

    private Base32Crockford() {
    }

    /**
     * Returns the encoded length of {@code byteCount} bytes: five bits per symbol, rounded up.
     */
    public static int encodedLength(final int byteCount) {
        if (byteCount < 0) {
            throw new IllegalArgumentException("byteCount must not be negative");
        }
        return (byteCount * BITS_PER_BYTE + BITS_PER_SYMBOL - 1) / BITS_PER_SYMBOL;
    }

    /**
     * Encodes bytes as uppercase, unpadded Crockford base32.
     *
     * @return the encoded text, empty for an empty input
     * @throws IllegalArgumentException if {@code data} is null
     */
    @NonNull
    public static String encode(@NonNull final byte[] data) {
        if (data == null) {
            throw new IllegalArgumentException("data must not be null");
        }
        if (data.length == 0) {
            return "";
        }

        final StringBuilder encoded = new StringBuilder(encodedLength(data.length));
        int buffer = 0;
        int bits = 0;
        for (final byte value : data) {
            buffer = (buffer << BITS_PER_BYTE) | (value & 0xFF);
            bits += BITS_PER_BYTE;
            while (bits >= BITS_PER_SYMBOL) {
                bits -= BITS_PER_SYMBOL;
                encoded.append(ALPHABET.charAt((buffer >> bits) & (RADIX - 1)));
            }
        }
        if (bits > 0) {
            encoded.append(ALPHABET.charAt((buffer << (BITS_PER_SYMBOL - bits)) & (RADIX - 1)));
        }
        return encoded.toString();
    }

    /**
     * Decodes Crockford base32 text, ignoring case, hyphens and whitespace.
     *
     * <p>Only whole bytes are returned, so the two trailing padding bits of a fully encoded block
     * are dropped. Ambiguous letters are mapped the way Crockford specifies.</p>
     *
     * @throws IllegalArgumentException if the text is null or contains a character outside the
     *     alphabet
     */
    @NonNull
    public static byte[] decode(@NonNull final String text) {
        if (text == null) {
            throw new IllegalArgumentException("text must not be null");
        }

        final byte[] decoded = new byte[text.length() * BITS_PER_SYMBOL / BITS_PER_BYTE];
        int buffer = 0;
        int bits = 0;
        int index = 0;
        for (int i = 0; i < text.length(); i++) {
            final char symbol = text.charAt(i);
            if (symbol == '-' || Character.isWhitespace(symbol)) {
                continue;
            }
            buffer = (buffer << BITS_PER_SYMBOL) | decodeSymbol(symbol);
            bits += BITS_PER_SYMBOL;
            if (bits >= BITS_PER_BYTE) {
                bits -= BITS_PER_BYTE;
                decoded[index++] = (byte) ((buffer >> bits) & 0xFF);
                buffer &= bits == 0 ? 0 : (1 << bits) - 1;
            }
        }
        if (index == decoded.length) {
            return decoded;
        }

        // Hyphens and whitespace shortened the useful length; return exactly the decoded bytes.
        final byte[] trimmed = new byte[index];
        System.arraycopy(decoded, 0, trimmed, 0, index);
        return trimmed;
    }

    private static int decodeSymbol(final char symbol) {
        final char upper = Character.toUpperCase(symbol);
        switch (upper) {
            case 'I':
            case 'L':
                return 1;
            case 'O':
                return 0;
            default:
                break;
        }

        final int value = ALPHABET.indexOf(upper);
        if (value < 0) {
            throw new IllegalArgumentException(
                String.format(Locale.ROOT, "invalid base32 character: %s", symbol));
        }
        return value;
    }
}
