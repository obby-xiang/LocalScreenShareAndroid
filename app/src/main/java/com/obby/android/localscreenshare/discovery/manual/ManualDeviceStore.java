package com.obby.android.localscreenshare.discovery.manual;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.obby.android.localscreenshare.App;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Durable record of the devices the user added by address.
 *
 * <p>A manual entry is user intent in the same sense a favorite is: the user typed an address
 * because the automatic channels could not find the device, so losing it on the next process start
 * asks for the same work again. This store keeps the confirmed identity and the address of every
 * manual entry and restores them at discovery start, which is exactly what {@code FavoriteStore}
 * does for favorites.</p>
 *
 * <p><b>One record per address and per identity.</b> Records are keyed by device id, and an add that
 * carries the same address under another id replaces the older record: a device that was re-keyed
 * answers at the address the user typed, and keeping both would put two rows in the list for one
 * address. An identical record is not rewritten, so the periodic re-probe of a manual entry does not
 * touch the file every fifteen seconds.</p>
 *
 * <p><b>Failures.</b> A corrupt document is treated as empty rather than retried, matching the
 * favorite document: nothing can be recovered from it and the next change rewrites it. A storage
 * failure never loses the in-memory list; the write is simply attempted again on the next change.
 * All methods are synchronized, because the discovery thread mutates the store while the UI reads
 * it.</p>
 */
public final class ManualDeviceStore {

    @NonNull
    private final ManualDeviceStorage mStorage;

    @NonNull
    private final Gson mGson = new Gson();

    // Newest first, so a seeded list shows the most recently added entry on top.
    @NonNull
    private final List<ManualDevice> mDevices = new ArrayList<>();

    /**
     * Creates a store over the given storage and loads what is already persisted.
     */
    public ManualDeviceStore(@NonNull final ManualDeviceStorage storage) {
        mStorage = Objects.requireNonNull(storage, "storage");
        load();
    }

    /**
     * Returns the process-wide store backed by the private manual entry file.
     *
     * <p>Safe to call from any component after {@code App.onCreate()}.</p>
     */
    @NonNull
    public static ManualDeviceStore get() {
        return InstanceHolder.INSTANCE;
    }

    /**
     * Returns every manual entry, most recently added first.
     */
    @NonNull
    public synchronized List<ManualDevice> devices() {
        return Collections.unmodifiableList(new ArrayList<>(mDevices));
    }

    /**
     * Returns the manual entry of {@code id}, or null when the device was not added by hand.
     */
    @Nullable
    public synchronized ManualDevice find(@NonNull final String id) {
        for (final ManualDevice device : mDevices) {
            if (device.getId().equals(id)) {
                return device;
            }
        }
        return null;
    }

    /**
     * Returns the manual entry stored at {@code host:port}, or null.
     */
    @Nullable
    public synchronized ManualDevice findByAddress(@NonNull final String host, final int port) {
        for (final ManualDevice device : mDevices) {
            if (device.isAt(host, port)) {
                return device;
            }
        }
        return null;
    }

    /**
     * Adds a manual entry, or replaces the one with the same id or the one that owns the address.
     *
     * <p>Called from the confirming handshake path on every successful probe, so an unchanged record
     * returns false and does not write the document again.</p>
     *
     * @param device entry to store
     * @return whether the stored list changed
     */
    public synchronized boolean add(@NonNull final ManualDevice device) {
        final ManualDevice existing = find(device.getId());
        final ManualDevice addressOwner = findByAddress(device.getHost(), device.getPort());
        if (device.equals(existing) && (addressOwner == null || addressOwner == existing)) {
            return false;
        }
        if (existing != null) {
            mDevices.remove(existing);
        }
        mDevices.removeIf(candidate -> candidate.isAt(device.getHost(), device.getPort()));
        mDevices.add(0, device);
        persist();
        return true;
    }

    /**
     * Removes the manual entry of {@code id}; returns false when it was not one.
     */
    public synchronized boolean remove(@NonNull final String id) {
        final ManualDevice existing = find(id);
        if (existing == null) {
            return false;
        }
        mDevices.remove(existing);
        persist();
        return true;
    }

    private void load() {
        mDevices.clear();
        final String document = mStorage.read();
        if (document == null || document.trim().isEmpty()) {
            return;
        }
        try {
            final ManualRecord[] records = mGson.fromJson(document, ManualRecord[].class);
            if (records == null) {
                return;
            }
            for (final ManualRecord record : records) {
                final ManualDevice device = record == null ? null : record.toDevice();
                if (device != null && find(device.getId()) == null) {
                    mDevices.add(device);
                }
            }
        } catch (RuntimeException e) {
            // A corrupt document must not make discovery unusable; the next change rewrites it.
            mDevices.clear();
        }
    }

    private void persist() {
        try {
            mStorage.write(mGson.toJson(toRecords()));
        } catch (RuntimeException e) {
            // Keep the in-memory list; the next change retries the write.
        }
    }

    @NonNull
    private List<ManualRecord> toRecords() {
        final List<ManualRecord> records = new ArrayList<>(mDevices.size());
        for (final ManualDevice device : mDevices) {
            records.add(ManualRecord.from(device));
        }
        return records;
    }

    @NonNull
    private static ManualDeviceStore createDefault() {
        return new ManualDeviceStore(new SharedPreferencesManualDeviceStorage(App.get()));
    }

    /**
     * Lazily holds the process-wide manual entry store; class loading stays out of JVM tests.
     */
    private static final class InstanceHolder {
        private static final ManualDeviceStore INSTANCE = createDefault();
    }

    /**
     * Gson shape of one record; kept separate so the value object stays immutable.
     */
    private static final class ManualRecord {
        private String id;
        private String fp;
        private String name;
        private String ip;
        private int port;

        @NonNull
        private static ManualRecord from(@NonNull final ManualDevice device) {
            final ManualRecord record = new ManualRecord();
            record.id = device.getId();
            record.fp = device.getFingerprint();
            record.name = device.getName();
            record.ip = device.getHost();
            record.port = device.getPort();
            return record;
        }

        @Nullable
        private ManualDevice toDevice() {
            if (id == null || id.trim().isEmpty() || ip == null || ip.trim().isEmpty()) {
                return null;
            }
            try {
                return new ManualDevice(id, fp, name, ip, port);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }
}
