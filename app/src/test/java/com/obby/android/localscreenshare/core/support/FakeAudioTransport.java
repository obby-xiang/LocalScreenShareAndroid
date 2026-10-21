package com.obby.android.localscreenshare.core.support;

import com.obby.android.localscreenshare.core.audio.AudioTransport;
import com.obby.android.localscreenshare.core.model.AudioAccessUnit;
import com.obby.android.localscreenshare.core.model.AudioDescriptor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * In-memory audio transport that records every send in order. Test-only.
 *
 * <p>It can report "not ready" (the client cannot take another message), fail the next send with an
 * {@link IOException}, or block a send until a latch is released, which is how the tests model a
 * slow client without sleeping.</p>
 */
public class FakeAudioTransport implements AudioTransport {

    public enum Kind {
        DESCRIPTOR,
        ACCESS_UNIT
    }

    public static final class Event {

        private final Kind kind;
        private final AudioDescriptor descriptor;
        private final AudioAccessUnit accessUnit;

        private Event(Kind kind, AudioDescriptor descriptor, AudioAccessUnit accessUnit) {
            this.kind = kind;
            this.descriptor = descriptor;
            this.accessUnit = accessUnit;
        }

        public Kind getKind() {
            return kind;
        }

        public AudioDescriptor getDescriptor() {
            return descriptor;
        }

        public AudioAccessUnit getAccessUnit() {
            return accessUnit;
        }

        public boolean isDescriptor() {
            return kind == Kind.DESCRIPTOR;
        }

        public boolean isAccessUnit() {
            return kind == Kind.ACCESS_UNIT;
        }
    }

    public final List<Event> events = Collections.synchronizedList(new ArrayList<Event>());

    private boolean ready = true;

    private boolean failNextSend;

    private int notReadyCount;

    private CountDownLatch blockLatch;

    private final CountDownLatch sendStarted = new CountDownLatch(1);

    public void setReady(boolean ready) {
        this.ready = ready;
    }

    public void failNextSend() {
        this.failNextSend = true;
    }

    public int getNotReadyCount() {
        return notReadyCount;
    }

    public void blockSendsUntil(CountDownLatch latch) {
        this.blockLatch = latch;
    }

    public int getDescriptorCount() {
        synchronized (events) {
            int count = 0;
            for (Event event : events) {
                if (event.isDescriptor()) {
                    count++;
                }
            }
            return count;
        }
    }

    public int getAccessUnitCount() {
        synchronized (events) {
            int count = 0;
            for (Event event : events) {
                if (event.isAccessUnit()) {
                    count++;
                }
            }
            return count;
        }
    }

    @Override
    public boolean sendAudioDescriptor(AudioDescriptor descriptor) throws IOException {
        awaitBlock();
        if (failNextSend) {
            failNextSend = false;
            throw new IOException("audio transport failed");
        }
        if (!ready) {
            notReadyCount++;
            return false;
        }
        events.add(new Event(Kind.DESCRIPTOR, descriptor, null));
        return true;
    }

    @Override
    public boolean sendAudioUnit(AudioAccessUnit accessUnit) throws IOException {
        awaitBlock();
        if (failNextSend) {
            failNextSend = false;
            throw new IOException("audio transport failed");
        }
        if (!ready) {
            notReadyCount++;
            return false;
        }
        events.add(new Event(Kind.ACCESS_UNIT, null, accessUnit));
        return true;
    }

    private void awaitBlock() throws IOException {
        final CountDownLatch latch = blockLatch;
        if (latch == null) {
            return;
        }
        sendStarted.countDown();
        try {
            if (!latch.await(5_000L, TimeUnit.MILLISECONDS)) {
                throw new IOException("blocked send timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("blocked send interrupted", e);
        }
    }

    /**
     * Waits until a blocked send has actually started.
     */
    public boolean awaitSendStarted(long timeoutMs) throws InterruptedException {
        return sendStarted.await(timeoutMs, TimeUnit.MILLISECONDS);
    }
}
