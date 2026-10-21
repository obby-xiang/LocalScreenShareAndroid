package com.obby.android.localscreenshare.authorization.store;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.obby.android.localscreenshare.App;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Durable set of device identities that may watch without asking.
 *
 * <p>An entry is written only by an explicit user decision ("always allow") and removed only by an
 * explicit revocation. A document that cannot be read is treated as an empty set: a corrupted file
 * means every peer is asked again instead of being let in. A failed write never fails the caller;
 * the in-memory state stays authoritative for the running process and the next change retries.</p>
 *
 * <p>Lookups compare the id <b>and</b> the fingerprint (the comparison itself is
 * {@link com.obby.android.localscreenshare.authorization.AuthorizationPolicy#isGrantValid}), so a
 * device that changed its key is a different identity and asks again. All methods are synchronized
 * and only touch memory, because the streaming drain checks the set on every pass.</p>
 */
public final class AuthorizationStore {

    private static final String KEY_ENTRIES = "entries";

    @NonNull
    private final AuthorizationStorage mStorage;

    @NonNull
    private final Gson mGson = new Gson();

    @NonNull
    private final List<AuthorizationEntry> mEntries = new ArrayList<>();

    /**
     * Creates a store over the given storage and loads what is already persisted.
     *
     * @throws IllegalArgumentException if {@code storage} is null
     */
    public AuthorizationStore(@NonNull final AuthorizationStorage storage) {
        mStorage = Objects.requireNonNull(storage, "storage");
        load();
    }

    /**
     * Returns the process-wide store backed by the private authorization file.
     *
     * <p>Safe to call after {@code App.onCreate()}. Never call it from a component field
     * initializer: that path runs before the base context exists.</p>
     */
    @NonNull
    public static AuthorizationStore get() {
        return InstanceHolder.INSTANCE;
    }

    /**
     * Returns the entry of {@code deviceId}, or null when it is not authorized.
     *
     * <p>Callers that decide whether a peer may watch must compare the fingerprint as well; see
     * {@link com.obby.android.localscreenshare.authorization.AuthorizationPolicy#isGrantValid}.</p>
     */
    @Nullable
    public synchronized AuthorizationEntry find(@Nullable final String deviceId) {
        final String key = deviceId == null ? "" : deviceId.trim();
        if (key.isEmpty()) {
            return null;
        }
        for (final AuthorizationEntry entry : mEntries) {
            if (entry.getDeviceId().equals(key)) {
                return entry;
            }
        }
        return null;
    }

    /**
     * Writes or replaces the authorization of one identity.
     *
     * @return the stored entry
     * @throws IllegalArgumentException if the identity is blank
     */
    @NonNull
    public synchronized AuthorizationEntry authorize(@NonNull final String deviceId,
                                                    @NonNull final String fingerprint,
                                                    @Nullable final String deviceName,
                                                    final long grantedAtMs) {
        final AuthorizationEntry entry =
            new AuthorizationEntry(deviceId, fingerprint, deviceName, grantedAtMs);
        final AuthorizationEntry existing = find(entry.getDeviceId());
        if (existing != null) {
            mEntries.set(mEntries.indexOf(existing), entry);
        } else {
            mEntries.add(entry);
        }
        persist();
        return entry;
    }

    /**
     * Removes the authorization of {@code deviceId}; the next request asks for approval again.
     *
     * @return true when an authorization existed
     */
    public synchronized boolean revoke(@Nullable final String deviceId) {
        final AuthorizationEntry existing = find(deviceId);
        if (existing == null) {
            return false;
        }
        mEntries.remove(existing);
        persist();
        return true;
    }

    /**
     * Returns every authorization, most recently granted first.
     */
    @NonNull
    public synchronized List<AuthorizationEntry> entries() {
        final List<AuthorizationEntry> entries = new ArrayList<>(mEntries);
        Collections.reverse(entries);
        return Collections.unmodifiableList(entries);
    }

    private void load() {
        mEntries.clear();
        final String document = mStorage.read();
        if (document == null || document.trim().isEmpty()) {
            return;
        }
        try {
            final Document parsed = mGson.fromJson(document, Document.class);
            if (parsed == null || parsed.entries == null) {
                return;
            }
            for (final EntryRecord record : parsed.entries) {
                if (record == null) {
                    continue;
                }
                try {
                    mEntries.add(new AuthorizationEntry(record.deviceId, record.fingerprint,
                        record.deviceName, record.grantedAtMs));
                } catch (IllegalArgumentException ignored) {
                    // A malformed entry is dropped; that device is asked again instead.
                }
            }
        } catch (JsonParseException | IllegalStateException e) {
            // Fail closed: an unreadable document authorizes nobody.
            mEntries.clear();
        }
    }

    private void persist() {
        final Document document = new Document();
        document.entries = new ArrayList<>(mEntries.size());
        for (final AuthorizationEntry entry : mEntries) {
            final EntryRecord record = new EntryRecord();
            record.deviceId = entry.getDeviceId();
            record.fingerprint = entry.getFingerprint();
            record.deviceName = entry.getDeviceName();
            record.grantedAtMs = entry.getGrantedAtMs();
            document.entries.add(record);
        }
        try {
            mStorage.write(mGson.toJson(document));
        } catch (RuntimeException ignored) {
            // The in-memory entry is authoritative for this process; the next change retries.
        }
    }

    // On-disk schema; kept separate from the value object so the store owns the format.
    private static final class Document {
        private List<EntryRecord> entries;
    }

    private static final class EntryRecord {
        private String deviceId;
        private String fingerprint;
        private String deviceName;
        private long grantedAtMs;
    }

    // Holds the process-wide store; initialized on the first call to get().
    private static final class InstanceHolder {
        private static final AuthorizationStore INSTANCE =
            new AuthorizationStore(new SharedPreferencesAuthorizationStorage(App.get()));
    }
}
