package com.obby.android.localscreenshare.client;

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
 * measurable at all. It is deliberately not thread-safe: the
 * owning client must call {@link #onServerMessage} from a single decode thread so decoding never
 * runs on the gRPC shared executor. The session itself serializes its own state, so the receiver
 * only has to serialize the deserialization plus the secure-flag edge tracking.</p>
 */
public final class ClientMediaReceiver {

    /**
     * Arrival marker for a message whose arrival time was not measured.
     */
    private static final long UNKNOWN_ARRIVAL_NS = Long.MIN_VALUE;

    private final StreamKey streamKey;

    private final ClientMediaSession session;

    private final ClientSecureListener secureListener;

    private final StreamGeometryListener geometryListener;

    private final ArrivalTracker arrivalTracker;

    private final PipelineMetrics metrics;

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
                com.obby.android.localscreenshare.grpc.screenstream.Descriptor descriptor =
                        message.getDescriptor();
                boolean secure = descriptor.getSecure();
                if (lastSecure == null || lastSecure != secure) {
                    lastSecure = secure;
                    if (secureListener != null) {
                        secureListener.onSecureChanged(secure);
                    }
                }
                final StreamDescriptor streamDescriptor =
                        ScreenStreamProtoMapper.toStreamDescriptor(streamKey, descriptor);
                if (geometryListener != null && streamDescriptor.getVisibleWidth() > 0
                        && streamDescriptor.getVisibleHeight() > 0) {
                    geometryListener.onStreamGeometry(streamDescriptor.getVisibleWidth(),
                            streamDescriptor.getVisibleHeight(),
                            streamDescriptor.getRotationDegrees());
                }
                session.onDescriptor(streamDescriptor);
                break;
            }
            case ACCESS_UNIT: {
                final EncodedAccessUnit accessUnit = ScreenStreamProtoMapper.toAccessUnit(streamKey,
                        message.getAccessUnit());
                // One write per received unit: the frame-rate and bitrate the viewer reports are
                // counts of what actually arrived, not of what the server believed it sent.
                metrics.increment(PipelineMetrics.Counter.ACCESS_UNITS);
                metrics.add(PipelineMetrics.Counter.ACCESS_UNIT_BYTES,
                        accessUnit.getPayloadLength());
                if (arrivalTracker != null && arrivalNs != UNKNOWN_ARRIVAL_NS) {
                    arrivalTracker.feed(accessUnit.getConfigEpoch(), accessUnit.getSequence(),
                            accessUnit.getPtsUs(), arrivalNs);
                }
                session.onAccessUnit(accessUnit);
                break;
            }
            case PAYLOAD_NOT_SET:
            default:
                break;
        }
    }

    /**
     * Returns the session driven by this receiver.
     */
    public ClientMediaSession getSession() {
        return session;
    }

    /**
     * Closes the underlying session; idempotent.
     */
    public void close() {
        session.close();
    }
}
