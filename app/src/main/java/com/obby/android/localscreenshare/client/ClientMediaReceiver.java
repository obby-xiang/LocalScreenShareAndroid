package com.obby.android.localscreenshare.client;

import com.obby.android.localscreenshare.core.audio.ClientAudioSession;
import com.obby.android.localscreenshare.core.client.ClientMediaSession;
import com.obby.android.localscreenshare.core.metrics.ArrivalTracker;
import com.obby.android.localscreenshare.core.metrics.PipelineMetrics;
import com.obby.android.localscreenshare.core.model.EncodedAccessUnit;
import com.obby.android.localscreenshare.core.model.StreamDescriptor;
import com.obby.android.localscreenshare.core.model.StreamKey;
import com.obby.android.localscreenshare.grpc.screenstream.ServerMessage;
import com.obby.android.localscreenshare.transport.ScreenStreamProtoMapper;

/**
 * Drives a {@link ClientMediaSession} from wire messages on the client's decode thread.
 *
 * <p>The receiver owns the deserialization of server messages and the stream identity, and reports
 * the session-level secure flag to a {@link ClientSecureListener} and the display geometry of the
 * incoming stream to a {@link StreamGeometryListener}. It also counts every received access unit and
 * its payload bytes into the metric scope, which is what makes the viewer's frame rate and bitrate
 * measurable at all. It is deliberately not thread-safe: the owning client must call
 * {@link #onServerMessage} for video messages from a single decode thread, and for audio messages
 * from the single audio thread, so decoding never runs on the gRPC shared executor. The two tracks
 * touch disjoint state - {@code lastSecure} and the video session belong to the decode thread, the
 * audio session to the audio thread and its own lock - which is why the split needs no further
 * serialization.</p>
 *
 * <p><b>Two entry points.</b> A message can be applied either as the raw wire format of the
 * control plane ({@link #onServerMessage}) or as the model objects a media channel already decoded
 * ({@link #onDescriptor} and {@link #onAccessUnit}). Both paths converge on the same model entry
 * points, so geometry, the secure flag, arrival tracking, the receive counters and the session see
 * exactly one implementation regardless of the channel the bytes arrived on.</p>
 */
public final class ClientMediaReceiver {

    /**
     * Arrival marker for a message whose arrival time was not measured.
     *
     * <p>Shared with the model entry points so a channel that cannot timestamp a unit reports no
     * arrival sample instead of a fabricated one.</p>
     */
    public static final long UNKNOWN_ARRIVAL_NS = Long.MIN_VALUE;

    private final StreamKey streamKey;

    private final ClientMediaSession session;

    private final ClientSecureListener secureListener;

    private final StreamGeometryListener geometryListener;

    private final ArrivalTracker arrivalTracker;

    private final PipelineMetrics metrics;

    // Optional audio track of the same stream. Audio messages never reach the video session, and a
    // failure in the audio path is contained here.
    private final ClientAudioSession audioSession;

    private Boolean lastSecure;

    /**
     * Creates a receiver that reports only the secure flag.
     *
     * @param streamKey      stream identity used to rebuild descriptors and access units
     * @param session        the session driven by this receiver
     * @param secureListener optional listener for secure-flag changes; may be null
     * @throws IllegalArgumentException if {@code streamKey} or {@code session} is null
     */
    public ClientMediaReceiver(StreamKey streamKey, ClientMediaSession session,
                               ClientSecureListener secureListener) {
        this(streamKey, session, secureListener, null);
    }

    /**
     * Creates a receiver for one session.
     *
     * @param streamKey        stream identity used to rebuild descriptors and access units
     * @param session          the session driven by this receiver
     * @param secureListener   optional listener for secure-flag changes; may be null
     * @param geometryListener optional listener for stream geometry changes; may be null
     * @throws IllegalArgumentException if {@code streamKey} or {@code session} is null
     */
    public ClientMediaReceiver(StreamKey streamKey, ClientMediaSession session,
                               ClientSecureListener secureListener,
                               StreamGeometryListener geometryListener) {
        this(streamKey, session, secureListener, geometryListener, null);
    }

    /**
     * Creates a receiver that also feeds arrival probes.
     *
     * <p>The tracker is fed from the same single decode thread that drives the session, so its
     * intervals follow wire order and never need locking.</p>
     *
     * @param streamKey        stream identity used to rebuild descriptors and access units
     * @param session          the session driven by this receiver
     * @param secureListener   optional listener for secure-flag changes; may be null
     * @param geometryListener optional listener for stream geometry changes; may be null
     * @param arrivalTracker   optional receiver of arrival statistics; may be null
     * @throws IllegalArgumentException if {@code streamKey} or {@code session} is null
     */
    public ClientMediaReceiver(StreamKey streamKey, ClientMediaSession session,
                               ClientSecureListener secureListener,
                               StreamGeometryListener geometryListener,
                               ArrivalTracker arrivalTracker) {
        this(streamKey, session, secureListener, geometryListener, arrivalTracker,
                PipelineMetrics.NONE);
    }

    /**
     * Creates a receiver that also counts received access units.
     *
     * <p>The counters answer "how many frames and how many bytes did this viewer receive", which is
     * the frame-rate and bitrate the overlay shows. Counting happens on the same decoded access unit
     * the arrival probe already consumes, so it adds no timestamp, no allocation and no second
     * deserialization; a disabled scope makes both calls a single volatile read.</p>
     *
     * @param streamKey        stream identity used to rebuild descriptors and access units
     * @param session          the session driven by this receiver
     * @param secureListener   optional listener for secure-flag changes; may be null
     * @param geometryListener optional listener for stream geometry changes; may be null
     * @param arrivalTracker   optional receiver of arrival statistics; may be null
     * @param metrics          receive-side metric scope; required, may be
     *                         {@link PipelineMetrics#NONE}
     * @throws IllegalArgumentException if {@code streamKey}, {@code session} or {@code metrics} is
     *                                  null
     */
    public ClientMediaReceiver(StreamKey streamKey, ClientMediaSession session,
                               ClientSecureListener secureListener,
                               StreamGeometryListener geometryListener,
                               ArrivalTracker arrivalTracker,
                               PipelineMetrics metrics) {
        this(streamKey, session, secureListener, geometryListener, arrivalTracker, metrics, null);
    }

    /**
     * Creates a receiver that also drives the stream's optional audio track.
     *
     * <p>Audio messages are deserialized and applied on the audio thread - never on the video decode
     * thread - and they never enter {@link ClientMediaSession}: a malformed audio message, an
     * unknown audio codec or a failing audio decoder only affects the audio session.</p>
     *
     * @param streamKey        stream identity used to rebuild descriptors and access units
     * @param session          the video session driven by this receiver
     * @param secureListener   optional listener for secure-flag changes; may be null
     * @param geometryListener optional listener for stream geometry changes; may be null
     * @param arrivalTracker   optional receiver of arrival statistics; may be null
     * @param metrics          receive-side metric scope; required
     * @param audioSession     optional audio session of the same stream; may be null
     * @throws IllegalArgumentException if {@code streamKey}, {@code session} or {@code metrics} is
     *                                  null
     */
    public ClientMediaReceiver(StreamKey streamKey, ClientMediaSession session,
                               ClientSecureListener secureListener,
                               StreamGeometryListener geometryListener,
                               ArrivalTracker arrivalTracker, PipelineMetrics metrics,
                               ClientAudioSession audioSession) {
        if (streamKey == null) {
            throw new IllegalArgumentException("streamKey must not be null");
        }
        if (session == null) {
            throw new IllegalArgumentException("session must not be null");
        }
        if (metrics == null) {
            throw new IllegalArgumentException("metrics must not be null");
        }
        this.streamKey = streamKey;
        this.session = session;
        this.secureListener = secureListener;
        this.geometryListener = geometryListener;
        this.arrivalTracker = arrivalTracker;
        this.metrics = metrics;
        this.audioSession = audioSession;
    }

    /**
     * Applies one server message to the session.
     *
     * <p>A descriptor is deserialized and applied before its access units; the secure flag is
     * reported on every change from the previous value, including the first descriptor. The stream
     * geometry is reported for every descriptor, before the session applies it, so the viewer can
     * resize its window before the new decoder produces frames; an epoch-only change repeats the
     * report, which the display side ignores when nothing moved. A descriptor cannot carry a
     * non-positive visible size (the model rejects it), so the geometry report is guarded purely
     * defensively. A unit from a stale or unknown epoch is handled by the session without changing
     * its state incorrectly.</p>
     */
    public void onServerMessage(ServerMessage message) {
        onServerMessage(message, UNKNOWN_ARRIVAL_NS);
    }

    /**
     * Applies one server message and records its arrival time.
     *
     * <p>The arrival timestamp is the moment the gRPC callback saw the message; it is what turns a
     * sequence of access units into an arrival cadence and a relative one-way delay. A message
     * whose arrival was not measured is applied exactly as before and contributes no sample.</p>
     *
     * @param message   server message to apply; may be null
     * @param arrivalNs local monotonic arrival time in nanoseconds, or an unmeasured marker
     */
    public void onServerMessage(ServerMessage message, long arrivalNs) {
        if (message == null || session.isClosed()) {
            return;
        }
        switch (message.getPayloadCase()) {
            case DESCRIPTOR: {
                final com.obby.android.localscreenshare.grpc.screenstream.Descriptor descriptor =
                        message.getDescriptor();
                onDescriptor(ScreenStreamProtoMapper.toStreamDescriptor(streamKey, descriptor),
                        descriptor.getSecure());
                break;
            }
            case ACCESS_UNIT: {
                onAccessUnit(ScreenStreamProtoMapper.toAccessUnit(streamKey,
                        message.getAccessUnit()), arrivalNs);
                break;
            }
            case AUDIO_DESCRIPTOR: {
                applyAudioMessage(() -> audioSession.onDescriptor(
                        ScreenStreamProtoMapper.toAudioDescriptor(message.getAudioDescriptor())));
                break;
            }
            case AUDIO_ACCESS_UNIT: {
                applyAudioMessage(() -> audioSession.onAccessUnit(
                        ScreenStreamProtoMapper.toAudioAccessUnit(message.getAudioAccessUnit())));
                break;
            }
            case PAYLOAD_NOT_SET:
            default:
                break;
        }
    }

    /**
     * Applies one descriptor that a media channel already decoded.
     *
     * <p>This is the entry point a channel other than the gRPC stream uses: it owns the same
     * side effects as the descriptor case of {@link #onServerMessage}, in the same order - the
     * secure flag is reported only on a change, the geometry is reported for every descriptor
     * before the session applies it, and one session drives both paths. A null descriptor or a
     * closed session is ignored.</p>
     *
     * @param descriptor descriptor of one config epoch; may be null
     * @param secure     whether the peer reported this stream as secure
     */
    public void onDescriptor(StreamDescriptor descriptor, boolean secure) {
        if (descriptor == null || session.isClosed()) {
            return;
        }
        if (lastSecure == null || lastSecure != secure) {
            lastSecure = secure;
            if (secureListener != null) {
                secureListener.onSecureChanged(secure);
            }
        }
        if (geometryListener != null && descriptor.getVisibleWidth() > 0
                && descriptor.getVisibleHeight() > 0) {
            geometryListener.onStreamGeometry(descriptor.getVisibleWidth(),
                    descriptor.getVisibleHeight(), descriptor.getRotationDegrees());
        }
        session.onDescriptor(descriptor);
    }

    /**
     * Applies one access unit that a media channel already decoded.
     *
     * <p>This is the entry point a channel other than the gRPC stream uses, and it carries the
     * same single write of the receive counters and the same arrival sample as the access-unit case
     * of {@link #onServerMessage}: a channel never reimplements the metrics, so a frame is counted
     * once no matter which path delivered it. A null unit or a closed session is ignored.</p>
     *
     * @param accessUnit access unit in receive order; may be null
     * @param arrivalNs  local monotonic arrival time in nanoseconds, or
     *                   {@link #UNKNOWN_ARRIVAL_NS} when the arrival was not measured
     */
    public void onAccessUnit(EncodedAccessUnit accessUnit, long arrivalNs) {
        if (accessUnit == null || session.isClosed()) {
            return;
        }
        // One write per received unit: the frame-rate and bitrate the viewer reports are
        // counts of what actually arrived, not of what the server believed it sent.
        metrics.increment(PipelineMetrics.Counter.ACCESS_UNITS);
        metrics.add(PipelineMetrics.Counter.ACCESS_UNIT_BYTES, accessUnit.getPayloadLength());
        if (arrivalTracker != null && arrivalNs != UNKNOWN_ARRIVAL_NS) {
            arrivalTracker.feed(accessUnit.getConfigEpoch(), accessUnit.getSequence(),
                    accessUnit.getPtsUs(), arrivalNs);
        }
        session.onAccessUnit(accessUnit);
    }

    /**
     * Applies one audio message without letting its failure reach the video path.
     *
     * <p>A malformed payload, an unsupported codec or a failing decoder is exactly the situation the
     * audio track is designed to absorb: the message is dropped and the next video frame is still
     * decoded normally.</p>
     */
    private void applyAudioMessage(Runnable action) {
        if (audioSession == null) {
            return;
        }
        try {
            action.run();
        } catch (RuntimeException ignored) {
            // Audio is optional; the video stream must keep decoding.
        }
    }

    /**
     * Returns the stream identity this receiver rebuilds descriptors and access units for.
     */
    public StreamKey getStreamKey() {
        return streamKey;
    }

    /**
     * Returns the session driven by this receiver.
     */
    public ClientMediaSession getSession() {
        return session;
    }

    /**
     * Closes the audio and video sessions; idempotent.
     */
    public void close() {
        if (audioSession != null) {
            try {
                audioSession.close();
            } catch (RuntimeException ignored) {
                // A failing audio close must not keep the video session open.
            }
        }
        session.close();
    }
}
