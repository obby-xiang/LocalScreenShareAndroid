package com.obby.android.localscreenshare.support;

import android.os.Build;
import android.view.WindowManager;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * Process-wide contract between the activity and the two foreground services.
 *
 * <p>It covers the package-scoped broadcast actions, the intent extras, the overlay window type
 * and the Messenger message ids. Both sides of each contract read these values, so an action, an
 * extra or a message id has to change together with its sender and receiver.</p>
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ServiceProtocol {
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

    /**
     * Package-scoped broadcast reporting that the device identity was replaced. The capture
     * service restarts its multicast announcements with the new identity; it deliberately keeps
     * the running projection, because nothing in the media path depends on the identity.
     */
    @SuppressWarnings("SpellCheckingInspection")
    public static final String ACTION_IDENTITY_RESET =
        "com.obby.android.localscreenshare.ACTION_IDENTITY_RESET";

    /**
     * Package-scoped broadcast carrying one answer to a waiting viewing request.
     *
     * <p>It is sent by the approval notification's action buttons and received by a not-exported
     * receiver in the capture service. The request id and the answer travel as extras.</p>
     */
    @SuppressWarnings("SpellCheckingInspection")
    public static final String ACTION_AUTHORIZATION_DECISION =
        "com.obby.android.localscreenshare.ACTION_AUTHORIZATION_DECISION";

    /**
     * Intent extra carrying the MediaProjection consent result passed to the capture service.
     */
    public static final String EXTRA_MEDIA_PROJECTION_RESULT = "mediaProjectionResult";

    /**
     * Intent extra carrying the id of a waiting viewing request.
     */
    public static final String EXTRA_AUTHORIZATION_REQUEST_ID = "authorizationRequestId";

    /**
     * Intent extra carrying the answer name of an {@code ApprovalOutcome}.
     */
    public static final String EXTRA_AUTHORIZATION_OUTCOME = "authorizationOutcome";

    /**
     * Message extra carrying the display name of the device that is waiting for approval.
     */
    public static final String EXTRA_AUTHORIZATION_DEVICE_NAME = "authorizationDeviceName";

    /**
     * Message extra carrying the short id of the device that is waiting for approval.
     */
    public static final String EXTRA_AUTHORIZATION_DEVICE_ID = "authorizationDeviceId";

    /**
     * Message extra carrying the display form of the waiting device's fingerprint.
     */
    public static final String EXTRA_AUTHORIZATION_FINGERPRINT = "authorizationFingerprint";

    /**
     * Intent extra carrying the device the client service connects to.
     *
     * <p>The value is a {@code DeviceInfo}; its primary address is the endpoint and its id and
     * fingerprint bind the session to the discovered device.</p>
     */
    public static final String EXTRA_SERVICE_INFO = "serviceInfo";

    /**
     * Window type for overlay windows: {@code TYPE_APPLICATION_OVERLAY} from API 26, the older
     * {@code TYPE_SYSTEM_ALERT} below it. Both require the overlay permission.
     */
    public static final int FLOATING_WINDOW_TYPE = Build.VERSION.SDK_INT < Build.VERSION_CODES.O
        ? WindowManager.LayoutParams.TYPE_SYSTEM_ALERT : WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;

    /**
     * Client-to-service message: registers {@code msg.replyTo} for server state notifications.
     */
    public static final int MSG_REGISTER_SERVICE_CLIENT = 1;

    /**
     * Client-to-service message: removes {@code msg.replyTo} from the notification list.
     */
    public static final int MSG_UNREGISTER_SERVICE_CLIENT = 2;

    /**
     * Service-to-client message: the server started; carries no payload.
     */
    public static final int MSG_SERVER_STARTED = 3;

    /**
     * Service-to-client message: the server stopped; carries no payload.
     */
    public static final int MSG_SERVER_STOPPED = 4;

    /**
     * Service-to-client message: {@code msg.obj} is the current server info snapshot.
     */
    public static final int MSG_SERVER_INFO_CHANGED = 5;

    /**
     * Service-to-client message: {@code msg.obj} is the current server stats snapshot.
     */
    public static final int MSG_SERVER_STATS_CHANGED = 6;

    /**
     * Service-to-client message: a viewing request is waiting; {@code msg.getData()} carries the
     * request id, the device name, the device id short code and the fingerprint.
     */
    public static final int MSG_AUTHORIZATION_REQUEST = 7;

    /**
     * Service-to-client message: a waiting request was answered elsewhere or expired;
     * {@code msg.arg1} carries the request id.
     */
    public static final int MSG_AUTHORIZATION_FINISHED = 8;

    /**
     * Client-to-service message: the answer to a waiting request. {@code msg.arg1} carries the
     * request id and {@code msg.arg2} the ordinal of an {@code ApprovalOutcome}.
     */
    public static final int MSG_AUTHORIZATION_DECISION = 9;
}
