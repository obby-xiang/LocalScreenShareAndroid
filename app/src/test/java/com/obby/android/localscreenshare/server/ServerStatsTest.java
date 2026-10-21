package com.obby.android.localscreenshare.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Unit tests for {@link ServerStats} and its {@link ServerStats.TransportStats} row.
 *
 * <p><b>Parcel coverage.</b> A real {@code Parcel} round trip needs an Android runtime: JVM unit
 * tests run against the mockable {@code android.jar}, where {@code Parcel.obtain()} throws. The
 * reachable substitute is the field-order guard below, which fails when a field is added or
 * reordered without revisiting {@code writeToParcel} and the reading constructor. The round trip
 * itself stays a device-verification item.</p>
 */
public class ServerStatsTest {

    @Test
    public void builder_exposesEveryField() {
        ServerStats.TransportStats row = ServerStats.TransportStats.builder()
                .remoteAddress("10.0.0.2:51234")
                .outboundDataSize(1_024L)
                .outboundDataRate(512L)
                .clientId("client-A")
                .clientState("STREAMING")
                .queuedUnits(3L)
                .queuedBytes(900L)
                .droppedUnits(1L)
                .resyncCount(2L)
                .sentUnits(40L)
                .sendLatencyP95Ms(7L)
                .build();

        assertEquals("10.0.0.2:51234", row.getRemoteAddress());
        assertEquals(1_024L, row.getOutboundDataSize());
        assertEquals(512L, row.getOutboundDataRate());
        assertEquals("client-A", row.getClientId());
        assertEquals("STREAMING", row.getClientState());
        assertEquals(3L, row.getQueuedUnits());
        assertEquals(900L, row.getQueuedBytes());
        assertEquals(1L, row.getDroppedUnits());
        assertEquals(2L, row.getResyncCount());
        assertEquals(40L, row.getSentUnits());
        assertEquals(7L, row.getSendLatencyP95Ms());
    }

    @Test
    public void trafficOnlyRow_hasNoClientBlock() {
        ServerStats.TransportStats row = ServerStats.TransportStats.builder()
                .remoteAddress("10.0.0.9:5000")
                .outboundDataSize(10L)
                .outboundDataRate(10L)
                .build();

        assertNull(row.getClientId());
        assertNull(row.getClientState());
        assertEquals(0L, row.getQueuedUnits());
        assertEquals(0L, row.getSendLatencyP95Ms());
    }

    @Test
    public void equals_includesTheClientBlock() {
        ServerStats.TransportStats base = ServerStats.TransportStats.builder()
                .remoteAddress("10.0.0.2:51234")
                .outboundDataSize(10L)
                .outboundDataRate(10L)
                .clientId("client-A")
                .queuedUnits(1L)
                .build();
        ServerStats.TransportStats same = base.toBuilder().build();
        ServerStats.TransportStats changed = base.toBuilder().queuedUnits(2L).build();

        assertEquals(base, same);
        assertNotEquals(base, changed);
    }

    @Test
    public void comparator_ordersByAddressThenClientId() {
        List<ServerStats.TransportStats> rows = new ArrayList<>(Arrays.asList(
                row("10.0.0.3:1", "client-A"),
                row("10.0.0.2:1", "client-B"),
                row("10.0.0.2:1", "client-A")));

        Collections.sort(rows);

        assertEquals("10.0.0.2:1/client-A", rows.get(0).getRemoteAddress() + "/" + rows.get(0).getClientId());
        assertEquals("10.0.0.2:1/client-B", rows.get(1).getRemoteAddress() + "/" + rows.get(1).getClientId());
        assertEquals("10.0.0.3:1/client-A", rows.get(2).getRemoteAddress() + "/" + rows.get(2).getClientId());
    }

    @Test
    public void transportStatsFields_matchTheParcelOrder() {
        assertEquals(Arrays.asList("mRemoteAddress", "mOutboundDataSize", "mOutboundDataRate",
                        "mClientId", "mClientState", "mQueuedUnits", "mQueuedBytes", "mDroppedUnits",
                        "mResyncCount", "mSentUnits", "mSendLatencyP95Ms"),
                instanceFieldNames(ServerStats.TransportStats.class));
    }

    @Test
    public void statsFields_matchTheParcelOrder() {
        assertEquals(Arrays.asList("mOutboundDataSize", "mOutboundDataRate", "mTransports"),
                instanceFieldNames(ServerStats.class));
    }

    private static ServerStats.TransportStats row(String address, String clientId) {
        return ServerStats.TransportStats.builder()
                .remoteAddress(address)
                .outboundDataSize(0L)
                .outboundDataRate(0L)
                .clientId(clientId)
                .build();
    }

    private static List<String> instanceFieldNames(Class<?> type) {
        List<String> names = new ArrayList<>();
        for (Field field : type.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                continue;
            }
            names.add(field.getName());
        }
        return names;
    }
}
