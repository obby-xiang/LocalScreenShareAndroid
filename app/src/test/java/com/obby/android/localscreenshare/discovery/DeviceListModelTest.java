package com.obby.android.localscreenshare.discovery;

import com.obby.android.localscreenshare.discovery.model.DeviceAddress;
import com.obby.android.localscreenshare.discovery.model.DevicePresence;
import com.obby.android.localscreenshare.discovery.model.DeviceRole;
import com.obby.android.localscreenshare.discovery.model.DiscoveryChannel;
import com.obby.android.localscreenshare.discovery.model.JoinPolicy;
import com.obby.android.localscreenshare.discovery.model.LssDeviceInfo;
import com.obby.android.localscreenshare.discovery.model.ShareState;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Unit tests for {@link DeviceListModel}. */
public class DeviceListModelTest {

    private static final String SERVER_ID = "AAAAAAAAAAAAAAAAAAAAAAAAAA";

    private static final String CLIENT_ID = "BBBBBBBBBBBBBBBBBBBBBBBBBB";

    @Test
    public void emptySnapshotYieldsOneEmptyHintRow() {
        List<DeviceListModel.Row> rows = DeviceListModel.rows(Collections.emptyList());

        assertEquals(1, rows.size());
        assertEquals(DeviceListModel.RowType.EMPTY_HINT, rows.get(0).getType());
        assertNull(rows.get(0).getDevice());
    }

    @Test
    public void emptyStateDistinguishesDisabledSearchingAndNotFound() {
        assertEquals(DeviceListModel.EmptyState.DISABLED,
            DeviceListModel.emptyState(false, false));
        assertEquals(DeviceListModel.EmptyState.DISABLED,
            DeviceListModel.emptyState(false, true));
        assertEquals(DeviceListModel.EmptyState.SEARCHING,
            DeviceListModel.emptyState(true, false));
        assertEquals(DeviceListModel.EmptyState.NOT_FOUND,
            DeviceListModel.emptyState(true, true));
    }

    @Test
    public void serversAndClientsGetTheirOwnSections() {
        List<DeviceListModel.Row> rows = DeviceListModel.rows(Arrays.asList(
            device(SERVER_ID, DeviceRole.SERVER, DevicePresence.HEARD, false, ShareState.IDLE,
                JoinPolicy.OPEN, "10.0.0.5"),
            device(CLIENT_ID, DeviceRole.CLIENT, DevicePresence.HEARD, false, ShareState.IDLE,
                JoinPolicy.OPEN, "10.0.0.6")));

        assertEquals(4, rows.size());
        assertEquals(DeviceListModel.RowType.HEADER_SERVERS, rows.get(0).getType());
        assertEquals(DeviceListModel.RowType.DEVICE, rows.get(1).getType());
        assertEquals(SERVER_ID, rows.get(1).getDevice().getId());
        assertEquals(DeviceListModel.RowType.HEADER_CLIENTS, rows.get(2).getType());
        assertEquals(DeviceListModel.RowType.DEVICE, rows.get(3).getType());
        assertEquals(CLIENT_ID, rows.get(3).getDevice().getId());
    }

    @Test
    public void unknownRoleIsListedAsAServer() {
        List<DeviceListModel.Row> rows = DeviceListModel.rows(Collections.singletonList(
            device(SERVER_ID, DeviceRole.UNKNOWN, DevicePresence.HEARD, false, ShareState.UNKNOWN,
                JoinPolicy.UNKNOWN, "10.0.0.5")));

        assertEquals(DeviceListModel.RowType.HEADER_SERVERS, rows.get(0).getType());
    }

    @Test
    public void confirmedDeviceComesBeforeAHeardOne() {
        List<DeviceListModel.Row> rows = DeviceListModel.rows(Arrays.asList(
            device(SERVER_ID, DeviceRole.SERVER, DevicePresence.HEARD, true, ShareState.IDLE,
                JoinPolicy.OPEN, "10.0.0.5"),
            device(CLIENT_ID, DeviceRole.SERVER, DevicePresence.CONFIRMED, false, ShareState.IDLE,
                JoinPolicy.OPEN, "10.0.0.6")));

        assertEquals(CLIENT_ID, rows.get(1).getDevice().getId());
        assertEquals(SERVER_ID, rows.get(2).getDevice().getId());
    }

    @Test
    public void favoriteComesBeforeANonFavoriteAtTheSamePresence() {
        List<DeviceListModel.Row> rows = DeviceListModel.rows(Arrays.asList(
            device(SERVER_ID, DeviceRole.SERVER, DevicePresence.HEARD, false, ShareState.IDLE,
                JoinPolicy.OPEN, "10.0.0.5"),
            device(CLIENT_ID, DeviceRole.SERVER, DevicePresence.HEARD, true, ShareState.IDLE,
                JoinPolicy.OPEN, "10.0.0.6")));

        assertEquals(CLIENT_ID, rows.get(1).getDevice().getId());
    }

    @Test
    public void ipv4PrimaryAddressComesBeforeIpv6() {
        List<DeviceListModel.Row> rows = DeviceListModel.rows(Arrays.asList(
            device(SERVER_ID, DeviceRole.SERVER, DevicePresence.HEARD, false, ShareState.IDLE,
                JoinPolicy.OPEN, "[fe80::1]"),
            device(CLIENT_ID, DeviceRole.SERVER, DevicePresence.HEARD, false, ShareState.IDLE,
                JoinPolicy.OPEN, "10.0.0.6")));

        assertEquals(CLIENT_ID, rows.get(1).getDevice().getId());
    }

    @Test
    public void statusReflectsPresenceJoinAndState() {
        assertEquals(DeviceListModel.DeviceStatus.LIVE, DeviceListModel.status(
            device(SERVER_ID, DeviceRole.SERVER, DevicePresence.CONFIRMED, false, ShareState.LIVE,
                JoinPolicy.OPEN, "10.0.0.5")));
        assertEquals(DeviceListModel.DeviceStatus.IDLE, DeviceListModel.status(
            device(SERVER_ID, DeviceRole.SERVER, DevicePresence.HEARD, false, ShareState.IDLE,
                JoinPolicy.OPEN, "10.0.0.5")));
        assertEquals(DeviceListModel.DeviceStatus.NEEDS_APPROVAL, DeviceListModel.status(
            device(SERVER_ID, DeviceRole.SERVER, DevicePresence.HEARD, false, ShareState.LIVE,
                JoinPolicy.APPROVAL, "10.0.0.5")));
        assertEquals(DeviceListModel.DeviceStatus.CLOSED, DeviceListModel.status(
            device(SERVER_ID, DeviceRole.SERVER, DevicePresence.HEARD, false, ShareState.LIVE,
                JoinPolicy.CLOSED, "10.0.0.5")));
    }

    @Test
    public void unreachablePresenceWinsOverJoinAndState() {
        assertEquals(DeviceListModel.DeviceStatus.UNREACHABLE, DeviceListModel.status(
            device(SERVER_ID, DeviceRole.SERVER, DevicePresence.UNREACHABLE, true, ShareState.LIVE,
                JoinPolicy.CLOSED, "10.0.0.5")));
    }

    @Test
    public void onlyStaleAndUnreachableRowsAreDimmed() {
        assertTrue(DeviceListModel.isDimmed(
            device(SERVER_ID, DeviceRole.SERVER, DevicePresence.STALE, false, ShareState.IDLE,
                JoinPolicy.OPEN, "10.0.0.5")));
        assertTrue(DeviceListModel.isDimmed(
            device(SERVER_ID, DeviceRole.SERVER, DevicePresence.UNREACHABLE, false, ShareState.IDLE,
                JoinPolicy.OPEN, "10.0.0.5")));
        assertFalse(DeviceListModel.isDimmed(
            device(SERVER_ID, DeviceRole.SERVER, DevicePresence.CONFIRMED, false, ShareState.IDLE,
                JoinPolicy.OPEN, "10.0.0.5")));
        assertFalse(DeviceListModel.isDimmed(
            device(SERVER_ID, DeviceRole.SERVER, DevicePresence.HEARD, false, ShareState.IDLE,
                JoinPolicy.OPEN, "10.0.0.5")));
    }

    @Test
    public void addressTextUsesThePrimaryAddressAndIsEmptyWithoutOne() {
        assertEquals("10.0.0.5:8080", DeviceListModel.addressText(
            device(SERVER_ID, DeviceRole.SERVER, DevicePresence.HEARD, false, ShareState.IDLE,
                JoinPolicy.OPEN, "10.0.0.5")));
        assertEquals("", DeviceListModel.addressText(LssDeviceInfo.builder().id(SERVER_ID).build()));
    }

    private static LssDeviceInfo device(String id, DeviceRole role, DevicePresence presence,
        boolean favorite, ShareState state, JoinPolicy join, String host) {
        return LssDeviceInfo.builder()
            .id(id)
            .role(role)
            .presence(presence)
            .favorite(favorite)
            .state(state)
            .join(join)
            .addresses(Collections.singletonList(
                new DeviceAddress(host, 8080, DiscoveryChannel.NSD)))
            .build();
    }
}
