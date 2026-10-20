package com.obby.android.localscreenshare.core.codec.h264;

import android.view.Surface;

import com.obby.android.localscreenshare.core.codec.CodecIds;
import com.obby.android.localscreenshare.core.codec.Decoder;
import com.obby.android.localscreenshare.core.codec.DecoderException;
import com.obby.android.localscreenshare.core.codec.mediacodec.DecodedOutput;
import com.obby.android.localscreenshare.core.codec.mediacodec.DecoderFormat;
import com.obby.android.localscreenshare.core.codec.mediacodec.MediaCodecBridgeFactory;
import com.obby.android.localscreenshare.core.codec.mediacodec.MediaCodecDecoderBridge;
import com.obby.android.localscreenshare.core.model.CodecConfigPart;
import com.obby.android.localscreenshare.core.model.EncodedAccessUnit;
import com.obby.android.localscreenshare.core.model.StreamDescriptor;
import com.obby.android.localscreenshare.core.model.StreamKey;
import com.obby.android.localscreenshare.core.render.RenderTarget;

import java.util.List;

/**
 * H.264 (AVC) decoder adapter over a {@link MediaCodecDecoderBridge}.
 *
 * <p><b>Validation before side effects.</b> {@link #configure(StreamDescriptor)} fully validates the
 * descriptor (codec id, config epoch, exactly one SPS and one PPS) <i>before</i> reading the render
 * target's surface or creating the bridge. The render target's surface is passed through
 * {@link DecoderFormat} only; a surface availability change is recovered by re-creating the decoder
 * through {@code ClientMediaSession.retryConfigure()} rather than by mutating an existing decoder.</p>
 *
 * <p><b>Decode ordering.</b> {@link #decode(EncodedAccessUnit)} validates null/configured/epoch
 * state before any bridge I/O, then parses the access unit and strips in-band SPS/PPS. A
 * config-plus-VCL packet is stripped but still decoded so the first IDR is never lost. VCL presence
 * is decided on the <i>stripped</i> payload via {@link H264AccessUnitInfo#hasVcl()} (never by the
 * stripped array length, since SEI/AUD survive stripping but carry no VCL). B slices are rejected;
 * a non-IDR frame before the first IDR is dropped without state change.</p>
 *
 * <p><b>Input budget.</b> Input-buffer acquisition is bounded by a total (~20ms) budget. Exhausting
 * it on a validated IDR join point is a hard failure (the client must not silently stall a join);
 * exhausting it on an ordinary frame is ordinary back-pressure and the frame is dropped without
 * error.</p>
 *
 * <p><b>Threading.</b> Instances are driven by a single owner (a {@code ClientMediaSession}, which
 * synchronizes its own entry points); this decoder adds no internal locking and must not be shared
 * across threads.</p>
 */
public final class H264Decoder implements Decoder {

    private static final String H264_MIME = "video/avc";

    /** Total budget for acquiring an input buffer, in nanoseconds (~20ms). */
    private static final long INPUT_BUDGET_NANOS = 20_000_000L;

    private final StreamKey streamKey;
    private final RenderTarget renderTarget;
    private final MediaCodecBridgeFactory bridgeFactory;

    private StreamDescriptor descriptor;
    private int configEpoch;
    private String configHash;
    private MediaCodecDecoderBridge bridge;

    private boolean configured;
    private boolean started;
    private boolean closed;
    private boolean firstFrameSeen;

    public H264Decoder(StreamKey streamKey, StreamDescriptor descriptor,
                       RenderTarget renderTarget, MediaCodecBridgeFactory bridgeFactory) {
        if (streamKey == null) {
            throw new IllegalArgumentException("streamKey must not be null");
        }
        if (renderTarget == null) {
            throw new IllegalArgumentException("renderTarget must not be null");
        }
        if (bridgeFactory == null) {
            throw new IllegalArgumentException("bridgeFactory must not be null");
        }
        this.streamKey = streamKey;
        this.descriptor = descriptor;
        this.renderTarget = renderTarget;
        this.bridgeFactory = bridgeFactory;
    }

    /**
     * Validates the descriptor, then creates, configures and starts the bridge.
     *
     * <p>The descriptor check (codec id, {@code configEpoch >= 1}, exactly one SPS and one PPS)
     * runs first and must not read the surface or create a bridge, so a caller can distinguish a
     * rejected descriptor from a bridge failure. Any bridge failure releases the created bridge
     * exactly once and leaves the decoder closed.</p>
     */
    @Override
    public void configure(StreamDescriptor descriptor) {
        if (descriptor == null) {
            throw new DecoderException("descriptor must not be null", null);
        }
        if (!CodecIds.H264.equals(descriptor.getCodecId())) {
            throw new DecoderException("descriptor codecId must be " + CodecIds.H264, null);
        }
        if (descriptor.getConfigEpoch() < 1) {
            throw new DecoderException("configEpoch must be >= 1", null);
        }
        byte[] spsPayload = requireSingleConfigPart(descriptor.getConfigParts(),
                H264Codec.CONFIG_PART_SPS);
        byte[] ppsPayload = requireSingleConfigPart(descriptor.getConfigParts(),
                H264Codec.CONFIG_PART_PPS);

        MediaCodecDecoderBridge created = null;
        boolean bridgeStarted = false;
        try {
            Surface surface = renderTarget.getSurface();
            DecoderFormat format = new DecoderFormat(descriptor.getCodedWidth(),
                    descriptor.getCodedHeight(), H264ConfigExtractor.toAnnexB(spsPayload),
                    H264ConfigExtractor.toAnnexB(ppsPayload), surface);
            created = bridgeFactory.createDecoder(H264_MIME);
            created.configure(format);
            created.start();
            bridgeStarted = true;
        } catch (RuntimeException e) {
            releaseBridgeQuietly(created, bridgeStarted);
            closed = true;
            throw new DecoderException("failed to configure H.264 decoder", e);
        }

        this.bridge = created;
        this.descriptor = descriptor;
        this.configEpoch = descriptor.getConfigEpoch();
        this.configHash = descriptor.getConfigHash();
        this.configured = true;
        this.started = true;
        this.firstFrameSeen = false;
    }

    @Override
    public void decode(EncodedAccessUnit accessUnit) throws DecoderException {
        // Validation first, before any bridge I/O.
        if (accessUnit == null) {
            throw new DecoderException("access unit must not be null", null);
        }
        if (!configured) {
            throw new DecoderException("decoder is not configured", null);
        }
        if (accessUnit.getConfigEpoch() != configEpoch) {
            throw new DecoderException("config epoch mismatch: expected=" + configEpoch
                    + ", actual=" + accessUnit.getConfigEpoch(), null);
        }

        byte[] payload = accessUnit.getPayload();

        // Parse the original access unit to reject malformed input (including a malformed slice
        // header) before touching the bridge.
        try {
            H264AnnexBParser.analyze(payload);
        } catch (H264FormatException e) {
            throw new DecoderException("malformed access unit", e);
        }

        // Strip in-band SPS/PPS. This re-frames the surviving NAL units but keeps every VCL unit,
        // so a config-plus-VCL packet still carries its first IDR.
        byte[] stripped;
        try {
            stripped = H264AnnexBParser.stripSpsPps(payload);
        } catch (H264FormatException e) {
            throw new DecoderException("malformed access unit", e);
        }

        // Decide VCL presence on the stripped payload with hasVcl(), never with the array length:
        // SEI/AUD survive stripping and would make a length check lie.
        H264AccessUnitInfo strippedInfo;
        try {
            strippedInfo = H264AnnexBParser.analyze(stripped);
        } catch (H264FormatException e) {
            // Stripping left no NAL units (a pure SPS/PPS packet): nothing to decode.
            return;
        }
        if (!strippedInfo.hasVcl()) {
            return;
        }
        if (strippedInfo.hasBSlice()) {
            throw new DecoderException("B slices are not supported", null);
        }
        boolean isIdr = H264AnnexBParser.isIdrAccessUnit(stripped);
        if (!firstFrameSeen && !isIdr) {
            // The decoder cannot start from a non-IDR frame; drop it without any bridge I/O or
            // state change so the first real IDR is still accepted.
            return;
        }

        // Acquire an input buffer within a bounded total budget. A validated IDR join point must
        // never be silently dropped, but an ordinary frame may be shed under back-pressure.
        long deadline = System.nanoTime() + INPUT_BUDGET_NANOS;
        int inputIndex = -1;
        while (inputIndex < 0) {
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) {
                if (isIdr) {
                    throw new DecoderException("join point input timeout", null);
                }
                return;
            }
            long remainingUs = remainingNanos / 1000L;
            try {
                inputIndex = bridge.dequeueInputBuffer(remainingUs);
            } catch (RuntimeException e) {
                throw new DecoderException("failed to dequeue input buffer", e);
            }
        }

        try {
            bridge.queueInputBuffer(inputIndex, stripped, accessUnit.getPtsUs());
        } catch (RuntimeException e) {
            throw new DecoderException("failed to queue input buffer", e);
        }
        firstFrameSeen = true;

        // Drain any immediately available output; format changes are ignored, but end-of-stream and
        // bridge failures surface as decoder failures.
        try {
            DecodedOutput output;
            while ((output = bridge.dequeueOutput(0L)) != null) {
                if (output.isEndOfStream()) {
                    throw new DecoderException("decoder reported end of stream", null);
                }
            }
        } catch (DecoderException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new DecoderException("failed to dequeue output", e);
        }
    }

    /**
     * Releases the bridge exactly once and never throws. The render target is not closed: it is a
     * caller-owned resource whose lifetime outlives any single decoder.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        releaseBridgeQuietly(bridge, started);
    }

    /** Returns the unique config part payload for {@code id}, or throws on missing/duplicate. */
    private static byte[] requireSingleConfigPart(List<CodecConfigPart> parts, String id)
            throws DecoderException {
        byte[] found = null;
        if (parts != null) {
            for (CodecConfigPart part : parts) {
                if (part != null && id.equals(part.getId())) {
                    if (found != null) {
                        throw new DecoderException("duplicate " + id + " config part", null);
                    }
                    found = part.getPayload();
                }
            }
        }
        if (found == null) {
            throw new DecoderException("missing " + id + " config part", null);
        }
        return found;
    }

    /** Best-effort stop (when started) and release of a bridge; never throws. */
    private static void releaseBridgeQuietly(MediaCodecDecoderBridge bridge, boolean started) {
        if (bridge == null) {
            return;
        }
        if (started) {
            try {
                bridge.stop();
            } catch (RuntimeException ignored) {
                // Best-effort stop on the failure path.
            }
        }
        try {
            bridge.release();
        } catch (RuntimeException ignored) {
            // Best-effort release on the failure path.
        }
    }
}
