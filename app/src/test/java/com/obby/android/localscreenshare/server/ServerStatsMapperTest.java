package com.obby.android.localscreenshare.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.obby.android.localscreenshare.core.model.ClientSessionKey;
import com.obby.android.localscreenshare.core.model.ClientState;
import com.obby.android.localscreenshare.core.model.StreamKey;
import com.obby.android.localscreenshare.core.stream.ClientSendStats;
import com.obby.android.localscreenshare.core.stream.SessionSnapshot;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Unit tests for {@link ServerStatsMapper}.
 *
 * <p>The mapper is a pure function, so every aggregation rule - address association, byte
 * attribution, ordering and rate derivation - is asserted here instead of on a device.</p>
 */
public class ServerStatsMapperTest {

    private static final StreamKey STREAM_KEY = new StreamKey("session-1", "stream-1");

    private static final String ADDRESS_A = "10.0.0.2:51234";

    private static final String ADDRESS_B = "10.0.0.3:51235";

    @Test
    public void emptyInputs_produceEmptyStatistics() {
        ServerStats stats = ServerStatsMapper.map(Collections.emptyList(),
                Collections.emptyList(), Collections.emptyMap(), 1000L);

        assertEquals(0L, stats.getOutboundDataSize());
        assertEquals(0L, stats.getOutboundDataRate());
        assertTrue(stats.getTransports().isEmpty());
    }

    @Test
    public void singleSession_attachesItsCountersToTheAddressRow() {
        ServerStats stats = ServerStatsMapper.map(
                Collections.singletonList(new ServerStatsMapper.AddressTraffic(ADDRESS_A, 500_000L)),
                Collections.singletonList(snapshot("client-A", 2L, 400L, 1L, 2L, 30L, 8_500_000L)),
                Collections.singletonMap("client-A", ADDRESS_A), 1000L);

        assertEquals(1, stats.getTransports().size());
        ServerStats.TransportStats row = stats.getTransports().get(0);
        assertEquals(ADDRESS_A, row.getRemoteAddress());
        assertEquals(500_000L, row.getOutboundDataSize());
        assertEquals(500_000L, row.getOutboundDataRate());
        assertEquals("client-A", row.getClientId());
        assertEquals(ClientState.STREAMING.name(), row.getClientState());
        assertEquals(2L, row.getQueuedUnits());
        assertEquals(400L, row.getQueuedBytes());
        assertEquals(1L, row.getDroppedUnits());
        assertEquals(2L, row.getResyncCount());
        assertEquals(30L, row.getSentUnits());
        assertEquals(9L, row.getSendLatencyP95Ms());
        assertEquals(500_000L, stats.getOutboundDataSize());
        assertEquals(500_000L, stats.getOutboundDataRate());
    }

    @Test
    public void addressWithoutSession_keepsATrafficOnlyRow() {
        ServerStats stats = ServerStatsMapper.map(
                Collections.singletonList(new ServerStatsMapper.AddressTraffic(ADDRESS_A, 2_000L)),
                Collections.emptyList(), Collections.emptyMap(), 2000L);

        assertEquals(1, stats.getTransports().size());
        assertNull(stats.getTransports().get(0).getClientId());
        assertEquals(1_000L, stats.getTransports().get(0).getOutboundDataRate());
    }

    @Test
    public void sessionWithoutAddress_getsAnUnknownRowWithoutBytes() {
        ServerStats stats = ServerStatsMapper.map(
                Collections.emptyList(),
                Collections.singletonList(snapshot("client-A", 0L, 0L, 0L, 0L, 3L, 0L)),
                Collections.emptyMap(), 1000L);

        assertEquals(1, stats.getTransports().size());
        ServerStats.TransportStats row = stats.getTransports().get(0);
        assertEquals(ServerStatsMapper.UNKNOWN_REMOTE_ADDRESS, row.getRemoteAddress());
        assertEquals("client-A", row.getClientId());
        assertEquals(0L, row.getOutboundDataSize());
        assertEquals(0L, row.getOutboundDataRate());
    }

    @Test
    public void twoSessionsOnOneAddress_keepBytesOnTheFirstRowOnly() {
        List<SessionSnapshot> sessions = Arrays.asList(
                snapshot("client-A", 1L, 100L, 0L, 0L, 10L, 0L),
                snapshot("client-B", 2L, 200L, 1L, 1L, 20L, 0L));

        ServerStats stats = ServerStatsMapper.map(
                Collections.singletonList(new ServerStatsMapper.AddressTraffic(ADDRESS_A, 9_000L)),
                sessions, Collections.singletonMap("client-A", ADDRESS_A), 1000L);

        assertEquals(2, stats.getTransports().size());
        assertEquals("client-A", stats.getTransports().get(0).getClientId());
        assertEquals(9_000L, stats.getTransports().get(0).getOutboundDataSize());
        assertEquals("client-B", stats.getTransports().get(1).getClientId());
        assertEquals("the second session must not claim a share of the address bytes", 0L,
                stats.getTransports().get(1).getOutboundDataSize());
        assertEquals(9_000L, stats.getOutboundDataSize());
    }

    @Test
    public void rows_areSortedByAddressThenClientId() {
        List<SessionSnapshot> sessions = Arrays.asList(
                snapshot("client-C", 0L, 0L, 0L, 0L, 1L, 0L),
                snapshot("client-A", 0L, 0L, 0L, 0L, 1L, 0L),
                snapshot("client-B", 0L, 0L, 0L, 0L, 1L, 0L));
        Map<String, String> addresses = new HashMap<>();
        addresses.put("client-A", ADDRESS_B);
        addresses.put("client-B", ADDRESS_A);
        addresses.put("client-C", ADDRESS_A);

        ServerStats stats = ServerStatsMapper.map(
                Arrays.asList(new ServerStatsMapper.AddressTraffic(ADDRESS_B, 1L),
                        new ServerStatsMapper.AddressTraffic(ADDRESS_A, 2L)),
                sessions, addresses, 1000L);

        List<String> order = new ArrayList<>();
        for (ServerStats.TransportStats row : stats.getTransports()) {
            order.add(row.getRemoteAddress() + "/" + row.getClientId());
        }
        assertEquals(Arrays.asList(ADDRESS_A + "/client-B", ADDRESS_A + "/client-C",
                ADDRESS_B + "/client-A"), order);
    }

    @Test
    public void nonPositivePeriod_yieldsZeroRates() {
        ServerStats stats = ServerStatsMapper.map(
                Collections.singletonList(new ServerStatsMapper.AddressTraffic(ADDRESS_A, 4_000L)),
                Collections.emptyList(), Collections.emptyMap(), 0L);

        assertEquals(0L, stats.getTransports().get(0).getOutboundDataRate());
        assertEquals(0L, stats.getOutboundDataRate());
        assertEquals(4_000L, stats.getOutboundDataSize());
    }

    @Test
    public void nullArguments_areRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> ServerStatsMapper.map(null, Collections.emptyList(),
                        Collections.emptyMap(), 1000L));
        assertThrows(IllegalArgumentException.class,
                () -> ServerStatsMapper.map(Collections.emptyList(), null,
                        Collections.emptyMap(), 1000L));
        assertThrows(IllegalArgumentException.class,
                () -> ServerStatsMapper.map(Collections.emptyList(), Collections.emptyList(),
                        null, 1000L));
        assertThrows(IllegalArgumentException.class,
                () -> new ServerStatsMapper.AddressTraffic(null, 0L));
    }

    private static SessionSnapshot snapshot(String clientId, long queuedUnits, long queuedBytes,
                                            long droppedUnits, long resyncCount, long sentUnits,
                                            long sendP95Ns) {
        return new SessionSnapshot(new ClientSessionKey(STREAM_KEY, clientId),
                ClientState.STREAMING,
                new ClientSendStats(queuedUnits, queuedBytes, droppedUnits, resyncCount, sentUnits,
                        1L),
                sendP95Ns);
    }
}
