package com.obby.android.localscreenshare.utils;

import androidx.annotation.NonNull;

import java.net.Inet6Address;
import java.net.InetAddress;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * Host address formatting shared by discovery and server registration.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class NetUtils {
    /**
     * Formats an address for use in a {@code host:port} string. IPv6 literals are wrapped in
     * brackets so their colons are not mistaken for the port separator; IPv4 literals are
     * returned unchanged.
     */
    @NonNull
    public static String getHostAddress(@NonNull final InetAddress address) {
        return String.format(address instanceof Inet6Address ? "[%s]" : "%s", address.getHostAddress());
    }
}
