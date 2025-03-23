package com.obby.android.localscreenshare.server;

import androidx.annotation.NonNull;

@FunctionalInterface
public interface LssServerStatsListener {
    void onServerStatsChanged(@NonNull LssServerStats serverStats);
}
