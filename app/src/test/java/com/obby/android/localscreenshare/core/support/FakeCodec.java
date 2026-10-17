package com.obby.android.localscreenshare.core.support;

import android.view.Surface;

import com.obby.android.localscreenshare.core.codec.CodecDescriptor;
import com.obby.android.localscreenshare.core.codec.CodecPolicy;
import com.obby.android.localscreenshare.core.codec.CodecRegistration;
import com.obby.android.localscreenshare.core.codec.Decoder;
import com.obby.android.localscreenshare.core.codec.DecoderException;
import com.obby.android.localscreenshare.core.codec.DecoderFactory;
import com.obby.android.localscreenshare.core.codec.Encoder;
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
import com.obby.android.localscreenshare.core.render.EncodedImageFrame;
import com.obby.android.localscreenshare.core.render.RenderTarget;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * JVM-only codec whose encoder and decoder never touch Android media APIs.
 *
 * <p>Encoders emit access units through the {@link EncoderOutput} supplied to the
 * factory, and decoders push {@link EncodedImageFrame}s into the {@link RenderTarget}
 * supplied to the factory.</p>
 */
public final class FakeCodec {

    public static final String DEFAULT_CODEC_ID = "fake";

    public static final String DEFAULT_MIME = "video/fake";

    private final String codecId;

    private final int fallbackPriority;

    private final CodecRegistration registration;

    private FakeEncoder lastEncoder;

    private FakeDecoder lastDecoder;

    private boolean autoEmitJoinOnRequest;

    private boolean failNextEncoderCreate;

    private boolean failNextEncoderStart;

    private int encoderCreateCount;

    private int decoderCreateCount;

    public FakeCodec(String codecId, int fallbackPriority, boolean surfaceInputRequired,
                     boolean surfaceOutputRequired) {
        this.codecId = codecId;
        this.fallbackPriority = fallbackPriority;
        this.autoEmitJoinOnRequest = true;
        CodecDescriptor descriptor = new CodecDescriptor(codecId, DEFAULT_MIME,
                BitstreamFormat.ANNEX_B, surfaceInputRequired, surfaceOutputRequired, false);
        this.registration = new CodecRegistration(descriptor, encoderFactory(), decoderFactory(),
                policy(), fallbackPriority);
    }

    public CodecRegistration registration() {
        return registration;
    }

    public FakeEncoder getLastEncoder() {
        return lastEncoder;
    }

    public FakeDecoder getLastDecoder() {
        return lastDecoder;
    }

    public void setAutoEmitJoinOnRequest(boolean autoEmitJoinOnRequest) {
        this.autoEmitJoinOnRequest = autoEmitJoinOnRequest;
        if (lastEncoder != null) {
            lastEncoder.setAutoEmitJoinOnRequest(autoEmitJoinOnRequest);
        }
    }

    public void failNextEncoderCreate() {
        this.failNextEncoderCreate = true;
    }

    public void failNextEncoderStart() {
        this.failNextEncoderStart = true;
    }

    public int getEncoderCreateCount() {
        return encoderCreateCount;
    }

    public int getDecoderCreateCount() {
        return decoderCreateCount;
    }

    public void failNextStop() {
        requireEncoder().failNextStop();
    }

    public void failNextRequestSyncPoint() {
        requireEncoder().failNextRequestSyncPoint(null);
    }

    public void failNextRequestSyncPoint(Runnable beforeFailure) {
        requireEncoder().failNextRequestSyncPoint(beforeFailure);
    }

    public int getRequestCount() {
        return lastEncoder == null ? 0 : lastEncoder.getRequestCount();
    }

    public void emitNonJoin(byte[] payload) {
        requireEncoder().emitNonJoin(payload);
    }

    public void emitJoin(byte[] payload) {
        requireEncoder().emitJoin(payload);
    }

    public void emit(int configEpoch, boolean joinPoint, byte[] payload) {
        requireEncoder().emit(configEpoch, joinPoint, payload);
    }

    private FakeEncoder requireEncoder() {
        if (lastEncoder == null) {
            throw new IllegalStateException("no encoder has been created yet");
        }
        return lastEncoder;
    }

    private EncoderFactory encoderFactory() {
        return new EncoderFactory() {
            @Override
            public Encoder create(StreamKey streamKey, StreamDescriptor descriptor,
                                  StreamParameters parameters, EncoderOutput output) {
                if (failNextEncoderCreate) {
                    failNextEncoderCreate = false;
                    throw new IllegalStateException("injected encoder creation failure");
                }
                boolean failOnStart = failNextEncoderStart;
                failNextEncoderStart = false;
                FakeEncoder encoder = new FakeEncoder(streamKey, descriptor, parameters, output,
                        autoEmitJoinOnRequest, failOnStart);
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
                FakeDecoder decoder = new FakeDecoder(streamKey, descriptor, renderTarget);
                lastDecoder = decoder;
                decoderCreateCount++;
                return decoder;
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
            public boolean requiresReconfigure(StreamDescriptor previous, StreamDescriptor next) {
                return previous == null || next == null || !previous.sameDecodeConfig(next);
            }
        };
    }

    @Override
    public String toString() {
        return "FakeCodec{" + codecId + ", priority=" + fallbackPriority + "}";
    }

    /**
     * Encoder that records lifecycle calls and emits synthetic access units.
     */
    public static final class FakeEncoder implements Encoder {

        private final StreamKey streamKey;

        private final StreamDescriptor descriptor;

        private final EncoderOutput output;

        private final List<EncodedAccessUnit> emitted = new ArrayList<>();

        private boolean autoEmitJoinOnRequest;

        private boolean failOnStart;

        private boolean failOnStop;

        private boolean failNextRequestSyncPoint;

        private Runnable beforeNextRequestSyncPointFailure;

        private boolean started;

        private boolean stopped;

        private boolean closed;

        private int requestCount;

        private int sequenceEpoch = -1;

        private long sequence;

        private StreamParameters lastParameters;

        private FakeEncoder(StreamKey streamKey, StreamDescriptor descriptor,
                            StreamParameters parameters, EncoderOutput output,
                            boolean autoEmitJoinOnRequest, boolean failOnStart) {
            this.streamKey = streamKey;
            this.descriptor = descriptor;
            this.lastParameters = parameters;
            this.output = output;
            this.autoEmitJoinOnRequest = autoEmitJoinOnRequest;
            this.failOnStart = failOnStart;
        }

        @Override
        public void start() {
            if (failOnStart) {
                failOnStart = false;
                throw new IllegalStateException("injected encoder start failure");
            }
            started = true;
        }

        @Override
        public void stop() {
            if (failOnStop) {
                failOnStop = false;
                throw new IllegalStateException("injected encoder stop failure");
            }
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
            if (failNextRequestSyncPoint) {
                failNextRequestSyncPoint = false;
                Runnable beforeFailure = beforeNextRequestSyncPointFailure;
                beforeNextRequestSyncPointFailure = null;
                if (beforeFailure != null) {
                    beforeFailure.run();
                }
                throw new IllegalStateException("injected sync point failure");
            }
            if (autoEmitJoinOnRequest) {
                emitJoin(new byte[]{0x01});
            }
        }

        @Override
        public void close() {
            closed = true;
        }

        public void setAutoEmitJoinOnRequest(boolean autoEmitJoinOnRequest) {
            this.autoEmitJoinOnRequest = autoEmitJoinOnRequest;
        }

        public void failNextStop() {
            this.failOnStop = true;
        }

        public void failNextRequestSyncPoint(Runnable beforeFailure) {
            this.failNextRequestSyncPoint = true;
            this.beforeNextRequestSyncPointFailure = beforeFailure;
        }

        public int getRequestCount() {
            return requestCount;
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

        public StreamParameters getLastParameters() {
            return lastParameters;
        }

        public List<EncodedAccessUnit> getEmitted() {
            return Collections.unmodifiableList(emitted);
        }

        public void emitNonJoin(byte[] payload) {
            emit(descriptor.getConfigEpoch(), false, payload);
        }

        public void emitJoin(byte[] payload) {
            emit(descriptor.getConfigEpoch(), true, payload);
        }

        public void emit(int configEpoch, boolean joinPoint, byte[] payload) {
            if (sequenceEpoch != configEpoch) {
                sequenceEpoch = configEpoch;
                sequence = 0;
            }
            long ptsUs = sequence * 1000L;
            AccessUnitFlags flags = AccessUnitFlags.of(joinPoint, joinPoint, false);
            JoinInfo joinInfo = joinPoint
                    ? new JoinInfo(configEpoch, descriptor.getConfigParts())
                    : null;
            EncodedAccessUnit accessUnit = new EncodedAccessUnit(streamKey, configEpoch, sequence,
                    ptsUs, EncodedAccessUnit.DTS_UNSET, flags, joinInfo, null, payload);
            sequence++;
            emitted.add(accessUnit);
            output.onAccessUnit(accessUnit);
        }
    }

    /**
     * Decoder that forwards encoded frames to the injected {@link RenderTarget}.
     */
    public static final class FakeDecoder implements Decoder {

        private final StreamKey streamKey;

        private final RenderTarget renderTarget;

        private final List<EncodedAccessUnit> decoded = new ArrayList<>();

        private StreamDescriptor descriptor;

        private boolean failNextDecode;

        private boolean closed;

        private int configureCount;

        private int closeCount;

        private FakeDecoder(StreamKey streamKey, StreamDescriptor descriptor,
                            RenderTarget renderTarget) {
            this.streamKey = streamKey;
            this.descriptor = descriptor;
            this.renderTarget = renderTarget;
        }

        @Override
        public void configure(StreamDescriptor descriptor) {
            this.descriptor = descriptor;
            configureCount++;
        }

        @Override
        public void decode(EncodedAccessUnit accessUnit) throws DecoderException {
            if (failNextDecode) {
                failNextDecode = false;
                throw new DecoderException("fake decoder failure",
                        new IllegalStateException("injected decoder failure"));
            }
            decoded.add(accessUnit);
            renderTarget.renderEncodedImage(new EncodedImageFrame(accessUnit.getPayload(),
                    accessUnit.getPtsUs(), descriptor.getCodedWidth(), descriptor.getCodedHeight()));
        }

        @Override
        public void close() {
            closed = true;
            closeCount++;
        }

        public void failNextDecode() {
            failNextDecode = true;
        }

        public StreamDescriptor getDescriptor() {
            return descriptor;
        }

        public StreamKey getStreamKey() {
            return streamKey;
        }

        public List<EncodedAccessUnit> getDecoded() {
            return Collections.unmodifiableList(decoded);
        }

        public RenderTarget getRenderTarget() {
            return renderTarget;
        }

        public int getConfigureCount() {
            return configureCount;
        }

        public int getCloseCount() {
            return closeCount;
        }

        public boolean isClosed() {
            return closed;
        }
    }
}
