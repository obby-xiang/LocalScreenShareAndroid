package com.obby.android.localscreenshare.client;

import androidx.annotation.NonNull;

import com.obby.android.localscreenshare.grpc.screenstream.ScreenFrame;

public interface LssClientObserver {
    void onScreenFrameReceived(@NonNull ScreenFrame frame);

    void onDisconnected();
}
