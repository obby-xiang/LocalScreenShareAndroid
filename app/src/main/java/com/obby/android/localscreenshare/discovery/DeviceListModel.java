package com.obby.android.localscreenshare.discovery;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.obby.android.localscreenshare.discovery.model.DeviceAddress;
import com.obby.android.localscreenshare.discovery.model.DevicePresence;
import com.obby.android.localscreenshare.discovery.model.DeviceRole;
import com.obby.android.localscreenshare.discovery.model.JoinPolicy;
import com.obby.android.localscreenshare.discovery.model.LssDeviceInfo;
import com.obby.android.localscreenshare.discovery.model.ShareState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Projects the device store snapshot onto the rows the discovery list renders.
 *
 * <p>The list is split into shared screens (devices that can serve a stream) and viewers
 * (client-only devices), with a header row in front of each non-empty group. Rows carry the
 * immutable {@link LssDeviceInfo}, so the adapter diffs values instead of re-querying the store.</p>
 *
 * <p><b>Ordering.</b> Inside a group, a confirmed device comes first, then a favorite, then the
 * device with an IPv4 primary address, then the most recently confirmed one; name and id make the
 * order total. The store snapshot is already ordered, but the model re-sorts because the favorite
 * and IPv4 rules are display rules, not store rules.</p>
 *
 * <p><b>Empty state.</b> An empty snapshot yields a single {@link RowType#EMPTY_HINT} row, and
 * {@link #emptyState(boolean, boolean)} tells the caller which placeholder text applies. The
 * three cases are deliberate: discovery switched off, still searching inside the manual-entry
 * window, and searched long enough that the manual entry is offered.</p>
 *
 * <p>The class is pure JVM: no Android types, no store access, so the sectioning and sorting rules
 * are covered by deterministic unit tests.</p>
 */
public final class DeviceListModel {

    /** Type of one list row. */
    public enum RowType {
        HEADER_SERVERS,
        HEADER_CLIENTS,
        DEVICE,
        EMPTY_HINT
    }

    /** Placeholder shown while the list is empty. */
    public enum EmptyState {
        DISABLED,
        SEARCHING,
        NOT_FOUND
    }

    /**
     * State of one device as the list shows it.
     *
     * <p>The order is the priority used when several flags apply: an unreachable device is
     * reported as unreachable even if it last advertised itself as live; a device that requires
     * approval is reported as such before its live state; a closed device is closed.</p>
     */
    public enum DeviceStatus {
        UNREACHABLE,
        CLOSED,
        NEEDS_APPROVAL,
        LIVE,
        IDLE
    }

    /** One row of the discovery list. */
    public static final class Row {

        @NonNull
        private final RowType mType;

        @Nullable
        private final LssDeviceInfo mDevice;

        private Row(@NonNull final RowType type, @Nullable final LssDeviceInfo device) {
            mType = type;
            mDevice = device;
        }

        @NonNull
        public RowType getType() {
            return mType;
        }

        /** Returns the device of a {@link RowType#DEVICE} row, or null for every other type. */
        @Nullable
        public LssDeviceInfo getDevice() {
            return mDevice;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Row)) {
                return false;
            }
            final Row that = (Row) o;
            return mType == that.mType && Objects.equals(mDevice, that.mDevice);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mType, mDevice);
        }

        @Override
        public String toString() {
            return "Row{type=" + mType + ", device=" + (mDevice == null ? null : mDevice.getId()) + '}';
        }
    }

    private static final Comparator<LssDeviceInfo> DEVICE_COMPARATOR =
        Comparator.comparing((LssDeviceInfo device) -> !device.isConfirmed())
            .thenComparing(device -> !device.isFavorite())
            .thenComparing(device -> !hasIpv4Address(device))
            .thenComparing(Comparator.comparingLong(LssDeviceInfo::getLastConfirmedMs).reversed())
            .thenComparing(LssDeviceInfo::displayName)
            .thenComparing(LssDeviceInfo::getId);

    private DeviceListModel() {
    }

    /**
     * Builds the rows of the discovery list.
     *
     * @param devices immutable store snapshot, possibly empty
     * @return headers followed by their devices, or a single empty-hint row
     */
    @NonNull
    public static List<Row> rows(@NonNull final List<LssDeviceInfo> devices) {
        final List<LssDeviceInfo> servers = new ArrayList<>();
        final List<LssDeviceInfo> clients = new ArrayList<>();
        for (final LssDeviceInfo device : devices) {
            if (device.getRole() == DeviceRole.CLIENT) {
                clients.add(device);
            } else {
                servers.add(device);
            }
        }
        Collections.sort(servers, DEVICE_COMPARATOR);
        Collections.sort(clients, DEVICE_COMPARATOR);

        final List<Row> rows = new ArrayList<>(devices.size() + 2);
        if (!servers.isEmpty()) {
            rows.add(new Row(RowType.HEADER_SERVERS, null));
            for (final LssDeviceInfo device : servers) {
                rows.add(new Row(RowType.DEVICE, device));
            }
        }
        if (!clients.isEmpty()) {
            rows.add(new Row(RowType.HEADER_CLIENTS, null));
            for (final LssDeviceInfo device : clients) {
                rows.add(new Row(RowType.DEVICE, device));
            }
        }
        if (rows.isEmpty()) {
            rows.add(new Row(RowType.EMPTY_HINT, null));
        }
        return Collections.unmodifiableList(rows);
    }

    /**
     * Returns the placeholder state of an empty list.
     *
     * @param discoveryEnabled whether the discovery toggle is on
     * @param manualHintVisible whether the empty-list window elapsed
     */
    @NonNull
    public static EmptyState emptyState(final boolean discoveryEnabled, final boolean manualHintVisible) {
        if (!discoveryEnabled) {
            return EmptyState.DISABLED;
        }
        return manualHintVisible ? EmptyState.NOT_FOUND : EmptyState.SEARCHING;
    }

    /** Returns the status displayed next to one device. */
    @NonNull
    public static DeviceStatus status(@NonNull final LssDeviceInfo device) {
        if (device.getPresence() == DevicePresence.UNREACHABLE) {
            return DeviceStatus.UNREACHABLE;
        }
        if (device.getJoin() == JoinPolicy.CLOSED) {
            return DeviceStatus.CLOSED;
        }
        if (device.getJoin() == JoinPolicy.APPROVAL) {
            return DeviceStatus.NEEDS_APPROVAL;
        }
        return device.getState() == ShareState.LIVE ? DeviceStatus.LIVE : DeviceStatus.IDLE;
    }

    /** Returns whether the row should be drawn dimmed, which marks a stale or unreachable device. */
    public static boolean isDimmed(@NonNull final LssDeviceInfo device) {
        return device.getPresence() == DevicePresence.STALE
            || device.getPresence() == DevicePresence.UNREACHABLE;
    }

    /** Returns the address text of one device, or an empty string when no address is known. */
    @NonNull
    public static String addressText(@NonNull final LssDeviceInfo device) {
        final DeviceAddress address = device.primaryAddress();
        return address == null ? "" : address.getHost() + ":" + address.getPort();
    }

    /** Returns whether the primary address is IPv4. */
    public static boolean hasIpv4Address(@NonNull final LssDeviceInfo device) {
        final DeviceAddress address = device.primaryAddress();
        return address != null && address.getFamily() == DeviceAddress.FAMILY_IPV4;
    }
}
