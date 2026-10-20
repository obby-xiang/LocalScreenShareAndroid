package com.obby.android.localscreenshare.discovery.udp;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.obby.android.localscreenshare.support.BackoffPolicy;
import com.obby.android.localscreenshare.support.DelayedScheduler;

import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * {@link MulticastTransport} over interface sockets, with receive-loop recovery.
 *
 * <p>Each endpoint gets its own daemon receiver thread. A receiver resets its error counter on
 * every successful datagram; after {@link Constants#MULTICAST_MAX_RECEIVE_ERRORS} consecutive
 * failures it closes its endpoint and asks for a rebuild. Rebuilding closes and reopens every
 * endpoint after a {@link BackoffPolicy} delay, which is what recovers the channel after Wi-Fi is
 * replaced or a socket is torn down underneath it. The receiver thread of a closed endpoint simply
 * exits, so a rebuild never has to join threads.</p>
 *
 * <p>Sending iterates over a snapshot of the endpoint list and reports failures instead of
 * throwing: an announcement is best-effort and must never crash the scheduler.</p>
 */
public final class SocketMulticastTransport implements MulticastTransport {

    @NonNull
    private final MulticastSocketFactory mSocketFactory;

    @NonNull
    private final MulticastConfig mConfig;

    @NonNull
    private final DelayedScheduler mScheduler;

    @NonNull
    private final BackoffPolicy mRebuildBackoff;

    @NonNull
    private final List<MulticastEndpoint> mEndpoints = new ArrayList<>();

    @Nullable
    private volatile Listener mListener;

    private volatile boolean mRunning;

    private volatile boolean mRebuilding;

    private int mGeneration;

    /**
     * Creates a transport.
     *
     * @param socketFactory factory that opens the interface sockets
     * @param config group, port and interface bound
     * @param scheduler scheduler used to delay a rebuild; owned and shut down by this transport
     */
    public SocketMulticastTransport(@NonNull final MulticastSocketFactory socketFactory,
        @NonNull final MulticastConfig config, @NonNull final DelayedScheduler scheduler) {
        mSocketFactory = Objects.requireNonNull(socketFactory, "socketFactory");
        mConfig = Objects.requireNonNull(config, "config");
        mScheduler = Objects.requireNonNull(scheduler, "scheduler");
        mRebuildBackoff = BackoffPolicy.defaults();
    }

    @Override
    public synchronized void start(@Nullable final Listener listener) {
        if (mRunning) {
            mListener = listener;
            return;
        }
        mRunning = true;
        mListener = listener;
        openEndpoints();
    }

    @Override
    public void send(@NonNull final byte[] payload) {
        final List<MulticastEndpoint> endpoints;
        synchronized (this) {
            endpoints = new ArrayList<>(mEndpoints);
        }
        for (final MulticastEndpoint endpoint : endpoints) {
            try {
                endpoint.send(payload);
            } catch (IOException | RuntimeException e) {
                reportError("send failed on " + endpoint.interfaceName() + ": " + e.getMessage());
            }
        }
    }

    @Override
    public void close() {
        final List<MulticastEndpoint> endpoints;
        synchronized (this) {
            if (!mRunning) {
                return;
            }
            mRunning = false;
            mListener = null;
            endpoints = new ArrayList<>(mEndpoints);
            mEndpoints.clear();
            notifyAll();
        }
        for (final MulticastEndpoint endpoint : endpoints) {
            closeQuietly(endpoint);
        }
        mScheduler.shutdown();
    }

    private synchronized void openEndpoints() {
        if (!mRunning) {
            return;
        }
        final List<MulticastEndpoint> opened;
        try {
            opened = mSocketFactory.open(mConfig);
        } catch (IOException | RuntimeException e) {
            reportError("open failed: " + e.getMessage());
            scheduleRebuild();
            return;
        }
        mEndpoints.addAll(opened);
        final int generation = ++mGeneration;
        if (mListener == null) {
            return;
        }
        if (opened.isEmpty()) {
            reportError("no usable multicast interface");
            return;
        }
        for (final MulticastEndpoint endpoint : opened) {
            final Thread thread = new Thread(() -> receiveLoop(endpoint, generation),
                "lss-multicast-rx-" + endpoint.interfaceName());
            thread.setDaemon(true);
            thread.start();
        }
    }

    private void receiveLoop(@NonNull final MulticastEndpoint endpoint, final int generation) {
        final byte[] buffer = new byte[MulticastConstants.MULTICAST_RECEIVE_BUFFER];
        int consecutiveErrors = 0;
        while (mRunning) {
            try {
                final int length = endpoint.receive(buffer);
                consecutiveErrors = 0;
                final InetAddress sender = endpoint.lastSender();
                final Listener listener = mListener;
                if (listener != null && sender != null && length > 0) {
                    listener.onPacket(Arrays.copyOf(buffer, length), sender);
                }
            } catch (IOException | RuntimeException e) {
                if (!mRunning) {
                    return;
                }
                consecutiveErrors++;
                if (consecutiveErrors >= MulticastConstants.MULTICAST_MAX_RECEIVE_ERRORS) {
                    onEndpointFailed(endpoint, generation, e);
                    return;
                }
            }
        }
    }

    private synchronized void onEndpointFailed(@NonNull final MulticastEndpoint endpoint, final int generation,
        @NonNull final Exception cause) {
        if (!mRunning || generation != mGeneration || mRebuilding) {
            return;
        }
        reportError("receive failed on " + endpoint.interfaceName() + ": " + cause.getMessage());

        final List<MulticastEndpoint> stale = new ArrayList<>(mEndpoints);
        mEndpoints.clear();
        for (final MulticastEndpoint staleEndpoint : stale) {
            closeQuietly(staleEndpoint);
        }
        scheduleRebuild();
    }

    private synchronized void scheduleRebuild() {
        if (!mRunning || mRebuilding) {
            return;
        }
        mRebuilding = true;
        final long delayMs = mRebuildBackoff.nextDelayMs();
        mScheduler.schedule(delayMs, this::rebuild);
    }

    private synchronized void rebuild() {
        mRebuilding = false;
        if (!mRunning) {
            return;
        }
        openEndpoints();
    }

    private void reportError(@NonNull final String reason) {
        final Listener listener = mListener;
        if (listener != null) {
            listener.onTransportError(reason);
        }
    }

    private static void closeQuietly(@NonNull final MulticastEndpoint endpoint) {
        try {
            endpoint.close();
        } catch (IOException | RuntimeException e) {
            // Closing an already broken socket is not actionable.
        }
    }
}
