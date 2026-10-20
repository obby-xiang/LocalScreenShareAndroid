package com.obby.android.localscreenshare.discovery.favorite;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.obby.android.localscreenshare.App;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Durable favorites and the last address that worked for each of them.
 *
 * <p>Favorites are the discovery fallback that works when multicast is filtered and scanning is
 * blocked: the store remembers who the user cares about and where that device was last seen, and
 * discovery probes those addresses at start. A favorite is never evidence of reachability — the
 * probe still goes through Register, and only an accepted answer confirms the device.</p>
 *
 * <p><b>Identity.</b> A favorite pins {@code id} and {@code fingerprint}. A remote that answers
 * with a different fingerprint is flagged {@link FavoriteDevice#isIdentityChanged()} and is not
 * rebound; the flag is persisted and only the user clears it by confirming. A missing fingerprint
 * on either side proves nothing, so two legacy entries still merge by id.</p>
 *
 * <p><b>Alias.</b> While {@code customAlias} is false the alias follows the advertised name, so a
 * rename propagates. Once the user sets an alias it stays, because a rename must not silently undo
 * a deliberate label.</p>
 *
 * <p><b>Migration.</b> {@link #migrateLegacy()} imports the pre-identity document once. Those
 * entries have no fingerprint; they keep their old id and address so the first successful Register
 * can offer a rebind instead of dropping the favorite.</p>
 *
 * <p>All methods are synchronized; the store is small and read from the discovery and UI threads.
 * Storage failures never lose the in-memory list: a failed write is ignored and retried on the
 * next change.</p>
 */
public final class FavoriteStore {

    /**
     * Result of comparing a stored fingerprint with a just-observed one.
     */
    public enum IdentityStatus {
        /**
         * Both fingerprints are known and equal.
         */
        MATCH,
        /**
         * Both fingerprints are known and differ.
         */
        CHANGED,
        /**
         * At least one side has no fingerprint, so no conclusion can be drawn.
         */
        UNKNOWN
    }

    /**
     * Result of applying a remote observation to a favorite.
     */
    public enum SyncOutcome {
        /**
         * The device is not a favorite.
         */
        NOT_FAVORITE,
        /**
         * Nothing about the favorite changed.
         */
        UNCHANGED,
        /**
         * The address or the automatic alias changed.
         */
        UPDATED,
        /**
         * The remote answered with a different fingerprint; the favorite was not rebound.
         */
        IDENTITY_CHANGED
    }

    private static final Comparator<FavoriteDevice> PROBE_ORDER =
        Comparator.comparingLong(FavoriteDevice::getLastSuccessMs).reversed()
            .thenComparing(FavoriteDevice::getId);

    @NonNull
    private final FavoriteStorage mStorage;

    @NonNull
    private final Gson mGson = new Gson();

    @NonNull
    private final List<FavoriteDevice> mFavorites = new ArrayList<>();

    /**
     * Creates a store over the given storage and loads what is already persisted.
     */
    public FavoriteStore(@NonNull final FavoriteStorage storage) {
        mStorage = Objects.requireNonNull(storage, "storage");
        load();
    }

    /**
     * Returns the process-wide store backed by the private favorites file.
     *
     * <p>Safe to call from any component after {@code App.onCreate()}.</p>
     */
    @NonNull
    public static FavoriteStore get() {
        return InstanceHolder.INSTANCE;
    }

    /**
     * Returns every favorite, most recently added first.
     */
    @NonNull
    public synchronized List<FavoriteDevice> favorites() {
        return Collections.unmodifiableList(new ArrayList<>(mFavorites));
    }

    /**
     * Returns whether {@code id} is a favorite.
     */
    public synchronized boolean isFavorite(@NonNull final String id) {
        return find(id) != null;
    }

    /**
     * Returns the favorite of {@code id}, or null.
     */
    @Nullable
    public synchronized FavoriteDevice find(@NonNull final String id) {
        for (final FavoriteDevice favorite : mFavorites) {
            if (favorite.getId().equals(id)) {
                return favorite;
            }
        }
        return null;
    }

    /**
     * Returns the favorite whose last known address is {@code host:port}, or null.
     */
    @Nullable
    public synchronized FavoriteDevice findByAddress(@NonNull final String host, final int port) {
        for (final FavoriteDevice favorite : mFavorites) {
            if (favorite.getPort() == port && favorite.getHost().equals(host)) {
                return favorite;
            }
        }
        return null;
    }

    /**
     * Adds a favorite or replaces the favorite with the same id.
     *
     * <p>Replacing keeps the stored success time, because the caller usually only knows the
     * identity and the address; the probe order must not reset on a rename.</p>
     */
    @NonNull
    public synchronized FavoriteDevice add(@NonNull final FavoriteDevice favorite) {
        FavoriteDevice stored = favorite;
        final FavoriteDevice existing = find(favorite.getId());
        if (existing != null) {
            mFavorites.remove(existing);
            stored = favorite.withSuccess(favorite.getHost(),
                favorite.getPort() > 0 ? favorite.getPort() : existing.getPort(),
                existing.getLastSuccessMs());
        }
        mFavorites.add(0, stored);
        persist();
        return stored;
    }

    /**
     * Removes the favorite of {@code id}; returns false when it was not a favorite.
     */
    public synchronized boolean remove(@NonNull final String id) {
        final FavoriteDevice existing = find(id);
        if (existing == null) {
            return false;
        }
        mFavorites.remove(existing);
        persist();
        return true;
    }

    /**
     * Sets the user-visible alias.
     *
     * @param alias label, or null to fall back to the advertised name
     * @param customAlias true when the user chose the alias and the remote name must not win
     */
    public synchronized boolean setAlias(@NonNull final String id, @Nullable final String alias,
        final boolean customAlias) {
        final FavoriteDevice existing = find(id);
        if (existing == null) {
            return false;
        }
        replace(existing.withAlias(alias, customAlias));
        persist();
        return true;
    }

    /**
     * Records that a connection to {@code host:port} succeeded; unknown ids are ignored.
     */
    public synchronized boolean recordSuccess(@NonNull final String id, @NonNull final String host,
        final int port, final long nowMs) {
        final FavoriteDevice existing = find(id);
        if (existing == null || port <= 0) {
            return false;
        }
        replace(existing.withSuccess(host, port, nowMs));
        persist();
        return true;
    }

    /**
     * Returns the favorites worth probing, most recent success first.
     *
     * <p>Favorites without an address are skipped; they can still be selected from the list once
     * a channel reports them.</p>
     */
    @NonNull
    public synchronized List<FavoriteDevice> probeTargets() {
        final List<FavoriteDevice> targets = new ArrayList<>(mFavorites.size());
        for (final FavoriteDevice favorite : mFavorites) {
            if (favorite.hasAddress()) {
                targets.add(favorite);
            }
        }
        Collections.sort(targets, PROBE_ORDER);
        return Collections.unmodifiableList(targets);
    }

    /**
     * Applies a remote observation to the favorite of the same id.
     *
     * <p>A changed fingerprint flags the favorite and is never written; the stored fingerprint is
     * the anchor until the user confirms. Otherwise the last known address is refreshed and, when
     * the alias is not custom, the advertised name replaces it.</p>
     */
    @NonNull
    public synchronized SyncOutcome sync(@NonNull final String id, @Nullable final String fingerprint,
        @Nullable final String name, @NonNull final String host, final int port) {
        final FavoriteDevice existing = find(id);
        if (existing == null) {
            return SyncOutcome.NOT_FAVORITE;
        }

        boolean changed = false;
        boolean identityChanged = existing.isIdentityChanged();
        final IdentityStatus status = checkIdentity(existing.getFingerprint(), fingerprint);
        if (status == IdentityStatus.CHANGED) {
            if (!identityChanged) {
                identityChanged = true;
                changed = true;
            }
        } else if (status == IdentityStatus.MATCH && identityChanged) {
            identityChanged = false;
            changed = true;
        }

        FavoriteDevice updated = existing;
        if (port > 0 && !host.trim().isEmpty()
            && (!existing.getHost().equals(host) || existing.getPort() != port)) {
            updated = updated.withAddress(host, port);
            changed = true;
        }
        if (!updated.isCustomAlias() && name != null && !name.trim().isEmpty()
            && !name.trim().equals(updated.getAlias())) {
            updated = updated.withAlias(name, false);
            changed = true;
        }
        if (identityChanged != updated.isIdentityChanged()) {
            updated = updated.withIdentityChanged(identityChanged);
        }

        if (changed) {
            replace(updated);
            persist();
        }
        if (identityChanged) {
            return SyncOutcome.IDENTITY_CHANGED;
        }
        return changed ? SyncOutcome.UPDATED : SyncOutcome.UNCHANGED;
    }

    /**
     * Confirms a changed identity after the user accepted it.
     *
     * @return false when the device is not a favorite
     */
    public synchronized boolean confirmIdentity(@NonNull final String id,
        @Nullable final String fingerprint) {
        final FavoriteDevice existing = find(id);
        if (existing == null) {
            return false;
        }
        replace(existing.withFingerprint(fingerprint));
        persist();
        return true;
    }

    /**
     * Imports the pre-identity favorite document once and removes it.
     *
     * <p>The legacy document is a JSON array whose entries use any of {@code id}/{@code serviceId},
     * {@code host}/{@code ip}/{@code address}, {@code port}, and {@code alias}/{@code name}.
     * Entries already present and entries without a usable address are skipped. Malformed input is
     * dropped rather than retried, because nothing can be recovered from it.</p>
     *
     * @return the number of imported favorites
     */
    public synchronized int migrateLegacy() {
        final String legacy = mStorage.readLegacy();
        if (legacy == null || legacy.trim().isEmpty()) {
            return 0;
        }

        int imported = 0;
        try {
            final JsonElement root = JsonParser.parseString(legacy);
            if (root.isJsonArray()) {
                final JsonArray array = root.getAsJsonArray();
                for (final JsonElement element : array) {
                    final FavoriteDevice favorite = readLegacyEntry(element);
                    if (favorite == null || find(favorite.getId()) != null) {
                        continue;
                    }
                    mFavorites.add(favorite);
                    imported++;
                }
            }
        } catch (RuntimeException e) {
            imported = 0;
        }

        mStorage.clearLegacy();
        if (imported > 0) {
            persist();
        }
        return imported;
    }

    /**
     * Compares a stored fingerprint with an observed one.
     */
    @NonNull
    public static IdentityStatus checkIdentity(@Nullable final String stored,
        @Nullable final String observed) {
        if (stored == null || stored.trim().isEmpty()
            || observed == null || observed.trim().isEmpty()) {
            return IdentityStatus.UNKNOWN;
        }
        return stored.equals(observed) ? IdentityStatus.MATCH : IdentityStatus.CHANGED;
    }

    @Nullable
    private FavoriteDevice readLegacyEntry(@Nullable final JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return null;
        }
        final JsonObject object = element.getAsJsonObject();
        final String id = firstNonBlank(string(object, "id"), string(object, "serviceId"));
        final String host = firstNonBlank(string(object, "host"), string(object, "ip"),
            string(object, "address"));
        final int port = integer(object, "port", 0);
        final String alias = firstNonBlank(string(object, "alias"), string(object, "name"));
        if (id == null || host == null || port <= 0 || port > 0xFFFF) {
            return null;
        }
        try {
            return FavoriteDevice.create(id, null, host, port, alias, false);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void load() {
        mFavorites.clear();
        final String document = mStorage.read();
        if (document == null || document.trim().isEmpty()) {
            return;
        }
        try {
            final FavoriteRecord[] records = mGson.fromJson(document, FavoriteRecord[].class);
            if (records == null) {
                return;
            }
            for (final FavoriteRecord record : records) {
                final FavoriteDevice favorite = record == null ? null : record.toDevice();
                if (favorite != null) {
                    mFavorites.add(favorite);
                }
            }
        } catch (RuntimeException e) {
            // A corrupt document must not make discovery unusable; the next change rewrites it.
            mFavorites.clear();
        }
    }

    private void replace(@NonNull final FavoriteDevice favorite) {
        for (int i = 0; i < mFavorites.size(); i++) {
            if (mFavorites.get(i).getId().equals(favorite.getId())) {
                mFavorites.set(i, favorite);
                return;
            }
        }
        mFavorites.add(favorite);
    }

    private void persist() {
        try {
            mStorage.write(mGson.toJson(toRecords()));
        } catch (RuntimeException e) {
            // Keep the in-memory list; the next change retries the write.
        }
    }

    @NonNull
    private List<FavoriteRecord> toRecords() {
        final List<FavoriteRecord> records = new ArrayList<>(mFavorites.size());
        for (final FavoriteDevice favorite : mFavorites) {
            records.add(FavoriteRecord.from(favorite));
        }
        return records;
    }

    @Nullable
    private static String string(@NonNull final JsonObject object, @NonNull final String key) {
        final JsonElement value = object.get(key);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) {
            return null;
        }
        try {
            return value.getAsString();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static int integer(@NonNull final JsonObject object, @NonNull final String key,
        final int fallback) {
        final JsonElement value = object.get(key);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return value.getAsInt();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    @Nullable
    private static String firstNonBlank(@Nullable final String... values) {
        for (final String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return null;
    }

    @NonNull
    private static FavoriteStore createDefault() {
        return new FavoriteStore(new SharedPreferencesFavoriteStorage(App.get()));
    }

    /**
     * Lazily holds the process-wide favorite store; class loading stays out of JVM tests.
     */
    private static final class InstanceHolder {
        private static final FavoriteStore INSTANCE = createDefault();
    }

    /**
     * Gson shape of one record; kept separate so the value object stays immutable.
     */
    private static final class FavoriteRecord {
        private String id;
        private String fp;
        private String ip;
        private int port;
        private String alias;
        private boolean customAlias;
        private boolean identityChanged;
        private long lastSuccessMs;

        @NonNull
        private static FavoriteRecord from(@NonNull final FavoriteDevice favorite) {
            final FavoriteRecord record = new FavoriteRecord();
            record.id = favorite.getId();
            record.fp = favorite.getFingerprint();
            record.ip = favorite.getHost();
            record.port = favorite.getPort();
            record.alias = favorite.getAlias();
            record.customAlias = favorite.isCustomAlias();
            record.identityChanged = favorite.isIdentityChanged();
            record.lastSuccessMs = favorite.getLastSuccessMs();
            return record;
        }

        @Nullable
        private FavoriteDevice toDevice() {
            if (id == null || id.trim().isEmpty() || ip == null || ip.trim().isEmpty()) {
                return null;
            }
            try {
                return new FavoriteDevice(id, fp, ip, port, alias, customAlias, identityChanged,
                    lastSuccessMs);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }
}
