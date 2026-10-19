package com.obby.android.localscreenshare.core.stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.obby.android.localscreenshare.core.codec.CodecIds;
import com.obby.android.localscreenshare.core.codec.CodecRegistry;
import com.obby.android.localscreenshare.core.model.BitstreamFormat;
import com.obby.android.localscreenshare.core.model.EncodedAccessUnit;
import com.obby.android.localscreenshare.core.model.StreamDescriptor;
import com.obby.android.localscreenshare.core.model.StreamKey;
import com.obby.android.localscreenshare.core.model.StreamParameters;
import com.obby.android.localscreenshare.core.support.FakeCodec;
import com.obby.android.localscreenshare.core.support.JpegFixtures;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/** Unit tests for {@link StreamRuntime#submitFrame}. */
public class StreamRuntimeSubmitFrameTest {

    private static final StreamKey KEY = new StreamKey("session", "stream");

    @Test
    public void submitFrame_emitsAccessUnitOnJpegRuntime() {
        RecordingListener listener = new RecordingListener();
        StreamRuntime runtime = jpegRuntime(listener);
        runtime.start();

        assertTrue(runtime.submitFrame(1000L, JpegFixtures.REAL_JPEG));

        assertEquals(1, listener.accessUnits.size());
        EncodedAccessUnit unit = listener.accessUnits.get(0);
        assertEquals(1, unit.getConfigEpoch());
        assertEquals(1000L, unit.getPtsUs());
        assertTrue(unit.getFlags().isJoinPoint());
    }

    @Test
    public void submitFrame_rejectedBeforeStart() {
        RecordingListener listener = new RecordingListener();
        StreamRuntime runtime = jpegRuntime(listener);

        assertFalse(runtime.submitFrame(1000L, JpegFixtures.REAL_JPEG));
        assertTrue(listener.accessUnits.isEmpty());
    }

    @Test
    public void submitFrame_rejectsNullOrEmptyPayload() {
        StreamRuntime runtime = jpegRuntime(new RecordingListener());
        runtime.start();

        assertFalse(runtime.submitFrame(1000L, null));
        assertFalse(runtime.submitFrame(1000L, new byte[0]));
    }

    @Test
    public void submitFrame_rejectedForNonFrameInputCodec() {
        CodecRegistry registry = new CodecRegistry();
        registry.register(new FakeCodec(FakeCodec.DEFAULT_CODEC_ID, 0, false, false).registration());
        StreamRuntime runtime = new StreamRuntime(registry, KEY,
                StreamDescriptor.builder(KEY, 1, FakeCodec.DEFAULT_CODEC_ID,
                                FakeCodec.DEFAULT_MIME, BitstreamFormat.ANNEX_B)
                        .codedSize(64, 48).build(),
                StreamParameters.DEFAULT);
        runtime.start();

        assertFalse(runtime.submitFrame(1000L, JpegFixtures.REAL_JPEG));
    }

    private static StreamRuntime jpegRuntime(RecordingListener listener) {
        CodecRegistry registry = CodecRegistry.withDefaults();
        StreamRuntime runtime = new StreamRuntime(registry, KEY,
                StreamDescriptor.builder(KEY, 1, CodecIds.JPEG, "image/jpeg",
                                BitstreamFormat.JPEG_FRAME)
                        .codedSize(64, 48).build(),
                StreamParameters.DEFAULT);
        runtime.addListener(listener);
        return runtime;
    }

    private static final class RecordingListener implements StreamRuntimeListener {
        final List<EncodedAccessUnit> accessUnits = new ArrayList<>();

        @Override
        public void onAccessUnit(EncodedAccessUnit accessUnit) {
            accessUnits.add(accessUnit);
        }

        @Override
        public void onDescriptorChanged(StreamDescriptor descriptor) {
        }
    }
}
