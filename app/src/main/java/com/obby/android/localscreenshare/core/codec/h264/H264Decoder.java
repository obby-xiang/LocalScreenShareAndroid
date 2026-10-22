package com.obby.android.localscreenshare.core.codec.h264;

import android.view.Surface;

import com.obby.android.localscreenshare.core.audio.SessionClock;
import com.obby.android.localscreenshare.core.codec.CodecIds;
import com.obby.android.localscreenshare.core.codec.Decoder;
import com.obby.android.localscreenshare.core.codec.DecoderException;
import com.obby.android.localscreenshare.core.codec.KeyFrameRequester;
import com.obby.android.localscreenshare.core.codec.mediacodec.DecodedOutput;
import com.obby.android.localscreenshare.core.codec.mediacodec.DecoderFormat;
import com.obby.android.localscreenshare.core.codec.mediacodec.MediaCodecBridgeFactory;
import com.obby.android.localscreenshare.core.codec.mediacodec.MediaCodecDecoderBridge;
import com.obby.android.localscreenshare.core.metrics.PipelineMetrics;
import com.obby.android.localscreenshare.core.model.CodecConfigPart;
import com.obby.android.localscreenshare.core.model.EncodedAccessUnit;
import com.obby.android.localscreenshare.core.model.StreamDescriptor;
import com.obby.android.localscreenshare.core.model.StreamKey;
import com.obby.android.localscreenshare.core.render.PresentationPacer;
import com.obby.android.localscreenshare.core.render.RenderTarget;
import com.obby.android.localscreenshare.core.util.NanoClock;

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
 * exhausting it on an ordinary frame is ordinary back-pressure, so the frame is dropped and the
 * {@link KeyFrameRequester} is told that the reference chain now has a gap.</p>
 *
 * <p><b>Presentation.</b> Decoded outputs are drained after each queued unit and merged: only the
 * newest output is presented and every older one is discarded, so the picture never lags behind by
 * more than one frame and no output buffer is leaked. The frame is presented at its stream timestamp
 * mapped onto the monotonic clock, or immediately when that timestamp has already passed;
 * {@link PresentationPacer} owns the decision, including the optional local display-rate cap. The
 * first picture after {@link #configure(StreamDescriptor)} or {@link #flush()} is presented
 * immediately when its mapped time would defer it beyond the pacer's first-frame bound: waiting
 * would leave the surface on the content it held before, which is what the viewer perceives as a
 * green first frame. A dropped or discarded frame is never an encoded-frame drop: the bitstream
 * itself is intact.</p>
 *
 * <p><b>Threading.</b> Instances are driven by a single owner (a {@code ClientMediaSession}, which
 * synchronizes its own entry points); this decoder adds no internal locking and must not be shared
 * across threads.</p>
 */
public final class H264Decoder implements Decoder {

    private static final String H264_MIME = "video/avc";

    /**
     * Total budget for acquiring an input buffer, in nanoseconds (~20ms).
     */
    private static final long INPUT_BUDGET_NANOS = 20_000_000L;

    /**
     * Local display-rate cap in nanoseconds; zero keeps every frame the pacer accepts.
     *
     * <p>The surface output path already discards excess frames in the platform, so the cap is a
     * secondary tool. The effective frame-rate limiter belongs on the encode side.</p>
     */
    private static final long MIN_PRESENT_INTERVAL_NANOS = 0L;

    /**
     * Number of recently queued decodes whose timestamps are kept for latency matching.
     */
    private static final int DECODE_SAMPLE_CAPACITY = 8;

    /**
     * Number of presentation intervals the freeze threshold is averaged over.
     */
    private static final int PRESENT_INTERVAL_WINDOW = 30;

    /**
     * Floor of the freeze threshold, in nanoseconds, mirroring the arrival stall floor.
     */
    private static final long FREEZE_FLOOR_NS = 150_000_000L;

    private static final long UNSET = Long.MIN_VALUE;

    private final StreamKey streamKey;
    private final RenderTarget renderTarget;
    private final MediaCodecBridgeFactory bridgeFactory;
    private final KeyFrameRequester keyFrameRequester;
    private final PresentationPacer pacer = new PresentationPacer();
    private final PipelineMetrics metrics;
    private final NanoClock clock;

    // Shared audio/video timeline, or null to keep the historical decoder-local anchor.
    private final SessionClock sessionClock;

    // Timestamps of the most recent input submissions, keyed by presentation time, used to match a
    // dequeued output with the moment its frame was handed to the decoder.
    private final long[] pendingPtsUs = new long[DECODE_SAMPLE_CAPACITY];
    private final long[] pendingAtNs = new long[DECODE_SAMPLE_CAPACITY];
    private int pendingCount;

    // Presentation cadence state for the interval and freeze metrics, on the hand-off clock.
    private final long[] presentIntervals = new long[PRESENT_INTERVAL_WINDOW];
    private int presentIntervalCount;
    private int presentIntervalCursor;
    private long lastHandOffNs = UNSET;

    // Moment the current configuration was applied; the first presented frame afterwards reports
    // the switch blackout.
    private long configuredAtNs = UNSET;
    private boolean blackoutPending;

    private StreamDescriptor descriptor;
    private int configEpoch;
    private String configHash;
    private MediaCodecDecoderBridge bridge;

    // Stream-timestamp anchor: the first decoded output defines the offset between the stream
    // timeline and the monotonic clock, so later frames can be scheduled relative to it.
    private long ptsAnchorUs = PresentationPacer.UNSET;
    private long timeAnchorNs = PresentationPacer.UNSET;

    // Presentation time of the last shown frame, or UNSET before the first one.
    private long lastPresentedNs = PresentationPacer.UNSET;

    private boolean configured;
    private boolean started;
    private boolean closed;
    private boolean firstFrameSeen;

    public H264Decoder(StreamKey streamKey, StreamDescriptor descriptor,
                       RenderTarget renderTarget, MediaCodecBridgeFactory bridgeFactory) {
        this(streamKey, descriptor, renderTarget, bridgeFactory, KeyFrameRequester.NONE);
    }

    /**
     * Creates a decoder that reports its need for a new random-access point.
     *
     * @param streamKey         identity of the stream being decoded
     * @param descriptor        descriptor used before {@link #configure(StreamDescriptor)} is called
     * @param renderTarget      output target; owned by the caller
     * @param bridgeFactory     creates the platform decoder bridge
     * @param keyFrameRequester notified when frames were dropped inside the decoder; not null
     * @throws IllegalArgumentException if a required argument is null
     */
    public H264Decoder(StreamKey streamKey, StreamDescriptor descriptor,
                       RenderTarget renderTarget, MediaCodecBridgeFactory bridgeFactory,
                       KeyFrameRequester keyFrameRequester) {
        this(streamKey, descriptor, renderTarget, bridgeFactory, keyFrameRequester,
                PipelineMetrics.NONE, NanoClock.MONOTONIC);
    }

    /**
     * Creates a decoder that reports its decode and presentation metrics.
     *
     * <p>The scope receives the decode latency (matched by presentation timestamp), the number of
     * frames that missed their own presentation target, the merged and shed frames, and the
     * presentation cadence, lateness, drops and freezes. A disabled scope keeps every probe a
     * single volatile read and allocates nothing.</p>
     *
     * @param streamKey         identity of the stream being decoded
     * @param descriptor        descriptor used before {@link #configure(StreamDescriptor)} is called
     * @param renderTarget      output target; owned by the caller
     * @param bridgeFactory     creates the platform decoder bridge
     * @param keyFrameRequester notified when frames were dropped inside the decoder; not null
     * @param metrics           metric scope of the receiving pipeline; required
     * @param clock             clock used by the decode and presentation probes; required
     * @throws IllegalArgumentException if a required argument is null
     */
    public H264Decoder(StreamKey streamKey, StreamDescriptor descriptor,
                       RenderTarget renderTarget, MediaCodecBridgeFactory bridgeFactory,
                       KeyFrameRequester keyFrameRequester, PipelineMetrics metrics,
                       NanoClock clock) {
        this(streamKey, descriptor, renderTarget, bridgeFactory, keyFrameRequester, metrics,
                clock, null);
    }

    /**
     * Creates a decoder that maps stream timestamps through a shared session timeline.
     *
     * <p>When a {@link SessionClock} is supplied, the decoder no longer anchors its own timeline:
     * the first frame seen by either track anchors the shared one, and every later frame is
     * presented at the shared mapping. That is what keeps the picture and the sound on one
     * timeline instead of two drifting ones. A null clock keeps the historical self-anchored
     * behaviour, so existing callers and tests are unaffected.</p>
     *
     * @param streamKey         identity of the stream being decoded
     * @param descriptor        descriptor used before {@link #configure(StreamDescriptor)} is called
     * @param renderTarget      output target; owned by the caller
     * @param bridgeFactory     creates the platform decoder bridge
     * @param keyFrameRequester notified when frames were dropped inside the decoder; not null
     * @param metrics           metric scope of the receiving pipeline; required
     * @param clock             clock used by the decode and presentation probes; required
     * @param sessionClock      shared timeline, or null to keep a decoder-local anchor
     * @throws IllegalArgumentException if a required argument is null
     */
    public H264Decoder(StreamKey streamKey, StreamDescriptor descriptor,
                       RenderTarget renderTarget, MediaCodecBridgeFactory bridgeFactory,
                       KeyFrameRequester keyFrameRequester, PipelineMetrics metrics,
                       NanoClock clock, SessionClock sessionClock) {
        if (streamKey == null) {
            throw new IllegalArgumentException("streamKey must not be null");
        }
        if (renderTarget == null) {
            throw new IllegalArgumentException("renderTarget must not be null");
        }
        if (bridgeFactory == null) {
            throw new IllegalArgumentException("bridgeFactory must not be null");
        }
        if (keyFrameRequester == null) {
            throw new IllegalArgumentException("keyFrameRequester must not be null");
        }
        if (metrics == null) {
            throw new IllegalArgumentException("metrics must not be null");
        }
        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
        }
        this.streamKey = streamKey;
        this.descriptor = descriptor;
        this.renderTarget = renderTarget;
        this.bridgeFactory = bridgeFactory;
        this.keyFrameRequester = keyFrameRequester;
        this.metrics = metrics;
        this.clock = clock;
        this.sessionClock = sessionClock;
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
        this.ptsAnchorUs = PresentationPacer.UNSET;
        this.timeAnchorNs = PresentationPacer.UNSET;
        this.lastPresentedNs = PresentationPacer.UNSET;
        this.pendingCount = 0;
        this.lastHandOffNs = UNSET;
        this.presentIntervalCount = 0;
        this.presentIntervalCursor = 0;
        if (metrics.isEnabled()) {
            this.configuredAtNs = clock.nowNs();
            this.blackoutPending = true;
        }
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
                // The frame is gone: the reference chain now has a gap, so the next frame can only
                // be decoded cleanly from a new random-access point.
                metrics.increment(PipelineMetrics.Counter.DECODE_SHEDS);
                keyFrameRequester.requestKeyFrame();
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
        rememberQueued(accessUnit.getPtsUs());

        // Drain every immediately available output and keep only the newest: the older ones are
        // released without rendering, so the displayed frame never lags by more than one frame.
        drainOutputs();
    }

    /**
     * Drains all available outputs, presents the newest one and discards the rest.
     *
     * <p>Format-change entries carry no buffer and are ignored. End-of-stream releases its buffer
     * before failing, so the codec is left with no leaked output. A bridge failure while draining
     * discards the frame held for presentation before propagating, because a decoder that cannot
     * drain must not also hold a buffer.</p>
     */
    private void drainOutputs() {
        DecodedOutput newest = null;
        try {
            DecodedOutput output;
            while ((output = bridge.dequeueOutput(0L)) != null) {
                if (output.isEndOfStream()) {
                    if (output.hasBuffer()) {
                        bridge.discard(output.getBufferIndex());
                    }
                    discardQuietly(newest);
                    newest = null;
                    throw new DecoderException("decoder reported end of stream", null);
                }
                if (!output.hasBuffer()) {
                    continue;
                }
                if (newest != null) {
                    metrics.increment(PipelineMetrics.Counter.DECODE_MERGED);
                    bridge.discard(newest.getBufferIndex());
                }
                newest = output;
            }
        } catch (DecoderException e) {
            discardQuietly(newest);
            throw e;
        } catch (RuntimeException e) {
            discardQuietly(newest);
            throw new DecoderException("failed to dequeue output", e);
        }

        if (newest != null) {
            try {
                present(newest);
            } catch (RuntimeException e) {
                discardQuietly(newest);
                throw new DecoderException("failed to present output", e);
            }
        }
    }

    /**
     * Presents one output at its mapped timestamp, or discards it when the pacer rejects it.
     */
    private void present(DecodedOutput output) {
        long nowNs = clock.nowNs();
        recordDecodeLatency(output.getPtsUs(), nowNs);
        long targetNs = toDeviceTimeNs(output.getPtsUs(), nowNs);
        PresentationPacer.Decision decision =
                pacer.decide(nowNs, targetNs, lastPresentedNs, MIN_PRESENT_INTERVAL_NANOS);
        recordPresentationMetrics(nowNs, targetNs, decision);
        switch (decision) {
            case PRESENT_NOW:
                bridge.present(output.getBufferIndex(), nowNs);
                lastPresentedNs = nowNs;
                break;
            case SCHEDULE_AT:
                bridge.present(output.getBufferIndex(), targetNs);
                lastPresentedNs = targetNs;
                break;
            case DROP:
            default:
                bridge.discard(output.getBufferIndex());
                break;
        }
    }

    /**
     * Remembers the moment a frame was handed to the decoder so its output can be matched later.
     *
     * <p>The table is a fixed ring of eight entries: frames beyond that depth were never going to
     * be measured accurately, so an unmatched output simply produces no sample instead of a
     * growing map.</p>
     */
    private void rememberQueued(long ptsUs) {
        if (!metrics.isEnabled()) {
            return;
        }
        int index = pendingCount % DECODE_SAMPLE_CAPACITY;
        pendingPtsUs[index] = ptsUs;
        pendingAtNs[index] = clock.nowNs();
        pendingCount++;
    }

    /**
     * Records the decode latency of an output whose frame was seen by {@link #rememberQueued}.
     */
    private void recordDecodeLatency(long ptsUs, long nowNs) {
        if (!metrics.isEnabled()) {
            return;
        }
        int size = Math.min(pendingCount, DECODE_SAMPLE_CAPACITY);
        for (int i = 0; i < size; i++) {
            if (pendingPtsUs[i] == ptsUs) {
                long queuedAtNs = pendingAtNs[i];
                pendingPtsUs[i] = UNSET;
                if (nowNs >= queuedAtNs) {
                    metrics.record(PipelineMetrics.Window.DECODE_LATENCY, nowNs - queuedAtNs);
                }
                return;
            }
        }
    }

    /**
     * Records the presentation cadence, lateness and deadline misses of one output.
     *
     * <p>Lateness is measured only when the frame is handed off late: a frame scheduled ahead is
     * not late by definition. A deadline miss is the same comparison taken at the moment the output
     * became retrievable, which is exactly "the decoder did not catch up with the frame's own
     * presentation time".</p>
     */
    private void recordPresentationMetrics(long nowNs, long targetNs,
                                           PresentationPacer.Decision decision) {
        if (!metrics.isEnabled()) {
            return;
        }
        if (blackoutPending && configuredAtNs != UNSET && decision != PresentationPacer.Decision.DROP) {
            blackoutPending = false;
            if (nowNs >= configuredAtNs) {
                metrics.record(PipelineMetrics.Window.SWITCH_BLACKOUT, nowNs - configuredAtNs);
            }
        }
        if (lastHandOffNs != UNSET && nowNs >= lastHandOffNs) {
            long interval = nowNs - lastHandOffNs;
            long threshold = freezeThresholdNs();
            metrics.record(PipelineMetrics.Window.PRESENT_INTERVAL, interval);
            if (interval > threshold) {
                metrics.increment(PipelineMetrics.Counter.FREEZES);
            }
            presentIntervals[presentIntervalCursor] = interval;
            presentIntervalCursor = (presentIntervalCursor + 1) % PRESENT_INTERVAL_WINDOW;
            if (presentIntervalCount < PRESENT_INTERVAL_WINDOW) {
                presentIntervalCount++;
            }
        }
        lastHandOffNs = nowNs;

        if (decision == PresentationPacer.Decision.DROP) {
            metrics.increment(PipelineMetrics.Counter.PRESENT_DROPS);
            return;
        }
        if (nowNs > targetNs) {
            metrics.record(PipelineMetrics.Window.PRESENT_LATE, nowNs - targetNs);
            metrics.increment(PipelineMetrics.Counter.DEADLINE_MISSES);
        } else {
            metrics.record(PipelineMetrics.Window.PRESENT_LATE, 0L);
        }
    }

    /**
     * Returns the freeze threshold: three times the mean of the last thirty presentation
     * intervals, floored at 150 ms.
     */
    private long freezeThresholdNs() {
        if (presentIntervalCount == 0) {
            return FREEZE_FLOOR_NS;
        }
        long sum = 0L;
        for (int i = 0; i < presentIntervalCount; i++) {
            sum += presentIntervals[i];
        }
        return Math.max(FREEZE_FLOOR_NS, 3L * (sum / presentIntervalCount));
    }

    /**
     * Maps a stream timestamp onto the monotonic clock.
     *
     * <p>The first decoded output anchors the stream timeline at the moment it was dequeued, so the
     * stream's own origin (which may start at any value) does not matter. Later frames are offset
     * from that anchor, which makes a late frame present immediately and a frame that arrives early
     * wait for its slot.</p>
     */
    private long toDeviceTimeNs(long ptsUs, long nowNs) {
        if (sessionClock != null) {
            sessionClock.anchorIfUnset(ptsUs, nowNs);
            final long mapped = sessionClock.map(ptsUs);
            if (mapped != SessionClock.UNSET) {
                return mapped;
            }
        }
        if (timeAnchorNs == PresentationPacer.UNSET || ptsAnchorUs == PresentationPacer.UNSET) {
            ptsAnchorUs = ptsUs;
            timeAnchorNs = nowNs;
            return nowNs;
        }
        return timeAnchorNs + (ptsUs - ptsAnchorUs) * 1000L;
    }

    /**
     * Releases a held output without rendering, swallowing release failures.
     */
    private void discardQuietly(DecodedOutput output) {
        if (output == null || !output.hasBuffer()) {
            return;
        }
        try {
            bridge.discard(output.getBufferIndex());
        } catch (RuntimeException ignored) {
            // A failing release is already a failing decoder; the original failure wins.
        }
    }

    /**
     * Discards buffered input, undisplayed output and the stream-timeline anchor.
     *
     * <p>Used when the receiver skips ahead: the frames already handed to the codec belong to a
     * picture the viewer decided not to show, and the codec must not continue their reference
     * chain. The decoder stays configured and started, but {@code firstFrameSeen} is cleared so a
     * reference frame that is not an IDR is dropped instead of decoded, and the timeline anchor is
     * cleared so the join point re-anchors the mapping between stream time and the monotonic
     * clock. The last presented time is cleared with it, because the next picture is the first
     * picture of the new run: with nothing on record it is no longer held back by the abandoned
     * run's cadence, so an artefact lead cannot leave the old picture on screen for its duration. A
     * bridge that cannot flush is treated like a bridge that cannot stop: the recovery still
     * proceeds.</p>
     */
    @Override
    public void flush() {
        if (!configured || closed) {
            return;
        }
        if (bridge != null && started) {
            try {
                bridge.flush();
            } catch (RuntimeException ignored) {
                // A failing flush leaves the old frames in the codec, not a broken decoder.
            }
        }
        pendingCount = 0;
        firstFrameSeen = false;
        ptsAnchorUs = PresentationPacer.UNSET;
        timeAnchorNs = PresentationPacer.UNSET;
        // The next picture is the first one of the new decode run, so it must be shown immediately
        // rather than deferred to a mapped timestamp: the surface still holds the picture of the
        // run that was just given up.
        lastPresentedNs = PresentationPacer.UNSET;
        // The shared session anchor is deliberately left alone: a video flush must not shift the
        // audio timeline, and an audio reconfiguration never reaches this class.
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

    /**
     * Returns the unique config part payload for {@code id}, or throws on missing/duplicate.
     */
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

    /**
     * Best-effort stop (when started) and release of a bridge; never throws.
     */
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
