package com.obby.android.localscreenshare.discovery;

import androidx.annotation.NonNull;

import java.util.Comparator;

import lombok.Builder;
import lombok.Data;
import lombok.experimental.Accessors;

@Accessors(prefix = "m")
@Data
@Builder(builderClassName = "Builder", toBuilder = true)
public class ShareServiceInfo implements Comparable<ShareServiceInfo> {
    private static final Comparator<ShareServiceInfo> COMPARATOR =
        Comparator.nullsLast(Comparator.comparing(ShareServiceInfo::getHostAddress)
            .thenComparing(ShareServiceInfo::getPort)
            .thenComparing(ShareServiceInfo::getName)
            .thenComparing(ShareServiceInfo::getId));

    @NonNull
    private final String mId;

    @NonNull
    private final String mName;

    @NonNull
    private final String mHostAddress;

    private final int mPort;

    @Override
    public int compareTo(ShareServiceInfo serviceInfo) {
        return COMPARATOR.compare(this, serviceInfo);
    }
}
