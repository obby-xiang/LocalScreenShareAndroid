package com.obby.android.localscreenshare.discovery;

import androidx.annotation.NonNull;

import java.util.Comparator;

import lombok.Builder;
import lombok.Data;
import lombok.experimental.Accessors;

@Accessors(prefix = "m")
@Data
@Builder(builderClassName = "Builder", toBuilder = true)
public class LssServiceInfo implements Comparable<LssServiceInfo> {
    private static final Comparator<LssServiceInfo> COMPARATOR =
        Comparator.nullsLast(Comparator.comparing(LssServiceInfo::getHostAddress)
            .thenComparing(LssServiceInfo::getPort)
            .thenComparing(LssServiceInfo::getName)
            .thenComparing(LssServiceInfo::getId));

    @NonNull
    private final String mId;

    @NonNull
    private final String mName;

    @NonNull
    private final String mHostAddress;

    private final int mPort;

    @Override
    public int compareTo(LssServiceInfo serviceInfo) {
        return COMPARATOR.compare(this, serviceInfo);
    }
}
