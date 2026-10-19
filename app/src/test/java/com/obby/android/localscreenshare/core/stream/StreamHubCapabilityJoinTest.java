package com.obby.android.localscreenshare.core.stream;

import static org.junit.Assert.assertEquals;

import com.obby.android.localscreenshare.core.codec.CodecRegistry;
import com.obby.android.localscreenshare.core.model.BitstreamFormat;
import com.obby.android.localscreenshare.core.model.ClientSessionKey;
import com.obby.android.localscreenshare.core.model.StreamDescriptor;
import com.obby.android.localscreenshare.core.model.StreamKey;
import com.obby.android.localscreenshare.core.model.StreamParameters;
import com.obby.android.localscreenshare.core.support.FakeCodec;
import com.obby.android.localscreenshare.core.support.FakeTransport;
import com.obby.android.localscreenshare.core.support.MutableClock;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

/** Unit tests for capability-aware fallback selection in {@link StreamHub}. */
public class StreamHubCapabilityJoinTest {

    private static final String ACTIVE_CODEC = "fake";

    private static final String FALLBACK_CODEC = "fallbackA";

    private static final StreamKey KEY = new StreamKey("session", "stream");

    @Test
    public void fallback_rejectsIncompatibleProfile() {
        Fixture fixture = fixture();

        assertEquals(JoinDecision.Status.ACCEPTED,
                fixture.hub.join(new ClientSessionKey(KEY, "a"),
                        existingCapabilities(), new FakeTransport()).getStatus());

        JoinDecision decision = fixture.hub.join(new ClientSessionKey(KEY, "b"),
                new ClientCapabilities(capabilities(FALLBACK_CODEC, "high")),
                new FakeTransport());

        assertEquals(JoinDecision.Status.REJECTED, decision.getStatus());
        assertEquals(1, fixture.hub.getSessionCount());
    }

    @Test
    public void fallback_acceptsCompatibleProfile() {
        Fixture fixture = fixture();

        assertEquals(JoinDecision.Status.ACCEPTED,
                fixture.hub.join(new ClientSessionKey(KEY, "a"),
                        existingCapabilities(), new FakeTransport()).getStatus());

        JoinDecision decision = fixture.hub.join(new ClientSessionKey(KEY, "b"),
                new ClientCapabilities(capabilities(FALLBACK_CODEC, "baseline")),
                new FakeTransport());

        assertEquals(JoinDecision.Status.REQUIRES_EXPLICIT_FALLBACK, decision.getStatus());
        assertEquals(FALLBACK_CODEC, decision.getFallbackCodecId());
        assertEquals(1, fixture.hub.getSessionCount());
    }

    private static ClientCapabilities existingCapabilities() {
        Map<String, CodecCapability> caps = new LinkedHashMap<>();
        caps.put(ACTIVE_CODEC, CodecCapability.any(ACTIVE_CODEC));
        caps.put(FALLBACK_CODEC, new CodecCapability(FALLBACK_CODEC, null, "baseline", null,
                0, 0, 0, 0, false, false));
        return new ClientCapabilities(caps);
    }

    private static Map<String, CodecCapability> capabilities(String codecId, String profile) {
        Map<String, CodecCapability> caps = new LinkedHashMap<>();
        caps.put(codecId, new CodecCapability(codecId, null, profile, null,
                0, 0, 0, 0, false, false));
        return caps;
    }

    private static Fixture fixture() {
        FakeCodec active = new FakeCodec(ACTIVE_CODEC, 0, false, false);
        FakeCodec fallback = new FakeCodec(FALLBACK_CODEC, 20, false, false);
        CodecRegistry registry = new CodecRegistry();
        registry.register(active.registration());
        registry.register(fallback.registration());
        StreamRuntime runtime = new StreamRuntime(registry, KEY,
                StreamDescriptor.builder(KEY, 1, ACTIVE_CODEC, FakeCodec.DEFAULT_MIME,
                                BitstreamFormat.ANNEX_B)
                        .codedSize(64, 48).build(),
                StreamParameters.DEFAULT, new MutableClock(), 100L);
        runtime.start();
        StreamHub hub = new StreamHub(registry);
        hub.register(runtime);
        hub.activate(KEY);
        return new Fixture(hub);
    }

    private static final class Fixture {
        final StreamHub hub;

        Fixture(StreamHub hub) {
            this.hub = hub;
        }
    }
}
