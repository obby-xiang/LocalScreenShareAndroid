package com.obby.android.localscreenshare.support;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class Constants {
    public static final String NSD_SERVICE_TYPE = "_localscreenshare._udp";

    public static final String NSD_SERVICE_ATTR_ID = "id";

    public static final String NSD_SERVICE_ATTR_NAME = "name";

    public static final String ACTION_WIFI_AP_STATE_CHANGED = "android.net.wifi.WIFI_AP_STATE_CHANGED";

    public static final String ACTION_STOP_LSS_SERVICE = "com.obby.android.localscreenshare.ACTION_STOP_LSS_SERVICE";

    public static final String EXTRA_MEDIA_PROJECTION_RESULT = "mediaProjectionResult";

    public static final int DEFAULT_LSS_SERVER_PORT = 8080;

    public static final int MSG_REGISTER_SERVICE_CLIENT = 1;

    public static final int MSG_UNREGISTER_SERVICE_CLIENT = 2;
}
