package com.obby.android.localscreenshare.trust;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.obby.android.localscreenshare.App;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Durable trust-on-first-use pins and a non-binding address hint.
 *
 * <p>A pin is looked up by the device id and compared byte for byte with the fingerprint derived
 * from the peer's certificate. Anything unreadable is treated as an empty table: a corrupted or
 * reset document causes the next connection to ask for confirmation again instead of silently
 * trusting a peer. Writes never fail the caller; a failed write is retried by the next change and
 * the in-memory state stays authoritative for the running process.</p>
 *
 * <p>The address index only answers "which id did this address last belong to". It never decides
 * whether a connection is allowed: local networks reuse addresses, so using it as a trust check
 * would reject a legitimately moved device.</p>
 *
 * <p>All methods are synchronized. The trust manager only calls {@link #find(String)}, which is a
 * memory lookup plus a trim; no file is read on a handshake path.</p>
 */
public final class TlsPinStore implements TlsPinLookup {

    private static final String KEY_PINS = "pins";

    private static final String KEY_ADDRESSES = "addresses";

    @NonNull
    private final TlsPinStorage mStorage;

    @NonNull
    private final Gson mGson = new Gson();

    @NonNull
    private final List<TlsPin> mPins = new ArrayList<>();

    // address -> device id; informational only, never used by PinPolicy.
    @NonNull
    private final Map<String, String> mLastDeviceIdByAddress = new LinkedHashMap<>();

    /**
     * Creates a store over the given storage and loads what is already persisted.
     */
    public TlsPinStore(@NonNull final TlsPinStorage storage) {
        mStorage = Objects.requireNonNull(storage, "storage");
        load();
    }

    /**
     * Returns the process-wide store backed by the private trust file.
     *
     * <p>Safe to call from any component after {@code App.onCreate()}.</p>
     */
    @NonNull
    public static TlsPinStore get() {
        return InstanceHolder.INSTANCE;
    }

    @Nullable
    @Override
    public synchronized TlsPin find(@NonNull final String id) {
        final String key = id == null ? "" : id.trim();
        for (final TlsPin pin : mPins) {
            if (pin.getId().equals(key)) {
                return pin;
            }
        }
        return null;
    }

    /**
     * Writes or replaces the pin of {@code id}.
     *
     * @return the stored pin
     * @throws IllegalArgumentException if the id or fingerprint is blank
     */
    @NonNull
    public synchronized TlsPin pin(@NonNull final String id, @NonNull final String fingerprint) {
        final TlsPin pin = new TlsPin(id, fingerprint, System.currentTimeMillis());
        final TlsPin existing = find(pin.getId());
        if (existing != null) {
            mPins.set(mPins.indexOf(existing), pin);
        } else {
            mPins.add(pin);
        }
        persist();
        return pin;
    }

    /**
     * Removes the pin of {@code id}; the next connection asks for confirmation again.
     *
     * @return true when a pin existed
     */
    public synchronized boolean unpin(@NonNull final String id) {
        final TlsPin existing = find(id);
        if (existing == null) {
            return false;
        }
        mPins.remove(existing);
        persist();
        return true;
    }

    /**
     * Returns every pin, most recently written first.
     */
    @NonNull
    public synchronized List<TlsPin> pins() {
        final List<TlsPin> pins = new ArrayList<>(mPins);
        Collections.reverse(pins);
        return Collections.unmodifiableList(pins);
    }

    /**
     * Remembers the id that was last reached at {@code host:port}.
     *
     * <p>Used only to warn that an address changed hands; it never affects a trust decision.</p>
     */
    public synchronized void rememberAddress(@NonNull final String host, final int port,
                                             @NonNull final String id) {
        if (host == null || host.trim().isEmpty() || id == null || id.trim().isEmpty()) {
            return;
        }
        final String key = addressKey(host, port);
        final String value = id.trim();
        if (value.equals(mLastDeviceIdByAddress.get(key))) {
            return;
        }
        mLastDeviceIdByAddress.put(key, value);
        persist();
    }

    /**
     * Returns the id last reached at {@code host:port}, or null when the address is new.
     */
    @Nullable
    public synchronized String lastDeviceId(@NonNull final String host, final int port) {
        return mLastDeviceIdByAddress.get(addressKey(host, port));
    }

    @NonNull
    private static String addressKey(@NonNull final String host, final int port) {
        return host.trim() + ":" + port;
    }

    private void load() {
        mPins.clear();
        mLastDeviceIdByAddress.clear();
        final String document = mStorage.read();
        if (document == null || document.trim().isEmpty()) {
            return;
        }
        try {
            final Document parsed = mGson.fromJson(document, Document.class);
            if (parsed == null) {
                return;
            }
            if (parsed.pins != null) {
                for (final PinRecord record : parsed.pins) {
                    if (record == null) {
                        continue;
                    }
                    try {
                        mPins.add(new TlsPin(record.id, record.fingerprint, record.pinnedAtMs));
                    } catch (IllegalArgumentException ignored) {
                        // A malformed entry is dropped; the device is re-confirmed instead.
                    }
                }
            }
            if (parsed.addresses != null) {
                for (final Map.Entry<String, String> entry : parsed.addresses.entrySet()) {
                    if (entry.getKey() != null && entry.getKey().trim().length() > 0
                        && entry.getValue() != null && entry.getValue().trim().length() > 0) {
                        mLastDeviceIdByAddress.put(entry.getKey(), entry.getValue().trim());
                    }
                }
            }
        } catch (JsonParseException | IllegalStateException e) {
            // Fail closed: an unreadable document means no pins, so every peer is confirmed again.
            mPins.clear();
            mLastDeviceIdByAddress.clear();
        }
    }

    private void persist() {
        final Document document = new Document();
        document.pins = new ArrayList<>(mPins.size());
        for (final TlsPin pin : mPins) {
            final PinRecord record = new PinRecord();
            record.id = pin.getId();
            record.fingerprint = pin.getFingerprint();
            record.pinnedAtMs = pin.getPinnedAtMs();
            document.pins.add(record);
        }
        document.addresses = new LinkedHashMap<>(mLastDeviceIdByAddress);
        try {
            mStorage.write(mGson.toJson(document));
        } catch (RuntimeException ignored) {
            // The in-memory pin is authoritative for this process; the next change retries.
        }
    }

    // On-disk schema; kept separate from the value object so the store owns the format.
    private static final class Document {
        private List<PinRecord> pins;
        private Map<String, String> addresses;
    }

    private static final class PinRecord {
        private String id;
        private String fingerprint;
        private long pinnedAtMs;
    }

    // Holds the process-wide store; initialized on the first call to get().
    private static final class InstanceHolder {
        private static final TlsPinStore INSTANCE =
            new TlsPinStore(new SharedPreferencesTlsPinStorage(App.get()));
    }
}
