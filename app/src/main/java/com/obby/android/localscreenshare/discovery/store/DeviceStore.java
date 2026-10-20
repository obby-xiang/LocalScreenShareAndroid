package com.obby.android.localscreenshare.discovery.store;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.obby.android.localscreenshare.core.util.TimeSource;
import com.obby.android.localscreenshare.discovery.legacy.LegacyServiceInfo;
import com.obby.android.localscreenshare.discovery.model.DeviceAddress;
import com.obby.android.localscreenshare.discovery.model.DevicePresence;
import com.obby.android.localscreenshare.discovery.model.DiscoveryChannel;
import com.obby.android.localscreenshare.discovery.model.DeviceInfo;
import com.obby.android.localscreenshare.discovery.provider.DeviceObservation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Single source of truth for the devices this process has observed.
 *
 * <p>Every discovery provider reports into this store; the store merges the observations of one
 * device into one entry, derives presence from the observation and confirmation timestamps, and
 * publishes an immutable snapshot whenever the result changes. It performs no I/O and holds no
 * Android dependency, so TTL, merging and eviction are covered by JVM tests with an injected
 * {@link TimeSource}.</p>
 *
 * <p><b>Reachability.</b> Observations only ever produce {@link DevicePresence#HEARD}. A device
 * becomes {@link DevicePresence#CONFIRMED} only through {@link #confirm(String, DiscoveryChannel,
 * String, int)}, which the register client calls after a successful application-level handshake.
 * An address confirmation expires after {@link #CONFIRMED_TTL_MS}.</p>
 *
 * <p><b>Time.</b> Entries expire {@link #REMOVE_AFTER_MS} after their last observation unless they
 * are favorites or pinned manual entries, which degrade to {@link DevicePresence#UNREACHABLE}
 * instead. A goodbye removes a non-favorite, non-pinned entry immediately. {@link #tick()} is meant
 * to be driven once per
 * {@link #TICK_INTERVAL_MS} by the main thread; there is one timer for the whole table, not one
 * per device.</p>
 *
 * <p><b>Threading.</b> All state is main-thread confined: providers marshal their callbacks, and
 * listeners are notified synchronously on the thread that mutated the store. There is no lock and
 * no background access. Snapshots are replaced, never mutated, so consumers can keep the list they
 * were handed.</p>
 */
public final class DeviceStore {

    /**
     * A heard device becomes stale after three active heartbeat periods.
     */
    public static final long HEARD_TTL_MS = 15_000L;

    /**
     * A confirmed address stays usable for three idle heartbeat periods.
     */
    public static final long CONFIRMED_TTL_MS = 45_000L;

    /**
     * Non-favorite entries are removed two confirmed windows after their last observation.
     */
    public static final long REMOVE_AFTER_MS = 90_000L;

    /**
     * Upper bound of retained devices; oldest stale entries are evicted first.
     */
    public static final int CAPACITY = 256;

    /**
     * Interval of the store-wide main-thread tick.
     */
    public static final long TICK_INTERVAL_MS = 1_000L;

    private static final Comparator<DeviceInfo> SNAPSHOT_COMPARATOR =
        Comparator.comparingInt((DeviceInfo device) -> presenceRank(device.getPresence()))
            .thenComparing(device -> !device.isFavorite())
            .thenComparing(Comparator.comparingLong(DeviceInfo::getLastConfirmedMs).reversed())
            .thenComparing(DeviceInfo::displayName)
            .thenComparing(DeviceInfo::getId);

    @NonNull
    private final TimeSource mTimeSource;

    // Insertion order keeps eviction deterministic when timestamps tie.
    @NonNull
    private final Map<String, DeviceEntry> mEntries = new LinkedHashMap<>();

    // Copy-on-write so a listener can remove itself while being notified.
    @NonNull
    private final List<DeviceStoreListener> mListeners = new CopyOnWriteArrayList<>();

    @NonNull
    private List<DeviceInfo> mSnapshot = Collections.emptyList();

    public DeviceStore(@NonNull final TimeSource timeSource) {
        mTimeSource = Objects.requireNonNull(timeSource, "timeSource");
    }

    /**
     * Subscribes a listener; a duplicate subscription is ignored.
     */
    public void addListener(@NonNull final DeviceStoreListener listener) {
        if (!mListeners.contains(listener)) {
            mListeners.add(listener);
        }
    }

    /**
     * Unsubscribes a listener; an unknown listener is ignored.
     */
    public void removeListener(@NonNull final DeviceStoreListener listener) {
        mListeners.remove(listener);
    }

    /**
     * Merges one provider observation.
     *
     * <p>Observations without an id are dropped, out-of-order announcements are dropped, a goodbye
     * removes a non-favorite entry immediately, and a lost observation only drops the address of
     * the reporting channel. Must be called on the main thread.</p>
     */
    public void onObserved(@NonNull final DeviceObservation observation) {
        if (!observation.hasIdentity()) {
            return;
        }

        final long nowMs = observation.getLastSeenMs() != null ? observation.getLastSeenMs() : mTimeSource.nowMs();
        final DeviceEntry existing = mEntries.get(observation.getId());

        if (observation.isGoodbye()) {
            if (existing != null) {
                if (existing.favorite || existing.pinned) {
                    existing.leaving = true;
                } else {
                    mEntries.remove(existing.id);
                }
            }
            refresh(nowMs);
            return;
        }

        if (observation.isLost()) {
            if (existing != null && observation.hasAddress()
                && existing.removeAddress(observation.getSource(), observation.getHost(), observation.getPort())) {
                refresh(nowMs);
            }
            return;
        }

        DeviceEntry entry = existing;
        if (entry == null) {
            entry = new DeviceEntry(observation.getId(), nowMs);
            mEntries.put(entry.id, entry);
        }
        if (!entry.merge(observation, nowMs)) {
            return;
        }
        enforceCapacity(nowMs);
        refresh(nowMs);
    }

    /**
     * Records an application-level confirmation of one address.
     *
     * <p>This is the only path that can promote a device to {@link DevicePresence#CONFIRMED}.
     * The address is inserted when the channel never reported it before.</p>
     *
     * @return false when the device is unknown to the store
     */
    public boolean confirm(@NonNull final String id, @NonNull final DiscoveryChannel channel,
        @NonNull final String host, final int port) {
        final DeviceEntry entry = mEntries.get(id);
        if (entry == null) {
            return false;
        }
        final long nowMs = mTimeSource.nowMs();
        entry.confirm(channel, host, port, nowMs);
        refresh(nowMs);
        return true;
    }

    /**
     * Marks or clears the favorite flag of a device.
     *
     * <p>Favorites are never removed by TTL or goodbye; they degrade to
     * {@link DevicePresence#UNREACHABLE} instead.</p>
     *
     * @return false when the device is unknown to the store
     */
    public boolean setFavorite(@NonNull final String id, final boolean favorite) {
        final DeviceEntry entry = mEntries.get(id);
        if (entry == null) {
            return false;
        }
        entry.favorite = favorite;
        refresh(mTimeSource.nowMs());
        return true;
    }

    /**
     * Marks or clears the pinned flag of a manually added device.
     *
     * <p>Pinned entries, like favorites, are never removed by TTL or goodbye; they degrade to
     * {@link DevicePresence#UNREACHABLE} instead, and are re-probed so a reachable device stays
     * confirmed.</p>
     *
     * @return false when the device is unknown to the store
     */
    public boolean setPinned(@NonNull final String id, final boolean pinned) {
        final DeviceEntry entry = mEntries.get(id);
        if (entry == null) {
            return false;
        }
        entry.pinned = pinned;
        refresh(mTimeSource.nowMs());
        return true;
    }

    /**
     * Marks or clears the identity-changed flag of a device.
     *
     * <p>The store sets the flag itself when a known device answers with a different fingerprint.
     * This setter lets the favorite layer push the flag it persisted for a device that has not been
     * heard yet, so the list shows the warning as soon as the favorite is seeded.</p>
     *
     * @return false when the device is unknown to the store
     */
    public boolean setIdentityChanged(@NonNull final String id, final boolean identityChanged) {
        final DeviceEntry entry = mEntries.get(id);
        if (entry == null) {
            return false;
        }
        if (entry.identityChanged == identityChanged) {
            return true;
        }
        entry.identityChanged = identityChanged;
        refresh(mTimeSource.nowMs());
        return true;
    }

    /**
     * Rebinds the trust anchor of a device after the user explicitly confirmed the change.
     *
     * <p>Unlike {@link #setIdentityChanged(String, boolean)} this replaces the fingerprint, so the
     * next comparison uses the accepted key. It is only called from a user confirmation; the
     * automatic merge path keeps the established fingerprint and flags the conflict instead.</p>
     *
     * @return false when the device is unknown to the store
     */
    public boolean confirmIdentity(@NonNull final String id, @Nullable final String fingerprint) {
        final DeviceEntry entry = mEntries.get(id);
        if (entry == null) {
            return false;
        }
        entry.fingerprint = fingerprint;
        entry.identityChanged = false;
        refresh(mTimeSource.nowMs());
        return true;
    }

    /**
     * Recomputes presence and expires entries; the periodic main-thread maintenance step.
     */
    public void tick() {
        refresh(mTimeSource.nowMs());
    }

    /**
     * Returns whether {@code host:port} was confirmed within the last {@code windowMs}.
     *
     * <p>Used by the announcement channel to avoid re-confirming a healthy peer on every
     * heartbeat: the server counts Register calls per address and a heartbeat would otherwise
     * exhaust the quota. The check reads the same confirmation timestamps the TTL uses, so it
     * never reports an expired address as recent.</p>
     */
    public boolean isConfirmedWithin(@NonNull final String host, final int port, final long windowMs) {
        final long nowMs = mTimeSource.nowMs();
        for (final DeviceEntry entry : mEntries.values()) {
            for (final DeviceAddress address : entry.addresses()) {
                if (address.isConfirmed() && address.getPort() == port && address.getHost().equals(host)
                    && nowMs - address.getLastConfirmedMs() <= windowMs) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Drops every entry and publishes an empty snapshot.
     */
    public void clear() {
        mEntries.clear();
        refresh(mTimeSource.nowMs());
    }

    /**
     * Returns the current immutable snapshot, ordered by presence, favorite, confirmation and name.
     */
    @NonNull
    public List<DeviceInfo> snapshot() {
        return mSnapshot;
    }

    /**
     * Returns the snapshot projected onto the legacy UI contract.
     *
     * <p>Only devices with an address are included; the display name falls back to
     * {@code LSS-<short id>} so the non-null {@link LegacyServiceInfo#getName() name} contract keeps
     * holding. The list is sorted exactly like the pre-store discovery manager sorted it.</p>
     */
    @NonNull
    public List<LegacyServiceInfo> legacySnapshot() {
        final List<LegacyServiceInfo> services = new ArrayList<>();
        for (final DeviceInfo device : mSnapshot) {
            final DeviceAddress address = device.primaryAddress();
            if (address == null) {
                continue;
            }
            services.add(LegacyServiceInfo.builder()
                .id(device.getId())
                .name(device.displayName())
                .hostAddress(address.getHost())
                .port(address.getPort())
                .fingerprint(device.getFingerprint())
                .build());
        }
        Collections.sort(services);
        return Collections.unmodifiableList(services);
    }

    /**
     * Returns the current description of one device, or null when it is unknown or expired.
     */
    @Nullable
    public DeviceInfo device(@NonNull final String id) {
        for (final DeviceInfo device : mSnapshot) {
            if (device.getId().equals(id)) {
                return device;
            }
        }
        return null;
    }

    /**
     * Returns the number of retained devices.
     */
    public int size() {
        return mEntries.size();
    }

    private void refresh(final long nowMs) {
        mEntries.values().removeIf(entry -> entry.isExpired(nowMs, HEARD_TTL_MS, CONFIRMED_TTL_MS, REMOVE_AFTER_MS));

        final List<DeviceInfo> snapshot = buildSnapshot(nowMs);
        if (snapshot.equals(mSnapshot)) {
            return;
        }

        mSnapshot = Collections.unmodifiableList(snapshot);
        for (final DeviceStoreListener listener : mListeners) {
            listener.onDevicesChanged(mSnapshot);
        }
    }

    @NonNull
    private List<DeviceInfo> buildSnapshot(final long nowMs) {
        final List<DeviceInfo> devices = new ArrayList<>(mEntries.size());
        for (final DeviceEntry entry : mEntries.values()) {
            final List<DeviceAddress> addresses = new ArrayList<>();
            for (final DeviceAddress address : entry.addresses()) {
                final boolean stillConfirmed = address.isConfirmed()
                    && nowMs - address.getLastConfirmedMs() <= CONFIRMED_TTL_MS;
                addresses.add(address.withConfirmation(stillConfirmed, address.getLastConfirmedMs()));
            }

            devices.add(DeviceInfo.builder()
                .id(entry.id)
                .fingerprint(entry.fingerprint)
                .name(entry.name)
                .role(entry.role)
                .state(entry.state)
                .join(entry.join)
                .secure(entry.secure)
                .auth(entry.auth)
                .protocolVersion(entry.protocolVersion)
                .metadataVersion(entry.metadataVersion)
                .capabilities(entry.capabilities)
                .addresses(addresses)
                .presence(entry.presence(nowMs, CONFIRMED_TTL_MS, HEARD_TTL_MS, REMOVE_AFTER_MS))
                .lastSeenMs(entry.lastSeenMs)
                .lastConfirmedMs(entry.lastConfirmedMs)
                .favorite(entry.favorite)
                .identityChanged(entry.identityChanged)
                .build());
        }
        Collections.sort(devices, SNAPSHOT_COMPARATOR);
        return devices;
    }

    private void enforceCapacity(final long nowMs) {
        while (mEntries.size() > CAPACITY) {
            final DeviceEntry victim = selectEvictionVictim(nowMs);
            if (victim == null) {
                return;
            }
            mEntries.remove(victim.id);
        }
    }

    @Nullable
    private DeviceEntry selectEvictionVictim(final long nowMs) {
        DeviceEntry oldestStale = null;
        DeviceEntry oldest = null;
        for (final DeviceEntry entry : mEntries.values()) {
            if (entry.favorite || entry.pinned) {
                continue;
            }
            if (oldest == null || entry.lastSeenMs < oldest.lastSeenMs) {
                oldest = entry;
            }
            final DevicePresence presence = entry.presence(nowMs, CONFIRMED_TTL_MS, HEARD_TTL_MS, REMOVE_AFTER_MS);
            if (presence == DevicePresence.STALE
                && (oldestStale == null || entry.lastSeenMs < oldestStale.lastSeenMs)) {
                oldestStale = entry;
            }
        }
        if (oldestStale != null) {
            return oldestStale;
        }
        if (oldest != null) {
            return oldest;
        }

        // Every entry is a favorite: drop the least recently seen one so the bound still holds.
        for (final DeviceEntry entry : mEntries.values()) {
            if (oldest == null || entry.lastSeenMs < oldest.lastSeenMs) {
                oldest = entry;
            }
        }
        return oldest;
    }

    private static int presenceRank(@NonNull final DevicePresence presence) {
        switch (presence) {
            case CONFIRMED:
                return 0;
            case HEARD:
                return 1;
            case STALE:
                return 2;
            case UNREACHABLE:
            default:
                return 3;
        }
    }
}
