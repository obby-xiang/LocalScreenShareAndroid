package com.obby.android.localscreenshare.transport;

import com.obby.android.localscreenshare.authorization.AuthorizationPolicy;
import com.obby.android.localscreenshare.core.audio.AudioTransport;
import com.obby.android.localscreenshare.core.model.AudioAccessUnit;
import com.obby.android.localscreenshare.core.model.AudioDescriptor;
import com.obby.android.localscreenshare.core.model.EncodedAccessUnit;
import com.obby.android.localscreenshare.core.model.StreamDescriptor;
import com.obby.android.localscreenshare.core.net.MediaChannelOffer;
import com.obby.android.localscreenshare.core.stream.MediaTransport;
import com.obby.android.localscreenshare.grpc.screenstream.AuthorizationStatus;
import com.obby.android.localscreenshare.grpc.screenstream.Pong;
import com.obby.android.localscreenshare.grpc.screenstream.ServerMessage;

import java.io.IOException;

import io.grpc.Status;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;

/**
 * Server-to-client {@link MediaTransport} backed by a single gRPC {@link StreamObserver}.
 *
 * <p>The observer is not thread-safe, so every send is serialized behind a private lock. The
 * owning {@link com.obby.android.localscreenshare.core.stream.ClientSendSession} already guarantees
 * at most one drainer per session, but a transport must not assume that and additionally guards
 * against concurrent sends and a concurrent {@link #close()}.</p>
 *
 * <p>The {@code secure} flag is fixed at construction and attached to every descriptor; it is
 * session-level metadata, not a per-unit property.</p>
 *
 * <p><b>Termination.</b> {@link #close()} only releases the outbound side: the owner of the call
 * decides how the RPC ends. {@link #abort(String)} instead ends the RPC itself with
 * {@link Status#ABORTED} and the given description, so a peer whose session was taken over stops
 * waiting for media immediately. An abort still terminates the RPC when {@link #close()} already
 * ran, because a revoked session must not leave the peer waiting; aborting twice does nothing the
 * second time. Both are serialized with sends behind the same lock, so an {@code onError} never
 * races an in-flight {@code onNext}.</p>
 *
 * <p><b>Audio.</b> The same object also carries the audio track through {@link AudioTransport}.
 * Audio sends check {@code isReady()} first and report "not ready" instead of queueing, so a client
 * that cannot keep up loses audio frames rather than adding latency to both tracks. The send lock
 * is shared with video because the observer is not thread-safe; a video send can therefore delay one
 * audio frame by the duration of that send, which is bounded by one access unit on a local
 * network.</p>
 */
public final class GrpcMediaTransport implements MediaTransport, AudioTransport {

    private final StreamObserver<ServerMessage> observer;

    private final boolean secure;

    private final Object sendLock = new Object();

    private boolean closed;

    private boolean aborted;

    /**
     * Creates a transport over {@code observer}.
     *
     * @throws IllegalArgumentException if {@code observer} is null
     */
    public GrpcMediaTransport(StreamObserver<ServerMessage> observer, boolean secure) {
        if (observer == null) {
            throw new IllegalArgumentException("observer must not be null");
        }
        this.observer = observer;
        this.secure = secure;
    }

    @Override
    public void sendDescriptor(StreamDescriptor descriptor) throws IOException {
        send(ServerMessage.newBuilder()
                .setDescriptor(ScreenStreamProtoMapper.toProtoDescriptor(descriptor, secure))
                .build());
    }

    @Override
    public void sendAccessUnit(EncodedAccessUnit accessUnit) throws IOException {
        send(ServerMessage.newBuilder()
                .setAccessUnit(ScreenStreamProtoMapper.toProtoAccessUnit(accessUnit))
                .build());
    }

    @Override
    public boolean sendAudioDescriptor(AudioDescriptor descriptor) throws IOException {
        return sendIfReady(ServerMessage.newBuilder()
                .setAudioDescriptor(ScreenStreamProtoMapper.toProtoAudioDescriptor(descriptor))
                .build());
    }

    @Override
    public boolean sendAudioUnit(AudioAccessUnit accessUnit) throws IOException {
        return sendIfReady(ServerMessage.newBuilder()
                .setAudioAccessUnit(ScreenStreamProtoMapper.toProtoAudioAccessUnit(accessUnit))
                .build());
    }

    @Override
    public void close() {
        synchronized (sendLock) {
            closed = true;
        }
    }

    /**
     * Echoes one round-trip probe.
     *
     * <p>Serialized behind the same lock as the media sends, because the observer is not
     * thread-safe and the echo arrives on the gRPC callback thread while media leaves from the
     * drain thread. The echo deliberately bypasses the session queue: measuring the queue would
     * hide the path latency this sample exists to capture. A closing transport drops the echo
     * silently, which costs one sample and never disturbs the caller.</p>
     *
     * @param seq            sequence number the client assigned to the ping
     * @param clientUptimeMs client uptime the ping carried, echoed verbatim
     */
    public void sendPong(int seq, long clientUptimeMs) {
        try {
            send(ServerMessage.newBuilder()
                    .setPong(Pong.newBuilder()
                            .setSeq(seq)
                            .setClientUptimeMs(clientUptimeMs)
                            .build())
                    .build());
        } catch (IOException ignored) {
            // The transport is closing; the client simply records one lost sample.
        }
    }

    /**
     * Tells the client that the request is parked until the server user answers.
     *
     * <p>Serialized behind the same lock as media and probes, because the observer is not
     * thread-safe and this notice is written while the client's own probes may still arrive. It is
     * sent once per call, before any media; an old client ignores the unknown payload and keeps
     * waiting, which is exactly the behaviour it had before the field existed.</p>
     */
    public void sendApprovalPending() {
        try {
            send(ServerMessage.newBuilder()
                    .setAuthorization(AuthorizationStatus.newBuilder()
                            .setState(AuthorizationStatus.State.PENDING)
                            .setTimeoutSeconds((int) (AuthorizationPolicy.APPROVAL_TIMEOUT_MS / 1000L))
                            .build())
                    .build());
        } catch (IOException e) {
            throw new IllegalStateException("transport is closed", e);
        }
    }

    /**
     * Offers one media channel to the client.
     *
     * <p>Serialized behind the same lock as media and probes. The offer is advisory: media keeps
     * flowing on this stream until the client answers READY, so a failed send only means the
     * negotiation falls back to the carrier after the offer window elapses. An old client ignores
     * the unknown payload and never notices the message.</p>
     *
     * @param offer channel to offer; must not be null
     */
    public void sendMediaOffer(MediaChannelOffer offer) {
        try {
            send(ServerMessage.newBuilder()
                    .setMediaOffer(MediaChannelOfferMapper.toProtoOffer(offer))
                    .build());
        } catch (IOException ignored) {
            // The call is closing; the offer times out on the server and falls back to the carrier.
        }
    }

    @Override
    public void abort(String reason) {
        synchronized (sendLock) {
            closed = true;
            if (aborted) {
                return;
            }
            aborted = true;
            final String description = reason == null ? "transport aborted" : reason;
            try {
                observer.onError(Status.ABORTED.withDescription(description).asRuntimeException());
            } catch (RuntimeException ignored) {
                // The observer was already completed or the client cancelled the call.
            }
        }
    }

    private void send(ServerMessage message) throws IOException {
        synchronized (sendLock) {
            if (closed) {
                throw new IOException("transport is closed");
            }
            observer.onNext(message);
        }
    }

    /**
     * Sends an audio message only when the call is ready to take it.
     *
     * <p>The readiness check and the send share the send lock, so an {@code onNext} never races a
     * concurrent {@link #close()} or {@link #abort(String)}. An observer that is not a
     * {@link ServerCallStreamObserver} (client-side fakes, tests) is always treated as ready.</p>
     *
     * @return true when the message was handed to the observer, false when the call was not ready
     * @throws IOException if the transport was already closed
     */
    private boolean sendIfReady(ServerMessage message) throws IOException {
        synchronized (sendLock) {
            if (closed) {
                throw new IOException("transport is closed");
            }
            if (observer instanceof ServerCallStreamObserver
                    && !((ServerCallStreamObserver<?>) observer).isReady()) {
                return false;
            }
            observer.onNext(message);
            return true;
        }
    }
}
