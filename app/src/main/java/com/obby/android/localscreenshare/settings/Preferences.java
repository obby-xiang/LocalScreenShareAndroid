package com.obby.android.localscreenshare.settings;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.PointF;
import android.provider.Settings;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.obby.android.localscreenshare.App;
import com.obby.android.localscreenshare.discovery.udp.MulticastConstants;

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
    /**
     * Server bind port; see {@link #getServerPort()}.
     */
    public static final String KEY_SERVER_PORT = "server_port";

    /**
     * Secure-frame flag; see {@link #isProjectionSecure()}.
     */
    public static final String KEY_PROJECTION_SECURE = "projection_secure";

    /**
     * Capture scale percentage; see {@link #getProjectionScale()}.
     */
    public static final String KEY_PROJECTION_SCALE = "projection_scale";

    /**
     * Keep-screen-on flag for capture; see {@link #isProjectionKeepScreenOn()}.
     */
    public static final String KEY_PROJECTION_KEEP_SCREEN_ON = "projection_keep_screen_on";

    /**
     * Capture frame rate cap; see {@link #getProjectionFrameRate()}.
     */
    public static final String KEY_PROJECTION_FRAME_RATE = "projection_frame_rate";

    /**
     * JPEG quality for the legacy capture path; see {@link #getProjectionQuality()}.
     */
    public static final String KEY_PROJECTION_QUALITY = "projection_quality";

    /**
     * Capture codec mode; see {@link #getProjectionCodec()}.
     */
    public static final String KEY_PROJECTION_CODEC = "projection_codec";

    /**
     * Capture bitrate in bits per second; see {@link #getProjectionBitrate()}.
     */
    public static final String KEY_PROJECTION_BITRATE = "projection_bitrate";

    /**
     * Capture key-frame interval in milliseconds; see {@link #getProjectionIdrIntervalMs()}.
     */
    public static final String KEY_PROJECTION_IDR_INTERVAL = "projection_idr_interval";

    /**
     * Normalized chip position; see {@link #getServiceChipLocation()}.
     */
    public static final String KEY_SERVICE_CHIP_LOCATION = "service_chip_location";

    /**
     * Keep-screen-on flag for the viewer overlay; see {@link #isViewerKeepScreenOn()}.
     */
    public static final String KEY_VIEWER_KEEP_SCREEN_ON = "viewer_keep_screen_on";

    /**
     * Overlay opacity percentage; see {@link #getViewerOpacity()}.
     */
    public static final String KEY_VIEWER_OPACITY = "viewer_opacity";

    /**
     * Overlay scale percentage; see {@link #getViewerScale()}.
     */
    public static final String KEY_VIEWER_SCALE = "viewer_scale";

    /**
     * Normalized overlay position; see {@link #getViewerLocation()}.
     */
    public static final String KEY_VIEWER_LOCATION = "viewer_location";

    /**
     * Rounded-corner flag for the overlay; see {@link #isViewerRounded()}.
     */
    public static final String KEY_VIEWER_ROUNDED = "viewer_rounded";

    /**
     * Overlay zoom preset; see {@link #getViewerZoomMode()}.
     */
    public static final String KEY_VIEWER_ZOOM_MODE = "viewer_zoom_mode";

    /**
     * Discovery toggle; see {@link #isDiscoveryEnabled()}.
     */
    public static final String KEY_DISCOVERY_ENABLED = "discovery_enabled";

    /**
     * Multicast announcement group; see {@link #getMulticastGroup()}.
     */
    public static final String KEY_MULTICAST_GROUP = "multicast_group";

    /**
     * Multicast announcement port; see {@link #getMulticastPort()}.
     */
    public static final String KEY_MULTICAST_PORT = "multicast_port";

    /**
     * Multicast announcement toggle; see {@link #isMulticastAnnounceEnabled()}.
     */
    public static final String KEY_MULTICAST_ANNOUNCE_ENABLED = "multicast_announce_enabled";

    /**
     * Discovery refresh preset; see {@link #getDiscoveryRefreshTier()}.
     */
    public static final String KEY_DISCOVERY_REFRESH_TIER = "discovery_refresh_tier";

    /**
     * Visibility preset; see {@link #getDiscoveryVisibility()}.
     */
    public static final String KEY_DISCOVERY_VISIBILITY = "discovery_visibility";

    /**
     * Absolute expiry of the temporary visibility preset; see {@link #getDiscoveryVisibilityUntil()}.
     */
    public static final String KEY_DISCOVERY_VISIBILITY_UNTIL = "discovery_visibility_until";

    /**
     * mDNS-only compatibility mode; see {@link #isDiscoveryCompatOnly()}.
     */
    public static final String KEY_DISCOVERY_COMPAT_ONLY = "discovery_compat_only";

    private static final String PREF_FILE_NAME = "lss-preferences";

    private static final String KEY_SERVICE_ID = "service_id";

    private static final String KEY_SERVICE_NAME = "service_name";

    private static final String KEY_DEVICE_ALIAS = "device_alias";

    /**
     * Name-sharing flag; see {@link #isShareNameEnabled()}.
     */
    public static final String KEY_SHARE_NAME_ENABLED = "share_name_enabled";

    /**
     * Pipeline-metrics flag shared by both roles; see {@link #isMetricsEnabled()}.
     */
    public static final String KEY_METRICS_ENABLED = "metrics_enabled";

    /**
     * Viewer debug-overlay flag; see {@link #isViewerHudEnabled()}.
     */
    public static final String KEY_VIEWER_HUD_ENABLED = "viewer_hud_enabled";

    /**
     * Whether the shared stream carries the device's playback audio; see {@link #isAudioEnabled()}.
     */
    public static final String KEY_AUDIO_ENABLED = "audio_enabled";

    /**
     * Whether a hot device may lower the applied frame rate and capture size; see
     * {@link #isThermalThrottleEnabled()}.
     */
    public static final String KEY_THERMAL_THROTTLE = "thermal_throttle";

    private static final String KEY_AUDIO_CHANNELS = "audio_channels";

    private static final String KEY_AUDIO_BITRATE = "audio_bitrate";

    private static final String KEY_AUDIO_BUFFER_MS = "audio_buffer_ms";

    /**
     * Hidden plaintext-transport override; see {@link #isTlsAllowPlaintext()}.
     */
    public static final String KEY_TLS_ALLOW_PLAINTEXT = "tls_allow_plaintext";

    /**
     * Viewing-authorization switch; see {@link #isAuthorizationEnabled()}.
     */
    public static final String KEY_AUTHORIZATION_ENABLED = "authorization_enabled";

    /**
     * Hidden media-path override; see {@link #getMediaTransport()}.
     */
    public static final String KEY_MEDIA_TRANSPORT = "media_transport";

    /**
     * Experimental datagram media channel; see {@link #isMediaUdpEnabled()}.
     */
    public static final String KEY_MEDIA_UDP_ENABLED = "media_udp_enabled";

    private static final String DEFAULT_MEDIA_TRANSPORT = "auto";

    private static final String KEY_IDENTITY_ID = "identity_id";

    private static final String KEY_IDENTITY_FINGERPRINT = "identity_fingerprint";

    private static final String KEY_LEGACY_SERVICE_ID = "identity_legacy_service_id";

    private static final int DEFAULT_SERVER_PORT = 8080;

    /**
     * Default audio channel count: stereo, the screen-share convention for music and video.
     */
    public static final int DEFAULT_AUDIO_CHANNELS = 2;

    /**
     * Default audio bitrate in bits per second: the same 128 kbps a desktop screen-share tool uses.
     */
    public static final int DEFAULT_AUDIO_BITRATE = 128_000;

    /**
     * Default client audio buffer target in milliseconds.
     */
    public static final int DEFAULT_AUDIO_BUFFER_MS = 60;

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

    /**
     * Removes an observer; the last removal detaches the SharedPreferences listener.
     */
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

    /**
     * Persists the device alias; a null or blank value removes it.
     */
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

    /**
     * Persists the name-sharing flag; the next advertisement picks it up.
     */
    public void setShareNameEnabled(final boolean isEnabled) {
        mPreferences.edit().putBoolean(KEY_SHARE_NAME_ENABLED, isEnabled).commit();
    }

    /**
     * Whether pipeline probes and the structured metric log line are enabled; defaults to false.
     *
     * <p>The same key is read by the server and the viewer, because both sides emit the same
     * {@code lss.metrics} line with a different role. It is off by default so an ordinary run
     * neither formats a line every five seconds nor keeps any sample window alive; when it is off
     * every probe is a single volatile read and no snapshot is taken.</p>
     */
    public boolean isMetricsEnabled() {
        return mPreferences.getBoolean(KEY_METRICS_ENABLED, false);
    }

    /**
     * Persists the pipeline-metrics flag; a running pipeline picks it up on its next start.
     */
    public void setMetricsEnabled(final boolean isEnabled) {
        mPreferences.edit().putBoolean(KEY_METRICS_ENABLED, isEnabled).commit();
    }

    /**
     * Whether the viewer shows the debug overlay; defaults to false.
     *
     * <p>It is separate from {@link #isMetricsEnabled()} on purpose: the overlay only decides
     * whether the numbers are visible, never whether they are collected, so turning the overlay on
     * cannot silently start measuring a pipeline the user switched off.</p>
     */
    public boolean isViewerHudEnabled() {
        return mPreferences.getBoolean(KEY_VIEWER_HUD_ENABLED, false);
    }

    /**
     * Persists the viewer debug-overlay flag; a running viewer applies it immediately.
     */
    public void setViewerHudEnabled(final boolean isEnabled) {
        mPreferences.edit().putBoolean(KEY_VIEWER_HUD_ENABLED, isEnabled).commit();
    }

    /**
     * Whether the shared stream carries the device's playback audio; defaults to false.
     *
     * <p>Off by default: capturing what the device plays is a privacy-relevant decision, so it is
     * always an explicit choice and never a side effect of starting a share. The value only
     * describes intent; whether capture is possible is decided at runtime (API level, permission,
     * device support), and every failure keeps video running.</p>
     */
    public boolean isAudioEnabled() {
        return mPreferences.getBoolean(KEY_AUDIO_ENABLED, false);
    }

    /**
     * Persists the audio-sharing intent; a running share starts or stops capture immediately.
     */
    public void setAudioEnabled(final boolean isEnabled) {
        mPreferences.edit().putBoolean(KEY_AUDIO_ENABLED, isEnabled).commit();
    }

    /**
     * Whether a hot device may lower the applied frame rate and capture size; defaults to true.
     *
     * <p>On by default because the alternative is a device that keeps encoding at full rate while
     * the platform is already throttling it, which costs heat and battery for a picture the
     * viewer can barely receive. The switch only controls the temporary override: the stored frame
     * rate and capture percentage are never written by it.</p>
     */
    public boolean isThermalThrottleEnabled() {
        return mPreferences.getBoolean(KEY_THERMAL_THROTTLE, true);
    }

    /**
     * Persists the thermal-throttle intent; a running share applies or drops the override at once.
     */
    public void setThermalThrottleEnabled(final boolean isEnabled) {
        mPreferences.edit().putBoolean(KEY_THERMAL_THROTTLE, isEnabled).commit();
    }

    /**
     * Returns the configured audio channel count, one or two.
     */
    public int getAudioChannels() {
        return mPreferences.getInt(KEY_AUDIO_CHANNELS, DEFAULT_AUDIO_CHANNELS);
    }

    /**
     * Returns the configured audio bitrate in bits per second.
     */
    public int getAudioBitrate() {
        return mPreferences.getInt(KEY_AUDIO_BITRATE, DEFAULT_AUDIO_BITRATE);
    }

    /**
     * Returns the configured client buffer target in milliseconds.
     */
    public int getAudioBufferMs() {
        return mPreferences.getInt(KEY_AUDIO_BUFFER_MS, DEFAULT_AUDIO_BUFFER_MS);
    }

    /**
     * Whether the transport may fall back to plaintext; defaults to false.
     *
     * <p>This is an operations and device-verification switch, not a user preference: it has no UI
     * entry and never flips automatically. When it is on, both endpoints advertise
     * {@code secure=false} and a device that requires TLS refuses the connection instead of
     * degrading silently.</p>
     */
    public boolean isTlsAllowPlaintext() {
        return mPreferences.getBoolean(KEY_TLS_ALLOW_PLAINTEXT, false);
    }

    /**
     * Persists the plaintext override; both endpoints apply it on their next connection.
     */
    public void setTlsAllowPlaintext(final boolean isAllowed) {
        mPreferences.edit().putBoolean(KEY_TLS_ALLOW_PLAINTEXT, isAllowed).commit();
    }

    /**
     * Whether an unknown device must be approved before it may watch; defaults to true.
     *
     * <p>Security first: a device whose identity was never allowed is parked and the user is asked.
     * Turning it off returns to the behaviour before authorization existed, in which any device
     * that can reach the port may watch; the settings sheet states that risk next to the switch.</p>
     */
    public boolean isAuthorizationEnabled() {
        return mPreferences.getBoolean(KEY_AUTHORIZATION_ENABLED, true);
    }

    /**
     * Persists the authorization switch; the gate reads it on every decision.
     */
    public void setAuthorizationEnabled(final boolean isEnabled) {
        mPreferences.edit().putBoolean(KEY_AUTHORIZATION_ENABLED, isEnabled).commit();
    }

    /**
     * Media path preference of the server, one of {@code auto}, {@code grpc}, {@code tcp} or
     * {@code udp}.
     *
     * <p>Hidden on purpose: it exists for A/B measurement and for pinning a call to the
     * control-plane carrier while a dedicated channel is evaluated, so it has no user-facing
     * control. An unknown stored value reads back as {@code auto} through the policy.</p>
     */
    @NonNull
    public String getMediaTransport() {
        return mPreferences.getString(KEY_MEDIA_TRANSPORT, DEFAULT_MEDIA_TRANSPORT);
    }

    /**
     * Persists the media path preference; it applies to calls that start afterwards.
     */
    public void setMediaTransport(@NonNull final String transport) {
        mPreferences.edit().putString(KEY_MEDIA_TRANSPORT, transport).commit();
    }

    /**
     * Whether the experimental datagram media channel may be offered; defaults to false.
     *
     * <p>It is an opt-in on purpose: the tier trades reliability controls for latency, so a device
     * that never enables it keeps negotiating exactly the channels it had before. Only the default
     * {@code auto} path consults it; an explicit {@code media_transport} value wins.</p>
     */
    public boolean isMediaUdpEnabled() {
        return mPreferences.getBoolean(KEY_MEDIA_UDP_ENABLED, false);
    }

    /**
     * Persists the experimental datagram switch; it applies to calls that start afterwards.
     */
    public void setMediaUdpEnabled(final boolean isEnabled) {
        mPreferences.edit().putBoolean(KEY_MEDIA_UDP_ENABLED, isEnabled).commit();
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

    /**
     * Returns the device id recorded by the last identity load, or null before the first one.
     */
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

    /**
     * Captures the pre-identity server id once; later calls are ignored by the caller.
     */
    public void setLegacyIdentityServiceId(@Nullable final String serviceId) {
        if (serviceId == null || serviceId.trim().isEmpty()) {
            mPreferences.edit().remove(KEY_LEGACY_SERVICE_ID).commit();
        } else {
            mPreferences.edit().putString(KEY_LEGACY_SERVICE_ID, serviceId).commit();
        }
    }

    /**
     * Server bind port; defaults to 8080. Read when the server is constructed.
     */
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

    /**
     * Persists the secure-frame flag; the capture service applies it to later frames.
     */
    public void setProjectionSecure(final boolean isSecure) {
        mPreferences.edit().putBoolean(KEY_PROJECTION_SECURE, isSecure).commit();
    }

    /**
     * Whether capture keeps the screen on while sharing; defaults to true.
     */
    public boolean isProjectionKeepScreenOn() {
        return mPreferences.getBoolean(KEY_PROJECTION_KEEP_SCREEN_ON, true);
    }

    /**
     * Persists the keep-screen-on flag; it takes effect when a capture session starts.
     */
    public void setProjectionKeepScreenOn(final boolean isKeepScreenOn) {
        mPreferences.edit().putBoolean(KEY_PROJECTION_KEEP_SCREEN_ON, isKeepScreenOn).commit();
    }

    /**
     * Maximum capture frame rate in frames per second; defaults to 60.
     */
    public int getProjectionFrameRate() {
        return mPreferences.getInt(KEY_PROJECTION_FRAME_RATE, DEFAULT_PROJECTION_FRAME_RATE);
    }

    /**
     * Persists the frame rate cap; the capture service applies it to later frames.
     */
    public void setProjectionFrameRate(final int frameRate) {
        mPreferences.edit().putInt(KEY_PROJECTION_FRAME_RATE, frameRate).commit();
    }

    /**
     * JPEG quality used by the legacy capture path; defaults to 85.
     */
    public int getProjectionQuality() {
        return mPreferences.getInt(KEY_PROJECTION_QUALITY, DEFAULT_PROJECTION_QUALITY);
    }

    /**
     * Persists the JPEG quality; the capture service applies it to later frames.
     */
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

    /**
     * Persists the codec mode; it takes effect when a capture session starts or is rebuilt.
     */
    public void setProjectionCodec(@NonNull final String codec) {
        mPreferences.edit().putString(KEY_PROJECTION_CODEC, codec).commit();
    }

    /**
     * Capture bitrate in bits per second; defaults to 4 Mbps.
     */
    public int getProjectionBitrate() {
        return mPreferences.getInt(KEY_PROJECTION_BITRATE, DEFAULT_PROJECTION_BITRATE);
    }

    /**
     * Persists the capture bitrate; the running encoder applies it live.
     */
    public void setProjectionBitrate(final int bitrate) {
        mPreferences.edit().putInt(KEY_PROJECTION_BITRATE, bitrate).commit();
    }

    /**
     * Capture key-frame interval in milliseconds; defaults to 1000.
     */
    public int getProjectionIdrIntervalMs() {
        return mPreferences.getInt(KEY_PROJECTION_IDR_INTERVAL, DEFAULT_PROJECTION_IDR_INTERVAL_MS);
    }

    /**
     * Persists the key-frame interval; it takes effect when the encoder is rebuilt.
     */
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

    /**
     * Persists the capture scale; the capture service resizes an active capture.
     */
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

    /**
     * Persists the chip position; the coordinates are fractions of the bounds, not pixels.
     */
    public void setServiceChipLocation(@NonNull final PointF location) {
        mPreferences.edit().putString(KEY_SERVICE_CHIP_LOCATION, mGson.toJson(location)).commit();
    }

    /**
     * Whether the viewer overlay keeps the screen on while showing a stream; defaults to true.
     */
    public boolean isViewerKeepScreenOn() {
        return mPreferences.getBoolean(KEY_VIEWER_KEEP_SCREEN_ON, true);
    }

    /**
     * Persists the viewer keep-screen-on flag; applied the next time the overlay is shown.
     */
    public void setViewerKeepScreenOn(final boolean isKeepScreenOn) {
        mPreferences.edit().putBoolean(KEY_VIEWER_KEEP_SCREEN_ON, isKeepScreenOn).commit();
    }

    /**
     * Viewer overlay opacity in percent; defaults to 100.
     */
    public int getViewerOpacity() {
        return mPreferences.getInt(KEY_VIEWER_OPACITY, DEFAULT_VIEWER_OPACITY);
    }

    /**
     * Persists the overlay opacity; applied when the overlay is shown or the user gestures.
     */
    public void setViewerOpacity(final int opacity) {
        mPreferences.edit().putInt(KEY_VIEWER_OPACITY, opacity).commit();
    }

    /**
     * Viewer overlay size in percent; defaults to 80.
     */
    public int getViewerScale() {
        return mPreferences.getInt(KEY_VIEWER_SCALE, DEFAULT_VIEWER_SCALE);
    }

    /**
     * Persists the overlay size; applied when the overlay is shown or the user gestures.
     */
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

    /**
     * Persists the overlay position as fractions of the bounds, not pixels.
     */
    public void setViewerLocation(@NonNull final PointF location) {
        mPreferences.edit().putString(KEY_VIEWER_LOCATION, mGson.toJson(location)).commit();
    }

    /**
     * Whether the viewer overlay uses rounded corners; defaults to true.
     */
    public boolean isViewerRounded() {
        return mPreferences.getBoolean(KEY_VIEWER_ROUNDED, true);
    }

    /**
     * Persists the rounded-corner flag; applied the next time the overlay shape is updated.
     */
    public void setViewerRounded(final boolean isRounded) {
        mPreferences.edit().putBoolean(KEY_VIEWER_ROUNDED, isRounded).commit();
    }

    /**
     * Viewer zoom preset wire value; empty means the default preset (fit the window).
     *
     * <p>The value is validated by {@link com.obby.android.localscreenshare.render.ZoomMode},
     * which turns an empty or unknown value into the fit mode. It is stored separately from the
     * viewing percentage, so switching presets never overwrites the size the user chose.</p>
     */
    @NonNull
    public String getViewerZoomMode() {
        return Optional.ofNullable(mPreferences.getString(KEY_VIEWER_ZOOM_MODE, null)).orElse("");
    }

    /**
     * Persists the zoom preset; the viewer applies it the next time it recomputes its window.
     */
    public void setViewerZoomMode(@NonNull final String zoomMode) {
        mPreferences.edit().putString(KEY_VIEWER_ZOOM_MODE, zoomMode).commit();
    }

    /**
     * Whether the app discovers and lists services; defaults to true.
     */
    public boolean isDiscoveryEnabled() {
        return mPreferences.getBoolean(KEY_DISCOVERY_ENABLED, true);
    }

    /**
     * Persists the discovery toggle; the activity and discovery manager read it on next use.
     */
    public void setDiscoveryEnabled(final boolean isEnabled) {
        mPreferences.edit().putBoolean(KEY_DISCOVERY_ENABLED, isEnabled).commit();
    }

    /**
     * Multicast group used by the announcement channel; defaults to
     * {@link MulticastConstants#MULTICAST_GROUP}. It is read when the channel starts, so a change applies
     * to the next start.
     */
    @NonNull
    public String getMulticastGroup() {
        final String group = mPreferences.getString(KEY_MULTICAST_GROUP, MulticastConstants.MULTICAST_GROUP);
        return group == null || group.trim().isEmpty() ? MulticastConstants.MULTICAST_GROUP : group.trim();
    }

    /**
     * Persists the multicast group; the next channel start uses it.
     */
    public void setMulticastGroup(@Nullable final String group) {
        if (group == null || group.trim().isEmpty()) {
            mPreferences.edit().remove(KEY_MULTICAST_GROUP).commit();
        } else {
            mPreferences.edit().putString(KEY_MULTICAST_GROUP, group.trim()).commit();
        }
    }

    /**
     * Multicast port used by the announcement channel; defaults to {@link MulticastConstants#MULTICAST_PORT}.
     */
    public int getMulticastPort() {
        return mPreferences.getInt(KEY_MULTICAST_PORT, MulticastConstants.MULTICAST_PORT);
    }

    /**
     * Persists the multicast port; the next channel start uses it.
     */
    public void setMulticastPort(final int port) {
        mPreferences.edit().putInt(KEY_MULTICAST_PORT, port).commit();
    }

    /**
     * Whether the multicast discovery channel is enabled; defaults to true.
     *
     * <p>The switch covers both directions: disabling it stops this device from announcing itself
     * and stops the discovery managers from joining the group, so only mDNS and the manual entry
     * remain. The legacy key name is kept so an existing preference keeps its meaning.</p>
     */
    public boolean isMulticastAnnounceEnabled() {
        return mPreferences.getBoolean(KEY_MULTICAST_ANNOUNCE_ENABLED, true);
    }

    /**
     * Persists the multicast channel toggle; discovery and announcements apply it immediately.
     */
    public void setMulticastAnnounceEnabled(final boolean isEnabled) {
        mPreferences.edit().putBoolean(KEY_MULTICAST_ANNOUNCE_ENABLED, isEnabled).commit();
    }

    /**
     * Discovery refresh preset wire value; empty means the default preset.
     *
     * <p>The value is validated by {@link DiscoverySettings},
     * which turns an unknown or empty value into the balanced preset.</p>
     */
    @NonNull
    public String getDiscoveryRefreshTier() {
        return Optional.ofNullable(mPreferences.getString(KEY_DISCOVERY_REFRESH_TIER, null)).orElse("");
    }

    /**
     * Persists the refresh preset; a discovery restart applies it.
     */
    public void setDiscoveryRefreshTier(@NonNull final String tier) {
        mPreferences.edit().putString(KEY_DISCOVERY_REFRESH_TIER, tier).commit();
    }

    /**
     * Visibility preset wire value; empty means the default preset (visible).
     *
     * <p>Validation and the expiry rule live in
     * {@link com.obby.android.localscreenshare.settings.VisibilityPolicy}.</p>
     */
    @NonNull
    public String getDiscoveryVisibility() {
        return Optional.ofNullable(mPreferences.getString(KEY_DISCOVERY_VISIBILITY, null)).orElse("");
    }

    /**
     * Absolute expiry of the temporary visibility preset; {@code 0} when it never expires.
     */
    public long getDiscoveryVisibilityUntil() {
        return mPreferences.getLong(KEY_DISCOVERY_VISIBILITY_UNTIL, 0L);
    }

    /**
     * Persists the visibility preset and its expiry in one commit.
     *
     * <p>Both values are written together so an observer never reads a temporary preset without
     * the window that bounds it.</p>
     */
    public void setDiscoveryVisibility(@NonNull final String visibility, final long expiresAtMs) {
        mPreferences.edit()
            .putString(KEY_DISCOVERY_VISIBILITY, visibility)
            .putLong(KEY_DISCOVERY_VISIBILITY_UNTIL, expiresAtMs)
            .commit();
    }

    /**
     * Whether discovery is restricted to mDNS and the legacy service type; defaults to false.
     */
    public boolean isDiscoveryCompatOnly() {
        return mPreferences.getBoolean(KEY_DISCOVERY_COMPAT_ONLY, false);
    }

    /**
     * Persists the compatibility-mode flag; discovery and announcements apply it immediately.
     */
    public void setDiscoveryCompatOnly(final boolean compatOnly) {
        mPreferences.edit().putBoolean(KEY_DISCOVERY_COMPAT_ONLY, compatOnly).commit();
    }

    /**
     * Returns the validated discovery settings snapshot.
     *
     * <p>Built from the individual keys so the model stays free of Android types; invalid stored
     * values fall back to the defaults inside {@link DiscoverySettings}.</p>
     */
    @NonNull
    public DiscoverySettings getDiscoverySettings() {
        return DiscoverySettings.of(isMulticastAnnounceEnabled(), getMulticastGroup(), getMulticastPort(),
            getDiscoveryRefreshTier(), getDiscoveryVisibility(), getDiscoveryVisibilityUntil(),
            isDiscoveryCompatOnly());
    }

    /**
     * Receives preference change notifications. The callback runs on the main thread with the
     * changed key, or with a null key when the whole file was cleared.
     */
    @FunctionalInterface
    public interface Observer {
        void onChanged(@Nullable String key);
    }

    /**
     * Initializes the singleton on first access; class initialization publishes it safely.
     */
    private static class InstanceHolder {
        private static final Preferences INSTANCE = new Preferences();
    }
}
