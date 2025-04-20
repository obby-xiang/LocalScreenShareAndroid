package com.obby.android.localscreenshare.client;

import android.graphics.Bitmap;

import androidx.annotation.NonNull;

import com.obby.android.localscreenshare.support.Reference;

public interface LssClientObserver {
    void onScreenFrameReceived(@NonNull Reference<Bitmap> frame);

    void onDisconnected();
}
