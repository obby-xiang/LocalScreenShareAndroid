package com.obby.android.localscreenshare.server;

import androidx.annotation.NonNull;

@FunctionalInterface
public interface LssServerInfoListener {
    void onServerInfoChanged(@NonNull LssServerInfo serverInfo);
}
