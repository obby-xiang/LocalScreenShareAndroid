package com.obby.android.localscreenshare.client;

import androidx.annotation.NonNull;

@FunctionalInterface
public interface LssClientStatsListener {
    void onClientStatsChanged(@NonNull LssClientStats clientStats);
}
