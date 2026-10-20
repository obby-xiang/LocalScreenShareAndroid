package com.obby.android.localscreenshare.support;

import android.os.Build;
import android.view.WindowManager;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * Process-wide constants shared by the Android components.
 *
 * <p>The groups are resource sizing, NSD registration and discovery, service command actions and
 * intent extras, the overlay window type, Messenger message ids, and gRPC limits. Action strings,
 * extra names and message ids define the contract between {@code MainActivity} and the two
 * services, so both sides have to change together. All values are compile-time constants and
 * carry no state.</p>
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class Constants {
    /** Processor count read when the class is initialized; sizes the gRPC executor pools. */
    public static final int CPU_COUNT = Runtime.getRuntime().availableProcessors();

    /**
     * Placeholder host address reported until NSD resolution yields a real address, so a server
     * info value is never built with an empty host.
     */
    public static final String UNKNOWN_HOST_ADDRESS = "N/A";

    /** DNS-SD service type used for registration and discovery; fits the 15-character RFC 6763 label limit. */
    @SuppressWarnings("SpellCheckingInspection")
    public static final String NSD_SERVICE_TYPE = "_lss._tcp";

    /**
     * Pre-_lss service type, kept registered during the transition so clients that only discover
     * the old type keep working. It is also discovered so this client still sees old servers.
     */
    @SuppressWarnings("SpellCheckingInspection")
    public static final String NSD_SERVICE_TYPE_LEGACY = "_localscreenshare._udp";

    /** TXT attribute key holding the server id as UTF-8; discovery drops services without it. */
    public static final String NSD_SERVICE_ATTR_ID = "id";

    /** TXT attribute key holding the user-visible server name as UTF-8 (legacy namespace). */
    public static final String NSD_SERVICE_ATTR_NAME = "name";

    /** TXT attribute key holding the minimal-record schema version. */
    public static final String NSD_SERVICE_ATTR_VERSION = "v";

    /** TXT attribute key holding the device role wire value. */
    public static final String NSD_SERVICE_ATTR_ROLE = "r";

    /** TXT attribute key holding the share-state wire value. */
    public static final String NSD_SERVICE_ATTR_STATE = "s";

    /** TXT attribute key holding the join-policy wire value. */
    public static final String NSD_SERVICE_ATTR_JOIN = "j";

    /** TXT attribute key holding the secure-transport flag ({@code 0}/{@code 1}). */
    public static final String NSD_SERVICE_ATTR_SECURE = "sec";

    /** TXT attribute key holding the auth-mode wire value. */
    public static final String NSD_SERVICE_ATTR_AUTH = "auth";

    /** TXT attribute key holding the protocol version. */
    public static final String NSD_SERVICE_ATTR_PROTOCOL_VERSION = "pv";

    /** TXT attribute key holding the metadata version. */
    public static final String NSD_SERVICE_ATTR_METADATA_VERSION = "mv";

    /** TXT attribute key holding the capability bitmap. */
    public static final String NSD_SERVICE_ATTR_CAPABILITIES = "caps";

    /**
     * TXT attribute key holding the device name. It is only written when the user explicitly
     * enables name sharing; the device name is otherwise never broadcast.
     */
    public static final String NSD_SERVICE_ATTR_DISPLAY_NAME = "n";

    /** Capability bit: the device can encode H.264. */
    public static final int DEVICE_CAPABILITY_H264 = 1;

    /** Capability bit: the device can encode JPEG. */
    public static final int DEVICE_CAPABILITY_JPEG = 1 << 1;

    /**
     * Hidden system broadcast reporting Wi-Fi access point state changes. The server listens for
     * it because a hotspot change can change the local address that clients can reach.
     */
    public static final String ACTION_WIFI_AP_STATE_CHANGED = "android.net.wifi.WIFI_AP_STATE_CHANGED";

    /**
     * Package-scoped broadcast that stops the capture service. It is sent by the activity and by
     * the service overlay, and received by a not-exported receiver.
     */
    @SuppressWarnings("SpellCheckingInspection")
    public static final String ACTION_STOP_SERVICE = "com.obby.android.localscreenshare.ACTION_STOP_SERVICE";

    /**
     * Package-scoped broadcast that dismisses the viewer overlay and stops the client service. It
     * is sent by the activity and by the overlay UI, and received by a not-exported receiver.
     */
    @SuppressWarnings("SpellCheckingInspection")
    public static final String ACTION_STOP_CLIENT_SERVICE =
        "com.obby.android.localscreenshare.ACTION_STOP_CLIENT_SERVICE";

    /** Intent extra carrying the MediaProjection consent result passed to the capture service. */
    public static final String EXTRA_MEDIA_PROJECTION_RESULT = "mediaProjectionResult";

    /** Intent extra carrying the service info the client service connects to. */
    public static final String EXTRA_SERVICE_INFO = "serviceInfo";

    /**
     * Window type for overlay windows: {@code TYPE_APPLICATION_OVERLAY} from API 26, the older
     * {@code TYPE_SYSTEM_ALERT} below it. Both require the overlay permission.
     */
    public static final int FLOATING_WINDOW_TYPE = Build.VERSION.SDK_INT < Build.VERSION_CODES.O
        ? WindowManager.LayoutParams.TYPE_SYSTEM_ALERT : WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;

    /** Client-to-service message: registers {@code msg.replyTo} for server state notifications. */
    public static final int MSG_REGISTER_SERVICE_CLIENT = 1;

    /** Client-to-service message: removes {@code msg.replyTo} from the notification list. */
    public static final int MSG_UNREGISTER_SERVICE_CLIENT = 2;

    /** Service-to-client message: the server started; carries no payload. */
    public static final int MSG_SERVER_STARTED = 3;

    /** Service-to-client message: the server stopped; carries no payload. */
    public static final int MSG_SERVER_STOPPED = 4;

    /** Service-to-client message: {@code msg.obj} is the current server info snapshot. */
    public static final int MSG_SERVER_INFO_CHANGED = 5;

    /** Service-to-client message: {@code msg.obj} is the current server stats snapshot. */
    public static final int MSG_SERVER_STATS_CHANGED = 6;

    /** HTTP/2 flow-control window used by both the gRPC client and server (1 MiB). */
    public static final int GRPC_FLOW_CONTROL_WINDOW = 1024 * 1024;

    /** Maximum inbound message size accepted by both the gRPC client and server (8 MiB). */
    public static final int GRPC_MAX_INBOUND_MESSAGE_SIZE = 8 * 1024 * 1024;
}
