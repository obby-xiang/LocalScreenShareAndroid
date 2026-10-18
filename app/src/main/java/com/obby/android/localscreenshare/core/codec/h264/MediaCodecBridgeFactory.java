package com.obby.android.localscreenshare.core.codec.h264;

public interface MediaCodecBridgeFactory {

    MediaCodecEncoderBridge createEncoder(String mime);

    MediaCodecDecoderBridge createDecoder(String mime);
}
