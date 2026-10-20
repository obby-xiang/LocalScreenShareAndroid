package com.obby.android.localscreenshare.core.support;

import com.obby.android.localscreenshare.core.codec.mediacodec.DecodedOutput;
import com.obby.android.localscreenshare.core.codec.mediacodec.DecoderFormat;
import com.obby.android.localscreenshare.core.codec.mediacodec.MediaCodecDecoderBridge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * JVM-only decoder bridge that records the configured format, queued input buffers and the
 * lifecycle of every output buffer.
 *
 * <p>Input buffer indices increment on every successful {@code dequeueInputBuffer} call;
 * {@link #failNextInputTimeout()} makes the next call report a timeout ({@code -1}) without
 * consuming an index, and {@link #failNextInputTimeouts(int)} does so for a run of calls so a
 * decoder's input budget can actually expire. Queued input bytes are copied both when recorded and
 * when read back.</p>
 *
 * <p>Output buffers are tracked as a contract: every index returned by {@link #dequeueOutput(long)}
 * must be released exactly once through {@link #present(int, long)} or {@link #discard(int)}.
 * {@link #getUnreleasedOutputIndices()} and {@link #getDoubleReleasedOutputIndices()} expose
 * violations so tests fail on a leak instead of silently passing.</p>
 */
public final class FakeMediaCodecDecoderBridge implements MediaCodecDecoderBridge {

    private final BlockingDeque<DecodedOutput> outputs = new LinkedBlockingDeque<>();

    private final List<Integer> queuedInputIndices = new CopyOnWriteArrayList<>();
    private final List<byte[]> queuedInputData = new CopyOnWriteArrayList<>();
    private final List<Long> queuedInputPtsUs = new CopyOnWriteArrayList<>();

    private final List<Integer> dequeuedOutputIndices = new CopyOnWriteArrayList<>();
    private final List<Integer> presentedIndices = new CopyOnWriteArrayList<>();
    private final List<Long> presentedTimestampsNs = new CopyOnWriteArrayList<>();
    private final List<Integer> discardedIndices = new CopyOnWriteArrayList<>();

    private final AtomicInteger configureCount = new AtomicInteger();
    private final AtomicInteger startCount = new AtomicInteger();
    private final AtomicInteger stopCount = new AtomicInteger();
    private final AtomicInteger releaseCount = new AtomicInteger();
    private final AtomicInteger dequeueInputCount = new AtomicInteger();
    private final AtomicInteger nextInputIndex = new AtomicInteger();

    private final AtomicReference<RuntimeException> nextConfigureFailure = new AtomicReference<>();
    private final AtomicReference<RuntimeException> nextStartFailure = new AtomicReference<>();
    private final AtomicReference<RuntimeException> nextQueueFailure = new AtomicReference<>();
    private final AtomicReference<RuntimeException> nextDequeueFailure = new AtomicReference<>();
    private final AtomicReference<Boolean> nextInputTimeout = new AtomicReference<>();
    private final AtomicInteger inputTimeoutsLeft = new AtomicInteger();

    private volatile DecoderFormat lastConfigureFormat;

    @Override
    public void configure(DecoderFormat format) {
        RuntimeException failure = nextConfigureFailure.getAndSet(null);
        if (failure != null) {
            throw failure;
        }
        lastConfigureFormat = format;
        configureCount.incrementAndGet();
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
    public int dequeueInputBuffer(long timeoutUs) {
        dequeueInputCount.incrementAndGet();
        if (inputTimeoutsLeft.getAndUpdate(left -> left > 0 ? left - 1 : 0) > 0) {
            return -1;
        }
        if (Boolean.TRUE.equals(nextInputTimeout.getAndSet(null))) {
            return -1;
        }
        return nextInputIndex.getAndIncrement();
    }

    @Override
    public void queueInputBuffer(int index, byte[] data, long ptsUs) {
        RuntimeException failure = nextQueueFailure.getAndSet(null);
        if (failure != null) {
            throw failure;
        }
        queuedInputIndices.add(index);
        queuedInputData.add(data.clone());
        queuedInputPtsUs.add(ptsUs);
    }

    @Override
    public DecodedOutput dequeueOutput(long timeoutUs) {
        RuntimeException failure = nextDequeueFailure.getAndSet(null);
        if (failure != null) {
            throw failure;
        }
        DecodedOutput output;
        try {
            if (timeoutUs <= 0L) {
                output = outputs.poll();
            } else {
                output = outputs.poll(timeoutUs, TimeUnit.MICROSECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        if (output != null && output.hasBuffer()) {
            dequeuedOutputIndices.add(output.getBufferIndex());
        }
        return output;
    }

    @Override
    public void present(int index, long renderTimestampNs) {
        presentedIndices.add(index);
        presentedTimestampsNs.add(renderTimestampNs);
    }

    @Override
    public void discard(int index) {
        discardedIndices.add(index);
    }

    public DecoderFormat getLastConfigureFormat() {
        return lastConfigureFormat;
    }

    public int getConfigureCount() {
        return configureCount.get();
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

    public int getDequeueInputCount() {
        return dequeueInputCount.get();
    }

    public List<Integer> getQueuedInputIndices() {
        return Collections.unmodifiableList(new ArrayList<>(queuedInputIndices));
    }

    public List<byte[]> getQueuedInputData() {
        List<byte[]> copies = new ArrayList<>(queuedInputData.size());
        for (byte[] data : queuedInputData) {
            copies.add(data.clone());
        }
        return Collections.unmodifiableList(copies);
    }

    public List<Long> getQueuedInputPtsUs() {
        return Collections.unmodifiableList(new ArrayList<>(queuedInputPtsUs));
    }

    public int getPendingOutputCount() {
        return outputs.size();
    }

    public void enqueueOutput(DecodedOutput output) {
        outputs.add(output);
    }

    public void failNextConfigure(RuntimeException failure) {
        nextConfigureFailure.set(failure);
    }

    public void failNextStart(RuntimeException failure) {
        nextStartFailure.set(failure);
    }

    public void failNextQueue(RuntimeException failure) {
        nextQueueFailure.set(failure);
    }

    public void failNextDequeue(RuntimeException failure) {
        nextDequeueFailure.set(failure);
    }

    public void failNextInputTimeout() {
        nextInputTimeout.set(Boolean.TRUE);
    }

    /**
     * Makes the next {@code count} input dequeues report a timeout.
     */
    public void failNextInputTimeouts(int count) {
        inputTimeoutsLeft.set(Math.max(0, count));
    }

    public List<Integer> getDequeuedOutputIndices() {
        return Collections.unmodifiableList(new ArrayList<>(dequeuedOutputIndices));
    }

    public List<Integer> getPresentedIndices() {
        return Collections.unmodifiableList(new ArrayList<>(presentedIndices));
    }

    public List<Long> getPresentedTimestampsNs() {
        return Collections.unmodifiableList(new ArrayList<>(presentedTimestampsNs));
    }

    public List<Integer> getDiscardedIndices() {
        return Collections.unmodifiableList(new ArrayList<>(discardedIndices));
    }

    /**
     * Returns the dequeued output indices that were neither presented nor discarded.
     */
    public List<Integer> getUnreleasedOutputIndices() {
        List<Integer> released = new ArrayList<>(presentedIndices);
        released.addAll(discardedIndices);
        List<Integer> leaked = new ArrayList<>();
        for (Integer index : dequeuedOutputIndices) {
            if (!released.remove(index)) {
                leaked.add(index);
            }
        }
        return Collections.unmodifiableList(leaked);
    }

    /**
     * Returns the output indices released more than once; each must be released exactly once.
     */
    public List<Integer> getDoubleReleasedOutputIndices() {
        List<Integer> released = new ArrayList<>(presentedIndices);
        released.addAll(discardedIndices);
        List<Integer> duplicated = new ArrayList<>();
        for (Integer index : released) {
            if (Collections.frequency(released, index) > 1 && !duplicated.contains(index)) {
                duplicated.add(index);
            }
        }
        return Collections.unmodifiableList(duplicated);
    }
}
