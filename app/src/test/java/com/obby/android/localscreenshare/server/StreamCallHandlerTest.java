package com.obby.android.localscreenshare.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.obby.android.localscreenshare.core.codec.CodecRegistry;
import com.obby.android.localscreenshare.core.codec.EncoderConfig;
import com.obby.android.localscreenshare.core.model.BitstreamFormat;
import com.obby.android.localscreenshare.core.model.ClientState;
import com.obby.android.localscreenshare.core.model.CodecConfigPart;
import com.obby.android.localscreenshare.core.model.ColorInfo;
import com.obby.android.localscreenshare.core.model.StreamDescriptor;
import com.obby.android.localscreenshare.core.model.StreamKey;
import com.obby.android.localscreenshare.core.model.StreamParameters;
import com.obby.android.localscreenshare.core.stream.StreamHub;
import com.obby.android.localscreenshare.core.stream.StreamRuntime;
import com.obby.android.localscreenshare.core.support.FakeCodec;
import com.obby.android.localscreenshare.core.support.FakeDeferredCodec;
import com.obby.android.localscreenshare.core.support.FakeTransport;
import com.obby.android.localscreenshare.core.support.MutableClock;
import com.obby.android.localscreenshare.grpc.screenstream.Capabilities;
import com.obby.android.localscreenshare.grpc.screenstream.ClientMessage;
import com.obby.android.localscreenshare.grpc.screenstream.CodecCapability;
import com.obby.android.localscreenshare.grpc.screenstream.Hello;
import com.obby.android.localscreenshare.grpc.screenstream.RequestKeyframe;

import org.junit.Test;

import java.util.Collections;

/**
 * Unit tests for {@link StreamCallHandler}.
 */
public class StreamCallHandlerTest {

    private static final StreamKey STREAM_KEY = new StreamKey("session-1", "stream-1");

    @Test
    public void helloThenDrainSendsDescriptorBeforeAccessUnit() throws Exception {
        Fixture fixture = fixture(FakeCodec.DEFAULT_CODEC_ID);
        FakeTransport transport = new FakeTransport();
        StreamCallHandler handler = new StreamCallHandler(fixture.hub, fixture.runtime, transport);

        handler.onClientMessage(hello(FakeCodec.DEFAULT_CODEC_ID));
        assertTrue(handler.drainOnce());

        fixture.codec.emitJoin(new byte[]{0x01});
        assertTrue(handler.drainOnce());

        assertEquals(2, transport.getEvents().size());
        assertTrue(transport.getEvents().get(0).isDescriptor());
        assertTrue(transport.getEvents().get(1).isAccessUnit());
        assertEquals(ClientState.STREAMING, fixture.hub.getSession(
                new com.obby.android.localscreenshare.core.model.ClientSessionKey(
                        STREAM_KEY, "client-1")).getState());
    }

    @Test
    public void keyframeRequestForwardsToRuntime() {
        Fixture fixture = fixture(FakeCodec.DEFAULT_CODEC_ID);
        fixture.codec.setAutoEmitJoinOnRequest(false);
        StreamCallHandler handler = new StreamCallHandler(fixture.hub, fixture.runtime,
                new FakeTransport());

        handler.onClientMessage(ClientMessage.newBuilder()
                .setRequestKeyframe(RequestKeyframe.getDefaultInstance()).build());

        assertEquals(1, fixture.codec.getRequestCount());
    }

    @Test
    public void closeDetachesSessionAndClosesTransport() {
        Fixture fixture = fixture(FakeCodec.DEFAULT_CODEC_ID);
        FakeTransport transport = new FakeTransport();
        StreamCallHandler handler = new StreamCallHandler(fixture.hub, fixture.runtime, transport);

        handler.onClientMessage(hello(FakeCodec.DEFAULT_CODEC_ID));
        handler.close();
        handler.close();

        assertTrue(handler.isClosed());
        assertEquals(0, fixture.hub.getSessionCount());
        assertEquals(1, transport.getCloseCount());
    }

    @Test
    public void emptyCapabilitiesDefaultsToH264Baseline() throws Exception {
        Fixture fixture = fixture("h264");
        FakeTransport transport = new FakeTransport();
        StreamCallHandler handler = new StreamCallHandler(fixture.hub, fixture.runtime, transport);

        handler.onClientMessage(ClientMessage.newBuilder()
                .setHello(Hello.newBuilder().setClientId("legacy").build())
                .build());
        assertTrue(handler.drainOnce());
        assertTrue(transport.getEvents().get(0).isDescriptor());
    }

    @Test
    public void joinRetriesUntilDescriptorPublished() throws Exception {
        FakeDeferredCodec deferred = new FakeDeferredCodec();
        CodecRegistry registry = new CodecRegistry();
        registry.register(deferred.registration());
        StreamRuntime runtime = new StreamRuntime(registry, STREAM_KEY,
                StreamDescriptor.builder(STREAM_KEY, 1, FakeDeferredCodec.DEFAULT_CODEC_ID,
                                FakeDeferredCodec.DEFAULT_MIME, BitstreamFormat.ANNEX_B)
                        .codedSize(64, 48)
                        .build(),
                StreamParameters.DEFAULT, new MutableClock(), 100L);
        runtime.start();
        StreamHub hub = new StreamHub(registry);
        hub.register(runtime);
        hub.activate(STREAM_KEY);

        FakeTransport transport = new FakeTransport();
        StreamCallHandler handler = new StreamCallHandler(hub, runtime, transport);
        handler.onClientMessage(hello(FakeDeferredCodec.DEFAULT_CODEC_ID));

        // The deferred runtime has not published its descriptor yet.
        assertFalse(handler.drainOnce());
        assertEquals(0, hub.getSessionCount());

        deferred.getLastEncoder().emitConfig(new EncoderConfig(64, 48, ColorInfo.UNSPECIFIED,
                Collections.singletonList(new CodecConfigPart("sps", new byte[]{0x01}))));

        assertTrue(handler.drainOnce());
        assertEquals(1, hub.getSessionCount());
        assertTrue(transport.getEvents().get(0).isDescriptor());
    }

    private static Fixture fixture(String codecId) {
        FakeCodec codec = new FakeCodec(codecId, 0, false, false);
        CodecRegistry registry = new CodecRegistry();
        registry.register(codec.registration());
        StreamRuntime runtime = new StreamRuntime(registry, STREAM_KEY,
                StreamDescriptor.builder(STREAM_KEY, 1, codecId, FakeCodec.DEFAULT_MIME,
                                BitstreamFormat.ANNEX_B)
                        .codedSize(64, 48)
                        .build(),
                StreamParameters.DEFAULT, new MutableClock(), 100L);
        runtime.start();
        StreamHub hub = new StreamHub(registry);
        hub.register(runtime);
        hub.activate(STREAM_KEY);
        return new Fixture(codec, runtime, hub);
    }

    @Test
    public void helloIdentityIsCapturedForSessionBinding() {
        Fixture fixture = fixture(FakeCodec.DEFAULT_CODEC_ID);
        StreamCallHandler handler = new StreamCallHandler(fixture.hub, fixture.runtime,
                new FakeTransport());

        handler.onClientMessage(ClientMessage.newBuilder()
                .setHello(Hello.newBuilder()
                        .setClientId("client-1")
                        .setDeviceId("DEVICE-ID")
                        .setFingerprint("DEVICE-FP")
                        .setCapabilities(capabilities(FakeCodec.DEFAULT_CODEC_ID))
                        .build())
                .build());

        assertEquals("DEVICE-ID", handler.getDeviceId());
        assertEquals("DEVICE-FP", handler.getFingerprint());
    }

    @Test
    public void helloWithoutIdentityLeavesTheBindingEmpty() {
        Fixture fixture = fixture(FakeCodec.DEFAULT_CODEC_ID);
        StreamCallHandler handler = new StreamCallHandler(fixture.hub, fixture.runtime,
                new FakeTransport());

        handler.onClientMessage(hello(FakeCodec.DEFAULT_CODEC_ID));

        assertNull(handler.getDeviceId());
        assertNull(handler.getFingerprint());
    }

    private static Capabilities capabilities(String codecId) {
        return Capabilities.newBuilder()
                .addCodecs(CodecCapability.newBuilder().setCodecId(codecId).build())
                .build();
    }

    private static ClientMessage hello(String codecId) {
        return ClientMessage.newBuilder()
                .setHello(Hello.newBuilder()
                        .setClientId("client-1")
                        .setCapabilities(capabilities(codecId))
                        .build())
                .build();
    }

    private static final class Fixture {
        final FakeCodec codec;
        final StreamRuntime runtime;
        final StreamHub hub;

        Fixture(FakeCodec codec, StreamRuntime runtime, StreamHub hub) {
            this.codec = codec;
            this.runtime = runtime;
            this.hub = hub;
        }
    }
}
