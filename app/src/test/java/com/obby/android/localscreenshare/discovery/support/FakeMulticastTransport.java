package com.obby.android.localscreenshare.discovery.support;

import androidx.annotation.NonNull;

import com.obby.android.localscreenshare.discovery.udp.MulticastTransport;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link MulticastTransport} that a test drives directly: it records sends and delivers packets
 * through the listener without opening a socket.
 */
public final class FakeMulticastTransport implements MulticastTransport {

    @NonNull
    private final List<byte[]> mPayloads = new ArrayList<>();

    private Listener mListener;

    private boolean mStarted;

    private boolean mClosed;

    @Override
    public void start(final Listener listener) {
        mListener = listener;
        mStarted = true;
    }

    @Override
    public void send(@NonNull final byte[] payload) {
        mPayloads.add(payload.clone());
    }

    @Override
    public void close() {
        mClosed = true;
        mListener = null;
    }

    /**
     * Delivers one datagram as if it arrived from {@code sender}.
     */
    public void deliver(@NonNull final byte[] payload, @NonNull final InetAddress sender) {
        final Listener listener = mListener;
        if (listener != null) {
            listener.onPacket(payload, sender);
        }
    }

    /**
     * Reports a transport-level failure through the listener.
     */
    public void fail(@NonNull final String reason) {
        final Listener listener = mListener;
        if (listener != null) {
            listener.onTransportError(reason);
        }
    }

    /**
     * Returns every payload passed to {@link #send(byte[])}.
     */
    @NonNull
    public List<byte[]> payloads() {
        return mPayloads;
    }

    public boolean isStarted() {
        return mStarted;
    }

    public boolean isClosed() {
        return mClosed;
    }
}
