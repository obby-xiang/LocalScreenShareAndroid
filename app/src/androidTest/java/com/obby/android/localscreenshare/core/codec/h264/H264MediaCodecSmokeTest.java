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
 * Smoke test for the Android MediaCodec bridge behind {@link H264Codec}.
 *
 * <p>CI only compiles this test: the Build workflow runs
 * {@code :app:assembleDebugAndroidTest}, so the production bridge and
 * {@code H264Codec.androidRegistration()} cannot disappear without breaking the build.
 * Compilation is not device evidence.</p>
 *
 * <p>The test can be run by hand on an authorized device with
 * {@code ./gradlew :app:connectedDebugAndroidTest}, and only then does it prove that an
 * AVC encoder and decoder can be created, configured and released. The decoder is
 * configured with a {@code null} codec-specific data buffer on purpose (no fabricated
 * SPS/PPS bytes), which some devices and software decoders reject: a failure here is not
 * an implementation defect, and a pass does not prove that encoding, decoding, Surface
 * output or SPS/PPS timing work. Real codec verification belongs to the device regression
 * list (TB-ENG-013).</p>
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
            encoder.configure(new EncoderFormat(1280, 720, 4_000_000, 30, 1, 0f));
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
            // CSD is null only to keep this smoke test free of fabricated SPS/PPS bytes;
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
