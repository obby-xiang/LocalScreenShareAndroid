package com.obby.android.localscreenshare.discovery.nsd;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.obby.android.localscreenshare.discovery.model.AuthMode;
import com.obby.android.localscreenshare.discovery.model.DeviceRole;
import com.obby.android.localscreenshare.discovery.model.DiscoveryChannel;
import com.obby.android.localscreenshare.discovery.model.JoinPolicy;
import com.obby.android.localscreenshare.discovery.model.LssDeviceInfo;
import com.obby.android.localscreenshare.discovery.model.ShareState;
import com.obby.android.localscreenshare.discovery.provider.DeviceObservation;
import com.obby.android.localscreenshare.support.Constants;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Encoder and decoder for the DNS-SD TXT record used by NSD discovery.
 *
 * <p>Two namespaces exist. The current one ({@code _lss._tcp}) writes the minimal record: schema
 * version, id, role, state, join policy, secure flag, auth mode, protocol version, metadata version
 * and capability bitmap. The device name is only written under the {@code n} key when the user
 * explicitly enables name sharing. The legacy namespace ({@code _localscreenshare._udp}) keeps
 * writing {@code id} plus {@code name} exactly as old clients expect, with
 * {@code LSS-<short id>} as the name so the system device name still never leaves the device.</p>
 *
 * <p>Decoding is deliberately lenient: unknown keys are ignored, missing optional keys stay null,
 * and a missing name is accepted because it is no longer broadcast by default. Only a missing or
 * malformed {@code id}, an unknown schema version or an unreadable value drops the record.</p>
 */
public final class NsdTxtRecord {

    /** TXT schema version written for the current namespace. */
    public static final String SCHEMA_VERSION = "1";

    /** Maximum accepted or written length of the device name, in characters. */
    public static final int MAX_NAME_LENGTH = 64;

    /** Maximum accepted length of a device id, in characters. */
    public static final int MAX_ID_LENGTH = 64;

    /** TXT value limit from RFC 6763; larger values are ignored while decoding. */
    private static final int MAX_VALUE_BYTES = 255;

    /** TXT namespace written by {@link #encode(LssDeviceInfo, Namespace, boolean)}. */
    public enum Namespace {
        /** Current {@code _lss._tcp} record: the full minimal set. */
        CURRENT,

        /** Legacy {@code _localscreenshare._udp} record understood by pre-_lss clients. */
        LEGACY
    }

    private NsdTxtRecord() {
    }

    /**
     * Decodes known attributes into their canonical string keys.
     *
     * <p>The legacy {@code name} key is normalized to {@code n} so callers only look at one key.
     * Unknown keys, oversized values and invalid UTF-8 are skipped.</p>
     */
    @NonNull
    public static Map<String, String> decode(@NonNull final Map<String, byte[]> attributes) {
        final Map<String, String> values = new LinkedHashMap<>();
        putDecoded(values, Constants.NSD_SERVICE_ATTR_VERSION, attributes);
        putDecoded(values, Constants.NSD_SERVICE_ATTR_ID, attributes);
        putDecoded(values, Constants.NSD_SERVICE_ATTR_ROLE, attributes);
        putDecoded(values, Constants.NSD_SERVICE_ATTR_STATE, attributes);
        putDecoded(values, Constants.NSD_SERVICE_ATTR_JOIN, attributes);
        putDecoded(values, Constants.NSD_SERVICE_ATTR_SECURE, attributes);
        putDecoded(values, Constants.NSD_SERVICE_ATTR_AUTH, attributes);
        putDecoded(values, Constants.NSD_SERVICE_ATTR_PROTOCOL_VERSION, attributes);
        putDecoded(values, Constants.NSD_SERVICE_ATTR_METADATA_VERSION, attributes);
        putDecoded(values, Constants.NSD_SERVICE_ATTR_CAPABILITIES, attributes);

        final String displayName = readValue(attributes, Constants.NSD_SERVICE_ATTR_DISPLAY_NAME);
        if (displayName != null) {
            values.put(Constants.NSD_SERVICE_ATTR_DISPLAY_NAME, displayName);
        } else {
            final String legacyName = readValue(attributes, Constants.NSD_SERVICE_ATTR_NAME);
            if (legacyName != null) {
                values.put(Constants.NSD_SERVICE_ATTR_DISPLAY_NAME, legacyName);
            }
        }
        return values;
    }

    /**
     * Builds the TXT attributes for one namespace.
     *
     * @param device device description to advertise
     * @param namespace record layout to write
     * @param shareName whether the user allowed broadcasting the device name
     */
    @NonNull
    public static Map<String, byte[]> encode(@NonNull final LssDeviceInfo device,
        @NonNull final Namespace namespace, final boolean shareName) {
        final Map<String, byte[]> attributes = new LinkedHashMap<>();
        putValue(attributes, Constants.NSD_SERVICE_ATTR_ID, device.getId());

        if (namespace == Namespace.LEGACY) {
            final String legacyName = shareName && device.getName() != null
                ? truncate(device.getName()) : LssDeviceInfo.NAME_PREFIX + device.shortId();
            putValue(attributes, Constants.NSD_SERVICE_ATTR_NAME, legacyName);
            return attributes;
        }

        putValue(attributes, Constants.NSD_SERVICE_ATTR_VERSION, SCHEMA_VERSION);
        putValue(attributes, Constants.NSD_SERVICE_ATTR_ROLE, device.getRole().toWire());
        putValue(attributes, Constants.NSD_SERVICE_ATTR_STATE, device.getState().toWire());
        putValue(attributes, Constants.NSD_SERVICE_ATTR_JOIN, device.getJoin().toWire());
        putValue(attributes, Constants.NSD_SERVICE_ATTR_SECURE, device.isSecure() ? "1" : "0");
        putValue(attributes, Constants.NSD_SERVICE_ATTR_AUTH, device.getAuth().toWire());
        putValue(attributes, Constants.NSD_SERVICE_ATTR_PROTOCOL_VERSION, device.getProtocolVersion());
        putValue(attributes, Constants.NSD_SERVICE_ATTR_METADATA_VERSION,
            String.valueOf(device.getMetadataVersion()));
        putValue(attributes, Constants.NSD_SERVICE_ATTR_CAPABILITIES, String.valueOf(device.getCapabilities()));
        if (shareName && device.getName() != null) {
            putValue(attributes, Constants.NSD_SERVICE_ATTR_DISPLAY_NAME, truncate(device.getName()));
        }
        return attributes;
    }

    /**
     * Converts decoded attributes plus the resolved endpoint into an NSD observation.
     *
     * <p>Returns {@code null} when the record has no usable id, advertises an unsupported schema
     * version or has no endpoint. A record without a name is accepted and yields an observation
     * whose name is null; the store then displays the short-id fallback.</p>
     */
    @Nullable
    public static DeviceObservation observe(@NonNull final Map<String, byte[]> attributes,
        @Nullable final String host, final int port, final long nowMs) {
        final Map<String, String> values = decode(attributes);

        final String version = values.get(Constants.NSD_SERVICE_ATTR_VERSION);
        if (version != null && !SCHEMA_VERSION.equals(version)) {
            return null;
        }

        final String id = values.get(Constants.NSD_SERVICE_ATTR_ID);
        if (id == null || id.length() > MAX_ID_LENGTH || !isPrintable(id)) {
            return null;
        }
        if (host == null || host.trim().isEmpty()) {
            return null;
        }

        return DeviceObservation.builder()
            .source(DiscoveryChannel.NSD)
            .id(id)
            .name(values.get(Constants.NSD_SERVICE_ATTR_DISPLAY_NAME))
            .role(values.containsKey(Constants.NSD_SERVICE_ATTR_ROLE)
                ? DeviceRole.fromWire(values.get(Constants.NSD_SERVICE_ATTR_ROLE)) : null)
            .state(values.containsKey(Constants.NSD_SERVICE_ATTR_STATE)
                ? ShareState.fromWire(values.get(Constants.NSD_SERVICE_ATTR_STATE)) : null)
            .join(values.containsKey(Constants.NSD_SERVICE_ATTR_JOIN)
                ? JoinPolicy.fromWire(values.get(Constants.NSD_SERVICE_ATTR_JOIN)) : null)
            .secure(parseBoolean(values.get(Constants.NSD_SERVICE_ATTR_SECURE)))
            .auth(values.containsKey(Constants.NSD_SERVICE_ATTR_AUTH)
                ? AuthMode.fromWire(values.get(Constants.NSD_SERVICE_ATTR_AUTH)) : null)
            .protocolVersion(values.get(Constants.NSD_SERVICE_ATTR_PROTOCOL_VERSION))
            .metadataVersion(parseInt(values.get(Constants.NSD_SERVICE_ATTR_METADATA_VERSION)))
            .capabilities(parseInt(values.get(Constants.NSD_SERVICE_ATTR_CAPABILITIES)))
            .host(host)
            .port(port)
            .lastSeenMs(nowMs)
            .build();
    }

    private static void putDecoded(@NonNull final Map<String, String> target,
        @NonNull final String key, @NonNull final Map<String, byte[]> attributes) {
        final String value = readValue(attributes, key);
        if (value != null) {
            target.put(key, value);
        }
    }

    @Nullable
    private static String readValue(@NonNull final Map<String, byte[]> attributes, @NonNull final String key) {
        final byte[] raw = attributes.get(key);
        if (raw == null || raw.length == 0 || raw.length > MAX_VALUE_BYTES) {
            return null;
        }
        final String value = new String(raw, StandardCharsets.UTF_8).trim();
        return value.isEmpty() ? null : value;
    }

    private static void putValue(@NonNull final Map<String, byte[]> target, @NonNull final String key,
        @Nullable final String value) {
        if (value == null) {
            return;
        }
        target.put(key, truncate(value).getBytes(StandardCharsets.UTF_8));
    }

    @NonNull
    private static String truncate(@NonNull final String value) {
        return value.length() <= MAX_NAME_LENGTH ? value : value.substring(0, MAX_NAME_LENGTH);
    }

    @Nullable
    private static Boolean parseBoolean(@Nullable final String value) {
        if (value == null) {
            return null;
        }
        switch (value.toLowerCase(Locale.ROOT)) {
            case "1":
            case "true":
            case "yes":
                return Boolean.TRUE;
            case "0":
            case "false":
            case "no":
                return Boolean.FALSE;
            default:
                return null;
        }
    }

    @Nullable
    private static Integer parseInt(@Nullable final String value) {
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean isPrintable(@NonNull final String value) {
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                return false;
            }
        }
        return true;
    }
}
