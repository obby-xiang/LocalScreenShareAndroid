package com.obby.android.localscreenshare.core.codec.h264;

import android.view.Surface;

import com.obby.android.localscreenshare.core.codec.CodecIds;
import com.obby.android.localscreenshare.core.codec.Encoder;
import com.obby.android.localscreenshare.core.codec.EncoderConfig;
import com.obby.android.localscreenshare.core.codec.EncoderException;
import com.obby.android.localscreenshare.core.codec.EncoderOutput;
import com.obby.android.localscreenshare.core.codec.mediacodec.EncodedSample;
import com.obby.android.localscreenshare.core.codec.mediacodec.EncoderFormat;
import com.obby.android.localscreenshare.core.codec.mediacodec.EncoderStreamFormat;
import com.obby.android.localscreenshare.core.codec.mediacodec.MediaCodecBridgeFactory;
import com.obby.android.localscreenshare.core.codec.mediacodec.MediaCodecEncoderBridge;
import com.obby.android.localscreenshare.core.metrics.PipelineMetrics;
import com.obby.android.localscreenshare.core.model.AccessUnitFlags;
import com.obby.android.localscreenshare.core.model.CodecConfigPart;
import com.obby.android.localscreenshare.core.model.ColorInfo;
import com.obby.android.localscreenshare.core.model.EncodedAccessUnit;
import com.obby.android.localscreenshare.core.model.JoinInfo;
import com.obby.android.localscreenshare.core.model.StreamDescriptor;
import com.obby.android.localscreenshare.core.model.StreamKey;
import com.obby.android.localscreenshare.core.model.StreamParameters;
import com.obby.android.localscreenshare.core.util.NanoClock;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * H.264 (AVC) encoder adapter over a {@link MediaCodecEncoderBridge}.
 *
 * <p><b>Lifecycle split.</b> The constructor validates the requested descriptor, creates the
 * bridge, {@code configure}s it and creates the input surface. {@link #start()} only calls
 * {@code bridge.start()} and launches a single daemon drain thread; it never reconfigures the
 * codec or recreates the surface.</p>
 *
 * <p><b>Config gate.</b> The first valid codec configuration (SPS/PPS plus a positive coded size)
 * wins and is emitted through {@link EncoderOutput#onEncoderConfig} before any access unit. The
 * coded size and color always come from the latest observed output format, never from the requested
 * descriptor; a config arriving before any output format is a failure. A later, different
 * configuration (different size, color or parts) is a hard failure because a new decode
 * configuration would require a new config epoch, which this encoder cannot mint on its own.</p>
 *
 * <p><b>First-IDR wait.</b> Once a configuration is published, non-IDR samples are dropped until
 * the first validated IDR access unit arrives; that IDR is emitted as sequence {@code 0}.</p>
 *
 * <p><b>Threading.</b> A private lock guards only counters and state snapshots; bridge calls and
 * {@code onEncoderConfig}/{@code onAccessUnit}/{@code onEncoderFailure} callbacks are always made
 * outside the lock. The start transition and {@code close()} are serialized so a close cannot
 * release the bridge underneath an in-progress {@code bridge.start()}.</p>
 */
public final class H264Encoder implements Encoder {

    private static final String H264_MIME = "video/avc";

    private static final long DEQUEUE_TIMEOUT_US = 10_000L;

    private static final long DRAIN_JOIN_TIMEOUT_MS = 1_000L;

    private static final String DRAIN_THREAD_NAME = "h264-encoder-drain";

    /**
     * Upper bound for a plausible capture-to-output latency, in nanoseconds.
     *
     * <p>A sample above this bound means the producer's timestamp base does not match the
     * monotonic clock, so the sample describes nothing and must not be published.</p>
     */
    private static final long MAX_PLAUSIBLE_CAP_TO_OUT_NS = 5_000_000_000L;

    /**
     * Consecutive implausible capture-to-output samples after which the window is disabled.
     */
    private static final int MAX_IMPLAUSIBLE_CAP_TO_OUT_SAMPLES = 3;

    private final Object lock = new Object();

    private final StreamKey streamKey;
    private final StreamDescriptor descriptor;
    private final EncoderOutput output;
    private final MediaCodecEncoderBridge bridge;
    private final Surface inputSurface;
    private final int configEpoch;
    private final PipelineMetrics metrics;
    private final NanoClock clock;

    // Probe state, written on the drain thread only.
    private boolean capToOutSuppressed;
    private int implausibleCapToOutSamples;
    private long lastOutputPtsNs = Long.MIN_VALUE;

    private StreamParameters parameters;

    // Lifecycle state, guarded by lock.
    private boolean started;
    private boolean stopRequested;
    private boolean closed;
    private boolean failed;
    private boolean bridgeReleased;
    private boolean leakDetected;
    private boolean startTransitionActive;
    private Thread drainThread;

    // Signals that the start transition (bridge.start() plus the drain-thread launch) has settled,
    // so a concurrent close() can wait for it without holding the lock or joining on the lock.
    private final CountDownLatch startComplete = new CountDownLatch(1);

    // Config / access-unit state, guarded by lock.
    private List<CodecConfigPart> publishedConfigParts;
    private int publishedCodedWidth;
    private int publishedCodedHeight;
    private ColorInfo publishedColor;
    private boolean awaitingFirstIdr;
    private boolean auEmitted;
    private long sequence;

    // Latest valid output-format snapshot, guarded by lock. Coded size stays zero until a positive
    // output-format size is observed; color stays null until observed. These are the only
    // acceptable source for an emitted config's coded size and color.
    private int observedCodedWidth;
    private int observedCodedHeight;
    private ColorInfo observedColor;

    /**
     * Creates, configures and surface-initializes the encoder; it is not started.
     *
     * @throws EncoderException if bridge creation, configuration or input-surface creation fails
     */
    public H264Encoder(StreamKey streamKey, StreamDescriptor requestedDescriptor,
                       StreamParameters parameters, EncoderOutput output,
                       MediaCodecBridgeFactory bridgeFactory) {
        this(streamKey, requestedDescriptor, parameters, output, bridgeFactory,
                PipelineMetrics.NONE, NanoClock.MONOTONIC);
    }

    /**
     * Creates, configures and surface-initializes the encoder with pipeline probes attached.
     *
     * <p>The scope receives the encode-stage windows: capture timestamp to retrievable output,
     * the interval between two output timestamps and the payload size of each emitted access
     * unit. A disabled scope makes every probe a single volatile read.</p>
     *
     * @param streamKey         identity of the stream; required
     * @param requestedDescriptor requested encode configuration; required
     * @param parameters        initial rate and cadence hints; required
     * @param output            sink for configuration, access units and failures; required
     * @param bridgeFactory     creates the platform encoder bridge; required
     * @param metrics           metric scope of this encoder; required, may be
     *                          {@link PipelineMetrics#NONE}
     * @param clock             clock used by the encode-stage probes; required
     * @throws EncoderException         if bridge creation, configuration or surface creation fails
     * @throws IllegalArgumentException if a required argument is null
     */
    public H264Encoder(StreamKey streamKey, StreamDescriptor requestedDescriptor,
                       StreamParameters parameters, EncoderOutput output,
                       MediaCodecBridgeFactory bridgeFactory, PipelineMetrics metrics,
                       NanoClock clock) {
        if (streamKey == null) {
            throw new IllegalArgumentException("streamKey must not be null");
        }
        if (requestedDescriptor == null) {
            throw new IllegalArgumentException("requestedDescriptor must not be null");
        }
        if (!CodecIds.H264.equals(requestedDescriptor.getCodecId())) {
            throw new IllegalArgumentException("requested codec must be " + CodecIds.H264);
        }
        if (requestedDescriptor.getConfigParts() != null
                && !requestedDescriptor.getConfigParts().isEmpty()) {
            throw new IllegalArgumentException("requested configParts must be empty for H.264");
        }
        if (requestedDescriptor.getCodedWidth() <= 0
                || requestedDescriptor.getCodedHeight() <= 0) {
            throw new IllegalArgumentException("coded size must be positive");
        }
        if (parameters == null) {
            throw new IllegalArgumentException("parameters must not be null");
        }
        if (output == null) {
            throw new IllegalArgumentException("output must not be null");
        }
        if (bridgeFactory == null) {
            throw new IllegalArgumentException("bridgeFactory must not be null");
        }
        if (metrics == null) {
            throw new IllegalArgumentException("metrics must not be null");
        }
        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
        }

        this.streamKey = streamKey;
        this.descriptor = requestedDescriptor;
        this.output = output;
        this.parameters = parameters;
        this.configEpoch = requestedDescriptor.getConfigEpoch();
        this.metrics = metrics;
        this.clock = clock;

        MediaCodecEncoderBridge created = null;
        try {
            created = bridgeFactory.createEncoder(H264_MIME);
            final int frameRate = Math.max(1, parameters.getFrameRateHint());
            // The hint doubles as the pre-encode cap: KEY_FRAME_RATE alone is a rate-control target
            // the hardware encoder does not throttle to, which is why a lower setting used to leave
            // the output interval untouched. A bridge retries without the cap if a codec rejects it.
            EncoderFormat format = new EncoderFormat(
                    requestedDescriptor.getCodedWidth(),
                    requestedDescriptor.getCodedHeight(),
                    Math.max(1, parameters.getTargetBitrateBps()),
                    frameRate,
                    keyFrameIntervalSeconds(parameters.getKeyFrameIntervalMs()),
                    frameRate);
            created.configure(format);
            Surface surface = created.createInputSurface();
            this.bridge = created;
            this.inputSurface = surface;
        } catch (RuntimeException e) {
            releaseQuietly(created);
            throw new EncoderException("failed to initialize H.264 encoder", e);
        }
    }

    /**
     * Converts a key-frame interval in milliseconds to whole seconds, rounding up, with a floor of
     * one second. The arithmetic is done in {@code long} and then narrowed to {@code int} so large
     * millisecond values cannot overflow before the division. Examples: 1000ms -&gt; 1s,
     * 1001ms -&gt; 2s, 250ms -&gt; 1s, 0ms -&gt; 1s.
     */
    private static int keyFrameIntervalSeconds(int keyFrameIntervalMs) {
        long seconds = Math.max(1L, ((long) keyFrameIntervalMs + 999L) / 1000L);
        return (int) seconds;
    }

    /**
     * Starts the encoder's drain loop. This only calls {@code bridge.start()} and launches the
     * single daemon drain thread; it does not reconfigure the codec or recreate the input surface,
     * both of which already happened during construction. The start transition is serialized
     * against {@link #close()}: a close waits, bounded, for the transition to settle before it may
     * release the bridge. Throws {@link IllegalStateException} when the encoder is already started,
     * already closed, or close is in progress; a {@code bridge.start()} failure is wrapped in an
     * {@link EncoderException}.
     */
    @Override
    public void start() {
        boolean launch;
        Thread thread;
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("encoder already closed");
            }
            if (started || startTransitionActive) {
                throw new IllegalStateException("encoder already started");
            }
            // Mark the transition before touching the bridge so a concurrent close() knows to wait
            // for bridge.start() and the drain launch instead of releasing underneath them.
            startTransitionActive = true;
        }
        try {
            bridge.start();
        } catch (RuntimeException e) {
            synchronized (lock) {
                failed = true;
                startTransitionActive = false;
            }
            startComplete.countDown();
            throw new EncoderException("failed to start H.264 encoder", e);
        }
        thread = new Thread(this::drain, DRAIN_THREAD_NAME);
        thread.setDaemon(true);
        synchronized (lock) {
            // Do not launch the drain thread when close() already won the race: the bridge is about
            // to be released, and a launched drain loop could call stop() after that release.
            launch = !closed;
            if (launch) {
                drainThread = thread;
            }
            started = true;
        }
        if (launch) {
            thread.start();
        }
        synchronized (lock) {
            startTransitionActive = false;
        }
        startComplete.countDown();
    }

    /**
     * Stops the encoder's drain loop. A not-yet-started encoder returns immediately. Otherwise the
     * stop is requested and the drain thread is joined with a bounded 1000ms wait (skipped when the
     * caller is the drain thread itself, which must not join itself); the drain thread's
     * {@code finally} performs a best-effort {@code bridge.stop()}, and this method never releases
     * the bridge. Idempotent: a subsequent call is a no-op. Throws {@link EncoderException} when
     * the join times out. After a normal return the drain thread has terminated, so no further
     * access-unit callback can occur.
     */
    @Override
    public void stop() {
        Thread thread;
        synchronized (lock) {
            if (!started) {
                return;
            }
            if (stopRequested) {
                return;
            }
            stopRequested = true;
            thread = drainThread;
        }
        if (thread != null && thread != Thread.currentThread() && !joinQuietly(thread)) {
            throw new EncoderException("drain thread did not terminate within "
                    + DRAIN_JOIN_TIMEOUT_MS + "ms");
        }
    }

    @Override
    public Surface getInputSurface() {
        synchronized (lock) {
            if (closed) {
                return null;
            }
        }
        return inputSurface;
    }

    @Override
    public void updateParameters(StreamParameters parameters) {
        if (parameters == null) {
            throw new IllegalArgumentException("parameters must not be null");
        }
        int previousBitrate = Math.max(1, this.parameters.getTargetBitrateBps());
        int newBitrate = Math.max(1, parameters.getTargetBitrateBps());
        // Frame rate and key-frame interval are recorded but not hot-applied: only the bitrate is
        // changed live. A bridge failure here propagates unchanged.
        this.parameters = parameters;
        if (newBitrate != previousBitrate) {
            bridge.updateBitrate(newBitrate);
        }
    }

    @Override
    public void requestSyncPoint() {
        try {
            bridge.requestSyncFrame();
        } catch (RuntimeException e) {
            throw new EncoderException("failed to request sync frame", e);
        }
    }

    /**
     * Closes the encoder. The bridge is released exactly once, and only after any in-progress start
     * transition has settled and the drain thread (if any) has been observed to terminate. If the
     * start transition or the drain thread is still alive after the bounded wait, the close marks a
     * leak, does not release (the live code could still touch the bridge) and returns without
     * throwing; a later close retries the deferred release once the transition and thread have
     * finished. Repeated closes are no-ops. The bounded waits happen outside the lock.
     */
    @Override
    public void close() {
        Thread thread;
        boolean transitionActive;
        synchronized (lock) {
            closed = true;
            stopRequested = true;
            transitionActive = startTransitionActive;
        }
        if (transitionActive && !awaitStartComplete()) {
            // The start transition did not settle within the bounded wait. Releasing now could race
            // with bridge.start(); record the leak and defer release to a later close().
            synchronized (lock) {
                leakDetected = true;
            }
            return;
        }
        synchronized (lock) {
            thread = drainThread;
        }
        if (thread != null && thread == Thread.currentThread()) {
            // Called from the drain thread itself: it cannot join itself and must not release its
            // own bridge while still running, so mark the leak and return.
            synchronized (lock) {
                leakDetected = true;
            }
            return;
        }
        if (thread != null && !joinQuietly(thread)) {
            synchronized (lock) {
                leakDetected = true;
            }
            return;
        }
        releaseOnce();
    }

    /**
     * Waits, bounded, for the start transition to settle; never blocks while holding the lock.
     */
    private boolean awaitStartComplete() {
        try {
            return startComplete.await(DRAIN_JOIN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * True after the bridge has been released at least once; exposed for tests and diagnostics.
     */
    public boolean isBridgeReleased() {
        synchronized (lock) {
            return bridgeReleased;
        }
    }

    /**
     * True when a close detected that the drain thread did not terminate; tests/diagnostics only.
     */
    public boolean isLeakDetected() {
        synchronized (lock) {
            return leakDetected;
        }
    }

    /**
     * Drains encoded output on the daemon thread; on exit it best-effort stops the bridge.
     */
    private void drain() {
        try {
            while (!shouldStop()) {
                EncodedSample sample;
                try {
                    sample = bridge.dequeueOutput(DEQUEUE_TIMEOUT_US);
                } catch (RuntimeException e) {
                    notifyFailure(new EncoderException("bridge dequeue failed", e));
                    return;
                }
                if (sample == null) {
                    continue;
                }
                try {
                    if (!processSample(sample)) {
                        return;
                    }
                } catch (RuntimeException e) {
                    notifyFailure(new EncoderException("encoder listener failed", e));
                    return;
                }
            }
        } finally {
            stopBridgeQuietly();
        }
    }

    private boolean shouldStop() {
        synchronized (lock) {
            return stopRequested || closed || failed;
        }
    }

    /**
     * Handles one dequeued sample. Returns {@code false} when the drain loop must stop (a terminal
     * failure was reported).
     */
    private boolean processSample(EncodedSample sample) {
        if (sample.isEndOfStream()) {
            return false;
        }
        if (sample.isFormatChanged()) {
            return handleFormatChange();
        }
        byte[] data = sample.getData();
        H264AccessUnitInfo info = analyzeOrNull(data);
        if (info == null) {
            fail("malformed access unit");
            return false;
        }
        if (sample.isCodecConfig()) {
            return handleCodecConfigPacket(sample, data, info);
        }
        return handleDisplayPacket(sample, data, info);
    }

    private boolean handleFormatChange() {
        EncoderStreamFormat format = bridge.getOutputFormat();
        if (format == null) {
            return true;
        }
        int width = format.getWidth();
        int height = format.getHeight();
        ColorInfo color = format.getColorInfo();

        boolean nonPositive;
        synchronized (lock) {
            nonPositive = width <= 0 || height <= 0;
            if (!nonPositive) {
                // Snapshot the latest valid actual size and color even when CSD extraction fails or
                // no config is published yet: a later codec-config or in-band SPS/PPS must use these
                // real values, never the requested coded size.
                observedCodedWidth = width;
                observedCodedHeight = height;
                observedColor = color;
            }
        }
        if (nonPositive) {
            // The requested size must never be used as a fallback; a non-positive coded size is a
            // terminal configuration failure reported exactly once.
            fail("encoder format has non-positive coded size");
            return false;
        }
        List<CodecConfigPart> parts;
        try {
            parts = extractConfigFromCsd(format.getCsd0(), format.getCsd1());
        } catch (H264FormatException e) {
            // Even without SPS/PPS a size/color change must still be caught now: the published
            // config would no longer match this format, so fail instead of silently waiting and
            // then emitting access units against a stale config. Identical size/color keeps the
            // existing "wait for in-band SPS/PPS" behavior.
            ColorInfo resolvedColor = isUnspecifiedColor(color) ? requestedColor() : color;
            boolean changed;
            synchronized (lock) {
                changed = publishedConfigParts != null
                        && (width != publishedCodedWidth
                            || height != publishedCodedHeight
                            || !colorsEqual(resolvedColor, publishedColor));
            }
            if (changed) {
                boolean published;
                synchronized (lock) {
                    published = auEmitted;
                }
                fail(published ? "encoder config changed without a new epoch"
                        : "encoder config changed before first access unit");
                return false;
            }
            // Incomplete CSD alone is not fatal: wait for a codec-config buffer or in-band SPS/PPS.
            return true;
        }
        return acceptConfig(parts);
    }

    private boolean handleCodecConfigPacket(EncodedSample sample, byte[] data,
                                            H264AccessUnitInfo info) {
        List<CodecConfigPart> parts;
        try {
            parts = H264ConfigExtractor.fromInBandPayload(data);
        } catch (H264FormatException e) {
            fail("codec config buffer missing SPS/PPS");
            return false;
        }
        if (!acceptConfig(parts)) {
            return false;
        }
        if (!info.hasVcl()) {
            // Config-only packet: the SPS/PPS were accepted above; there is no display AU to emit.
            return true;
        }
        return emitVcl(sample, data, info);
    }

    private boolean handleDisplayPacket(EncodedSample sample, byte[] data,
                                        H264AccessUnitInfo info) {
        if (info.hasSps() || info.hasPps()) {
            List<CodecConfigPart> parts;
            try {
                parts = H264ConfigExtractor.fromInBandPayload(data);
            } catch (H264FormatException e) {
                fail("in-band config missing SPS/PPS");
                return false;
            }
            if (!acceptConfig(parts)) {
                return false;
            }
            return emitVcl(sample, data, info);
        }
        boolean configReady;
        synchronized (lock) {
            configReady = publishedConfigParts != null;
        }
        if (!configReady) {
            fail("access unit before config");
            return false;
        }
        return emitVcl(sample, data, info);
    }

    /**
     * Emits the VCL carried by a packet whose config (if any) has already been accepted. In-band
     * SPS/PPS are stripped, B slices and negative PTS are rejected, and the first-IDR gate is
     * applied before emitting the access unit.
     */
    private boolean emitVcl(EncodedSample sample, byte[] data, H264AccessUnitInfo info) {
        byte[] displayPayload = data;
        if (info.hasSps() || info.hasPps()) {
            try {
                displayPayload = H264AnnexBParser.stripSpsPps(data);
            } catch (H264FormatException e) {
                fail("failed to strip in-band config");
                return false;
            }
        }
        if (info.hasBSlice()) {
            fail("B slices are not supported");
            return false;
        }
        if (!info.hasVcl()) {
            // SPS/PPS-only packet: configuration was already handled, nothing to emit.
            return true;
        }
        long pts = sample.getPtsUs();
        if (pts < 0) {
            fail("negative presentation timestamp");
            return false;
        }
        boolean joinPoint = H264AnnexBParser.isIdrAccessUnit(displayPayload);
        long seq;
        synchronized (lock) {
            if (awaitingFirstIdr) {
                if (!joinPoint) {
                    return true;
                }
                awaitingFirstIdr = false;
            }
            seq = sequence++;
            auEmitted = true;
        }
        emitAccessUnit(seq, pts, joinPoint, displayPayload);
        return true;
    }

    /**
     * Resolves the coded size and color from the latest observed output format and applies the
     * config gate. Fails when no positive output-format size has been observed yet; the requested
     * coded size is never used as a fallback.
     */
    private boolean acceptConfig(List<CodecConfigPart> parts) {
        int width;
        int height;
        ColorInfo observed;
        synchronized (lock) {
            width = observedCodedWidth;
            height = observedCodedHeight;
            observed = observedColor;
        }
        if (width <= 0 || height <= 0) {
            fail("encoder config received before any output format");
            return false;
        }
        ColorInfo color = isUnspecifiedColor(observed) ? requestedColor() : observed;
        return acceptConfig(parts, width, height, color);
    }

    /**
     * Applies the config gate. The first configuration wins and is emitted before any access unit;
     * an identical configuration (same coded size, color and parts) is ignored; a different
     * configuration is a terminal failure. Returns {@code false} when a failure was reported.
     */
    private boolean acceptConfig(List<CodecConfigPart> parts, int width, int height,
                                 ColorInfo colorInfo) {
        if (width <= 0 || height <= 0) {
            fail("encoder config has non-positive coded size");
            return false;
        }
        if (colorInfo == null) {
            colorInfo = ColorInfo.UNSPECIFIED;
        }

        EncoderConfig configToEmit = null;
        boolean changed = false;
        synchronized (lock) {
            if (publishedConfigParts == null) {
                publishedConfigParts = parts;
                publishedCodedWidth = width;
                publishedCodedHeight = height;
                publishedColor = colorInfo;
                awaitingFirstIdr = true;
                configToEmit = new EncoderConfig(width, height, colorInfo, parts);
            } else if (!configsEqual(parts, width, height, colorInfo)) {
                changed = true;
            }
        }

        if (changed) {
            boolean published;
            synchronized (lock) {
                published = auEmitted;
            }
            fail(published ? "encoder config changed without a new epoch"
                    : "encoder config changed before first access unit");
            return false;
        }
        if (configToEmit != null) {
            output.onEncoderConfig(configToEmit);
        }
        return true;
    }

    private void emitAccessUnit(long sequence, long ptsUs, boolean joinPoint, byte[] payload) {
        List<CodecConfigPart> configParts;
        synchronized (lock) {
            if (closed || failed) {
                return;
            }
            configParts = publishedConfigParts;
        }
        if (configParts == null) {
            return;
        }
        recordAccessUnitMetrics(ptsUs, payload);
        JoinInfo joinInfo = new JoinInfo(configEpoch, configParts);
        AccessUnitFlags flags = AccessUnitFlags.of(joinPoint, joinPoint, false);
        EncodedAccessUnit accessUnit = new EncodedAccessUnit(streamKey, configEpoch, sequence,
                ptsUs, ptsUs, flags, joinInfo, null, payload);
        output.onAccessUnit(accessUnit);
    }

    /**
     * Records the encode-stage windows for one emitted access unit.
     *
     * <p>Runs on the drain thread and writes only into the injected scope: one clock read, one
     * subtraction for the capture-to-output latency, one subtraction for the output interval and
     * one size sample. The capture-to-output sample self-calibrates instead of trusting the
     * assumption that the producer's timestamps share the monotonic clock base: a sample outside
     * {@code (0, 5 s)} is discarded, and three consecutive discarded samples disable the window
     * for the rest of the encoder's life, so an incompatible timestamp base cannot publish
     * garbage values.</p>
     *
     * @param ptsUs   presentation timestamp reported by the producer, in microseconds
     * @param payload emitted payload, whose length is the access-unit size
     */
    private void recordAccessUnitMetrics(long ptsUs, byte[] payload) {
        if (!metrics.isEnabled()) {
            return;
        }
        long nowNs = clock.nowNs();
        long ptsNs = ptsUs * 1000L;
        if (!capToOutSuppressed) {
            long capToOutNs = nowNs - ptsNs;
            if (capToOutNs > 0L && capToOutNs < MAX_PLAUSIBLE_CAP_TO_OUT_NS) {
                implausibleCapToOutSamples = 0;
                metrics.record(PipelineMetrics.Window.CAP_TO_OUT, capToOutNs);
            } else if (++implausibleCapToOutSamples >= MAX_IMPLAUSIBLE_CAP_TO_OUT_SAMPLES) {
                capToOutSuppressed = true;
                metrics.setGauge(PipelineMetrics.Gauge.CAP_TO_OUT_SUPPRESSED, 1L);
            }
        }
        if (lastOutputPtsNs != Long.MIN_VALUE && ptsNs > lastOutputPtsNs) {
            metrics.record(PipelineMetrics.Window.OUT_INTERVAL, ptsNs - lastOutputPtsNs);
        }
        lastOutputPtsNs = ptsNs;
        metrics.record(PipelineMetrics.Window.AU_BYTES, payload.length);
    }

    /**
     * Returns whether the capture-to-output probe disabled itself because the producer's
     * timestamps did not match the monotonic clock; exposed for tests and diagnostics.
     */
    public boolean isCapToOutSuppressed() {
        return capToOutSuppressed;
    }

    /**
     * Reports a terminal failure exactly once; subsequent failures are suppressed.
     */
    private void fail(String message) {
        notifyFailure(new EncoderException(message));
    }

    private void notifyFailure(EncoderException failure) {
        boolean first;
        synchronized (lock) {
            first = !failed;
            failed = true;
        }
        if (first) {
            output.onEncoderFailure(failure);
        }
    }

    private H264AccessUnitInfo analyzeOrNull(byte[] data) {
        try {
            return H264AnnexBParser.analyze(data);
        } catch (H264FormatException e) {
            return null;
        }
    }

    /**
     * Extracts SPS/PPS from codec-specific data. This batch accepts any of three framings: Annex B,
     * AVCC (length-prefixed), or a single raw NAL unit. A raw NAL carries no start code, so
     * {@link H264ConfigExtractor#fromCodecSpecificData(byte[], byte[])} rejects it on the first
     * attempt; the fallback then wraps each non-empty blob in an Annex B start code and retries.
     * Both paths must still yield exactly one SPS and one PPS, so the fallback does not relax the
     * format-failure semantics. Mixed framing across the two blobs and a single unified
     * normalization in the bridge are left for later evaluation, not handled here.
     */
    private static List<CodecConfigPart> extractConfigFromCsd(byte[] csd0, byte[] csd1)
            throws H264FormatException {
        try {
            return H264ConfigExtractor.fromCodecSpecificData(csd0, csd1);
        } catch (H264FormatException firstAttempt) {
            return H264ConfigExtractor.fromCodecSpecificData(frameAsAnnexB(csd0),
                    frameAsAnnexB(csd1));
        }
    }

    private static byte[] frameAsAnnexB(byte[] csd) {
        if (csd == null || csd.length == 0) {
            return csd;
        }
        return H264ConfigExtractor.toAnnexB(csd);
    }

    /**
     * Compares the resolved coded size, color and config parts against the published config.
     */
    private boolean configsEqual(List<CodecConfigPart> parts, int width, int height,
                                 ColorInfo color) {
        // Called with the lock held; reads the published config snapshot.
        if (width != publishedCodedWidth || height != publishedCodedHeight) {
            return false;
        }
        if (!colorsEqual(color, publishedColor)) {
            return false;
        }
        return configPartsEqual(parts, publishedConfigParts);
    }

    private static boolean configPartsEqual(List<CodecConfigPart> a, List<CodecConfigPart> b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            CodecConfigPart partA = a.get(i);
            CodecConfigPart partB = b.get(i);
            if (!partA.getId().equals(partB.getId())
                    || !Arrays.equals(partA.getPayload(), partB.getPayload())) {
                return false;
            }
        }
        return true;
    }

    /**
     * {@link ColorInfo} has no {@code equals}; compare the three fields that describe a color.
     */
    private static boolean colorsEqual(ColorInfo a, ColorInfo b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (a.isFullRange() != b.isFullRange()) {
            return false;
        }
        return equalStrings(a.getColorSpace(), b.getColorSpace())
                && equalStrings(a.getTransferFunction(), b.getTransferFunction());
    }

    private static boolean equalStrings(String a, String b) {
        if (a == null || b == null) {
            return a == b;
        }
        return a.equals(b);
    }

    /**
     * True when no usable color was observed, so the requested color must be used instead.
     */
    private static boolean isUnspecifiedColor(ColorInfo color) {
        return color == null
                || (color.getColorSpace() == null
                    && color.getTransferFunction() == null
                    && !color.isFullRange());
    }

    private ColorInfo requestedColor() {
        ColorInfo color = descriptor.getColorInfo();
        return color != null ? color : ColorInfo.UNSPECIFIED;
    }

    private static boolean joinQuietly(Thread thread) {
        try {
            thread.join(DRAIN_JOIN_TIMEOUT_MS);
            return !thread.isAlive();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void stopBridgeQuietly() {
        try {
            bridge.stop();
        } catch (RuntimeException ignored) {
            // Best-effort stop on the drain thread; a failure here must not break the loop.
        }
    }

    private void releaseOnce() {
        synchronized (lock) {
            if (bridgeReleased) {
                return;
            }
            bridgeReleased = true;
        }
        releaseQuietly(bridge);
    }

    private static void releaseQuietly(MediaCodecEncoderBridge bridge) {
        if (bridge == null) {
            return;
        }
        try {
            bridge.release();
        } catch (RuntimeException ignored) {
            // Release is best-effort; close must never throw.
        }
    }
}
