package com.obby.android.localscreenshare.core.codec.h264;

import android.graphics.SurfaceTexture;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.view.Surface;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.obby.android.localscreenshare.core.codec.CodecRegistration;
import com.obby.android.localscreenshare.core.codec.mediacodec.AndroidMediaCodecBridgeFactory;
import com.obby.android.localscreenshare.core.codec.mediacodec.AndroidMediaCodecDecoderBridge;
import com.obby.android.localscreenshare.core.codec.mediacodec.AndroidMediaCodecEncoderBridge;
import com.obby.android.localscreenshare.core.codec.mediacodec.DecoderFormat;
import com.obby.android.localscreenshare.core.codec.mediacodec.EncoderFormat;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Compile-only smoke test for the Android MediaCodec bridge produced by Task 8.
 *
 * <p><b>This test is never executed in this batch.</b> It is compiled against the production
 * classes so that the build fails while the Android bridge and {@code H264Codec.androidRegistration()}
 * are missing, and compiles once they exist. Passing compilation is the only evidence it produces:
 * it is not device evidence and does not prove that encoding, decoding, Surface output or SPS/PPS
 * timing work on a real device. No {@code connectedAndroidTest}, adb, emulator or physical device
 * run accompanies it.</p>
 */
@RunWith(AndroidJUnit4.class)
public class H264MediaCodecSmokeTest {

    @Test
    public void avcEncoderAndDecoderAreListed() {
        boolean encoderListed = false;
        boolean decoderListed = false;
        for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            for (String type : info.getSupportedTypes()) {
                if (!H264Codec.MIME.equalsIgnoreCase(type)) {
                    continue;
                }
                if (info.isEncoder()) {
                    encoderListed = true;
                } else {
                    decoderListed = true;
                }
            }
        }
        assertTrue("No AVC encoder is listed on this device", encoderListed);
        assertTrue("No AVC decoder is listed on this device", decoderListed);

        CodecRegistration registration = H264Codec.androidRegistration();
        assertEquals(H264Codec.MIME, registration.getDescriptor().getMime());
        assertTrue(registration.getDescriptor().isSurfaceInputRequired());
        assertTrue(registration.getDescriptor().isSurfaceOutputRequired());
    }

    @Test
    public void encoderCanCreateInputSurfaceAndRelease() {
        AndroidMediaCodecBridgeFactory bridgeFactory = new AndroidMediaCodecBridgeFactory();
        AndroidMediaCodecEncoderBridge encoder =
                (AndroidMediaCodecEncoderBridge) bridgeFactory.createEncoder(H264Codec.MIME);
        assertNotNull(encoder);
        try {
            encoder.configure(new EncoderFormat(1280, 720, 4_000_000, 30, 1));
            Surface inputSurface = encoder.createInputSurface();
            assertNotNull(inputSurface);
        } finally {
            encoder.release();
            // release must be idempotent; the second call must not throw.
            encoder.release();
        }
    }

    @Test
    public void decoderCanBeCreatedAndReleased() {
        SurfaceTexture texture = new SurfaceTexture(0);
        Surface outputSurface = new Surface(texture);
        AndroidMediaCodecDecoderBridge decoder = null;
        try {
            AndroidMediaCodecBridgeFactory bridgeFactory = new AndroidMediaCodecBridgeFactory();
            decoder = (AndroidMediaCodecDecoderBridge) bridgeFactory.createDecoder(H264Codec.MIME);
            assertNotNull(decoder);
            // CSD is null only to keep this compile-only test free of fabricated SPS/PPS bytes;
            // this is not a claim that a decoder can be configured without CSD.
            decoder.configure(new DecoderFormat(1280, 720, null, null, outputSurface));
        } finally {
            if (decoder != null) {
                decoder.release();
                // release must be idempotent; the second call must not throw.
                decoder.release();
            }
            outputSurface.release();
            texture.release();
        }
    }
}
