package com.obby.android.localscreenshare.discovery.nsd;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * DNS-SD service types, TXT attribute keys and advertised capability bits of the NSD channel.
 *
 * <p>These values are the discovery contract with other devices: the registration and the
 * discovery side both read them, so a change here changes what this device advertises and what it
 * accepts at the same time.</p>
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class NsdConstants {
    /**
     * DNS-SD service type used for registration and discovery; fits the 15-character RFC 6763 label limit.
     */
    @SuppressWarnings("SpellCheckingInspection")
    public static final String NSD_SERVICE_TYPE = "_lss._tcp";

    /**
     * Pre-_lss service type, kept registered during the transition so clients that only discover
     * the old type keep working. It is also discovered so this client still sees old servers.
     */
    @SuppressWarnings("SpellCheckingInspection")
    public static final String NSD_SERVICE_TYPE_LEGACY = "_localscreenshare._udp";

    /**
     * TXT attribute key holding the server id as UTF-8; discovery drops services without it.
     */
    public static final String NSD_SERVICE_ATTR_ID = "id";

    /**
     * TXT attribute key holding the user-visible server name as UTF-8 (legacy namespace).
     */
    public static final String NSD_SERVICE_ATTR_NAME = "name";

    /**
     * TXT attribute key holding the minimal-record schema version.
     */
    public static final String NSD_SERVICE_ATTR_VERSION = "v";

    /**
     * TXT attribute key holding the device role wire value.
     */
    public static final String NSD_SERVICE_ATTR_ROLE = "r";

    /**
     * TXT attribute key holding the share-state wire value.
     */
    public static final String NSD_SERVICE_ATTR_STATE = "s";

    /**
     * TXT attribute key holding the join-policy wire value.
     */
    public static final String NSD_SERVICE_ATTR_JOIN = "j";

    /**
     * TXT attribute key holding the secure-transport flag ({@code 0}/{@code 1}).
     */
    public static final String NSD_SERVICE_ATTR_SECURE = "sec";

    /**
     * TXT attribute key holding the auth-mode wire value.
     */
    public static final String NSD_SERVICE_ATTR_AUTH = "auth";

    /**
     * TXT attribute key holding the protocol version.
     */
    public static final String NSD_SERVICE_ATTR_PROTOCOL_VERSION = "pv";

    /**
     * TXT attribute key holding the metadata version.
     */
    public static final String NSD_SERVICE_ATTR_METADATA_VERSION = "mv";

    /**
     * TXT attribute key holding the capability bitmap.
     */
    public static final String NSD_SERVICE_ATTR_CAPABILITIES = "caps";

    /**
     * TXT attribute key holding the device name. It is only written when the user explicitly
     * enables name sharing; the device name is otherwise never broadcast.
     */
    public static final String NSD_SERVICE_ATTR_DISPLAY_NAME = "n";

    /**
     * Capability bit: the device can encode H.264.
     */
    public static final int DEVICE_CAPABILITY_H264 = 1;

    /**
     * Capability bit: the device can encode JPEG.
     */
    public static final int DEVICE_CAPABILITY_JPEG = 1 << 1;

    /**
     * Platform value advertised in multicast announcements.
     */
    public static final String DEVICE_PLATFORM_ANDROID = "android";
}
