package com.obby.android.localscreenshare.utils;

import androidx.annotation.NonNull;

import java.net.Inet6Address;
import java.net.InetAddress;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class NetUtils {
    @NonNull
    public static String getHostAddress(@NonNull final InetAddress address) {
        return String.format(address instanceof Inet6Address ? "[%s]" : "%s", address.getHostAddress());
    }
}
