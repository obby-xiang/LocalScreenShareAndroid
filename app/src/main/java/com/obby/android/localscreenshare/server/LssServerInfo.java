package com.obby.android.localscreenshare.server;

import androidx.annotation.NonNull;

import lombok.Builder;
import lombok.Data;
import lombok.experimental.Accessors;

@Accessors(prefix = "m")
@Data
@Builder(builderClassName = "Builder", toBuilder = true)
public class LssServerInfo {
    @NonNull
    private final String mId;

    @NonNull
    private final String mName;

    @NonNull
    private final String mHostAddress;

    private final int mPort;
}
