package com.obby.android.localscreenshare.discovery.manual;

import androidx.annotation.NonNull;

import java.util.Locale;
import java.util.Objects;

/**
 * One address typed by the user: a literal host and a port.
 *
 * <p>The parser accepts the shapes users actually type: {@code 192.168.1.7}, {@code
 * 192.168.1.7:8080}, {@code [fe80::1]:8080}, {@code [fe80::1]} and bare IPv6 such as
 * {@code fe80::1}. A bare IPv6 literal cannot carry an unbracketed port, because its colons are
 * part of the address, so it falls back to the default port. Names are rejected: a manual entry is
 * meant to bypass DNS, and resolving one would make discovery depend on a server that may not
 * exist.</p>
 *
 * <p>The stored host is always the bare literal, matching what NSD resolution returns; the
 * bracketed form is only a display concern.</p>
 */
public final class ManualAddress {

    @NonNull
    private final String mHost;

    private final int mPort;

    private final boolean mIpv6;

    private ManualAddress(@NonNull final String host, final int port, final boolean ipv6) {
        mHost = host;
        mPort = port;
        mIpv6 = ipv6;
    }

    /**
     * Creates an address from a literal host and a port, bypassing the typed-input grammar.
     *
     * <p>Used for addresses that are already stored: the host is a bare literal, so the IPv6 shape
     * is detected here instead of being parsed out of a bracketed form.</p>
     *
     * @param host address literal; IPv6 literals are stored bare and lowercased
     * @param port port to store; must be in {@code 1..65535}
     * @return the address
     * @throws IllegalArgumentException if the host is blank or not a literal, or the port is out of
     *                                  range
     */
    @NonNull
    public static ManualAddress create(@NonNull final String host, final int port) {
        Objects.requireNonNull(host, "host");
        final String value = host.trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("empty host");
        }
        if (!isValidPort(port)) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
        if (looksLikeIpv6(value)) {
            return new ManualAddress(value.toLowerCase(Locale.ROOT), port, true);
        }
        if (!looksLikeIpv4(value)) {
            throw new IllegalArgumentException("not an IP literal: " + value);
        }
        return new ManualAddress(value, port, false);
    }

    /**
     * Parses one user entry.
     *
     * @param input text as typed
     * @param defaultPort port used when the entry omits one
     * @return the parsed address
     * @throws IllegalArgumentException if the input is not a literal address or the port is out of
     * range
     */
    @NonNull
    public static ManualAddress parse(@NonNull final String input, final int defaultPort) {
        Objects.requireNonNull(input, "input");
        final String value = input.trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("empty address");
        }
        if (!isValidPort(defaultPort)) {
            throw new IllegalArgumentException("default port out of range: " + defaultPort);
        }

        if (value.startsWith("[")) {
            final int closing = value.indexOf(']');
            if (closing < 0) {
                throw new IllegalArgumentException("unterminated IPv6 literal: " + value);
            }
            final String host = value.substring(1, closing);
            final String remainder = value.substring(closing + 1).trim();
            if (host.isEmpty() || !looksLikeIpv6(host)) {
                throw new IllegalArgumentException("not an IPv6 literal: " + value);
            }
            if (remainder.isEmpty()) {
                return new ManualAddress(host.toLowerCase(Locale.ROOT), defaultPort, true);
            }
            if (!remainder.startsWith(":")) {
                throw new IllegalArgumentException("unexpected suffix: " + value);
            }
            return new ManualAddress(host.toLowerCase(Locale.ROOT), parsePort(remainder.substring(1)),
                true);
        }

        final int firstColon = value.indexOf(':');
        final int lastColon = value.lastIndexOf(':');
        if (firstColon >= 0 && firstColon == lastColon) {
            final String host = value.substring(0, firstColon);
            if (!looksLikeIpv4(host)) {
                throw new IllegalArgumentException("not an IPv4 literal: " + value);
            }
            return new ManualAddress(host, parsePort(value.substring(firstColon + 1)), false);
        }
        if (firstColon >= 0) {
            // Several colons: an unbracketed IPv6 literal, which cannot also carry a port.
            if (!looksLikeIpv6(value)) {
                throw new IllegalArgumentException("not an IPv6 literal: " + value);
            }
            return new ManualAddress(value.toLowerCase(Locale.ROOT), defaultPort, true);
        }

        if (!looksLikeIpv4(value)) {
            throw new IllegalArgumentException("not an IPv4 literal: " + value);
        }
        return new ManualAddress(value, defaultPort, false);
    }

    @NonNull
    public String getHost() {
        return mHost;
    }

    public int getPort() {
        return mPort;
    }

    /**
     * True when the host is an IPv6 literal.
     */
    public boolean isIpv6() {
        return mIpv6;
    }

    /**
     * Returns the host in the form used in {@code host:port} text.
     */
    @NonNull
    public String displayHost() {
        return mIpv6 ? "[" + mHost + "]" : mHost;
    }

    @NonNull
    @Override
    public String toString() {
        return displayHost() + ":" + mPort;
    }

    private static int parsePort(@NonNull final String text) {
        final String value = text.trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("empty port");
        }
        final int port;
        try {
            port = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid port: " + value, e);
        }
        if (!isValidPort(port)) {
            throw new IllegalArgumentException("port out of range: " + value);
        }
        return port;
    }

    private static boolean isValidPort(final int port) {
        return port > 0 && port <= 0xFFFF;
    }

    private static boolean looksLikeIpv4(@NonNull final String host) {
        final String[] parts = host.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        for (final String part : parts) {
            if (part.isEmpty() || part.length() > 3) {
                return false;
            }
            for (int i = 0; i < part.length(); i++) {
                final char c = part.charAt(i);
                if (c < '0' || c > '9') {
                    return false;
                }
            }
            if (Integer.parseInt(part) > 255) {
                return false;
            }
        }
        return true;
    }

    private static boolean looksLikeIpv6(@NonNull final String host) {
        if (host.indexOf(':') < 0) {
            return false;
        }
        boolean hasDigit = false;
        for (int i = 0; i < host.length(); i++) {
            final char c = host.charAt(i);
            if (c >= '0' && c <= '9') {
                hasDigit = true;
            } else if (!((c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')
                || c == ':' || c == '.' || c == '%')) {
                return false;
            }
        }
        return hasDigit;
    }
}
