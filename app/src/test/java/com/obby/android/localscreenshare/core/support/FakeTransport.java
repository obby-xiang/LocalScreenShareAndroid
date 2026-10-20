package com.obby.android.localscreenshare.core.support;

import com.obby.android.localscreenshare.core.model.EncodedAccessUnit;
import com.obby.android.localscreenshare.core.model.StreamDescriptor;
import com.obby.android.localscreenshare.core.stream.MediaTransport;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * In-memory media transport that records every send in order. Test-only.
 *
 * <p>Events keep the descriptor and access unit references as passed, without copying, and
 * {@link #getEvents()} returns a snapshot of the synchronized list. Each failure injection is
 * one-shot: the checked failure applies to the next send of either kind, and the unchecked
 * variants are separate for descriptors and access units. {@code close()} and {@code abort()}
 * are idempotent; {@code abort()} is still recorded after {@code close()}, mirroring the
 * production transport, while {@code close()} after an abort changes nothing. The fake does not
 * enforce lifecycle, so a send after termination is still recorded.</p>
 */
public class FakeTransport implements MediaTransport {

    public enum Kind {
        DESCRIPTOR,
        ACCESS_UNIT
    }

    public static final class Event {

        private final Kind kind;
        private final StreamDescriptor descriptor;
        private final EncodedAccessUnit accessUnit;

        private Event(Kind kind, StreamDescriptor descriptor, EncodedAccessUnit accessUnit) {
            this.kind = kind;
            this.descriptor = descriptor;
            this.accessUnit = accessUnit;
        }

        public static Event descriptor(StreamDescriptor descriptor) {
            return new Event(Kind.DESCRIPTOR, descriptor, null);
        }

        public static Event accessUnit(EncodedAccessUnit accessUnit) {
            return new Event(Kind.ACCESS_UNIT, null, accessUnit);
        }

        public Kind getKind() {
            return kind;
        }

        public StreamDescriptor getDescriptor() {
            return descriptor;
        }

        public EncodedAccessUnit getAccessUnit() {
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

    private boolean failNextSend;
    private boolean failNextDescriptorSendUnchecked;
    private boolean failNextAccessUnitSendUnchecked;
    private int closeCount;
    private int abortCount;
    private String abortReason;
    private boolean closed;
    private boolean aborted;

    public synchronized void failNextSend() {
        failNextSend = true;
    }

    public synchronized void failNextDescriptorSendUnchecked() {
        failNextDescriptorSendUnchecked = true;
    }

    public synchronized void failNextAccessUnitSendUnchecked() {
        failNextAccessUnitSendUnchecked = true;
    }

    @Override
    public void sendDescriptor(StreamDescriptor descriptor) throws IOException {
        maybeFail();
        maybeFailUnchecked(true);
        events.add(Event.descriptor(descriptor));
    }

    @Override
    public void sendAccessUnit(EncodedAccessUnit accessUnit) throws IOException {
        maybeFail();
        maybeFailUnchecked(false);
        events.add(Event.accessUnit(accessUnit));
    }

    @Override
    public synchronized void close() {
        if (!closed) {
            closed = true;
            closeCount++;
        }
    }

    @Override
    public synchronized void abort(String reason) {
        closed = true;
        if (!aborted) {
            aborted = true;
            abortCount++;
            abortReason = reason;
        }
    }

    public synchronized boolean isClosed() {
        return closed;
    }

    public synchronized int getCloseCount() {
        return closeCount;
    }

    public synchronized int getAbortCount() {
        return abortCount;
    }

    public synchronized String getAbortReason() {
        return abortReason;
    }

    public List<Event> getEvents() {
        synchronized (events) {
            return new ArrayList<>(events);
        }
    }

    private synchronized void maybeFail() throws IOException {
        if (failNextSend) {
            failNextSend = false;
            throw new IOException("injected transport failure");
        }
    }

    private synchronized void maybeFailUnchecked(boolean descriptor) {
        if (descriptor && failNextDescriptorSendUnchecked) {
            failNextDescriptorSendUnchecked = false;
            throw new IllegalStateException("injected unchecked descriptor send failure");
        }
        if (!descriptor && failNextAccessUnitSendUnchecked) {
            failNextAccessUnitSendUnchecked = false;
            throw new IllegalStateException("injected unchecked access unit send failure");
        }
    }
}
