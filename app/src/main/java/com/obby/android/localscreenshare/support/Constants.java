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

    /** DNS-SD service type used for both registration and discovery. */
    @SuppressWarnings("SpellCheckingInspection")
    public static final String NSD_SERVICE_TYPE = "_localscreenshare._udp";

    /** TXT attribute key holding the server id as UTF-8; discovery drops services without it. */
    public static final String NSD_SERVICE_ATTR_ID = "id";

    /** TXT attribute key holding the user-visible server name as UTF-8. */
    public static final String NSD_SERVICE_ATTR_NAME = "name";

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
