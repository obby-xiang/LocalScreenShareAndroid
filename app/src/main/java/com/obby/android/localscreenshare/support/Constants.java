package com.obby.android.localscreenshare.support;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class Constants {
    public static final String NSD_SERVICE_TYPE = "_localscreenshare._udp";

    public static final String NSD_SERVICE_ATTR_ID = "id";

    public static final String NSD_SERVICE_ATTR_NAME = "name";

    public static final String ACTION_STOP_LSS_SERVICE = "com.obby.android.localscreenshare.ACTION_STOP_LSS_SERVICE";

    public static final String EXTRA_MEDIA_PROJECTION_RESULT = "mediaProjectionResult";
}
