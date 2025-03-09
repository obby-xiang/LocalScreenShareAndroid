package com.obby.android.localscreenshare.discovery;

import androidx.annotation.NonNull;

import java.util.List;

@FunctionalInterface
public interface LssServiceDiscoveryListener {
    void onServicesChanged(@NonNull List<LssServiceInfo> services);
}
