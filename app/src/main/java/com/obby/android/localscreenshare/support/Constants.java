package com.obby.android.localscreenshare.support;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class Constants {
    public static final int CPU_COUNT = Runtime.getRuntime().availableProcessors();

    @SuppressWarnings("SpellCheckingInspection")
    public static final String NSD_SERVICE_TYPE = "_localscreenshare._udp";

    public static final String NSD_SERVICE_ATTR_ID = "id";

    public static final String NSD_SERVICE_ATTR_NAME = "name";

    public static final String ACTION_WIFI_AP_STATE_CHANGED = "android.net.wifi.WIFI_AP_STATE_CHANGED";

    @SuppressWarnings("SpellCheckingInspection")
    public static final String ACTION_STOP_LSS_SERVICE = "com.obby.android.localscreenshare.ACTION_STOP_LSS_SERVICE";

    public static final String EXTRA_MEDIA_PROJECTION_RESULT = "mediaProjectionResult";

    public static final int DEFAULT_LSS_SERVER_PORT = 8080;

    public static final int MSG_REGISTER_SERVICE_CLIENT = 1;

    public static final int MSG_UNREGISTER_SERVICE_CLIENT = 2;

    public static final int MSG_SERVER_STARTED = 3;

    public static final int MSG_SERVER_STOPPED = 4;

    public static final int MSG_SERVER_INFO_CHANGED = 5;

    public static final int MSG_SERVER_STATS_CHANGED = 6;

    public static final int GRPC_FLOW_CONTROL_WINDOW = 8 * 1024 * 1024;

    public static final int GRPC_MAX_INBOUND_MESSAGE_SIZE = 8 * 1024 * 1024;
}
