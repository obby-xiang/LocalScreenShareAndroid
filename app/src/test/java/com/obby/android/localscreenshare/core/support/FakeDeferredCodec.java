package com.obby.android.localscreenshare.core.support;

import android.view.Surface;

import com.obby.android.localscreenshare.core.codec.CodecDescriptor;
import com.obby.android.localscreenshare.core.codec.CodecPolicy;
import com.obby.android.localscreenshare.core.codec.CodecRegistration;
import com.obby.android.localscreenshare.core.codec.Decoder;
import com.obby.android.localscreenshare.core.codec.DecoderFactory;
import com.obby.android.localscreenshare.core.codec.Encoder;
import com.obby.android.localscreenshare.core.codec.EncoderConfig;
import com.obby.android.localscreenshare.core.codec.EncoderException;
import com.obby.android.localscreenshare.core.codec.EncoderFactory;
import com.obby.android.localscreenshare.core.codec.EncoderOutput;
import com.obby.android.localscreenshare.core.codec.OverflowAction;
import com.obby.android.localscreenshare.core.model.AccessUnitFlags;
import com.obby.android.localscreenshare.core.model.BitstreamFormat;
import com.obby.android.localscreenshare.core.model.EncodedAccessUnit;
import com.obby.android.localscreenshare.core.model.JoinInfo;
import com.obby.android.localscreenshare.core.model.StreamDescriptor;
import com.obby.android.localscreenshare.core.model.StreamKey;
import com.obby.android.localscreenshare.core.model.StreamParameters;
import com.obby.android.localscreenshare.core.render.RenderTarget;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * JVM-only codec whose encoder publishes its configuration only after startup.
 *
 * <p>The codec advertises {@code carriesConfigParts=true}; the encoder is started
 * before it knows the final coded size and config parts, and reports them later via
 * {@link FakeEncoder#emitConfig(EncoderConfig)}. No Android media API is touched and
 * the input surface is always {@code null}, so the codec is usable on the JVM only
 * with {@code surfaceInputRequired=false}.</p>
 */
public final class FakeDeferredCodec {

    public static final String DEFAULT_CODEC_ID = "fake-deferred";

    public static final String DEFAULT_MIME = "video/fake-deferred";

    private final CodecRegistration registration;

    private Runnable startProbe;

    private Runnable closeProbe;

    private boolean failNextEncoderCreate;

    private boolean failNextEncoderStart;

    private FakeEncoder lastEncoder;

    private int encoderCreateCount;

    public FakeDeferredCodec() {
        this(DEFAULT_CODEC_ID, false);
    }

    public FakeDeferredCodec(String codecId) {
        this(codecId, false);
    }

    public FakeDeferredCodec(String codecId, boolean surfaceInputRequired) {
        CodecDescriptor descriptor = new CodecDescriptor(codecId, DEFAULT_MIME,
                BitstreamFormat.ANNEX_B, surfaceInputRequired, false, true);
        this.registration = new CodecRegistration(descriptor, encoderFactory(), decoderFactory(),
                policy(), 0);
    }

    public CodecRegistration registration() {
        return registration;
    }

    public FakeEncoder getLastEncoder() {
        return lastEncoder;
    }

    public int getEncoderCreateCount() {
        return encoderCreateCount;
    }

    public void setStartProbe(Runnable startProbe) {
        this.startProbe = startProbe;
        if (lastEncoder != null) {
            lastEncoder.setStartProbe(startProbe);
        }
    }

    public void setCloseProbe(Runnable closeProbe) {
        this.closeProbe = closeProbe;
        if (lastEncoder != null) {
            lastEncoder.setCloseProbe(closeProbe);
        }
    }

    public void failNextEncoderCreate() {
        this.failNextEncoderCreate = true;
    }

    public void failNextEncoderStart() {
        this.failNextEncoderStart = true;
    }

    private EncoderFactory encoderFactory() {
        return new EncoderFactory() {
            @Override
            public Encoder create(StreamKey streamKey, StreamDescriptor descriptor,
                                  StreamParameters parameters, EncoderOutput output) {
                if (failNextEncoderCreate) {
                    failNextEncoderCreate = false;
                    throw new IllegalStateException("injected deferred encoder creation failure");
                }
                boolean failOnStart = failNextEncoderStart;
                failNextEncoderStart = false;
                FakeEncoder encoder = new FakeEncoder(streamKey, descriptor, parameters, output,
                        startProbe, closeProbe, failOnStart);
                lastEncoder = encoder;
                encoderCreateCount++;
                return encoder;
            }
        };
    }

    private DecoderFactory decoderFactory() {
        return new DecoderFactory() {
            @Override
            public Decoder create(StreamKey streamKey, StreamDescriptor descriptor,
                                  RenderTarget renderTarget) {
                throw new UnsupportedOperationException("FakeDeferredCodec has no decoder");
            }
        };
    }

    private static CodecPolicy policy() {
        return new CodecPolicy() {
            @Override
            public OverflowAction getOverflowAction() {
                return OverflowAction.DROP_UNTIL_JOIN_POINT;
            }

            @Override
            public boolean isJoinPoint(EncodedAccessUnit accessUnit) {
                return accessUnit != null && accessUnit.getFlags().isJoinPoint();
            }

            @Override
            public boolean requiresReconfigure(StreamDescriptor previous,
                                               StreamDescriptor next) {
                return previous == null || next == null || !previous.sameDecodeConfig(next);
            }
        };
    }

    @Override
    public String toString() {
        return "FakeDeferredCodec{" + registration.getDescriptor().getCodecId() + "}";
    }

    /**
     * Encoder that starts before it knows its final configuration and records the
     * lifecycle calls the runtime is allowed to make.
     */
    public static final class FakeEncoder implements Encoder {

        private final StreamKey streamKey;

        private final StreamDescriptor descriptor;

        private final EncoderOutput output;

        private final List<EncodedAccessUnit> emitted = new ArrayList<>();

        private final List<EncoderConfig> configs = new ArrayList<>();

        private final List<EncoderException> failures = new ArrayList<>();

        private Runnable startProbe;

        private Runnable closeProbe;

        private boolean failOnStart;

        private boolean started;

        private boolean stopped;

        private boolean closed;

        private int startCount;

        private int stopCount;

        private int closeCount;

        private int requestCount;

        private int sequenceEpoch = -1;

        private long sequence;

        private StreamParameters lastParameters;

        private FakeEncoder(StreamKey streamKey, StreamDescriptor descriptor,
                            StreamParameters parameters, EncoderOutput output,
                            Runnable startProbe, Runnable closeProbe, boolean failOnStart) {
            this.streamKey = streamKey;
            this.descriptor = descriptor;
            this.lastParameters = parameters;
            this.output = output;
            this.startProbe = startProbe;
            this.closeProbe = closeProbe;
            this.failOnStart = failOnStart;
        }

        @Override
        public void start() {
            startCount++;
            if (startProbe != null) {
                startProbe.run();
            }
            if (failOnStart) {
                failOnStart = false;
                throw new IllegalStateException("injected deferred encoder start failure");
            }
            started = true;
        }

        @Override
        public void stop() {
            stopCount++;
            stopped = true;
        }

        @Override
        public Surface getInputSurface() {
            return null;
        }

        @Override
        public void updateParameters(StreamParameters parameters) {
            lastParameters = parameters;
        }

        @Override
        public void requestSyncPoint() {
            requestCount++;
        }

        @Override
        public void close() {
            closeCount++;
            closed = true;
            if (closeProbe != null) {
                closeProbe.run();
            }
        }

        public void emitConfig(EncoderConfig config) {
            configs.add(config);
            output.onEncoderConfig(config);
        }

        public void emit(boolean joinPoint, int configEpoch, byte[] payload) {
            if (sequenceEpoch != configEpoch) {
                sequenceEpoch = configEpoch;
                sequence = 0;
            }
            AccessUnitFlags flags = AccessUnitFlags.of(joinPoint, joinPoint, false);
            JoinInfo joinInfo = joinPoint ? new JoinInfo(configEpoch, descriptor.getConfigParts())
                    : null;
            EncodedAccessUnit accessUnit = new EncodedAccessUnit(streamKey, configEpoch, sequence,
                    sequence * 1000L, EncodedAccessUnit.DTS_UNSET, flags, joinInfo, null, payload);
            sequence++;
            emitted.add(accessUnit);
            output.onAccessUnit(accessUnit);
        }

        public void fail(EncoderException failure) {
            failures.add(failure);
            output.onEncoderFailure(failure);
        }

        public void setStartProbe(Runnable startProbe) {
            this.startProbe = startProbe;
        }

        public void setCloseProbe(Runnable closeProbe) {
            this.closeProbe = closeProbe;
        }

        public boolean isStarted() {
            return started;
        }

        public boolean isStopped() {
            return stopped;
        }

        public boolean isClosed() {
            return closed;
        }

        public int getStartCount() {
            return startCount;
        }

        public int getStopCount() {
            return stopCount;
        }

        public int getCloseCount() {
            return closeCount;
        }

        public int getRequestCount() {
            return requestCount;
        }

        public StreamParameters getLastParameters() {
            return lastParameters;
        }

        public List<EncodedAccessUnit> getEmitted() {
            return Collections.unmodifiableList(emitted);
        }

        public List<EncoderConfig> getEmittedConfigs() {
            return Collections.unmodifiableList(configs);
        }

        public List<EncoderException> getReportedFailures() {
            return Collections.unmodifiableList(failures);
        }
    }
}
