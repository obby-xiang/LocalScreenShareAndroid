package com.obby.android.localscreenshare.server;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.obby.android.localscreenshare.core.stream.SessionSnapshot;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Maps per-address traffic and per-session snapshots onto one {@link ServerStats} value.
 *
 * <p><b>Why a separate class.</b> The server collects statistics on the main thread and the
 * result crosses a {@code Parcel} boundary, so neither can be exercised on the JVM. This mapper is
 * a pure function of its inputs: it touches no clock, no Android type and no socket, which is what
 * makes the aggregation rules testable.</p>
 *
 * <p><b>Association.</b> Traffic is counted per remote address by the gRPC transport tracer, while
 * counters live per send session. The caller supplies the address of every attached session; a
 * session whose address is unknown, or whose address already carries another session, still gets
 * its own row, but the payload bytes of an address stay on the first row of that address. Bytes are
 * never divided between sessions, because the transport counter cannot attribute them.</p>
 *
 * <p><b>Rates.</b> Every row derives bytes per second from the interval it was collected over. A
 * non-positive interval yields a zero rate instead of a division by zero.</p>
 */
public final class ServerStatsMapper {

    /**
     * Address placeholder for a session whose remote address could not be determined.
     */
    public static final String UNKNOWN_REMOTE_ADDRESS = "unknown";

    private ServerStatsMapper() {
    }

    /**
     * Outbound traffic of one remote address over one collection interval.
     */
    public static final class AddressTraffic {

        private final String remoteAddress;

        private final long outboundDataSize;

        /**
         * Creates one address row.
         *
         * @param remoteAddress   address in socket form; required
         * @param outboundDataSize wire bytes over the interval; non-negative
         * @throws IllegalArgumentException if {@code remoteAddress} is null
         */
        public AddressTraffic(String remoteAddress, long outboundDataSize) {
            if (remoteAddress == null) {
                throw new IllegalArgumentException("remoteAddress must not be null");
            }
            this.remoteAddress = remoteAddress;
            this.outboundDataSize = Math.max(0L, outboundDataSize);
        }

        /**
         * Returns the address in socket form.
         */
        public String getRemoteAddress() {
            return remoteAddress;
        }

        /**
         * Returns the wire bytes over the interval.
         */
        public long getOutboundDataSize() {
            return outboundDataSize;
        }
    }

    /**
     * Builds one statistics snapshot.
     *
     * @param traffic            per-address traffic over the interval; required
     * @param sessions           attached send sessions, already ordered by client id; required
     * @param addressByClientId  remote address of each attached session keyed by client id;
     *                           required, entries may be missing
     * @param periodMs           interval the counters accumulated over; non-positive yields zero
     *                           rates
     * @return the statistics snapshot, never null
     * @throws IllegalArgumentException if an argument is null
     */
    @NonNull
    public static ServerStats map(@NonNull List<AddressTraffic> traffic,
                                  @NonNull List<SessionSnapshot> sessions,
                                  @NonNull Map<String, String> addressByClientId,
                                  long periodMs) {
        if (traffic == null) {
            throw new IllegalArgumentException("traffic must not be null");
        }
        if (sessions == null) {
            throw new IllegalArgumentException("sessions must not be null");
        }
        if (addressByClientId == null) {
            throw new IllegalArgumentException("addressByClientId must not be null");
        }

        final List<ServerStats.TransportStats> rows = new ArrayList<>(traffic.size() + sessions.size());
        final Set<String> attachedClients = new HashSet<>();

        for (final AddressTraffic address : traffic) {
            final SessionSnapshot attached =
                firstSessionOnAddress(address.getRemoteAddress(), sessions, addressByClientId,
                    attachedClients);
            if (attached != null) {
                attachedClients.add(attached.getClientId());
            }
            rows.add(row(address.getRemoteAddress(), address.getOutboundDataSize(),
                rateOf(address.getOutboundDataSize(), periodMs), attached));
        }

        // Sessions whose address carries no traffic row, or whose address already carried another
        // session, get their own row without bytes instead of an invented share.
        for (final SessionSnapshot session : sessions) {
            if (attachedClients.contains(session.getClientId())) {
                continue;
            }
            final String address = addressByClientId.get(session.getClientId());
            rows.add(row(address != null ? address : UNKNOWN_REMOTE_ADDRESS, 0L, 0L, session));
        }

        rows.sort(null);

        long outboundDataSize = 0L;
        for (final ServerStats.TransportStats row : rows) {
            outboundDataSize += row.getOutboundDataSize();
        }
        return ServerStats.builder()
            .outboundDataSize(outboundDataSize)
            .outboundDataRate(rateOf(outboundDataSize, periodMs))
            .transports(rows)
            .build();
    }

    /**
     * Returns the first not-yet-attached session whose address equals {@code remoteAddress}, or
     * null when the address carries no session.
     */
    @Nullable
    private static SessionSnapshot firstSessionOnAddress(@NonNull String remoteAddress,
                                                         @NonNull List<SessionSnapshot> sessions,
                                                         @NonNull Map<String, String> addressByClientId,
                                                         @NonNull Set<String> attachedClients) {
        for (final SessionSnapshot session : sessions) {
            if (attachedClients.contains(session.getClientId())) {
                continue;
            }
            if (remoteAddress.equals(addressByClientId.get(session.getClientId()))) {
                return session;
            }
        }
        return null;
    }

    /**
     * Builds one transport row, with or without an attached session.
     */
    @NonNull
    private static ServerStats.TransportStats row(@NonNull String remoteAddress,
                                                  long outboundDataSize, long outboundDataRate,
                                                  @Nullable SessionSnapshot session) {
        final ServerStats.TransportStats.Builder builder = ServerStats.TransportStats.builder()
            .remoteAddress(remoteAddress)
            .outboundDataSize(outboundDataSize)
            .outboundDataRate(outboundDataRate);
        if (session != null) {
            builder.clientId(session.getClientId())
                .clientState(session.getState().name())
                .queuedUnits(session.getStats().getQueuedUnits())
                .queuedBytes(session.getStats().getQueuedBytes())
                .droppedUnits(session.getStats().getDroppedUnits())
                .resyncCount(session.getStats().getResyncCount())
                .sentUnits(session.getStats().getSentUnits())
                .sendLatencyP95Ms(Math.round(session.getSendP95Ns() / 1_000_000d));
        }
        return builder.build();
    }

    /**
     * Converts a byte count over {@code periodMs} to bytes per second.
     */
    private static long rateOf(long bytes, long periodMs) {
        if (periodMs <= 0L) {
            return 0L;
        }
        return Math.round(bytes * 1000d / periodMs);
    }
}
