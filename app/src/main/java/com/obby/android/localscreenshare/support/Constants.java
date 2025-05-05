package com.obby.android.localscreenshare.support;

import android.os.Build;
import android.view.WindowManager;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class Constants {
    public static final int CPU_COUNT = Runtime.getRuntime().availableProcessors();

    public static final String UNKNOWN_HOST_ADDRESS = "N/A";

    @SuppressWarnings("SpellCheckingInspection")
    public static final String NSD_SERVICE_TYPE = "_localscreenshare._udp";

    public static final String NSD_SERVICE_ATTR_ID = "id";

    public static final String NSD_SERVICE_ATTR_NAME = "name";

    public static final String ACTION_WIFI_AP_STATE_CHANGED = "android.net.wifi.WIFI_AP_STATE_CHANGED";

    @SuppressWarnings("SpellCheckingInspection")
    public static final String ACTION_STOP_SERVICE = "com.obby.android.localscreenshare.ACTION_STOP_SERVICE";

    @SuppressWarnings("SpellCheckingInspection")
    public static final String ACTION_STOP_CLIENT_SERVICE =
        "com.obby.android.localscreenshare.ACTION_STOP_CLIENT_SERVICE";

    public static final String EXTRA_MEDIA_PROJECTION_RESULT = "mediaProjectionResult";

    public static final String EXTRA_SERVICE_INFO = "serviceInfo";

    public static final int FLOATING_WINDOW_TYPE = Build.VERSION.SDK_INT < Build.VERSION_CODES.O
        ? WindowManager.LayoutParams.TYPE_SYSTEM_ALERT : WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;

    public static final int MSG_REGISTER_SERVICE_CLIENT = 1;

    public static final int MSG_UNREGISTER_SERVICE_CLIENT = 2;

    public static final int MSG_SERVER_STARTED = 3;

    public static final int MSG_SERVER_STOPPED = 4;

    public static final int MSG_SERVER_INFO_CHANGED = 5;

    public static final int MSG_SERVER_STATS_CHANGED = 6;

    public static final int GRPC_FLOW_CONTROL_WINDOW = 8 * 1024 * 1024;

    public static final int GRPC_MAX_INBOUND_MESSAGE_SIZE = 8 * 1024 * 1024;
}
