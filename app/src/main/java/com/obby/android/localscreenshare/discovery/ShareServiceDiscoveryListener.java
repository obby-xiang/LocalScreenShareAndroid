package com.obby.android.localscreenshare.discovery;

import androidx.annotation.NonNull;

import java.util.List;

@FunctionalInterface
public interface ShareServiceDiscoveryListener {
    void onServicesChanged(@NonNull List<ShareServiceInfo> services);
}
