package com.obby.android.localscreenshare.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.obby.android.localscreenshare.core.codec.CodecRegistry;
import com.obby.android.localscreenshare.core.model.BitstreamFormat;
import com.obby.android.localscreenshare.core.model.ClientSessionKey;
import com.obby.android.localscreenshare.core.model.StreamDescriptor;
import com.obby.android.localscreenshare.core.model.StreamKey;
import com.obby.android.localscreenshare.core.model.StreamParameters;
import com.obby.android.localscreenshare.core.stream.ClientCapabilities;
import com.obby.android.localscreenshare.core.stream.StreamHub;
import com.obby.android.localscreenshare.core.stream.StreamRuntime;
import com.obby.android.localscreenshare.core.support.FakeCodec;
import com.obby.android.localscreenshare.core.support.FakeTransport;
import com.obby.android.localscreenshare.core.support.MutableClock;

import org.junit.Test;

import java.util.Collections;

/**
 * Unit tests for {@link RuntimeBitrateTarget}.
 */
public class RuntimeBitrateTargetTest {

    private static final StreamKey STREAM_KEY = new StreamKey("session-1", "stream-1");

    @Test
    public void codecId_isNullBeforeADescriptorIsPublished() {
        FakeDeferredFixture fixture = deferredFixture();

        assertNull(new RuntimeBitrateTarget(fixture.hub, fixture.runtime).getCodecId());
    }

    @Test
    public void sessionCount_readsTheHub() {
        Fixture fixture = fixture();

        RuntimeBitrateTarget target = new RuntimeBitrateTarget(fixture.hub, fixture.runtime);
        assertEquals(0, target.getSessionCount());

        fixture.hub.join(new ClientSessionKey(STREAM_KEY, "client-1"),
                new ClientCapabilities(Collections.singleton(FakeCodec.DEFAULT_CODEC_ID)),
                new FakeTransport());

        assertEquals(fixture.hub.getSessionCount(), target.getSessionCount());
        assertEquals(1, target.getSessionCount());
    }

    @Test
    public void applyTargetBitrate_updatesTheRuntimeParameters() {
        Fixture fixture = fixture();

        new RuntimeBitrateTarget(fixture.hub, fixture.runtime).applyTargetBitrate(2_000_000);

        assertEquals(2_000_000, fixture.runtime.getParameters().getTargetBitrateBps());
    }

    private static Fixture fixture() {
        FakeCodec codec = new FakeCodec(FakeCodec.DEFAULT_CODEC_ID, 0, false, false);
        CodecRegistry registry = new CodecRegistry();
        registry.register(codec.registration());
        StreamRuntime runtime = new StreamRuntime(registry, STREAM_KEY,
                StreamDescriptor.builder(STREAM_KEY, 1, FakeCodec.DEFAULT_CODEC_ID,
                                FakeCodec.DEFAULT_MIME, BitstreamFormat.ANNEX_B)
                        .codedSize(64, 48)
                        .build(),
                StreamParameters.DEFAULT, new MutableClock(), 100L);
        runtime.start();
        StreamHub hub = new StreamHub(registry);
        hub.register(runtime);
        hub.activate(STREAM_KEY);
        return new Fixture(runtime, hub);
    }

    private static final class Fixture {
        final StreamRuntime runtime;
        final StreamHub hub;

        Fixture(StreamRuntime runtime, StreamHub hub) {
            this.runtime = runtime;
            this.hub = hub;
        }
    }

    private static FakeDeferredFixture deferredFixture() {
        CodecRegistry registry = new CodecRegistry();
        com.obby.android.localscreenshare.core.support.FakeDeferredCodec codec =
                new com.obby.android.localscreenshare.core.support.FakeDeferredCodec();
        registry.register(codec.registration());
        StreamRuntime runtime = new StreamRuntime(registry, STREAM_KEY,
                StreamDescriptor.builder(STREAM_KEY, 1,
                                com.obby.android.localscreenshare.core.support.FakeDeferredCodec
                                        .DEFAULT_CODEC_ID,
                                com.obby.android.localscreenshare.core.support.FakeDeferredCodec
                                        .DEFAULT_MIME,
                                BitstreamFormat.ANNEX_B)
                        .codedSize(64, 48)
                        .build(),
                StreamParameters.DEFAULT, new MutableClock(), 100L);
        runtime.start();
        StreamHub hub = new StreamHub(registry);
        hub.register(runtime);
        hub.activate(STREAM_KEY);
        return new FakeDeferredFixture(runtime, hub);
    }

    private static final class FakeDeferredFixture {
        final StreamRuntime runtime;
        final StreamHub hub;

        FakeDeferredFixture(StreamRuntime runtime, StreamHub hub) {
            this.runtime = runtime;
            this.hub = hub;
        }
    }
}
