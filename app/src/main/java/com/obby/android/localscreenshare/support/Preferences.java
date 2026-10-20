package com.obby.android.localscreenshare.support;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.PointF;
import android.provider.Settings;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.obby.android.localscreenshare.App;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Process-wide preference store backed by a single private {@code SharedPreferences} file.
 *
 * <p>{@link #get()} returns the one instance for the process. Getters read the in-memory snapshot;
 * only the two service identity getters persist a value, and only on their first call. Setters use
 * {@code commit()} rather than {@code apply()} so a value is on disk before the call returns,
 * which blocks the calling thread on the write. Settings changes are written from the main
 * thread.</p>
 *
 * <p>Observers are notified for every changed key, including the private service identity keys,
 * and the callback runs on the main thread no matter which thread performed the write. A null key
 * means the whole file was cleared. The in-memory value has already changed when the callback
 * runs, so getters return the new value. Registering the same observer twice is ignored, and the
 * underlying SharedPreferences listener is attached only while at least one observer exists.
 * Adding or removing an observer from inside a callback is safe.</p>
 */
@SuppressLint("ApplySharedPref")
public final class Preferences {
    /** Server bind port; see {@link #getServerPort()}. */
    public static final String KEY_SERVER_PORT = "server_port";

    /** Secure-frame flag; see {@link #isProjectionSecure()}. */
    public static final String KEY_PROJECTION_SECURE = "projection_secure";

    /** Capture scale percentage; see {@link #getProjectionScale()}. */
    public static final String KEY_PROJECTION_SCALE = "projection_scale";

    /** Keep-screen-on flag for capture; see {@link #isProjectionKeepScreenOn()}. */
    public static final String KEY_PROJECTION_KEEP_SCREEN_ON = "projection_keep_screen_on";

    /** Capture frame rate cap; see {@link #getProjectionFrameRate()}. */
    public static final String KEY_PROJECTION_FRAME_RATE = "projection_frame_rate";

    /** JPEG quality for the legacy capture path; see {@link #getProjectionQuality()}. */
    public static final String KEY_PROJECTION_QUALITY = "projection_quality";

    /** Capture codec mode; see {@link #getProjectionCodec()}. */
    public static final String KEY_PROJECTION_CODEC = "projection_codec";

    /** Capture bitrate in bits per second; see {@link #getProjectionBitrate()}. */
    public static final String KEY_PROJECTION_BITRATE = "projection_bitrate";

    /** Capture key-frame interval in milliseconds; see {@link #getProjectionIdrIntervalMs()}. */
    public static final String KEY_PROJECTION_IDR_INTERVAL = "projection_idr_interval";

    /** Normalized chip position; see {@link #getServiceChipLocation()}. */
    public static final String KEY_SERVICE_CHIP_LOCATION = "service_chip_location";

    /** Keep-screen-on flag for the viewer overlay; see {@link #isViewerKeepScreenOn()}. */
    public static final String KEY_VIEWER_KEEP_SCREEN_ON = "viewer_keep_screen_on";

    /** Overlay opacity percentage; see {@link #getViewerOpacity()}. */
    public static final String KEY_VIEWER_OPACITY = "viewer_opacity";

    /** Overlay scale percentage; see {@link #getViewerScale()}. */
    public static final String KEY_VIEWER_SCALE = "viewer_scale";

    /** Normalized overlay position; see {@link #getViewerLocation()}. */
    public static final String KEY_VIEWER_LOCATION = "viewer_location";

    /** Rounded-corner flag for the overlay; see {@link #isViewerRounded()}. */
    public static final String KEY_VIEWER_ROUNDED = "viewer_rounded";

    /** Discovery toggle; see {@link #isDiscoveryEnabled()}. */
    public static final String KEY_DISCOVERY_ENABLED = "discovery_enabled";

    private static final String PREF_FILE_NAME = "lss-preferences";

    private static final String KEY_SERVICE_ID = "service_id";

    private static final String KEY_SERVICE_NAME = "service_name";

    private static final String KEY_DEVICE_ALIAS = "device_alias";

    private static final String KEY_SHARE_NAME_ENABLED = "share_name_enabled";

    private static final String KEY_IDENTITY_ID = "identity_id";

    private static final String KEY_IDENTITY_FINGERPRINT = "identity_fingerprint";

    private static final String KEY_LEGACY_SERVICE_ID = "identity_legacy_service_id";

    private static final int DEFAULT_SERVER_PORT = 8080;

    private static final int DEFAULT_PROJECTION_SCALE = 100;

    private static final int DEFAULT_PROJECTION_QUALITY = 85;

    private static final int DEFAULT_PROJECTION_FRAME_RATE = 60;

    private static final String DEFAULT_PROJECTION_CODEC = "h264";

    private static final int DEFAULT_PROJECTION_BITRATE = 4_000_000;

    private static final int DEFAULT_PROJECTION_IDR_INTERVAL_MS = 1000;

    private static final PointF DEFAULT_SERVICE_CHIP_LOCATION = new PointF(0f, 0f);

    private static final int DEFAULT_VIEWER_OPACITY = 100;

    private static final int DEFAULT_VIEWER_SCALE = 80;

    private static final PointF DEFAULT_VIEWER_LOCATION = new PointF(0.5f, 0.5f);

    @NonNull
    private final SharedPreferences mPreferences;

    @NonNull
    private final Gson mGson;

    // Copy-on-write so a callback can add or remove an observer while the listener fans out.
    @NonNull
    private final List<Observer> mObservers = new CopyOnWriteArrayList<>();

    @NonNull
    private final SharedPreferences.OnSharedPreferenceChangeListener mOnSharedPreferenceChangeListener =
        (sharedPreferences, key) -> mObservers.forEach(observer -> observer.onChanged(key));

    private Preferences() {
        mPreferences = App.get().getSharedPreferences(PREF_FILE_NAME, Context.MODE_PRIVATE);
        mGson = new Gson();
    }

    /**
     * Returns the process-wide instance. Only code running before {@code App.onCreate()} could
     * see an uninitialized application context.
     */
    @NonNull
    public static Preferences get() {
        return InstanceHolder.INSTANCE;
    }

    /**
     * Registers a change observer. A duplicate registration of the same observer is ignored, and
     * the SharedPreferences listener is attached when the first observer arrives.
     */
    public void addObserver(@NonNull final Observer observer) {
        if (mObservers.contains(observer)) {
            return;
        }

        if (mObservers.isEmpty()) {
            mPreferences.registerOnSharedPreferenceChangeListener(mOnSharedPreferenceChangeListener);
        }

        mObservers.add(observer);
    }

    /** Removes an observer; the last removal detaches the SharedPreferences listener. */
    public void removeObserver(@NonNull final Observer observer) {
        mObservers.remove(observer);

        if (mObservers.isEmpty()) {
            mPreferences.unregisterOnSharedPreferenceChangeListener(mOnSharedPreferenceChangeListener);
        }
    }

    /**
     * Returns the legacy server id, generating and persisting a random UUID without dashes on
     * first use. Clearing the app data produces a new id.
     *
     * <p>The device identity no longer comes from this value: discovery keys on the
     * keystore-derived short id ({@link com.obby.android.localscreenshare.identity.DeviceIdentity}).
     * It is still read by the media path as a stable per-installation session key, and it is
     * captured once as {@link #getLegacyIdentityServiceId()} so favorites stored before the
     * identity migration can be recognized.</p>
     */
    @NonNull
    public String getServiceId() {
        return Optional.ofNullable(mPreferences.getString(KEY_SERVICE_ID, null))
            .orElseGet(() -> {
                final String serviceId = UUID.randomUUID().toString().replace("-", "");
                mPreferences.edit().putString(KEY_SERVICE_ID, serviceId).commit();
                return serviceId;
            });
    }

    /**
     * Returns the local display name, defaulting to the system device name. The first non-null
     * value is persisted; while the system value is null the key stays absent, so the lookup runs
     * again on the next call.
     *
     * <p>This is a local label only. It is not broadcast unless name sharing is enabled; see
     * {@link #isShareNameEnabled()}.</p>
     */
    @NonNull
    public String getServiceName() {
        return Optional.ofNullable(mPreferences.getString(KEY_SERVICE_NAME, null))
            .orElseGet(() -> {
                final String serviceName =
                    Settings.Global.getString(App.get().getContentResolver(), Settings.Global.DEVICE_NAME);
                mPreferences.edit().putString(KEY_SERVICE_NAME, serviceName).commit();
                return serviceName;
            });
    }

    /**
     * Returns the user-chosen alias of this device, or null when the user never set one.
     *
     * <p>Unlike {@link #getServiceName()} the alias is never seeded from the system device name,
     * so it is safe to broadcast once the user opted in.</p>
     */
    @Nullable
    public String getDeviceAlias() {
        final String alias = mPreferences.getString(KEY_DEVICE_ALIAS, null);
        return alias == null || alias.trim().isEmpty() ? null : alias;
    }

    /** Persists the device alias; a null or blank value removes it. */
    public void setDeviceAlias(@Nullable final String alias) {
        if (alias == null || alias.trim().isEmpty()) {
            mPreferences.edit().remove(KEY_DEVICE_ALIAS).commit();
        } else {
            mPreferences.edit().putString(KEY_DEVICE_ALIAS, alias.trim()).commit();
        }
    }

    /**
     * Whether the device name may be broadcast in discovery records; defaults to false.
     *
     * <p>It is off by default because a device name often contains a person, a room or a model.
     * When enabled, only the user-chosen alias is broadcast; when no alias exists the short-id
     * placeholder is used.</p>
     */
    public boolean isShareNameEnabled() {
        return mPreferences.getBoolean(KEY_SHARE_NAME_ENABLED, false);
    }

    /** Persists the name-sharing flag; the next advertisement picks it up. */
    public void setShareNameEnabled(final boolean isEnabled) {
        mPreferences.edit().putBoolean(KEY_SHARE_NAME_ENABLED, isEnabled).commit();
    }

    /**
     * Returns the fingerprint recorded by the last identity load, or null before the first one.
     *
     * <p>It is a record, not the identity: the identity is recomputed from the keystore key on
     * every start, and this value is only compared against it to detect that the key changed —
     * a restore from backup or an app-data clear brings the record back without the key.</p>
     */
    @Nullable
    public String getIdentityFingerprint() {
        return mPreferences.getString(KEY_IDENTITY_FINGERPRINT, null);
    }

    /** Returns the device id recorded by the last identity load, or null before the first one. */
    @Nullable
    public String getIdentityId() {
        return mPreferences.getString(KEY_IDENTITY_ID, null);
    }

    /**
     * Records the identity derived from the keystore key; both values are written in one commit.
     *
     * <p>Called after an identity load whose values differ from the record. The next load compares
     * its fingerprint against this record and reports
     * {@link com.obby.android.localscreenshare.identity.DeviceIdentity#isChanged()} when they
     * differ, so a changed identity is surfaced instead of silently replacing the old one.</p>
     */
    public void recordIdentity(@NonNull final String id, @NonNull final String fingerprint) {
        mPreferences.edit()
            .putString(KEY_IDENTITY_ID, id)
            .putString(KEY_IDENTITY_FINGERPRINT, fingerprint)
            .commit();
    }

    /**
     * Returns the pre-identity server id captured on the first identity load, or null when the
     * identity was created before any {@link #getServiceId()} call.
     *
     * <p>Favorites stored by an older version use that id as their key; the discovery batches use
     * this value to recognize them once and to offer a rebind instead of dropping them.</p>
     */
    @Nullable
    public String getLegacyIdentityServiceId() {
        return mPreferences.getString(KEY_LEGACY_SERVICE_ID, null);
    }

    /** Captures the pre-identity server id once; later calls are ignored by the caller. */
    public void setLegacyIdentityServiceId(@Nullable final String serviceId) {
        if (serviceId == null || serviceId.trim().isEmpty()) {
            mPreferences.edit().remove(KEY_LEGACY_SERVICE_ID).commit();
        } else {
            mPreferences.edit().putString(KEY_LEGACY_SERVICE_ID, serviceId).commit();
        }
    }

    /** Server bind port; defaults to 8080. Read when the server is constructed. */
    public int getServerPort() {
        return mPreferences.getInt(KEY_SERVER_PORT, DEFAULT_SERVER_PORT);
    }

    /**
     * Persists the server port. A running server keeps the port it bound; the new value applies to
     * the next start.
     */
    public void setServerPort(final int port) {
        mPreferences.edit().putInt(KEY_SERVER_PORT, port).commit();
    }

    /**
     * Whether captured frames are flagged secure for clients; defaults to false. The client
     * mirrors the flag onto its overlay window so the shared screen is excluded from screenshots
     * and screen recordings.
     */
    public boolean isProjectionSecure() {
        return mPreferences.getBoolean(KEY_PROJECTION_SECURE, false);
    }

    /** Persists the secure-frame flag; the capture service applies it to later frames. */
    public void setProjectionSecure(final boolean isSecure) {
        mPreferences.edit().putBoolean(KEY_PROJECTION_SECURE, isSecure).commit();
    }

    /** Whether capture keeps the screen on while sharing; defaults to true. */
    public boolean isProjectionKeepScreenOn() {
        return mPreferences.getBoolean(KEY_PROJECTION_KEEP_SCREEN_ON, true);
    }

    /** Persists the keep-screen-on flag; it takes effect when a capture session starts. */
    public void setProjectionKeepScreenOn(final boolean isKeepScreenOn) {
        mPreferences.edit().putBoolean(KEY_PROJECTION_KEEP_SCREEN_ON, isKeepScreenOn).commit();
    }

    /** Maximum capture frame rate in frames per second; defaults to 60. */
    public int getProjectionFrameRate() {
        return mPreferences.getInt(KEY_PROJECTION_FRAME_RATE, DEFAULT_PROJECTION_FRAME_RATE);
    }

    /** Persists the frame rate cap; the capture service applies it to later frames. */
    public void setProjectionFrameRate(final int frameRate) {
        mPreferences.edit().putInt(KEY_PROJECTION_FRAME_RATE, frameRate).commit();
    }

    /** JPEG quality used by the legacy capture path; defaults to 85. */
    public int getProjectionQuality() {
        return mPreferences.getInt(KEY_PROJECTION_QUALITY, DEFAULT_PROJECTION_QUALITY);
    }

    /** Persists the JPEG quality; the capture service applies it to later frames. */
    public void setProjectionQuality(final int quality) {
        mPreferences.edit().putInt(KEY_PROJECTION_QUALITY, quality).commit();
    }

    /**
     * Capture codec mode, either {@code "h264"} or {@code "jpeg"}; defaults to H.264. JPEG is the
     * fallback used when H.264 encoding is unavailable or disabled.
     */
    @NonNull
    public String getProjectionCodec() {
        return mPreferences.getString(KEY_PROJECTION_CODEC, DEFAULT_PROJECTION_CODEC);
    }

    /** Persists the codec mode; it takes effect when a capture session starts or is rebuilt. */
    public void setProjectionCodec(@NonNull final String codec) {
        mPreferences.edit().putString(KEY_PROJECTION_CODEC, codec).commit();
    }

    /** Capture bitrate in bits per second; defaults to 4 Mbps. */
    public int getProjectionBitrate() {
        return mPreferences.getInt(KEY_PROJECTION_BITRATE, DEFAULT_PROJECTION_BITRATE);
    }

    /** Persists the capture bitrate; the running encoder applies it live. */
    public void setProjectionBitrate(final int bitrate) {
        mPreferences.edit().putInt(KEY_PROJECTION_BITRATE, bitrate).commit();
    }

    /** Capture key-frame interval in milliseconds; defaults to 1000. */
    public int getProjectionIdrIntervalMs() {
        return mPreferences.getInt(KEY_PROJECTION_IDR_INTERVAL, DEFAULT_PROJECTION_IDR_INTERVAL_MS);
    }

    /** Persists the key-frame interval; it takes effect when the encoder is rebuilt. */
    public void setProjectionIdrIntervalMs(final int intervalMs) {
        mPreferences.edit().putInt(KEY_PROJECTION_IDR_INTERVAL, intervalMs).commit();
    }

    /**
     * Capture size as a percentage of the maximum window bounds; defaults to 100. A change also
     * resizes an active capture.
     */
    public int getProjectionScale() {
        return mPreferences.getInt(KEY_PROJECTION_SCALE, DEFAULT_PROJECTION_SCALE);
    }

    /** Persists the capture scale; the capture service resizes an active capture. */
    public void setProjectionScale(final int scale) {
        mPreferences.edit().putInt(KEY_PROJECTION_SCALE, scale).commit();
    }

    /**
     * Normalized position of the service chip within the visible display frame, with both
     * coordinates in {@code 0..1}; defaults to the top-left corner. Serialized as JSON.
     */
    @NonNull
    public PointF getServiceChipLocation() {
        return Optional.ofNullable(mPreferences.getString(KEY_SERVICE_CHIP_LOCATION, null))
            .map(value -> mGson.fromJson(value, PointF.class))
            .orElseGet(() -> new PointF(DEFAULT_SERVICE_CHIP_LOCATION.x, DEFAULT_SERVICE_CHIP_LOCATION.y));
    }

    /** Persists the chip position; the coordinates are fractions of the bounds, not pixels. */
    public void setServiceChipLocation(@NonNull final PointF location) {
        mPreferences.edit().putString(KEY_SERVICE_CHIP_LOCATION, mGson.toJson(location)).commit();
    }

    /** Whether the viewer overlay keeps the screen on while showing a stream; defaults to true. */
    public boolean isViewerKeepScreenOn() {
        return mPreferences.getBoolean(KEY_VIEWER_KEEP_SCREEN_ON, true);
    }

    /** Persists the viewer keep-screen-on flag; applied the next time the overlay is shown. */
    public void setViewerKeepScreenOn(final boolean isKeepScreenOn) {
        mPreferences.edit().putBoolean(KEY_VIEWER_KEEP_SCREEN_ON, isKeepScreenOn).commit();
    }

    /** Viewer overlay opacity in percent; defaults to 100. */
    public int getViewerOpacity() {
        return mPreferences.getInt(KEY_VIEWER_OPACITY, DEFAULT_VIEWER_OPACITY);
    }

    /** Persists the overlay opacity; applied when the overlay is shown or the user gestures. */
    public void setViewerOpacity(final int opacity) {
        mPreferences.edit().putInt(KEY_VIEWER_OPACITY, opacity).commit();
    }

    /** Viewer overlay size in percent; defaults to 80. */
    public int getViewerScale() {
        return mPreferences.getInt(KEY_VIEWER_SCALE, DEFAULT_VIEWER_SCALE);
    }

    /** Persists the overlay size; applied when the overlay is shown or the user gestures. */
    public void setViewerScale(final int scale) {
        mPreferences.edit().putInt(KEY_VIEWER_SCALE, scale).commit();
    }

    /**
     * Normalized position of the viewer overlay within the visible display frame, with both
     * coordinates in {@code 0..1}; defaults to the center. Serialized as JSON.
     */
    @NonNull
    public PointF getViewerLocation() {
        return Optional.ofNullable(mPreferences.getString(KEY_VIEWER_LOCATION, null))
            .map(value -> mGson.fromJson(value, PointF.class))
            .orElseGet(() -> new PointF(DEFAULT_VIEWER_LOCATION.x, DEFAULT_VIEWER_LOCATION.y));
    }

    /** Persists the overlay position as fractions of the bounds, not pixels. */
    public void setViewerLocation(@NonNull final PointF location) {
        mPreferences.edit().putString(KEY_VIEWER_LOCATION, mGson.toJson(location)).commit();
    }

    /** Whether the viewer overlay uses rounded corners; defaults to true. */
    public boolean isViewerRounded() {
        return mPreferences.getBoolean(KEY_VIEWER_ROUNDED, true);
    }

    /** Persists the rounded-corner flag; applied the next time the overlay shape is updated. */
    public void setViewerRounded(final boolean isRounded) {
        mPreferences.edit().putBoolean(KEY_VIEWER_ROUNDED, isRounded).commit();
    }

    /** Whether the app discovers and lists services; defaults to true. */
    public boolean isDiscoveryEnabled() {
        return mPreferences.getBoolean(KEY_DISCOVERY_ENABLED, true);
    }

    /** Persists the discovery toggle; the activity and discovery manager read it on next use. */
    public void setDiscoveryEnabled(final boolean isEnabled) {
        mPreferences.edit().putBoolean(KEY_DISCOVERY_ENABLED, isEnabled).commit();
    }

    /**
     * Receives preference change notifications. The callback runs on the main thread with the
     * changed key, or with a null key when the whole file was cleared.
     */
    @FunctionalInterface
    public interface Observer {
        void onChanged(@Nullable String key);
    }

    /** Initializes the singleton on first access; class initialization publishes it safely. */
    private static class InstanceHolder {
        private static final Preferences INSTANCE = new Preferences();
    }
}
