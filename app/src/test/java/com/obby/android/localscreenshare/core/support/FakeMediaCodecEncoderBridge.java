package com.obby.android.localscreenshare.core.support;

import android.view.Surface;

import com.obby.android.localscreenshare.core.codec.mediacodec.EncodedSample;
import com.obby.android.localscreenshare.core.codec.mediacodec.EncoderFormat;
import com.obby.android.localscreenshare.core.codec.mediacodec.EncoderStreamFormat;
import com.obby.android.localscreenshare.core.codec.mediacodec.MediaCodecEncoderBridge;
import com.obby.android.localscreenshare.core.model.ColorInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * JVM-only encoder bridge that records every call and serves queued output events.
 *
 * <p>The fake never creates an Android {@link Surface}: {@link #createInputSurface()} always
 * returns {@code null}. A format change both updates the {@link #getOutputFormat()} snapshot
 * and enqueues a {@link EncodedSample} event with {@code formatChanged=true} (no display access
 * unit), so a downstream drain loop is driven exactly as it would be by MediaCodec's format
 * change notification.</p>
 */
public final class FakeMediaCodecEncoderBridge implements MediaCodecEncoderBridge {

    private static final long BLOCK_RELEASE_TIMEOUT_MS = 5_000L;

    private static final long AUTO_SYNC_PTS_STEP_US = 33_333L;

    private final BlockingDeque<EncodedSample> samples = new LinkedBlockingDeque<>();

    private final List<Integer> bitrateUpdates = new CopyOnWriteArrayList<>();

    private final AtomicInteger configureCount = new AtomicInteger();
    private final AtomicInteger createInputSurfaceCount = new AtomicInteger();
    private final AtomicInteger startCount = new AtomicInteger();
    private final AtomicInteger stopCount = new AtomicInteger();
    private final AtomicInteger releaseCount = new AtomicInteger();
    private final AtomicInteger requestSyncFrameCount = new AtomicInteger();
    private final AtomicLong autoSyncPtsUs = new AtomicLong();

    private final AtomicReference<RuntimeException> nextConfigureFailure = new AtomicReference<>();
    private final AtomicReference<RuntimeException> nextCreateInputSurfaceFailure =
            new AtomicReference<>();
    private final AtomicReference<RuntimeException> nextStartFailure = new AtomicReference<>();
    private final AtomicReference<RuntimeException> nextDequeueFailure = new AtomicReference<>();

    private final AtomicReference<CountDownLatch> pendingBlock = new AtomicReference<>();

    private volatile CountDownLatch activeBlock;

    private volatile EncoderFormat lastConfigureFormat;
    private volatile EncoderStreamFormat outputFormat;
    private volatile byte[] syncResponse;

    @Override
    public void configure(EncoderFormat format) {
        RuntimeException failure = nextConfigureFailure.getAndSet(null);
        if (failure != null) {
            throw failure;
        }
        lastConfigureFormat = format;
        configureCount.incrementAndGet();
    }

    @Override
    public Surface createInputSurface() {
        RuntimeException failure = nextCreateInputSurfaceFailure.getAndSet(null);
        if (failure != null) {
            throw failure;
        }
        createInputSurfaceCount.incrementAndGet();
        return null;
    }

    @Override
    public void start() {
        RuntimeException failure = nextStartFailure.getAndSet(null);
        if (failure != null) {
            throw failure;
        }
        startCount.incrementAndGet();
    }

    @Override
    public void stop() {
        stopCount.incrementAndGet();
    }

    @Override
    public void release() {
        releaseCount.incrementAndGet();
    }

    @Override
    public void requestSyncFrame() {
        requestSyncFrameCount.incrementAndGet();
        byte[] payload = syncResponse;
        if (payload != null) {
            samples.add(new EncodedSample(payload,
                    autoSyncPtsUs.getAndAdd(AUTO_SYNC_PTS_STEP_US),
                    true, false, false, false));
        }
    }

    @Override
    public void updateBitrate(int bitrateBps) {
        bitrateUpdates.add(bitrateBps);
    }

    @Override
    public EncodedSample dequeueOutput(long timeoutUs) {
        RuntimeException failure = nextDequeueFailure.getAndSet(null);
        if (failure != null) {
            throw failure;
        }
        awaitBlockGate();
        try {
            if (timeoutUs <= 0L) {
                return samples.poll();
            }
            return samples.poll(timeoutUs, TimeUnit.MICROSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    @Override
    public EncoderStreamFormat getOutputFormat() {
        return outputFormat;
    }

    public EncoderFormat getLastConfigureFormat() {
        return lastConfigureFormat;
    }

    public int getConfigureCount() {
        return configureCount.get();
    }

    public int getCreateInputSurfaceCount() {
        return createInputSurfaceCount.get();
    }

    public int getStartCount() {
        return startCount.get();
    }

    public int getStopCount() {
        return stopCount.get();
    }

    public int getReleaseCount() {
        return releaseCount.get();
    }

    public int getRequestSyncFrameCount() {
        return requestSyncFrameCount.get();
    }

    public List<Integer> getBitrateUpdates() {
        return Collections.unmodifiableList(new ArrayList<>(bitrateUpdates));
    }

    public int getPendingSampleCount() {
        return samples.size();
    }

    public void setSyncResponse(byte[] idrPayload) {
        this.syncResponse = idrPayload == null ? null : idrPayload.clone();
    }

    /**
     * Updates the format snapshot and enqueues one {@code formatChanged} event carrying no
     * display access unit (empty data, zero pts, all other flags false).
     */
    public void enqueueFormatChange(byte[] csd0, byte[] csd1, int width, int height) {
        outputFormat = new EncoderStreamFormat(csd0, csd1, width, height, ColorInfo.UNSPECIFIED);
        samples.add(new EncodedSample(new byte[0], 0L, false, false, true, false));
    }

    public void enqueueSample(byte[] data, long ptsUs, boolean keyFrame, boolean codecConfig) {
        enqueueSample(data, ptsUs, keyFrame, codecConfig, false, false);
    }

    public void enqueueSample(byte[] data, long ptsUs, boolean keyFrame, boolean codecConfig,
                              boolean formatChanged, boolean endOfStream) {
        samples.add(new EncodedSample(data, ptsUs, keyFrame, codecConfig, formatChanged,
                endOfStream));
    }

    public void failNextConfigure(RuntimeException failure) {
        nextConfigureFailure.set(failure);
    }

    public void failNextCreateInputSurface(RuntimeException failure) {
        nextCreateInputSurfaceFailure.set(failure);
    }

    public void failNextStart(RuntimeException failure) {
        nextStartFailure.set(failure);
    }

    public void failNextDequeue(RuntimeException failure) {
        nextDequeueFailure.set(failure);
    }

    /**
     * Makes the next {@link #dequeueOutput(long)} invocation wait until
     * {@link #releaseBlockedDequeue()} is called or the internal release timeout elapses.
     */
    public void blockNextDequeue() {
        pendingBlock.set(new CountDownLatch(1));
    }

    public void releaseBlockedDequeue() {
        CountDownLatch latch = pendingBlock.get();
        if (latch == null) {
            latch = activeBlock;
        }
        if (latch != null) {
            latch.countDown();
        }
    }

    private void awaitBlockGate() {
        CountDownLatch latch = pendingBlock.getAndSet(null);
        if (latch == null) {
            return;
        }
        activeBlock = latch;
        try {
            if (!latch.await(BLOCK_RELEASE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("blocked dequeue was not released");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            activeBlock = null;
        }
    }
}
