package com.obby.android.localscreenshare.discovery.udp;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.obby.android.localscreenshare.discovery.model.AuthMode;
import com.obby.android.localscreenshare.discovery.model.DeviceRole;
import com.obby.android.localscreenshare.discovery.model.JoinPolicy;
import com.obby.android.localscreenshare.discovery.model.DeviceInfo;
import com.obby.android.localscreenshare.discovery.model.ShareState;

import java.nio.charset.StandardCharsets;

/**
 * JSON codec and size guard of the multicast announcement.
 *
 * <p>The wire form is a flat JSON object encoded as UTF-8. JSON was chosen over the protobuf
 * control plane because an announcement is a small, self-describing record that has to be readable
 * from a packet capture, and because unknown keys can simply be ignored: a newer sender can add
 * fields without breaking older receivers. Only the whitelisted keys below are ever read, so an
 * announcement cannot inject arbitrary content into the discovery model.</p>
 *
 * <p><b>Limits.</b> A datagram larger than {@link #MAX_BYTES}, an object with more than
 * {@link #MAX_FIELDS} members, an unsupported schema version, a missing or malformed id and any
 * non-printable control character in a string make the decoder return null. Overlong display
 * strings are truncated to {@link Announcement#MAX_STRING_LENGTH} instead of dropping the whole
 * announcement, because a long name is not a reason to lose a device.</p>
 */
public final class AnnouncementCodec {

    /** Maximum wire size of one announcement. */
    public static final int MAX_BYTES = 512;

    /** Maximum number of JSON members accepted in one announcement. */
    public static final int MAX_FIELDS = 24;

    private static final String KEY_VERSION = "v";
    private static final String KEY_ID = "id";
    private static final String KEY_NAME = "n";
    private static final String KEY_ROLE = "role";
    private static final String KEY_STATE = "state";
    private static final String KEY_JOIN = "join";
    private static final String KEY_SECURE = "sec";
    private static final String KEY_AUTH = "auth";
    private static final String KEY_PROTOCOL_VERSION = "pv";
    private static final String KEY_METADATA_VERSION = "mv";
    private static final String KEY_CAPABILITIES = "caps";
    private static final String KEY_PORT = "port";
    private static final String KEY_PLATFORM = "plat";
    private static final String KEY_BOOT = "boot";
    private static final String KEY_SEQ = "seq";
    private static final String KEY_TTL = "ttl";
    private static final String KEY_ANNOUNCES = "announces";

    private AnnouncementCodec() {
    }

    /**
     * Encodes an announcement.
     *
     * @throws IllegalArgumentException if the encoded payload would exceed {@link #MAX_BYTES}
     */
    @NonNull
    public static byte[] encode(@NonNull final Announcement announcement) {
        final JsonObject json = new JsonObject();
        json.addProperty(KEY_VERSION, announcement.getVersion());
        json.addProperty(KEY_ID, truncate(announcement.getId()));
        if (announcement.getName() != null) {
            json.addProperty(KEY_NAME, truncate(announcement.getName()));
        }
        json.addProperty(KEY_ROLE, announcement.getRole().toWire());
        json.addProperty(KEY_STATE, announcement.getState().toWire());
        json.addProperty(KEY_JOIN, announcement.getJoin().toWire());
        json.addProperty(KEY_SECURE, announcement.isSecure());
        json.addProperty(KEY_AUTH, announcement.getAuth().toWire());
        json.addProperty(KEY_PROTOCOL_VERSION, truncate(announcement.getProtocolVersion()));
        json.addProperty(KEY_METADATA_VERSION, announcement.getMetadataVersion());
        json.addProperty(KEY_CAPABILITIES, announcement.getCapabilities());
        json.addProperty(KEY_PORT, announcement.getPort());
        if (announcement.getPlatform() != null) {
            json.addProperty(KEY_PLATFORM, truncate(announcement.getPlatform()));
        }
        if (announcement.getBoot() != null) {
            json.addProperty(KEY_BOOT, truncate(announcement.getBoot()));
        }
        json.addProperty(KEY_SEQ, announcement.getSeq());
        json.addProperty(KEY_TTL, announcement.getTtlSeconds());
        json.addProperty(KEY_ANNOUNCES, announcement.isAnnounces());

        final byte[] payload = json.toString().getBytes(StandardCharsets.UTF_8);
        if (payload.length > MAX_BYTES) {
            throw new IllegalArgumentException("announcement exceeds " + MAX_BYTES + " bytes");
        }
        return payload;
    }

    /**
     * Decodes one datagram.
     *
     * @return the announcement, or null when the payload is unusable
     */
    @Nullable
    public static Announcement decode(@Nullable final byte[] payload) {
        if (payload == null || payload.length == 0 || payload.length > MAX_BYTES) {
            return null;
        }

        try {
            final JsonElement element = JsonParser.parseString(new String(payload, StandardCharsets.UTF_8));
            if (element == null || !element.isJsonObject()) {
                return null;
            }
            final JsonObject json = element.getAsJsonObject();
            if (json.size() > MAX_FIELDS) {
                return null;
            }

            final int version = intValue(json, KEY_VERSION, Announcement.VERSION);
            if (version < 1 || version > Announcement.VERSION) {
                return null;
            }

            final String id = stringValue(json, KEY_ID, false);
            if (id == null || id.length() > Announcement.MAX_STRING_LENGTH || !isPrintable(id)) {
                return null;
            }

            final String name = stringValue(json, KEY_NAME, true);
            if (name != null && !isPrintable(name)) {
                return null;
            }
            final String platform = stringValue(json, KEY_PLATFORM, true);
            final String boot = stringValue(json, KEY_BOOT, true);
            final String protocolVersion = stringValue(json, KEY_PROTOCOL_VERSION, true);

            return Announcement.builder()
                .version(version)
                .id(id)
                .name(name)
                .role(DeviceRole.fromWire(stringValue(json, KEY_ROLE, true)))
                .state(ShareState.fromWire(stringValue(json, KEY_STATE, true)))
                .join(JoinPolicy.fromWire(stringValue(json, KEY_JOIN, true)))
                .secure(booleanValue(json, KEY_SECURE, false))
                .auth(AuthMode.fromWire(stringValue(json, KEY_AUTH, true)))
                .protocolVersion(protocolVersion == null
                    ? DeviceInfo.DEFAULT_PROTOCOL_VERSION : protocolVersion)
                .metadataVersion(intValue(json, KEY_METADATA_VERSION, 0))
                .capabilities(intValue(json, KEY_CAPABILITIES, 0))
                .port(clampPort(intValue(json, KEY_PORT, 0)))
                .platform(platform)
                .boot(boot)
                .seq(longValue(json, KEY_SEQ, -1L))
                .ttlSeconds(intValue(json, KEY_TTL, 0))
                .announces(booleanValue(json, KEY_ANNOUNCES, true))
                .build();
        } catch (RuntimeException e) {
            // Malformed JSON, a nested value where a primitive was expected and similar input
            // problems all mean the same thing for the caller: this datagram is unusable.
            return null;
        }
    }

    @Nullable
    private static String stringValue(@NonNull final JsonObject json, @NonNull final String key,
        final boolean truncate) {
        final JsonElement element = json.get(key);
        if (element == null || !element.isJsonPrimitive()) {
            return null;
        }
        final JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (!primitive.isString()) {
            return null;
        }
        final String value = primitive.getAsString();
        if (value.trim().isEmpty()) {
            return null;
        }
        return truncate ? truncate(value) : value;
    }

    private static int intValue(@NonNull final JsonObject json, @NonNull final String key, final int fallback) {
        final Long value = numberValue(json, key);
        return value == null ? fallback : value.intValue();
    }

    private static long longValue(@NonNull final JsonObject json, @NonNull final String key, final long fallback) {
        final Long value = numberValue(json, key);
        return value == null ? fallback : value;
    }

    private static boolean booleanValue(@NonNull final JsonObject json, @NonNull final String key,
        final boolean fallback) {
        final JsonElement element = json.get(key);
        if (element == null || !element.isJsonPrimitive()) {
            return fallback;
        }
        final JsonPrimitive primitive = element.getAsJsonPrimitive();
        return primitive.isBoolean() ? primitive.getAsBoolean() : fallback;
    }

    @Nullable
    private static Long numberValue(@NonNull final JsonObject json, @NonNull final String key) {
        final JsonElement element = json.get(key);
        if (element == null || !element.isJsonPrimitive()) {
            return null;
        }
        final JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (!primitive.isNumber()) {
            return null;
        }
        try {
            return primitive.getAsLong();
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int clampPort(final int port) {
        return port < 0 || port > 0xFFFF ? 0 : port;
    }

    @NonNull
    private static String truncate(@NonNull final String value) {
        return value.length() <= Announcement.MAX_STRING_LENGTH
            ? value : value.substring(0, Announcement.MAX_STRING_LENGTH);
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
