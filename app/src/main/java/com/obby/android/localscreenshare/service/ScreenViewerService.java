package com.obby.android.localscreenshare.service;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.DrawFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PaintFlagsDrawFilter;
import android.graphics.PixelFormat;
import android.graphics.PointF;
import android.graphics.Rect;
import android.os.Build;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.text.format.Formatter;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Size;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.TextureView;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.view.ContextThemeWrapper;
import androidx.core.app.NotificationChannelCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;
import androidx.core.text.HtmlCompat;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.imageview.ShapeableImageView;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.shape.CornerFamily;
import com.google.android.material.shape.MaterialShapeDrawable;
import com.google.android.material.shape.MaterialShapeUtils;
import com.google.android.material.shape.ShapeAppearanceModel;
import com.google.android.material.slider.LabelFormatter;
import com.google.android.material.slider.Slider;
import com.obby.android.localscreenshare.MainActivity;
import com.obby.android.localscreenshare.ViewerActivity;
import com.obby.android.localscreenshare.R;
import com.obby.android.localscreenshare.core.audio.AudioLog;
import com.obby.android.localscreenshare.core.audio.ClientAudioSession;
import com.obby.android.localscreenshare.core.audio.SessionClock;
import com.obby.android.localscreenshare.core.audio.mediacodec.AndroidAudioDecoder;
import com.obby.android.localscreenshare.core.audio.mediacodec.AudioTrackSink;
import com.obby.android.localscreenshare.authorization.AuthorizationPolicy;
import com.obby.android.localscreenshare.client.ClientMediaReceiver;
import com.obby.android.localscreenshare.client.ClientSecureListener;
import com.obby.android.localscreenshare.client.CodecCapabilityProbe;
import com.obby.android.localscreenshare.client.KeyFrameRequestThrottle;
import com.obby.android.localscreenshare.client.ReconnectPolicy;
import com.obby.android.localscreenshare.client.ScreenShareClient;
import com.obby.android.localscreenshare.client.ClientObserver;
import com.obby.android.localscreenshare.client.ClientStats;
import com.obby.android.localscreenshare.client.ClientStatsListener;
import com.obby.android.localscreenshare.client.StreamFailure;
import com.obby.android.localscreenshare.client.StreamGeometryListener;
import com.obby.android.localscreenshare.core.client.ClientMediaSession;
import com.obby.android.localscreenshare.core.client.ClientStateListener;
import com.obby.android.localscreenshare.core.codec.CodecRegistry;
import com.obby.android.localscreenshare.core.codec.h264.H264Codec;
import com.obby.android.localscreenshare.core.metrics.ArrivalTracker;
import com.obby.android.localscreenshare.core.metrics.DeviceProfileSampler;
import com.obby.android.localscreenshare.core.metrics.MetricsFormatter;
import com.obby.android.localscreenshare.core.metrics.PipelineMetrics;
import com.obby.android.localscreenshare.core.metrics.RateSampler;
import com.obby.android.localscreenshare.core.model.ClientSessionKey;
import com.obby.android.localscreenshare.core.model.ClientState;
import com.obby.android.localscreenshare.core.model.StreamKey;
import com.obby.android.localscreenshare.core.stream.ClientCapabilities;
import com.obby.android.localscreenshare.core.util.NanoClock;
import com.obby.android.localscreenshare.core.util.TimeSource;
import com.obby.android.localscreenshare.discovery.favorite.FavoriteStore;
import com.obby.android.localscreenshare.discovery.model.DeviceAddress;
import com.obby.android.localscreenshare.discovery.model.DeviceInfo;
import com.obby.android.localscreenshare.render.BitmapRenderTarget;
import com.obby.android.localscreenshare.render.HostRenderTarget;
import com.obby.android.localscreenshare.render.SurfaceRenderTarget;
import com.obby.android.localscreenshare.render.SwitchableRenderTarget;
import com.obby.android.localscreenshare.render.ViewerFitPolicy;
import com.obby.android.localscreenshare.render.ViewerHost;
import com.obby.android.localscreenshare.render.ViewerMenuFactory;
import com.obby.android.localscreenshare.render.ViewerPanelView;
import com.obby.android.localscreenshare.render.ViewerZoomMenuModel;
import com.obby.android.localscreenshare.render.ZoomMode;
import com.obby.android.localscreenshare.settings.Preferences;
import com.obby.android.localscreenshare.support.MutableReference;
import com.obby.android.localscreenshare.support.OverlayWindow;
import com.obby.android.localscreenshare.support.ResourceUtils;
import com.obby.android.localscreenshare.support.ServiceProtocol;

import org.apache.commons.lang3.math.NumberUtils;
import org.apache.commons.lang3.mutable.MutableBoolean;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Foreground service that connects to one discovered server and shows the received frames in a
 * floating viewer.
 *
 * <p><b>Lifecycle.</b> Started by {@link MainActivity} with
 * {@link ServiceProtocol#EXTRA_SERVICE_INFO} after the notification and overlay permissions have been
 * granted. {@link #onStartCommand(Intent, int, int)} creates the notification channel, promotes
 * the service to the foreground with the remote-messaging type, resets any previous session,
 * creates an {@link ScreenShareClient} and shows the loading overlay before the client connects. A
 * repeated start for the same target leaves the running session alone; a start for a different
 * target replaces it. {@link #stopService()} runs the one-shot {@link TeardownCoordinator} that
 * releases the client, the overlays and the foreground notification and then calls
 * {@link #stopSelf()}; {@link #onDestroy()} runs the same teardown as a fallback for a destruction
 * without that path and only then drops the instance reference and unregisters the stop
 * receiver.</p>
 *
 * <p><b>Connection state.</b> The surface viewer is mounted when the session starts so its texture
 * view acquires a surface and the decoder can be created; the loading overlay is shown on top until
 * the first frame arrives. The first frame drives the session into {@code STREAMING}, which dismisses
 * the loading overlay; a later disconnect, or a failed first connection, closes the client, shows a
 * floating confirmation and offers a retry. The retry creates a new client because an
 * {@link ScreenShareClient} is single-use. A refused peer identity is reported separately: the
 * failure carries the reason, so the dialog explains the security check and ends the session
 * instead of offering a retry that would fail the same check. Cancelling either confirmation
 * broadcasts {@link ServiceProtocol#ACTION_STOP_CLIENT_SERVICE}.</p>
 *
 * <p><b>Threading and ownership.</b> All service callbacks and {@link ClientObserver}
 * callbacks run on the main thread; {@link ScreenShareClient} decodes frames on its own executor and
 * delivers them on the main thread. A received frame arrives as a {@link MutableReference} owned by the
 * viewer, which clears the previous frame when it stores a new one. All overlay windows are added
 * and removed on the main thread and require the overlay permission checked by
 * {@link MainActivity}.</p>
 */
public class ScreenViewerService extends Service {
    private static final String NOTIFICATION_CHANNEL_ID = "lss-client-service";

    private static final int NOTIFICATION_ID = 2;

    /**
     * Running instance, used only by {@link #onViewerActivityCreated()} to cancel the expansion
     * watchdogs from the activity's {@code onCreate}.
     *
     * <p>Main thread only: both the service callbacks and the activity that reports the creation run
     * there. Null before {@link #onCreate()} and after {@link #onDestroy()}; a process holds at most
     * one instance because the manifest declares a single service component.</p>
     */
    @Nullable
    private static ScreenViewerService sInstance;

    // Cadence of the structured metric log line; slower than the three-second UI statistics.
    private static final long UPDATE_METRICS_INTERVAL_MS = 5000L;

    // Cadence of the debug overlay; only runs while the overlay is enabled.
    private static final long UPDATE_HUD_INTERVAL_MS = 1000L;

    // Moment at which an expansion request that has not reached attachHost() yet is logged. The
    // activity binds in onStart, which this deadline comfortably covers on a warm start; a miss
    // here is the first sign of a launch that will not complete.
    private static final long ACTIVITY_ATTACH_EXPECTED_MS = 200L;

    // Deadline for the same request. The floating window keeps the picture for the whole switch, so
    // a slow launch is not visible as a failure; the user is told only once the activity was never
    // even created. The value covers the launch hand-off on the slowest tested device, where a
    // working switch takes seconds.
    private static final long ACTIVITY_ATTACH_TIMEOUT_MS = 2000L;

    // The device block is logged every other metric line, that is every ten seconds.
    private static final int DEVICE_BLOCK_EVERY_N_LOGS = 2;

    // Target of the current session; null while stopped.
    @Nullable
    private DeviceInfo mServiceInfo;

    // Single-use client; replaced by a new instance on retry and nulled on stop.
    @Nullable
    private ScreenShareClient mClient;

    // One-shot release of one session; rebuilt for every accepted start and run by stopService()
    // and onDestroy().
    @Nullable
    private TeardownCoordinator mTeardown;

    // Last stats sample received on the main thread.
    @Nullable
    private ClientStats mClientStats;

    // Overlay shown while connecting; the same instance is reused across retries.
    @Nullable
    private ScreenShareLoading mScreenShareLoading;

    // Overlay shown once frames arrive; created on the first connect.
    @Nullable
    private ScreenShareViewer mScreenShareViewer;

    // Media session wiring: the registry resolves the active codec's decoder, the session drives
    // it, and the receiver feeds it from the wire on the client's dedicated decode thread.
    @Nullable
    private CodecRegistry mCodecRegistry;

    @Nullable
    private StreamKey mStreamKey;

    // Output target of the floating window: a surface half for H.264 and a bitmap half for JPEG;
    // released when the session is torn down.
    @Nullable
    private HostRenderTarget mOverlayRenderTarget;

    // Stable target handed to the session. Its delegate is the display host that currently owns the
    // decoder output, which is what makes a window <-> activity switch a decoder rebuild instead of
    // a reconnect.
    @Nullable
    private SwitchableRenderTarget mRenderTarget;

    @Nullable
    private ClientMediaSession mClientMediaSession;

    // Capabilities announced in the HELLO and enforced by the media session. Probed once per service
    // instance and reused by retries, because the device's decoders cannot change between attempts
    // and the server must never be offered a codec this session would refuse.
    @Nullable
    private ClientCapabilities mAdvertisedCapabilities;

    // Optional audio track of the viewing session. Created with the media session and released with
    // it; audio never shares state with the video session.
    @Nullable
    private ClientAudioSession mClientAudioSession;

    // Why the viewer has no sound, or null while it is fine; shown in the overlay in A6.
    @Nullable
    private String mAudioUnavailableReason;

    // Local mute of the audio track; the stream keeps arriving, only the volume changes.
    private boolean mAudioMuted;

    // One timeline for audio and video of the current viewing session. Reset when a new media
    // session starts; every other reset (video flush, audio reconfiguration) is deliberately not
    // forwarded to it.
    @NonNull
    private final SessionClock mSessionClock = new SessionClock();

    @Nullable
    private ClientMediaReceiver mClientMediaReceiver;

    // Surface viewer mounted as soon as the session starts so its texture view exposes a surface;
    // created on the first media-session start and reused across retries.
    @Nullable
    private SurfaceShareViewer mSurfaceShareViewer;

    // Full-screen host while the session is expanded; null while the floating window owns the
    // output. Only one host owns the decoder output at a time.
    @Nullable
    private ViewerHost mActivityHost;

    // Output target of the activity host; recreated for every attach because an activity instance
    // brings its own texture view.
    @Nullable
    private HostRenderTarget mActivityRenderTarget;

    // Last frame captured for the incoming host; taken once by that host and then dropped.
    @Nullable
    private Bitmap mTransitionSnapshot;

    // Latest stream state pushed by the receiver, forwarded to a host that attaches later: the
    // descriptor arrives once per connection, so a host that misses it would stay blank.
    private int mContentWidth;

    private int mContentHeight;

    private int mRotationDegrees;

    private boolean mIsSecure;

    @NonNull
    private List<String> mHudLines = Collections.emptyList();

    // True once the activity finished its entry animation; the floating window stays mounted until
    // both this flag and the first decoded frame are set, so the switch never shows black.
    private boolean mIsActivityTransitionDone;

    // Disconnect confirmation; created on demand and dismissed on stop.
    @Nullable
    private AlertDialog mDialog;

    // Distinguishes a lost connection from a failed first connection when choosing the message.
    private boolean mIsConnected;

    // Last rate rendered into the notification; the notification is rebuilt only on change.
    private long mInboundDataRate;

    // Metric scope of the receiving pipeline; only created when the preference is on. The receive
    // tracker is recreated per session because its baseline belongs to one stream.
    @Nullable
    private PipelineMetrics mMetrics;

    // One sampler per reader: the overlay refreshes every second and the log line every five, so a
    // shared baseline would make the five-second line report the last one-second sliver.
    @Nullable
    private RateSampler mHudRateSampler;

    @Nullable
    private RateSampler mLogRateSampler;

    @NonNull
    private final Runnable mMetricsLogRunnable = this::logMetrics;

    // Refreshes the debug overlay; reschedules itself only while the overlay stays enabled.
    @NonNull
    private final Runnable mHudRunnable = this::updateHud;

    // Logs an expansion request that is slower than ACTIVITY_ATTACH_EXPECTED_MS; the deadline below
    // confirms the failure.
    @NonNull
    private final Runnable mActivityAttachSlowRunnable = this::onActivityAttachSlow;

    // Reports an expansion request whose activity never attached and leaves the window in charge.
    @NonNull
    private final Runnable mActivityAttachTimeoutRunnable = this::onActivityAttachTimeout;

    // Last resynchronization reason rendered, so the reason is logged once per change.
    @Nullable
    private ClientMediaSession.ResyncReason mLoggedResyncReason;

    // Counts metric log lines so the device block is emitted every ten seconds.
    private int mMetricsLogCount;

    // Device readings emitted with the metric lines; platform lookups live in the service layer.
    // Created on the first sample: a field initialiser runs before attachBaseContext, and the
    // sampler reads the context in its constructor.
    @NonNull
    private final LazyContextValue<DeviceProfileSampler> mDeviceProfileSampler =
        new LazyContextValue<>(this::getApplicationContext, AndroidDeviceProfileSampler::new);

    private final String mTag = "ScreenViewerService@" + hashCode();

    // Main-thread delivery for decode-thread callbacks that touch the UI.
    @NonNull
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    // Backoff of the reconnect prompt; a successful connection starts the schedule over.
    @NonNull
    private final ReconnectPolicy mReconnectPolicy = new ReconnectPolicy();

    // Pending automatic reconnect and its countdown. The teardown removes both, so a retry cannot
    // start a session after the viewer was stopped.
    @Nullable
    private Runnable mReconnectRunnable;

    @Nullable
    private Runnable mRetryCountdownRunnable;

    // Limits key-frame requests raised by decode failures and queue overflows.
    @NonNull
    private final KeyFrameRequestThrottle mKeyFrameRequestThrottle = new KeyFrameRequestThrottle();

    @NonNull
    private final ClientStatsListener mClientStatsListener = this::onClientStatsChanged;

    // Client callbacks are delivered on the main thread; see ScreenShareClient.
    @NonNull
    private final ClientObserver mClientObserver = new ClientObserver() {
        @Override
        public void onConnected() {
            mIsConnected = true;
            // A working connection is what ends a failure streak: the next drop starts at the
            // immediate retry again.
            mReconnectPolicy.reset();
            // The address that just worked becomes the first favorite probe next time.
            if (mServiceInfo != null) {
                FavoriteStore.get().recordSuccess(mServiceInfo.getId(),
                    hostOf(mServiceInfo), portOf(mServiceInfo),
                    TimeSource.MONOTONIC.nowMs());
            }
        }

        @SuppressWarnings("DataFlowIssue")
        @Override
        public void onDisconnected(@NonNull final StreamFailure failure) {
            // A refused identity or a refused viewing request is not a lost connection: the same
            // check fails on every retry, so the dialog explains the refusal and offers no retry.
            // Trust is confirmed in the activity, where the fingerprint dialog lives; a viewing
            // request is answered on the sharing device, so the only action here is to end the
            // session and let the user ask again from the list.
            final boolean isIdentityRejected = failure.isIdentityRejected();
            final boolean isAuthorizationRejected = failure.isAuthorizationRejected();
            final boolean isRefused = isIdentityRejected || isAuthorizationRejected;
            Log.w(mTag, "onDisconnected: session ended, identityRejected = " + isIdentityRejected
                + ", authorizationRejected = " + isAuthorizationRejected
                + ", " + failure.toLogText());

            // A new disconnect supersedes whatever the previous prompt scheduled.
            cancelPendingRetry();

            // One attempt is consumed per disconnect, so the schedule advances even when the user
            // dismisses the dialog and reconnects from the list. A refused connection never
            // consumes one: it is not a transient failure and is not retried.
            final boolean canRetry = !isRefused && mReconnectPolicy.canRetry();
            final long retryDelayMs = canRetry ? mReconnectPolicy.nextDelayMs() : 0L;

            // A session that was connected reports a reconnect prompt; a failed first connection
            // reports a retry prompt; an exhausted schedule reports that the peer stayed
            // unreachable. A refusal reports its own reason instead.
            final int messageResId;
            if (isIdentityRejected) {
                messageResId = R.string.screen_share_identity_rejected_message;
            } else if (isAuthorizationRejected) {
                messageResId = authorizationRejectedMessage(failure.getReason());
            } else if (!canRetry) {
                messageResId = R.string.screen_share_retry_exhausted_message;
            } else {
                messageResId = mIsConnected ? R.string.screen_share_reconnect_confirm
                    : R.string.screen_share_retry_connect_confirm;
            }
            mIsConnected = false;
            mClientStats = null;

            updateInboundDataRate(0L);

            if (mDialog != null) {
                OverlayWindow.dismiss(mDialog);
            }

            if (mScreenShareLoading != null) {
                mScreenShareLoading.dismiss();
            }

            // Close the decoder before releasing the surface, so a streaming decoder does not
            // cancel buffers on a texture that is already being abandoned.
            teardownMediaSession();

            if (mSurfaceShareViewer != null) {
                mSurfaceShareViewer.dismiss();
            }

            if (mActivityHost != null) {
                // The expanded host keeps the last frame; the retry decision is a floating dialog.
                mActivityHost.hideSnapshot();
            }

            if (mClient != null) {
                mClient.stop();
                mClient = null;
            }

            // An ScreenShareClient cannot be restarted, so a retry builds a new instance and shows the
            // loading overlay again. The confirmation is a floating window because the activity
            // may not be visible.
            final Context themedContext = new ContextThemeWrapper(ScreenViewerService.this, R.style.Theme_ScreenShare);
            final MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(themedContext)
                .setMessage(HtmlCompat.fromHtml(isRefused || !canRetry
                        ? getString(messageResId)
                        : getString(messageResId, nameOf(mServiceInfo)),
                    HtmlCompat.FROM_HTML_MODE_LEGACY))
                .setCancelable(false);
            if (isRefused) {
                builder.setTitle(nameOf(mServiceInfo))
                    .setPositiveButton(android.R.string.ok, (dialog, which) -> sendBroadcast(
                        new Intent(ServiceProtocol.ACTION_STOP_CLIENT_SERVICE).setPackage(getPackageName())));
            } else if (canRetry) {
                builder.setTitle(nameOf(mServiceInfo))
                    .setPositiveButton(android.R.string.ok, (dialog, which) ->
                        scheduleRetry((AlertDialog) dialog, retryDelayMs))
                    .setNegativeButton(android.R.string.cancel, (dialog, which) -> {
                        cancelPendingRetry();
                        sendBroadcast(new Intent(ServiceProtocol.ACTION_STOP_CLIENT_SERVICE)
                            .setPackage(getPackageName()));
                    });
            } else {
                // The schedule is exhausted: the only remaining action ends the session, and the
                // list is where the user starts a fresh one.
                builder.setTitle(nameOf(mServiceInfo))
                    .setPositiveButton(R.string.viewer_action_stop_viewing, (dialog, which) ->
                        sendBroadcast(new Intent(ServiceProtocol.ACTION_STOP_CLIENT_SERVICE)
                            .setPackage(getPackageName())));
            }
            mDialog = builder.create();
            mDialog.getWindow().setType(ServiceProtocol.FLOATING_WINDOW_TYPE);
            OverlayWindow.show(mDialog);
        }

        @Override
        public void onApprovalPending(final int timeoutSeconds) {
            // The server parked the request; the stream stays open, so the caption explains the
            // wait instead of leaving the spinner saying "connecting" forever.
            Log.i(mTag, "onApprovalPending: waiting for the sharing device to approve, timeout = "
                + timeoutSeconds);
            if (mScreenShareLoading == null) {
                return;
            }
            mScreenShareLoading.setMessage(timeoutSeconds > 0
                ? getString(R.string.screen_share_authorization_pending_seconds, timeoutSeconds)
                : getString(R.string.screen_share_authorization_pending));
        }
    };

    // Runs on the client's decode thread; UI work is posted back to the main thread.
    @NonNull
    private final ClientStateListener mClientStateListener = new ClientStateListener() {
        @Override
        public void onStateChanged(@NonNull ClientState previous, @NonNull ClientState current) {
            if (current == ClientState.RESYNCING) {
                // A decode failure or a missing surface broke the reference chain; ask for a fresh
                // join point instead of waiting for the next periodic key frame.
                mMainHandler.post(ScreenViewerService.this::requestKeyFrame);
                return;
            }
            if (current != ClientState.STREAMING) {
                return;
            }
            mMainHandler.post(() -> {
                if (mScreenShareLoading != null) {
                    mScreenShareLoading.dismiss();
                }
                final ViewerHost host = mActivityHost;
                if (host != null) {
                    // The expanded host has live frames again; the snapshot and the floating window
                    // are no longer needed. The window is only removed once the entry animation
                    // finished, so a fast decoder cannot cut the transition short.
                    host.hideSnapshot();
                    removeOverlayIfTransitionFinished();
                    return;
                }
                if (mSurfaceShareViewer != null) {
                    mSurfaceShareViewer.show();
                    mSurfaceShareViewer.fadeOutSnapshot();
                }
            });
        }
    };

    // Runs on the client's decode thread; applies the secure flag on the main thread.
    @NonNull
    private final ClientSecureListener mClientSecureListener = new ClientSecureListener() {
        @Override
        public void onSecureChanged(final boolean secure) {
            mMainHandler.post(() -> applySecure(secure));
        }
    };

    // Stops the session when the notification action or a floating confirmation broadcasts it.
    @NonNull
    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ServiceProtocol.ACTION_STOP_CLIENT_SERVICE.equals(intent.getAction())) {
                stopService();
            }
        }
    };

    /**
     * Registers the receiver that backs the stop action of the notification and of the floating
     * confirmations.
     */
    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(mTag, "onCreate: service created");
        sInstance = this;

        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(ServiceProtocol.ACTION_STOP_CLIENT_SERVICE);
        ContextCompat.registerReceiver(this, mBroadcastReceiver, intentFilter, ContextCompat.RECEIVER_NOT_EXPORTED);

        ensureMetrics();

        // The order is fixed; onStartCommand replaces the coordinator for every accepted start, so
        // each session owns exactly one teardown and the first stop path releases it.
        mTeardown = createTeardownCoordinator();
    }

    /**
     * Reports that the full-screen activity was created, which cancels the expansion watchdogs.
     *
     * <p>The activity runs in this process, so it can hand this signal over directly instead of
     * going through the binder: a launch that reaches {@code onCreate} has succeeded, however long
     * the bind and attach take afterwards. Without that distinction a slow device would show the
     * failure message for a switch that works - the emulator used for the device test needs several
     * seconds between the start request and the attach.</p>
     *
     * <p>Called from {@code ViewerActivity.onCreate}, on the main thread. A null instance means no
     * session is running, in which case there is nothing to cancel.</p>
     */
    public static void onViewerActivityCreated() {
        final ScreenViewerService service = sInstance;
        if (service != null) {
            service.cancelActivityAttachWatchdogs();
        }
    }

    /**
     * Creates the receiving metric scope and its two readers on first use.
     *
     * <p>The scope lives as long as the service, so a reconnect keeps the same windows and a
     * cumulative counter never restarts mid-session. A disabled preference yields
     * {@link PipelineMetrics#NONE} and no sampler, which keeps both readers free of state.</p>
     */
    private void ensureMetrics() {
        if (mMetrics != null) {
            return;
        }
        mMetrics = Preferences.get().isMetricsEnabled()
            ? PipelineMetrics.create(NanoClock.MONOTONIC)
            : PipelineMetrics.NONE;
        mHudRateSampler = mMetrics.isEnabled() ? new RateSampler(mMetrics) : null;
        mLogRateSampler = mMetrics.isEnabled() ? new RateSampler(mMetrics) : null;
    }

    /**
     * Finishes the teardown and releases what outlives a session.
     *
     * <p>{@link #stopService()} normally ran the teardown already; running it here as well covers a
     * service destroyed without that path, and the coordinator turns the second call into a no-op.
     * The instance reference and the expansion watchdogs belong to the service rather than to the
     * session, so they are dropped here.</p>
     */
    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.i(mTag, "onDestroy: service destroyed");
        runTeardown();
        cancelActivityAttachWatchdogs();
        sInstance = null;

        unregisterReceiver(mBroadcastReceiver);
    }

    /**
     * Returns the local binder the full-screen activity uses to attach itself as a display host.
     *
     * <p>The service is started by {@link MainActivity}; the viewer activity only binds, so binding
     * never creates a second session. A null binder is never returned: the activity treats a
     * session-less service as "finish" through
     * {@link ViewerBinder#attachHost(ViewerHost)} instead.</p>
     */
    @NonNull
    @Override
    public IBinder onBind(Intent intent) {
        Log.i(mTag, "onBind: viewer activity bound");
        return new ViewerBinder();
    }

    /**
     * Starts or replaces the viewing session.
     *
     * <p>A null intent carries no target (for example after a system restart) and a repeated start
     * for the same running target is ignored; both return {@code START_NOT_STICKY}. A new target
     * tears the previous session down and starts a fresh client. The service is promoted to the
     * foreground before any overlay is created, and a foreground-start failure stops it.</p>
     */
    @SuppressWarnings("DataFlowIssue")
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(mTag, String.format("onStartCommand: startId = %d", startId));

        if (intent == null) {
            // A system restart after a crash delivers a null intent; stop the zombie foreground
            // service and clear its notification instead of leaving it running without a target.
            Log.w(mTag, "onStartCommand: intent is null");
            stopService();
            return START_NOT_STICKY;
        }

        final DeviceInfo serviceInfo = intent.getParcelableExtra(ServiceProtocol.EXTRA_SERVICE_INFO);
        if (serviceInfo == null || serviceInfo.primaryAddress() == null) {
            Log.w(mTag, "onStartCommand: no usable device in the intent");
            stopService();
            return START_NOT_STICKY;
        }
        if (isSameTarget(serviceInfo) && mClient != null) {
            Log.w(mTag, "onStartCommand: client already running");
            return START_NOT_STICKY;
        }

        // A new start can arrive before the previous stop reached onDestroy, in which case the old
        // coordinator is already spent; the new session needs its own one-shot teardown or its stop
        // would release nothing.
        mTeardown = createTeardownCoordinator();

        // A retry the previous prompt scheduled must not start a second session next to this one,
        // and a session the user starts from the list gets the full retry schedule again.
        cancelPendingRetry();
        mReconnectPolicy.reset();

        mServiceInfo = serviceInfo;
        mIsConnected = false;
        mClientStats = null;
        mInboundDataRate = 0L;

        createNotificationChannel();

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING);
            } else {
                startForeground(NOTIFICATION_ID, buildNotification());
            }
        } catch (Exception e) {
            Log.e(mTag, "onStartCommand: start foreground failed", e);
            stopService();
            return START_NOT_STICKY;
        }

        // Replace the previous session only after the foreground notification succeeded.
        if (mDialog != null) {
            OverlayWindow.dismiss(mDialog);
            mDialog = null;
        }

        if (mScreenShareLoading != null) {
            mScreenShareLoading.dismiss();
        }

        if (mClient != null) {
            mClient.stop();
        }

        teardownMediaSession();

        if (mSurfaceShareViewer != null) {
            mSurfaceShareViewer.dismiss();
            mSurfaceShareViewer = null;
        }

        final ClientMediaReceiver receiver = startMediaSession();
        mClient = new ScreenShareClient(hostOf(mServiceInfo), portOf(mServiceInfo), viewerMetrics(),
                    mServiceInfo.getId(), advertisedCapabilities());
        mClient.setClientStatsListener(mClientStatsListener);
        mScreenShareLoading = new ScreenShareLoading(this, mServiceInfo);
        mScreenShareLoading.show();
        mClient.start(mClientObserver, receiver);

        return START_STICKY;
    }

    /**
     * Returns whether {@code candidate} targets the session already running.
     *
     * <p>Only the identity and the primary address are compared: the store snapshot carries
     * timestamps that change on every observation, and a changed timestamp must not restart a
     * healthy session.</p>
     */
    private boolean isSameTarget(@NonNull final DeviceInfo candidate) {
        if (mServiceInfo == null) {
            return false;
        }
        final DeviceAddress previous = mServiceInfo.primaryAddress();
        final DeviceAddress next = candidate.primaryAddress();
        return mServiceInfo.getId().equals(candidate.getId())
            && Objects.equals(previous, next);
    }

    /**
     * Returns the host of the device to dial, or an empty string when it has no address.
     */
    @NonNull
    private static String hostOf(@Nullable final DeviceInfo device) {
        final DeviceAddress address = device == null ? null : device.primaryAddress();
        return address == null ? "" : address.getHost();
    }

    /**
     * Returns the port of the device to dial, or {@code 0} when it has no address.
     */
    private static int portOf(@Nullable final DeviceInfo device) {
        final DeviceAddress address = device == null ? null : device.primaryAddress();
        return address == null ? 0 : address.getPort();
    }

    /**
     * Returns the display name of the device, never null.
     */
    @NonNull
    private static String nameOf(@Nullable final DeviceInfo device) {
        return device == null ? "" : device.displayName();
    }

    /**
     * Returns the message resource of one authorization refusal.
     *
     * <p>Each reason tells the viewer whether the sharing device said no, never answered, or took
     * the permission away while the stream was running; the three are different situations for the
     * person holding the phone.</p>
     */
    private static int authorizationRejectedMessage(@NonNull final String reason) {
        if (AuthorizationPolicy.REASON_DENIED.equals(reason)) {
            return R.string.screen_share_authorization_denied_message;
        }
        if (AuthorizationPolicy.REASON_REVOKED.equals(reason)) {
            return R.string.screen_share_authorization_revoked_message;
        }
        return R.string.screen_share_authorization_timeout_message;
    }

    /**
     * Stops the viewing session by running the one-shot teardown, then stops the service.
     *
     * <p>Runs on the main thread and is reachable from the notification action, the floating
     * confirmations, a failed start and a lost intent. The release order lives in
     * {@link #createTeardownCoordinator()} so {@link #onDestroy()} cannot release a different
     * subset; a second call only asks the service to stop again.</p>
     */
    private void stopService() {
        Log.i(mTag, "stopService: stop service");
        if (mTeardown == null || mTeardown.isDone()) {
            return;
        }
        runTeardown();
        stopSelf();
    }

    /**
     * Builds the ordered release of one viewing session.
     *
     * <p>The order is the one {@code stopService()} executed directly before this coordinator
     * existed: state, scheduled main-thread work, dialogs and overlays, the expanded host, the media
     * session, the floating viewer, the client and finally the foreground notification. The client
     * is stopped before it is dropped, and the media session is closed before its render targets are
     * released. Every step is idempotent on its own, so a partially started session can run the same
     * list.</p>
     *
     * @return the coordinator; never null
     */
    @NonNull
    private TeardownCoordinator createTeardownCoordinator() {
        return new TeardownCoordinator(Arrays.asList(
            TeardownCoordinator.Step.of("reset-session-state", this::resetSessionState),
            TeardownCoordinator.Step.of("cancel-main-thread-work", this::cancelScheduledWork),
            TeardownCoordinator.Step.of("dismiss-dialog", this::dismissConfirmation),
            TeardownCoordinator.Step.of("dismiss-loading-overlay", this::dismissLoadingOverlay),
            TeardownCoordinator.Step.of("release-activity-host", this::releaseActivityHost),
            TeardownCoordinator.Step.of("teardown-media-session", this::teardownMediaSession),
            TeardownCoordinator.Step.of("dismiss-viewer-overlay", this::dismissViewerOverlay),
            TeardownCoordinator.Step.of("stop-client", this::stopClient),
            TeardownCoordinator.Step.of("remove-notification", this::removeForegroundNotification)));
    }

    /**
     * Runs the teardown and logs the steps that failed; never throws.
     */
    private void runTeardown() {
        final TeardownCoordinator teardown = mTeardown;
        if (teardown == null) {
            return;
        }
        for (TeardownCoordinator.StepFailure failure : teardown.run()) {
            Log.w(mTag, "teardown: step failed: " + failure.getStepName(), failure.getCause());
        }
    }

    /**
     * Clears the session state the notification and the activity read.
     */
    private void resetSessionState() {
        mServiceInfo = null;
        mIsConnected = false;
        mClientStats = null;
        mInboundDataRate = 0L;
    }

    /**
     * Drops every main-thread task that would otherwise re-arm itself, retry the connection or
     * report a stale expansion, then releases the metric scope.
     */
    private void cancelScheduledWork() {
        mMainHandler.removeCallbacks(mMetricsLogRunnable);
        mMainHandler.removeCallbacks(mHudRunnable);
        cancelActivityAttachWatchdogs();
        cancelPendingRetry();
        mMetrics = null;
        mHudRateSampler = null;
        mLogRateSampler = null;
        mLoggedResyncReason = null;
        mMetricsLogCount = 0;
    }

    /**
     * Schedules the retry behind its backoff delay and shows the wait on the button.
     *
     * <p>The user asks for the retry, the confirmation stays open, the button is disabled and its
     * label counts the remaining seconds down. The connection starts from the main-thread callback
     * after the delay, so a stop in the meantime removes the callback through
     * {@link #cancelPendingRetry()} and no session can start after the viewer was closed.</p>
     *
     * @param dialog  confirmation that asked for the retry; required
     * @param delayMs backoff delay of this attempt
     */
    private void scheduleRetry(@NonNull final AlertDialog dialog, final long delayMs) {
        cancelPendingRetry();
        if (delayMs <= 0L) {
            OverlayWindow.dismiss(dialog);
            if (mDialog == dialog) {
                mDialog = null;
            }
            startNewSession();
            return;
        }

        final Button retry = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (retry != null) {
            retry.setEnabled(false);
            retry.setText(getString(R.string.screen_share_retry_countdown,
                (delayMs + 999L) / 1_000L));
        }
        final long deadlineMs = SystemClock.elapsedRealtime() + delayMs;
        mRetryCountdownRunnable = new Runnable() {
            @Override
            public void run() {
                final long remainingMs = deadlineMs - SystemClock.elapsedRealtime();
                if (remainingMs <= 0L) {
                    mRetryCountdownRunnable = null;
                    return;
                }
                if (retry != null) {
                    retry.setText(getString(R.string.screen_share_retry_countdown,
                        (remainingMs + 999L) / 1_000L));
                }
                mMainHandler.postDelayed(this, 1_000L);
            }
        };
        mReconnectRunnable = () -> {
            mReconnectRunnable = null;
            cancelRetryCountdown();
            OverlayWindow.dismiss(dialog);
            if (mDialog == dialog) {
                mDialog = null;
            }
            startNewSession();
        };
        mMainHandler.postDelayed(mReconnectRunnable, delayMs);
        mMainHandler.post(mRetryCountdownRunnable);
    }

    /**
     * Cancels a retry that was scheduled but has not started yet; safe when none is pending.
     */
    private void cancelPendingRetry() {
        if (mReconnectRunnable != null) {
            mMainHandler.removeCallbacks(mReconnectRunnable);
            mReconnectRunnable = null;
        }
        cancelRetryCountdown();
    }

    /**
     * Stops the countdown that rewrites the retry button; safe when none is running.
     */
    private void cancelRetryCountdown() {
        if (mRetryCountdownRunnable != null) {
            mMainHandler.removeCallbacks(mRetryCountdownRunnable);
            mRetryCountdownRunnable = null;
        }
    }

    /**
     * Builds a fresh client for the current target and shows the loading overlay again.
     *
     * <p>A {@link ScreenShareClient} is single-use, so a retry is a new instance over a new media
     * receiver; the target itself is unchanged.</p>
     */
    private void startNewSession() {
        if (mServiceInfo == null) {
            return;
        }
        final ClientMediaReceiver receiver = startMediaSession();
        mClient = new ScreenShareClient(hostOf(mServiceInfo), portOf(mServiceInfo), viewerMetrics(),
            mServiceInfo.getId(), advertisedCapabilities());
        mClient.setClientStatsListener(mClientStatsListener);
        if (mScreenShareLoading != null) {
            mScreenShareLoading.show();
        }
        mClient.start(mClientObserver, receiver);
    }

    /**
     * Dismisses the disconnect confirmation, if one is open.
     */
    private void dismissConfirmation() {
        if (mDialog != null) {
            OverlayWindow.dismiss(mDialog);
            mDialog = null;
        }
    }

    /**
     * Dismisses the loading overlay.
     */
    private void dismissLoadingOverlay() {
        if (mScreenShareLoading != null) {
            mScreenShareLoading.dismiss();
            mScreenShareLoading = null;
        }
    }

    /**
     * Tells the expanded host that the session ended; the snapshot is dropped with it because the
     * host that would consume it is gone.
     */
    private void releaseActivityHost() {
        if (mActivityHost != null) {
            mActivityHost.onSessionEnded();
            mActivityHost = null;
        }
        mTransitionSnapshot = null;
    }

    /**
     * Dismisses the floating viewer window.
     */
    private void dismissViewerOverlay() {
        if (mSurfaceShareViewer != null) {
            mSurfaceShareViewer.dismiss();
            mSurfaceShareViewer = null;
        }
    }

    /**
     * Stops the single-use client before it is dropped.
     */
    private void stopClient() {
        if (mClient != null) {
            mClient.stop();
            mClient = null;
        }
    }

    /**
     * Removes the foreground notification.
     */
    private void removeForegroundNotification() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
    }

    // Stats are sampled every three seconds on the main thread; the rate is mirrored into the
    // notification.
    private void onClientStatsChanged(@NonNull final ClientStats clientStats) {
        mClientStats = clientStats;
        updateInboundDataRate(mClientStats.getInboundDataRate());
    }

    // Rebuilds the notification only when the displayed value changes.
    @SuppressLint("MissingPermission")
    private void updateInboundDataRate(final long inboundDataRate) {
        if (mInboundDataRate == inboundDataRate) {
            return;
        }

        mInboundDataRate = inboundDataRate;
        NotificationManagerCompat.from(ScreenViewerService.this).notify(NOTIFICATION_ID, buildNotification());
    }

    /**
     * Returns the capability set this viewer advertises, probing the decoders on first use.
     *
     * <p>One instance serves the HELLO and the media session of every attempt. A retry reuses it for
     * the same reason the probe itself runs once: the device's decoders do not change while the
     * service lives, and two independent results would let the HELLO offer a codec the session then
     * refuses. The probe runs on the main thread and is pure bookkeeping apart from the
     * {@code MediaCodecList} query.</p>
     */
    @NonNull
    private ClientCapabilities advertisedCapabilities() {
        if (mAdvertisedCapabilities == null) {
            mAdvertisedCapabilities = CodecCapabilityProbe.probeClientDecoders();
        }
        return mAdvertisedCapabilities;
    }

    /**
     * Builds the client media session for the current target.
     *
     * <p>The registry resolves the active codec's decoder, and the session is gated on the same
     * capabilities the client announces in its HELLO ({@link #advertisedCapabilities()}), so a
     * descriptor the server selected can never be refused here. The receiver drives the session from
     * wire messages on the client's decode thread, and the session receives one stable
     * {@link SwitchableRenderTarget} whose delegate is the display host that currently owns the
     * output: the floating window's texture normally, the activity's texture while the session is
     * expanded. When the window owns the output it is mounted immediately, so its texture view
     * exposes the surface the decoder needs. The registration carries a key-frame requester, so a
     * decoder that had to shed a frame asks the server for a fresh join point.</p>
     */
    @NonNull
    private ClientMediaReceiver startMediaSession() {
        ensureMetrics();
        mSessionClock.reset();
        final ClientCapabilities advertised = advertisedCapabilities();
        if (mCodecRegistry == null) {
            mCodecRegistry = CodecRegistry.withDefaults();
            mCodecRegistry.register(H264Codec.androidRegistration(
                () -> mMainHandler.post(this::requestKeyFrame), mMetrics, NanoClock.MONOTONIC,
                mSessionClock));
        }
        if (mStreamKey == null) {
            mStreamKey = new StreamKey("remote", "screen");
        }
        if (mSurfaceShareViewer == null) {
            mSurfaceShareViewer = new SurfaceShareViewer(this, mServiceInfo,
                this::syncHudLoop, this::expandToActivity, this::toggleAudioMuted,
                this::isAudioMuted);
        }
        // Both halves exist from the start: which one the session uses is decided by the descriptor
        // the server sends, which arrives after the target was handed over. The bitmap half delivers
        // on the main thread through the service handler.
        mOverlayRenderTarget = new HostRenderTarget(
            new SurfaceRenderTarget(mSurfaceShareViewer.getTextureView(),
                () -> onHostSurfaceAvailable(mOverlayRenderTarget)),
            new BitmapRenderTarget(mSurfaceShareViewer, mMainHandler::post));
        mActivityRenderTarget = mActivityHost == null ? null
            : new HostRenderTarget(
                new SurfaceRenderTarget(mActivityHost.getTextureView(),
                    () -> onHostSurfaceAvailable(mActivityRenderTarget)),
                new BitmapRenderTarget(mActivityHost, mMainHandler::post));
        mRenderTarget = new SwitchableRenderTarget(
            mActivityRenderTarget != null ? mActivityRenderTarget : mOverlayRenderTarget);
        final ClientMediaSession session = new ClientMediaSession(
                new ClientSessionKey(mStreamKey, "local"), mCodecRegistry,
                advertised,
                mRenderTarget, mClientStateListener, mMetrics);
        mClientMediaSession = session;
        mClientAudioSession = createAudioSession();
        mClientMediaReceiver = new ClientMediaReceiver(mStreamKey, session, mClientSecureListener,
            (visibleWidth, visibleHeight, rotationDegrees) -> mMainHandler.post(() ->
                // The descriptor arrives on the decode thread; the hosts are only touched here.
                applyContentGeometry(visibleWidth, visibleHeight, rotationDegrees)),
            new ArrivalTracker(mMetrics), mMetrics, mClientAudioSession);

        // Mount the surface viewer now so its texture view acquires a surface; the H.264 decoder
        // cannot be created until a surface exists, and STREAMING (which used to trigger the mount)
        // is only reached once the decoder starts producing frames. An expanded session keeps the
        // activity as its host and leaves the window unmounted.
        if (mActivityHost == null) {
            mSurfaceShareViewer.show();
        }

        mMainHandler.removeCallbacks(mMetricsLogRunnable);
        if (mMetrics.isEnabled()) {
            mMainHandler.postDelayed(mMetricsLogRunnable, UPDATE_METRICS_INTERVAL_MS);
        }
        syncHudLoop();

        return mClientMediaReceiver;
    }

    /**
     * Creates the audio session of a viewing stream.
     *
     * <p>The session owns its decoder and its {@code AudioTrack}; it is created with the media
     * session so the HELLO that declared an AAC decoder has something to receive into, and it is
     * closed with the session. A failure here only marks sound unavailable: {@link #startMediaSession()}
     * returns the video receiver either way.</p>
     */
    @NonNull
    private ClientAudioSession createAudioSession() {
        return new ClientAudioSession(new AndroidAudioDecoder(), new AudioTrackSink(),
                (state, reason) -> mMainHandler.post(() -> onAudioStateChanged(state, reason)),
                NanoClock.MONOTONIC, mSessionClock, mMetrics, true, new AudioLog() {
                    @Override
                    public void info(@NonNull final String message) {
                        Log.i(mTag, "audio: " + message);
                    }

                    @Override
                    public void warn(@NonNull final String message) {
                        Log.w(mTag, "audio: " + message);
                    }
                });
    }

    /**
     * Records one audio state transition.
     *
     * <p>The viewer shows sound as unavailable in its overlay; nothing here can stop the session,
     * because audio is optional and the video keeps rendering.</p>
     */
    private void onAudioStateChanged(@NonNull final ClientAudioSession.State state,
                                     @Nullable final String reason) {
        Log.i(mTag, "audio state = " + state + (reason == null ? "" : ", reason = " + reason));
        if (state == ClientAudioSession.State.UNAVAILABLE) {
            mAudioUnavailableReason = reason;
        }
        if (Preferences.get().isViewerHudEnabled()) {
            updateHud();
        }
    }

    /**
     * Mutes or unmutes the audio track without touching the stream.
     *
     * <p>Muting is local playback only: decoding and buffering continue, so unmuting resumes at the
     * current position instead of restarting or jumping.</p>
     */
    public void setAudioMuted(final boolean isMuted) {
        mAudioMuted = isMuted;
        final ClientAudioSession session = mClientAudioSession;
        if (session != null) {
            session.setVolume(isMuted ? 0f : 1f);
        }
        if (Preferences.get().isViewerHudEnabled()) {
            updateHud();
        }
    }

    /**
     * Returns whether the viewer's audio is muted locally.
     */
    public boolean isAudioMuted() {
        return mAudioMuted;
    }

    /**
     * Flips the local mute state; the audio row of the display menu calls this.
     */
    private void toggleAudioMuted() {
        setAudioMuted(!mAudioMuted);
    }

    /**
     * Returns the overlay line that describes the audio track.
     */
    @NonNull
    private String audioHudLine() {
        if (mAudioUnavailableReason != null) {
            return getString(R.string.viewer_audio_hud_unavailable);
        }
        final ClientAudioSession session = mClientAudioSession;
        if (session == null || session.getState() == ClientAudioSession.State.IDLE) {
            return getString(R.string.viewer_audio_hud_unavailable);
        }
        if (mAudioMuted) {
            return getString(R.string.viewer_audio_hud_muted);
        }
        return getString(R.string.viewer_audio_hud_receiving);
    }

    /**
     * Retries the decoder when the surface of the host that owns the output becomes available.
     *
     * <p>The inactive host can produce surface events too - a window that is re-added, an activity
     * that is recreated - and configuring a decoder against a surface nobody displays is exactly the
     * mistake this check prevents.</p>
     *
     * @param target the target that reported availability; may be null after a teardown
     */
    private void onHostSurfaceAvailable(@Nullable final HostRenderTarget target) {
        final ClientMediaSession session = mClientMediaSession;
        final SwitchableRenderTarget router = mRenderTarget;
        if (session == null || router == null || target == null
            || router.getDelegate() != target) {
            return;
        }
        if (session.retryConfigure()) {
            // The decoder exists now, so the request reaches a decoder that can use the join point.
            requestKeyFrameForced();
        }
    }

    /**
     * Stores the stream geometry and forwards it to the host that currently owns the output.
     */
    private void applyContentGeometry(final int visibleWidth, final int visibleHeight,
                                      final int rotationDegrees) {
        mContentWidth = visibleWidth;
        mContentHeight = visibleHeight;
        mRotationDegrees = rotationDegrees;
        final ViewerHost host = activeHost();
        if (host != null) {
            host.setContentGeometry(visibleWidth, visibleHeight, rotationDegrees);
        }
    }

    /**
     * Stores the secure flag and forwards it to the host that currently owns the output.
     */
    private void applySecure(final boolean secure) {
        mIsSecure = secure;
        final ViewerHost host = activeHost();
        if (host != null) {
            host.setSecure(secure);
        }
    }

    /**
     * Stores the debug overlay lines and forwards them to the host that currently owns the output.
     */
    private void applyHudLines(@NonNull final List<String> lines) {
        final List<String> effective;
        if (lines.isEmpty()) {
            effective = lines;
        } else {
            effective = new ArrayList<>(lines);
            effective.add(audioHudLine());
        }
        mHudLines = effective;
        final ViewerHost host = activeHost();
        if (host != null) {
            host.setHudLines(effective);
        }
    }

    /**
     * Returns the host that currently owns the decoder output, or null before the session starts.
     */
    @Nullable
    private ViewerHost activeHost() {
        if (mActivityHost != null) {
            return mActivityHost;
        }
        return mSurfaceShareViewer;
    }


    /**
     * Returns the viewer's metric scope, creating it on first use.
     *
     * <p>The scope lives as long as the service, so a reconnect keeps the same windows; the arrival
     * tracker is recreated per session because its baseline belongs to one stream.</p>
     */
    @NonNull
    private PipelineMetrics viewerMetrics() {
        ensureMetrics();
        return mMetrics;
    }

    /**
     * Starts, restarts or stops the overlay refresh loop to match the current preference.
     *
     * <p>Called when a session starts and whenever the menu toggles the overlay. While the overlay
     * is off no snapshot is taken at all, so a hidden overlay costs nothing beyond this check.</p>
     */
    private void syncHudLoop() {
        mMainHandler.removeCallbacks(mHudRunnable);
        if (activeHost() == null) {
            return;
        }
        if (!Preferences.get().isViewerHudEnabled() || mMetrics == null || !mMetrics.isEnabled()) {
            applyHudLines(Collections.emptyList());
            return;
        }
        updateHud();
    }

    /**
     * Refreshes the overlay from one snapshot and reschedules itself.
     */
    private void updateHud() {
        mMainHandler.removeCallbacks(mHudRunnable);
        final PipelineMetrics metrics = mMetrics;
        if (activeHost() == null || metrics == null) {
            return;
        }
        if (!Preferences.get().isViewerHudEnabled() || !metrics.isEnabled()) {
            applyHudLines(Collections.emptyList());
            return;
        }
        applyHudLines(MetricsFormatter.toHud(metrics.snapshot(UPDATE_HUD_INTERVAL_MS),
            mHudRateSampler != null ? mHudRateSampler.sample() : null));
        mMainHandler.postDelayed(mHudRunnable, UPDATE_HUD_INTERVAL_MS);
    }

    /**
     * Writes one structured client metric line and the current resynchronization reason.
     *
     * <p>Runs on the main thread next to the three-second statistics sample, so no extra thread is
     * created. The identifier is the tail of the random per-run client id, which is what lets this
     * line be matched with the server's session row.</p>
     */
    private void logMetrics() {
        mMainHandler.removeCallbacks(mMetricsLogRunnable);

        final PipelineMetrics metrics = mMetrics;
        if (metrics == null || !metrics.isEnabled()) {
            return;
        }
        final ScreenShareClient client = mClient;
        Log.i(mTag, MetricsFormatter.toLogLine(MetricsFormatter.Role.CLIENT,
            metrics.snapshot(UPDATE_METRICS_INTERVAL_MS),
            client == null ? null : client.getClientIdTail(),
            mLogRateSampler != null ? mLogRateSampler.sample() : null));

        final ClientMediaSession session = mClientMediaSession;
        final ClientMediaSession.ResyncReason reason =
            session == null ? null : session.getLastResyncReason();
        if (reason != null && reason != mLoggedResyncReason) {
            mLoggedResyncReason = reason;
            Log.i(mTag, "logMetrics: resync reason " + reason.name());
        }

        if (++mMetricsLogCount % DEVICE_BLOCK_EVERY_N_LOGS == 0) {
            Log.i(mTag, MetricsFormatter.toDeviceLine(MetricsFormatter.Role.CLIENT,
                mDeviceProfileSampler.get().sample()));
        }

        mMainHandler.postDelayed(mMetricsLogRunnable, UPDATE_METRICS_INTERVAL_MS);
    }

    /**
     * Asks the server for a fresh random-access point, at most once per throttle interval.
     *
     * <p>Runs on the main thread. Called when the session enters {@code RESYNCING} and when the
     * decoder reports a dropped frame, so a damaged reference chain recovers in one round trip
     * instead of waiting for the next periodic key frame.</p>
     */
    private void requestKeyFrame() {
        if (mClient == null || !mKeyFrameRequestThrottle.tryAcquire(
            TimeSource.MONOTONIC.nowMs())) {
            return;
        }
        Log.i(mTag, "requestKeyFrame: asking for a random access point");
        mClient.requestKeyframe();
    }

    /**
     * Asks the server for a random-access point without the throttle.
     *
     * <p>Used after a decoder rebuild: a rebuilt decoder cannot resume on a P frame, and the periodic
     * key frame may be seconds away, so the switch would otherwise freeze for that long. The throttle
     * exists to collapse burst failures, not to delay an explicit user action.</p>
     */
    private void requestKeyFrameForced() {
        if (mClient == null) {
            return;
        }
        Log.i(mTag, "requestKeyFrameForced: asking for a random access point");
        mClient.requestKeyframe();
    }

    /**
     * Opens the full-screen activity for the running session.
     *
     * <p>Captures the last frame first: the activity shows it until its own texture produced a
     * decoded frame, which is what keeps the switch free of a black screen. The activity binds back
     * to this service and attaches itself as the display host, so nothing here stops or restarts the
     * client. A failed or blocked activity start leaves the floating window in charge.</p>
     *
     * <p>Two watchdogs cover the failures that are observable from here: a start that throws is
     * reported at once, and a start that never produces the activity is reported after
     * {@value #ACTIVITY_ATTACH_TIMEOUT_MS} ms. Both watchdogs are dropped as soon as the activity
     * reports its creation through {@link #onViewerActivityCreated()}, so a slow but working switch
     * is never reported as a failure. In both failure cases the window keeps the decoder output and
     * the session continues; without the report the user would only see an expansion that did
     * nothing.</p>
     */
    private void expandToActivity() {
        if (mClientMediaSession == null || mActivityHost != null) {
            return;
        }
        Log.i(mTag, "expandToActivity: opening the full-screen viewer");
        final Intent intent = new Intent(this, ViewerActivity.class);
        // A service is not an activity context; the new task is what makes the start legal.
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
        } catch (RuntimeException e) {
            Log.w(mTag, "expandToActivity: starting the activity failed", e);
            cancelActivityAttachWatchdogs();
            showExpandFailed();
            return;
        }
        cancelActivityAttachWatchdogs();
        mMainHandler.postDelayed(mActivityAttachSlowRunnable, ACTIVITY_ATTACH_EXPECTED_MS);
        mMainHandler.postDelayed(mActivityAttachTimeoutRunnable, ACTIVITY_ATTACH_TIMEOUT_MS);
    }

    /**
     * Logs an expansion request whose activity has not attached yet.
     *
     * <p>A slow launch is not a failure: the window still shows the session while the activity
     * starts. The line exists so a device log tells a slow switch from a silent one; the deadline
     * below only covers a launch that never gets created.</p>
     */
    private void onActivityAttachSlow() {
        if (mActivityHost != null || mClientMediaSession == null) {
            return;
        }
        Log.i(mTag, "onActivityAttachSlow: the full-screen viewer has not attached after "
            + ACTIVITY_ATTACH_EXPECTED_MS + " ms, still waiting");
    }

    /**
     * Reports an expansion request that never produced an activity.
     *
     * <p>The activity cancels this watchdog from its {@code onCreate}, so reaching it means the
     * system never launched the component - a background-activity restriction, a disabled
     * component or an exception thrown by the launch. The floating window was never removed (its
     * removal requires an attached host), so the session is still visible and usable; only the
     * user-facing report is missing. A session that ended in the meantime is not reported, because
     * ending it is what the user asked for.</p>
     */
    private void onActivityAttachTimeout() {
        if (mActivityHost != null || mClientMediaSession == null) {
            return;
        }
        Log.w(mTag, "onActivityAttachTimeout: the full-screen viewer did not attach within "
            + ACTIVITY_ATTACH_TIMEOUT_MS + " ms, keeping the floating window");
        showExpandFailed();
    }

    /**
     * Tells the user that the full-screen host could not be opened.
     */
    private void showExpandFailed() {
        Toast.makeText(this, R.string.viewer_expand_error, Toast.LENGTH_LONG).show();
    }

    /**
     * Drops both expansion watchdogs; called when the request settles or the session ends.
     */
    private void cancelActivityAttachWatchdogs() {
        mMainHandler.removeCallbacks(mActivityAttachSlowRunnable);
        mMainHandler.removeCallbacks(mActivityAttachTimeoutRunnable);
    }

    /**
     * Returns the floating window rectangle, relative to the display center.
     *
     * <p>The overlay window is centred on the display and positioned by offsets, so the rectangle is
     * expressed the same way; the activity adds its own center to reach view coordinates. Null while
     * no viewer exists.</p>
     */
    @Nullable
    private Rect getOverlayWindowBounds() {
        return mSurfaceShareViewer == null ? null : mSurfaceShareViewer.getWindowBounds();
    }

    /**
     * Gives the decoder output to {@code host} and rebuilds the decoder against its surface.
     *
     * <p>The order matters: the delegate changes first, then the session closes the decoder that
     * wrote into the previous host and creates one for the new surface, and only then is the previous
     * target released. A host whose texture view has not produced a surface yet is handled by the
     * session itself: it waits in {@code RESYNCING} and retries when the surface appears.</p>
     *
     * @param host activity that takes over the output; required
     * @return false when there is no running session to attach to
     */
    private boolean attachActivityHost(@NonNull final ViewerHost host) {
        final ClientMediaSession session = mClientMediaSession;
        final SwitchableRenderTarget router = mRenderTarget;
        if (session == null || router == null || mSurfaceShareViewer == null) {
            return false;
        }
        if (mActivityHost == host) {
            return true;
        }
        // The activity made it; neither watchdog has anything left to report.
        cancelActivityAttachWatchdogs();

        final HostRenderTarget previous = mActivityRenderTarget;
        mActivityHost = host;
        mIsActivityTransitionDone = false;
        mActivityRenderTarget = new HostRenderTarget(
            new SurfaceRenderTarget(host.getTextureView(),
                () -> onHostSurfaceAvailable(mActivityRenderTarget)),
            new BitmapRenderTarget(host, mMainHandler::post));
        router.setDelegate(mActivityRenderTarget);

        // Capture the outgoing host's last frame before the decoder stops writing into it; the
        // activity shows it until its own surface presents a frame.
        final ViewerHost outgoing = mSurfaceShareViewer;
        if (outgoing != null) {
            mTransitionSnapshot = outgoing.captureSnapshot();
        }

        // A host that attaches late missed the descriptor, so the current state is pushed here.
        host.setContentGeometry(mContentWidth, mContentHeight, mRotationDegrees);
        host.setSecure(mIsSecure);
        host.setHudLines(mHudLines);

        // A frame can only arrive once a decoder exists, so the key frame is requested after the
        // rebuild succeeded; a missing surface is covered by the availability callback.
        final boolean configured = session.rebindSurface();
        if (configured) {
            requestKeyFrameForced();
        }
        if (previous != null) {
            previous.release();
        }
        Log.i(mTag, "attachActivityHost: decoder output moved to the activity, configured = "
            + configured);
        return true;
    }

    /**
     * Reports that the activity finished its entry animation, so the floating window can be removed
     * once frames are flowing again.
     *
     * @param host activity that reported the end; ignored when it is no longer the active host
     */
    private void finishActivityTransition(@NonNull final ViewerHost host) {
        if (mActivityHost != host) {
            return;
        }
        mIsActivityTransitionDone = true;
        removeOverlayIfTransitionFinished();
    }

    /**
     * Removes the floating window once the activity both finished its animation and receives frames.
     *
     * <p>Two conditions, on purpose: removing the window earlier would delete the live picture while
     * the snapshot is still growing, and removing it without frames would leave the snapshot as the
     * only thing on screen with no way back.</p>
     */
    private void removeOverlayIfTransitionFinished() {
        final ClientMediaSession session = mClientMediaSession;
        if (mActivityHost == null || !mIsActivityTransitionDone || session == null
            || session.getState() != ClientState.STREAMING) {
            return;
        }
        if (mSurfaceShareViewer != null) {
            mSurfaceShareViewer.dismiss();
        }
        if (mOverlayRenderTarget != null) {
            // The decoder writes into the activity's surface now; the window's can go until it
            // returns.
            mOverlayRenderTarget.release();
        }
        Log.i(mTag, "removeOverlayIfTransitionFinished: floating window removed");
    }

    /**
     * Gives the decoder output back to the floating window.
     *
     * <p>The window is re-added with the activity's last frame on top, so the gap between the old
     * surface and the new decoder is covered by a picture instead of black. The decoder is rebuilt
     * before the activity's target is released, and the session, its descriptor and the network
     * stream are untouched.</p>
     *
     * @param host     activity that gives the output back; required
     * @param snapshot last frame of the activity, or null when it could not be captured
     */
    private void detachActivityHost(@NonNull final ViewerHost host,
                                    @Nullable final Bitmap snapshot) {
        if (mActivityHost != host) {
            return;
        }
        mActivityHost = null;
        mIsActivityTransitionDone = false;

        final ClientMediaSession session = mClientMediaSession;
        final HostRenderTarget previous = mActivityRenderTarget;
        mActivityRenderTarget = null;

        if (session != null) {
            if (mRenderTarget != null && mOverlayRenderTarget != null) {
                mRenderTarget.setDelegate(mOverlayRenderTarget);
            }
            if (mSurfaceShareViewer != null) {
                mSurfaceShareViewer.setContentGeometry(mContentWidth, mContentHeight,
                    mRotationDegrees);
                mSurfaceShareViewer.setSecure(mIsSecure);
                mSurfaceShareViewer.show();
                mSurfaceShareViewer.showSnapshot(snapshot);
                mSurfaceShareViewer.setHudLines(mHudLines);
            }
            // A frame can only arrive once a decoder exists, so the key frame is requested after the
            // rebuild succeeded; a missing surface is covered by the availability callback.
            if (session.rebindSurface()) {
                requestKeyFrameForced();
            }
            if (previous != null) {
                previous.release();
            }
            syncHudLoop();
        } else if (previous != null) {
            previous.release();
        }
        Log.i(mTag, "detachActivityHost: decoder output returned to the floating window");
    }

    /**
     * Hands the frame captured before the switch to the incoming host; ownership transfers.
     */
    @Nullable
    private Bitmap takeTransitionSnapshot() {
        final Bitmap snapshot = mTransitionSnapshot;
        mTransitionSnapshot = null;
        return snapshot;
    }

    /**
     * Local binder through which {@link ViewerActivity} attaches itself as a display host.
     *
     * <p>Only the activity binds; the floating window is created by the service. The surface is
     * deliberately narrow: attach, detach with the last frame, take one snapshot, read the window
     * rectangle for the transition and refresh the debug overlay. Nothing here exposes the client,
     * the session or the decoder, so a bound activity cannot start, restart or stop the stream.</p>
     */
    public final class ViewerBinder extends Binder {

        /**
         * Registers the activity as the host that owns the decoder output.
         *
         * @param host activity host; required
         * @return false when no session is running, which the activity treats as "finish"
         */
        public boolean attachHost(@NonNull final ViewerHost host) {
            return attachActivityHost(host);
        }

        /**
         * Reports that the entry animation finished.
         *
         * @param host activity host; required
         */
        public void finishTransition(@NonNull final ViewerHost host) {
            finishActivityTransition(host);
        }

        /**
         * Gives the decoder output back to the floating window.
         *
         * @param host     activity host; required
         * @param snapshot last frame of the activity, or null
         */
        public void detachHost(@NonNull final ViewerHost host, @Nullable final Bitmap snapshot) {
            detachActivityHost(host, snapshot);
        }

        /**
         * Takes the frame captured before the switch, or null when none was captured.
         */
        @Nullable
        public Bitmap takeSnapshot() {
            return takeTransitionSnapshot();
        }

        /**
         * Returns the floating window rectangle relative to the display center, or null.
         */
        @Nullable
        public Rect getOverlayBounds() {
            return getOverlayWindowBounds();
        }

        /**
         * Re-reads the debug-overlay preference into the refresh loop.
         */
        public void refreshHud() {
            syncHudLoop();
        }

        /**
         * Mutes or unmutes the audio track; playback and buffering continue either way.
         *
         * <p>Exposed on the binder so the full-screen activity can offer the same control as the
         * floating window without touching the session, the decoder or the stream.</p>
         *
         * @param isMuted whether the viewer should be silent
         */
        public void setAudioMuted(final boolean isMuted) {
            ScreenViewerService.this.setAudioMuted(isMuted);
        }

        /**
         * Returns whether the viewer's audio is muted.
         */
        public boolean isAudioMuted() {
            return ScreenViewerService.this.isAudioMuted();
        }
    }

    /**
     * Closes the media session and releases the display targets; idempotent.
     */
    private void teardownMediaSession() {
        if (mClientMediaReceiver != null) {
            mClientMediaReceiver.close();
            mClientMediaReceiver = null;
        }
        if (mClientAudioSession != null) {
            mClientAudioSession.close();
            mClientAudioSession = null;
        }
        mAudioUnavailableReason = null;
        mClientMediaSession = null;
        mRenderTarget = null;
        mIsActivityTransitionDone = false;
        mTransitionSnapshot = null;
        if (mOverlayRenderTarget != null) {
            mOverlayRenderTarget.release();
            mOverlayRenderTarget = null;
        }
        if (mActivityRenderTarget != null) {
            mActivityRenderTarget.release();
            mActivityRenderTarget = null;
        }
        // Releasing the targets clears their own frame layers, but a host without a target (the
        // activity between two attaches, or a retry that reuses the floating window) must not keep
        // showing the last JPEG frame of the ended session.
        if (mSurfaceShareViewer != null) {
            mSurfaceShareViewer.clearEncodedFrame();
        }
        if (mActivityHost != null) {
            mActivityHost.clearEncodedFrame();
        }
    }

    private void createNotificationChannel() {
        final NotificationChannelCompat notificationChannel =
            new NotificationChannelCompat.Builder(NOTIFICATION_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(getString(R.string.client_service_notification_channel_name))
                .build();
        NotificationManagerCompat.from(this).createNotificationChannel(notificationChannel);
    }

    @SuppressWarnings("DataFlowIssue")
    @NonNull
    private Notification buildNotification() {
        return new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.client_service_notification_title, nameOf(mServiceInfo)))
            .setContentText(getString(R.string.client_service_notification_text, hostOf(mServiceInfo),
                portOf(mServiceInfo), Formatter.formatFileSize(this, mInboundDataRate)))
            .setContentIntent(PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
            .addAction(0, getString(R.string.client_service_stop_notification_action),
                PendingIntent.getBroadcast(this, 0,
                    new Intent(ServiceProtocol.ACTION_STOP_CLIENT_SERVICE).setPackage(getPackageName()),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build();
    }

    /**
     * Non-focusable overlay shown while the client connects.
     *
     * <p>Created and used on the main thread. A tap opens the disconnect confirmation as a
     * floating window; {@link #show()} is idempotent, so the same view can be shown again after a
     * retry.</p>
     */
    private static class ScreenShareLoading {
        @Nullable
        private AlertDialog mDialog;

        @NonNull
        private final WindowManager mWindowManager;

        @NonNull
        private final View mLoadingView;

        @NonNull
        private final WindowManager.LayoutParams mLayoutParams;

        @SuppressWarnings("DataFlowIssue")
        @SuppressLint("InflateParams")
        private ScreenShareLoading(@NonNull final Context context, @NonNull final DeviceInfo serviceInfo) {
            final Context themedContext = new ContextThemeWrapper(context, R.style.Theme_ScreenShare);
            mWindowManager = themedContext.getSystemService(WindowManager.class);
            mLoadingView = LayoutInflater.from(themedContext).inflate(R.layout.widget_screen_share_loading, null);
            mLayoutParams = new WindowManager.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, ServiceProtocol.FLOATING_WINDOW_TYPE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                    | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON, PixelFormat.TRANSLUCENT);
            mLayoutParams.gravity = Gravity.CENTER;

            mLoadingView.setOnClickListener(v -> {
                if (mDialog == null) {
                    mDialog = new MaterialAlertDialogBuilder(themedContext)
                        .setMessage(HtmlCompat.fromHtml(themedContext.getString(
                                R.string.screen_share_disconnect_confirm, nameOf(serviceInfo)),
                            HtmlCompat.FROM_HTML_MODE_LEGACY))
                        .setPositiveButton(android.R.string.ok, (dialog, which) -> themedContext.sendBroadcast(
                            new Intent(ServiceProtocol.ACTION_STOP_CLIENT_SERVICE)
                                .setPackage(themedContext.getPackageName())))
                        .setNegativeButton(android.R.string.cancel, null)
                        .setCancelable(false)
                        .create();
                    mDialog.getWindow().setType(ServiceProtocol.FLOATING_WINDOW_TYPE);
                }
                OverlayWindow.show(mDialog);
            });
        }

        public void show() {
            OverlayWindow.attach(mWindowManager, mLoadingView, mLayoutParams);
        }

        /**
         * Replaces the caption under the spinner, for a state the user has to wait out.
         */
        public void setMessage(@NonNull final CharSequence message) {
            final TextView text = mLoadingView.findViewById(R.id.screen_share_loading_text);
            if (text != null) {
                text.setText(message);
            }
        }

        public void dismiss() {
            if (mDialog != null) {
                OverlayWindow.dismiss(mDialog);
                mDialog = null;
            }

            OverlayWindow.detach(mWindowManager, mLoadingView);
        }
    }

    /**
     * Floating, draggable display host for H.264 frames.
     *
     * <p>Hosts a {@link ViewerPanelView} whose texture the decoder renders into directly, so no
     * per-frame callback is needed. Created and used on the main thread. A long press starts
     * dragging, a single tap opens the disconnect confirmation and a double tap opens the display
     * menu, whose last row expands the session into the full-screen activity. Opacity, scale, zoom
     * preset, corner rounding and the normalized location come from the persisted viewer settings;
     * the secure flag maps to {@code FLAG_SECURE}. The round-rect outline mirrors the shape of the
     * bitmap viewer this window replaced.</p>
     *
     * <p><b>Geometry.</b> The window size comes from {@link ViewerFitPolicy}: the visible size and
     * rotation reported by the stream descriptor, the usable display bounds, the viewing percentage
     * and the zoom preset. Until the first descriptor arrives the window keeps the legacy "bounds
     * times percentage" size, so the texture view can acquire a surface before any frame exists. A
     * rotated stream is fitted to the swapped content size and the panel transforms the texture to
     * match, which keeps the pixels upright without stretching them.</p>
     *
     * <p><b>Host role.</b> Implements {@link ViewerHost}: the service hands it the stream geometry,
     * the secure flag, the debug lines and a switch snapshot while it owns the decoder output. The
     * window object itself outlives a detach, so expanding into the activity and collapsing back
     * reuses it instead of building a second one.</p>
     */
    private static class SurfaceShareViewer implements ViewerHost {

        // Cross-fade of the switch snapshot once the rebuilt decoder presents a frame.
        private static final long SNAPSHOT_FADE_MS = 150L;

        @NonNull
        private final Context mContext;

        @NonNull
        private final DeviceInfo mServiceInfo;

        @NonNull
        private final WindowManager mWindowManager;

        @NonNull
        private final ViewerPanelView mPanelView;

        @NonNull
        private final WindowManager.LayoutParams mLayoutParams;

        @NonNull
        private final GestureDetector mGestureDetector;

        // Invoked after the display menu changed the overlay preference, so the service can start
        // or stop the one-second refresh loop instead of leaving it running for a hidden overlay.
        @NonNull
        private final Runnable onHudPreferenceChanged;

        private final Runnable onAudioMuteToggled;

        private final BooleanSupplier isAudioMuted;

        // Invoked when the user chooses the expand row of the display menu.
        @NonNull
        private final Runnable onExpandRequested;

        // Applies the rows chosen in the shared display menu.
        @NonNull
        private final ViewerMenuFactory.Listener mMenuListener =
            new ViewerMenuFactory.Listener() {
                @Override
                public void onZoomModeSelected(@NonNull final ZoomMode mode) {
                    setZoomMode(mode);
                }

                @Override
                public void onScalePercentSelected(final int percent) {
                    setViewerScale(percent);
                }

                @Override
                public void onHudEnabledSelected(final boolean enabled) {
                    setHudEnabled(enabled);
                }

                @Override
                public void onAudioMuteToggled() {
                    onAudioMuteToggled.run();
                }

                @Override
                public void onActionSelected() {
                    onExpandRequested.run();
                }
            };

        @NonNull
        private final PointF mLocation = new PointF();

        @NonNull
        private final Rect mBounds = new Rect();

        @NonNull
        private final PointF mDragOffset = new PointF();

        // Raw position of the latest ACTION_DOWN and the platform touch slop, used to start a drag
        // as soon as the finger really moves.
        private float mTouchDownX;

        private float mTouchDownY;

        private float mTouchSlop;

        @Nullable
        private AlertDialog mDialog;

        private int mOpacity;

        private int mScale;

        // Snapshot of the persisted zoom preset; the fit policy turns it into a window size.
        @NonNull
        private ZoomMode mZoomMode = ZoomMode.FIT;

        // Visible stream size and display rotation; zero rotation and zero size until the first
        // descriptor arrives, which makes the fit policy fall back to the legacy window size.
        private int mContentWidth;

        private int mContentHeight;

        private int mRotationDegrees;

        @SuppressWarnings("DataFlowIssue")
        private SurfaceShareViewer(@NonNull final Context context,
                                   @NonNull final DeviceInfo serviceInfo,
                                   @NonNull final Runnable onHudPreferenceChanged,
                                   @NonNull final Runnable onExpandRequested,
                                   @NonNull final Runnable onAudioMuteToggled,
                                   @NonNull final BooleanSupplier isAudioMuted) {
            mContext = new ContextThemeWrapper(context, R.style.Theme_ScreenShare);
            mServiceInfo = serviceInfo;
            this.onHudPreferenceChanged = onHudPreferenceChanged;
            this.onExpandRequested = onExpandRequested;
            this.onAudioMuteToggled = onAudioMuteToggled;
            this.isAudioMuted = isAudioMuted;
            mWindowManager = mContext.getSystemService(WindowManager.class);
            mTouchSlop = ViewConfiguration.get(mContext).getScaledTouchSlop();
            mPanelView = new ViewerPanelView(mContext);
            final TextureView textureView = mPanelView.getTextureView();
            // The usable bounds change with rotation and multi-window; re-fit after the new
            // configuration has been applied, as the bitmap viewer did.
            mPanelView.setConfigurationListener(this::onViewConfigurationChanged);
            mLayoutParams = new WindowManager.LayoutParams(0, 0, 0, 0, ServiceProtocol.FLOATING_WINDOW_TYPE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
            mLayoutParams.gravity = Gravity.CENTER;
            mGestureDetector = new GestureDetector(mContext, new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onDown(@NonNull MotionEvent e) {
                    return true;
                }

                @Override
                public boolean onSingleTapConfirmed(@NonNull MotionEvent e) {
                    showDisconnectDialog();
                    return true;
                }

                @Override
                public boolean onDoubleTap(@NonNull MotionEvent e) {
                    showZoomModeDialog();
                    return true;
                }

                @Override
                public void onLongPress(@NonNull MotionEvent e) {
                    beginDrag(e.getRawX(), e.getRawY());
                }
            });
            textureView.setOnTouchListener((v, event) -> {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        mTouchDownX = event.getRawX();
                        mTouchDownY = event.getRawY();
                        break;
                    case MotionEvent.ACTION_MOVE:
                        // Dragging used to need a stationary long press first, so an ordinary swipe
                        // moved nothing. The window follows the finger as soon as the touch travels
                        // past the platform's touch slop; a tap or a double tap stays below it, so
                        // the disconnect confirmation and the zoom menu are unaffected.
                        if (!textureView.isActivated()
                            && hasMovedPastTouchSlop(event.getRawX(), event.getRawY())) {
                            beginDrag(mTouchDownX, mTouchDownY);
                        }
                        if (textureView.isActivated()) {
                            updateLocation((int) (event.getRawX() + mDragOffset.x),
                                (int) (event.getRawY() + mDragOffset.y));
                        }
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (textureView.isActivated()) {
                            textureView.setActivated(false);
                            Preferences.get().setViewerLocation(mLocation);
                        }
                        break;
                    default:
                        break;
                }
                mGestureDetector.onTouchEvent(event);
                return true;
            });
        }

        /**
         * Switches the window into the dragged state, keeping the offset from the touch origin so
         * the window does not jump when the drag starts.
         */
        private void beginDrag(final float rawX, final float rawY) {
            if (mPanelView.getTextureView().isActivated()) {
                return;
            }
            mPanelView.getTextureView().setActivated(true);
            mDragOffset.set(mLayoutParams.x - rawX, mLayoutParams.y - rawY);
        }

        /**
         * Returns whether the touch travelled further than the platform's touch slop.
         */
        private boolean hasMovedPastTouchSlop(final float rawX, final float rawY) {
            return Math.hypot(rawX - mTouchDownX, rawY - mTouchDownY) > mTouchSlop;
        }

        @NonNull
        @Override
        public TextureView getTextureView() {
            return mPanelView.getTextureView();
        }

        /**
         * Replaces the debug overlay text.
         *
         * <p>Called on the main thread once per second. An empty list hides the overlay instead of
         * leaving stale numbers on screen, which is what a disabled overlay or a disabled metric
         * scope looks like. A disabled preference hides a populated line as well.</p>
         *
         * @param lines rendered lines, or an empty list to hide the overlay
         */
        @Override
        public void setHudLines(@NonNull final List<String> lines) {
            if (!Preferences.get().isViewerHudEnabled()) {
                mPanelView.setHudLines(Collections.emptyList());
                return;
            }
            mPanelView.setHudLines(lines);
        }

        /**
         * Hides the overlay when the preference is off and re-shows a populated one otherwise.
         *
         * <p>The panel owns the text, so the overlay only ever changes visibility; the texture
         * view, its outline and its transform are untouched.</p>
         */
        private void updateHudVisibility() {
            if (!Preferences.get().isViewerHudEnabled()) {
                mPanelView.setHudLines(Collections.emptyList());
            }
        }

        void show() {
            if (mPanelView.getParent() == null) {
                mOpacity = Preferences.get().getViewerOpacity();
                mScale = Preferences.get().getViewerScale();
                mZoomMode = ZoomMode.fromWire(Preferences.get().getViewerZoomMode());
                mLocation.set(Preferences.get().getViewerLocation());
                updateLayoutParams();
                mPanelView.setRounded(Preferences.get().isViewerRounded());
                mPanelView.setDisplaySize(0, 0);
                mPanelView.setContentGeometry(mContentWidth, mContentHeight, mRotationDegrees);
                OverlayWindow.attach(mWindowManager, mPanelView, mLayoutParams);
                updateHudVisibility();
            }
        }

        /**
         * Stores the visible stream geometry reported by a descriptor and resizes the window.
         *
         * <p>Called on the main thread. The geometry is retained even while the window is not
         * mounted, so a later {@link #show()} sizes the window before the first frame. An unchanged
         * geometry is ignored: a descriptor that only advances the configuration epoch must not
         * touch the window.</p>
         */
        @Override
        public void setContentGeometry(final int visibleWidth, final int visibleHeight,
                                       final int rotationDegrees) {
            if (mContentWidth == visibleWidth && mContentHeight == visibleHeight
                && mRotationDegrees == rotationDegrees) {
                return;
            }

            mContentWidth = visibleWidth;
            mContentHeight = visibleHeight;
            mRotationDegrees = rotationDegrees;

            if (mPanelView.getParent() != null) {
                updateView();
            }
        }

        /**
         * Re-applies the window size and shape and pushes them to the window.
         */
        private void updateView() {
            updateLayoutParams();
            mPanelView.setRounded(Preferences.get().isViewerRounded());
            mPanelView.setContentGeometry(mContentWidth, mContentHeight, mRotationDegrees);
            OverlayWindow.update(mWindowManager, mPanelView, mLayoutParams);
            updateHudVisibility();
        }


        /**
         * Re-fits the window after the display configuration changed.
         */
        private void onViewConfigurationChanged() {
            mPanelView.post(() -> {
                if (mPanelView.getParent() != null) {
                    updateView();
                }
            });
        }

        void dismiss() {
            mLayoutParams.flags &= ~WindowManager.LayoutParams.FLAG_SECURE;
            if (mDialog != null) {
                OverlayWindow.dismiss(mDialog);
                mDialog = null;
            }
            mPanelView.hideSnapshot();
            // The window object outlives a detach and is reused by the next session, so the JPEG
            // frame layer must not carry the previous session's last frame into the new one.
            mPanelView.clearFrame();
            OverlayWindow.detach(mWindowManager, mPanelView);
        }

        @Override
        public void setSecure(final boolean secure) {
            final boolean currentlySecure =
                (mLayoutParams.flags & WindowManager.LayoutParams.FLAG_SECURE) != 0;
            if (currentlySecure == secure) {
                return;
            }
            if (secure) {
                mLayoutParams.flags |= WindowManager.LayoutParams.FLAG_SECURE;
            } else {
                mLayoutParams.flags &= ~WindowManager.LayoutParams.FLAG_SECURE;
            }
            if (mPanelView.getParent() != null) {
                OverlayWindow.update(mWindowManager, mPanelView, mLayoutParams);
            }
        }

        /**
         * Shows the last frame of the outgoing host above the live picture.
         *
         * <p>Used when the session collapses from the activity back into this window: the window is
         * re-added and its texture needs a decoder rebuild before it can present anything, so the
         * captured frame covers that gap instead of a black rectangle.</p>
         */
        @Override
        public void showSnapshot(@Nullable final Bitmap snapshot) {
            mPanelView.showSnapshot(snapshot);
        }

        @Override
        public void hideSnapshot() {
            mPanelView.hideSnapshot();
        }

        /**
         * Fades the switch snapshot out once the live picture is back; the fade is short enough to
         * hide the decoder rebuild without being noticed as an animation.
         */
        void fadeOutSnapshot() {
            mPanelView.fadeOutSnapshot(SNAPSHOT_FADE_MS);
        }

        /**
         * Captures the currently displayed frame for the incoming host.
         *
         * @return the frame, or null when the texture cannot be read
         */
        @Override
        @Nullable
        public Bitmap captureSnapshot() {
            return mPanelView.captureSnapshot();
        }

        /**
         * Shows one decoded JPEG frame in the window's content box.
         */
        @Override
        public void showEncodedFrame(@NonNull final Bitmap frame) {
            mPanelView.showFrame(frame);
        }

        /**
         * Hides the JPEG frame layer, which is invisible for the whole H.264 path anyway.
         */
        @Override
        public void clearEncodedFrame() {
            mPanelView.clearFrame();
        }

        /**
         * Returns the box the picture is drawn in, used by the bitmap half to pick a sample size.
         */
        @Override
        public void getContentBox(@NonNull final Rect out) {
            mPanelView.getContentBox(out);
        }

        /**
         * Returns the window rectangle relative to the display center.
         *
         * <p>The window is centred on the display and placed by offsets, which is the same space the
         * activity needs for its shrink animation. The stored preferences are re-read when the window
         * was never shown, so a rectangle exists even after the session started expanded.</p>
         */
        @NonNull
        Rect getWindowBounds() {
            if (mScale <= 0) {
                mOpacity = Preferences.get().getViewerOpacity();
                mScale = Preferences.get().getViewerScale();
                mZoomMode = ZoomMode.fromWire(Preferences.get().getViewerZoomMode());
                mLocation.set(Preferences.get().getViewerLocation());
            }
            updateLayoutParams();
            final int halfWidth = mLayoutParams.width / 2;
            final int halfHeight = mLayoutParams.height / 2;
            return new Rect(mLayoutParams.x - halfWidth, mLayoutParams.y - halfHeight,
                mLayoutParams.x + halfWidth, mLayoutParams.y + halfHeight);
        }

        @Override
        public void onSessionEnded() {
            // The service removes the window itself; a window has no back stack to leave.
        }


        private void showDisconnectDialog() {
            if (mDialog == null) {
                mDialog = new MaterialAlertDialogBuilder(mContext)
                    .setMessage(HtmlCompat.fromHtml(mContext.getString(
                            R.string.screen_share_disconnect_confirm, nameOf(mServiceInfo)),
                        HtmlCompat.FROM_HTML_MODE_LEGACY))
                    .setPositiveButton(android.R.string.ok, (dialog, which) -> mContext.sendBroadcast(
                        new Intent(ServiceProtocol.ACTION_STOP_CLIENT_SERVICE).setPackage(mContext.getPackageName())))
                    .setNegativeButton(android.R.string.cancel, null)
                    .setCancelable(false)
                    .create();
                mDialog.getWindow().setType(ServiceProtocol.FLOATING_WINDOW_TYPE);
            }
            OverlayWindow.show(mDialog);
        }

        /**
         * Computes the window size from the stream geometry and maps the normalized location onto
         * the allowed center range.
         *
         * <p>Before the first descriptor the fit policy reports {@code fallback}, which is the
         * legacy "bounds times percentage" size; afterwards the window keeps the content aspect
         * ratio and is rotated with it. The position is recomputed because the allowed center range
         * depends on the window size.</p>
         */
        private void updateLayoutParams() {
            mLayoutParams.alpha = mOpacity / 100f;

            if (Preferences.get().isViewerKeepScreenOn()) {
                mLayoutParams.flags |= WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
            } else {
                mLayoutParams.flags &= ~WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
            }

            getMaxLayoutBounds(mBounds);
            final int minSize = mContext.getResources()
                .getDimensionPixelSize(R.dimen.screen_share_viewer_min_size);
            final ViewerFitPolicy.Fit fit = ViewerFitPolicy.fit(mContentWidth, mContentHeight,
                mRotationDegrees, mBounds.width(), mBounds.height(), mScale, minSize, mZoomMode);
            mLayoutParams.width = fit.getWidth();
            mLayoutParams.height = fit.getHeight();

            getLocationBounds(mBounds);
            mLayoutParams.x = (int) (mBounds.left + mBounds.width() * mLocation.x);
            mLayoutParams.y = (int) (mBounds.top + mBounds.height() * mLocation.y);
        }

        /**
         * Opens the display-mode menu for the current stream geometry.
         *
         * <p>Rows come from {@link ViewerZoomMenuModel} through {@link ViewerMenuFactory}: every row
         * states the scale it would apply, because the same window size can be reached by more than
         * one preset, and the integer preset is left out when it only repeats the actual-size
         * window. The display-size row opens its own submenu, because the percentage is stored
         * separately from the preset and one single-choice list cannot mark both. The trailing row
         * expands the session into the full-screen activity.</p>
         */
        private void showZoomModeDialog() {
            getMaxLayoutBounds(mBounds);
            final int minSize = mContext.getResources()
                .getDimensionPixelSize(R.dimen.screen_share_viewer_min_size);
            final List<ViewerZoomMenuModel.Entry> entries = ViewerZoomMenuModel.build(
                mContentWidth, mContentHeight, mRotationDegrees, mBounds.width(), mBounds.height(),
                mScale, minSize, mZoomMode, Preferences.get().isViewerHudEnabled());
            OverlayWindow.show(ViewerMenuFactory.createZoomDialog(mContext, entries, mScale,
                isAudioMuted.getAsBoolean()
                    ? R.string.viewer_audio_unmute_action
                    : R.string.viewer_audio_mute_action,
                R.string.viewer_action_expand_full_screen, true, mMenuListener));
        }

        /**
         * Persists and applies a zoom preset; the viewing percentage is left untouched.
         */
        private void setZoomMode(@NonNull final ZoomMode mode) {
            if (mZoomMode == mode) {
                return;
            }
            mZoomMode = mode;
            Preferences.get().setViewerZoomMode(mode.wire());
            if (mPanelView.getParent() != null) {
                updateView();
            }
        }

        /**
         * Persists and applies a viewing percentage; the zoom preset is left untouched.
         */
        private void setViewerScale(final int percent) {
            if (mScale == percent) {
                return;
            }
            mScale = percent;
            Preferences.get().setViewerScale(percent);
            if (mPanelView.getParent() != null) {
                updateView();
            }
        }

        /**
         * Persists the debug-overlay flag and applies it to the running session.
         */
        private void setHudEnabled(final boolean enabled) {
            Preferences.get().setViewerHudEnabled(enabled);
            updateHudVisibility();
            onHudPreferenceChanged.run();
        }

        private void updateLocation(final int x, final int y) {
            getLocationBounds(mBounds);

            final int finalX = Math.max(mBounds.left, Math.min(x, mBounds.right));
            final int finalY = Math.max(mBounds.top, Math.min(y, mBounds.bottom));
            mLocation.set(mBounds.width() <= 0 ? 0f : (float) (finalX - mBounds.left) / mBounds.width(),
                mBounds.height() <= 0 ? 0f : (float) (finalY - mBounds.top) / mBounds.height());

            if (mLayoutParams.x != finalX || mLayoutParams.y != finalY) {
                mLayoutParams.x = finalX;
                mLayoutParams.y = finalY;
                OverlayWindow.update(mWindowManager, mPanelView, mLayoutParams);
            }
        }

        private void getLocationBounds(@NonNull final Rect rect) {
            getMaxLayoutBounds(rect);
            rect.left += mLayoutParams.width / 2;
            rect.top += mLayoutParams.height / 2;
            rect.right -= mLayoutParams.width / 2;
            rect.bottom -= mLayoutParams.height / 2;
        }

        private void getMaxLayoutBounds(@NonNull final Rect rect) {
            rect.setEmpty();
            mPanelView.getWindowVisibleDisplayFrame(rect);
            if (rect.isEmpty()) {
                // The panel is not attached yet (mounted before its first layout pass), so the
                // window frame is unavailable; size the initial window from the display metrics.
                final DisplayMetrics metrics = mContext.getResources().getDisplayMetrics();
                rect.set(0, 0, metrics.widthPixels, metrics.heightPixels);
            }
            rect.offsetTo(0, 0);
            rect.offset(-rect.width() / 2, -rect.height() / 2);
        }
    }

    /**
     * Floating, draggable viewer for received frames.
     *
     * <p>Created and used on the main thread. The first {@link #show()} applies the stored
     * opacity, scale and normalized location; later frames only replace the bitmap and, when the
     * frame size or the secure flag changes, the window layout. A long press starts dragging, a
     * single tap opens the disconnect confirmation and a double tap opens the settings sheet.
     * Settings are applied live and persisted when a slider is released. The frame reference
     * passed to {@link #setFrame} is owned by the viewer until the next frame or
     * {@link #dismiss()} clears it.</p>
     */
    private static class ScreenShareViewer {
        private static final float CORNER_SIZE_RATIO = 1 / 16f;

        private static final int MIN_OPACITY = 50;

        private static final int MAX_OPACITY = 100;

        private static final int OPACITY_STEP = 1;

        private static final int MIN_SCALE = 25;

        private static final int MAX_SCALE = 100;

        private static final int SCALE_STEP = 1;

        // Live copies of the persisted viewer settings; the slider listeners update them.
        private int mOpacity;

        private int mScale;

        // Current frame; owned by this viewer until it is replaced or cleared.
        @Nullable
        private MutableReference<Bitmap> mFrame;

        // Pixel size of the current frame; null before the first frame and after dismiss().
        @Nullable
        private Size mFrameSize;

        // Last matrix applied to the frame view; compared before touching the view.
        @Nullable
        private Matrix mMatrix;

        // Disconnect confirmation, created on demand as a floating window.
        @Nullable
        private AlertDialog mDialog;

        // Settings sheet, created on demand and dismissed with the viewer.
        @Nullable
        private BottomSheetDialog mSettingsDialog;

        @NonNull
        private final Context mContext;

        @NonNull
        private final DeviceInfo mServiceInfo;

        @NonNull
        private final WindowManager mWindowManager;

        @NonNull
        private final ShapeableImageView mFrameView;

        @NonNull
        private final WindowManager.LayoutParams mLayoutParams;

        @NonNull
        private final GestureDetector mGestureDetector;

        // Normalized position within the allowed bounds, in the range [0, 1].
        @NonNull
        private final PointF mLocation = new PointF();

        // Scratch rectangle reused by the bounds calculations.
        @NonNull
        private final Rect mBounds = new Rect();

        // Difference between the window origin and the touch point while dragging.
        @NonNull
        private final PointF mDragOffset = new PointF();

        @NonNull
        private final LabelFormatter mPercentageLabelFormatter = new LabelFormatter() {
            @NonNull
            @Override
            public String getFormattedValue(float value) {
                return NumberFormat.getPercentInstance().format(value / 100f);
            }
        };

        @SuppressWarnings("FieldCanBeLocal")
        @NonNull
        private final GestureDetector.OnGestureListener mOnGestureListener =
            new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onDown(@NonNull MotionEvent e) {
                    return true;
                }

                @SuppressWarnings("DataFlowIssue")
                @Override
                public boolean onSingleTapConfirmed(@NonNull MotionEvent e) {
                    if (mDialog == null) {
                        mDialog = new MaterialAlertDialogBuilder(mContext)
                            .setMessage(HtmlCompat.fromHtml(mContext.getString(R.string.screen_share_disconnect_confirm,
                                nameOf(mServiceInfo)), HtmlCompat.FROM_HTML_MODE_LEGACY))
                            .setPositiveButton(android.R.string.ok, (dialog, which) -> mContext.sendBroadcast(
                                new Intent(ServiceProtocol.ACTION_STOP_CLIENT_SERVICE).setPackage(mContext.getPackageName())))
                            .setNegativeButton(android.R.string.cancel, null)
                            .setCancelable(false)
                            .create();
                        mDialog.getWindow().setType(ServiceProtocol.FLOATING_WINDOW_TYPE);
                    }
                    OverlayWindow.show(mDialog);
                    return true;
                }

                @Override
                public boolean onDoubleTap(@NonNull MotionEvent e) {
                    if (mSettingsDialog == null) {
                        mSettingsDialog = createSettingsDialog();
                    }
                    OverlayWindow.show(mSettingsDialog);
                    return true;
                }

                @Override
                public void onLongPress(@NonNull MotionEvent e) {
                    mFrameView.setActivated(true);
                    mDragOffset.set(mLayoutParams.x - e.getRawX(), mLayoutParams.y - e.getRawY());
                }
            };

        @SuppressLint("InflateParams")
        private ScreenShareViewer(@NonNull final Context context, @NonNull final DeviceInfo serviceInfo) {
            mContext = new ContextThemeWrapper(context, R.style.Theme_ScreenShare);
            mServiceInfo = serviceInfo;
            mWindowManager = mContext.getSystemService(WindowManager.class);
            mFrameView = createFrameView();
            mLayoutParams = new WindowManager.LayoutParams(0, 0, 0, 0, ServiceProtocol.FLOATING_WINDOW_TYPE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
            mLayoutParams.gravity = Gravity.CENTER;
            mGestureDetector = new GestureDetector(mContext, mOnGestureListener);
        }

        /**
         * Stores a new frame and updates the overlay only when its geometry or security changes.
         *
         * <p>Takes ownership of the reference and clears the previous frame. The bitmap is shown
         * immediately; the window layout is touched only when the frame size or the secure flag
         * differs from the previous frame.</p>
         */
        @SuppressWarnings("DataFlowIssue")
        public void setFrame(@NonNull final MutableReference<Bitmap> frame, boolean isSecure) {
            final MutableReference<Bitmap> oldFrame = mFrame;
            mFrame = frame;

            final Bitmap bitmap = mFrame.getValue();
            mFrameView.setImageBitmap(bitmap);

            final MutableBoolean shouldUpdate = new MutableBoolean();
            // A size change invalidates the fit scale, the image matrix and the corner radius.
            if (mFrameSize == null || mFrameSize.getWidth() != bitmap.getWidth()
                || mFrameSize.getHeight() != bitmap.getHeight()) {
                shouldUpdate.setTrue();
                mFrameSize = new Size(bitmap.getWidth(), bitmap.getHeight());
                updateLayoutParams();
                updateMatrix();
                updateShape();
            }

            // Mirrors the frame's secure flag onto the window; a secure window is excluded from
            // screenshots and screen recordings.
            if ((mLayoutParams.flags & WindowManager.LayoutParams.FLAG_SECURE) == 0 == isSecure) {
                shouldUpdate.setTrue();
                if (isSecure) {
                    mLayoutParams.flags |= WindowManager.LayoutParams.FLAG_SECURE;
                } else {
                    mLayoutParams.flags &= ~WindowManager.LayoutParams.FLAG_SECURE;
                }
            }

            if (shouldUpdate.isTrue()) {
                OverlayWindow.update(mWindowManager, mFrameView, mLayoutParams);
            }

            // Clearing the previous reference offers its bitmap back to the client's reuse pool,
            // or recycles it when the pool is full or the client is stopped.
            if (oldFrame != null) {
                oldFrame.clear();
            }
        }

        /**
         * Adds the overlay window the first time it is called and loads the persisted viewer
         * settings at that moment. Later calls are no-ops while the view is attached.
         */
        public void show() {
            if (mFrameView.getParent() == null) {
                mOpacity = Preferences.get().getViewerOpacity();
                mScale = Preferences.get().getViewerScale();
                mLocation.set(Preferences.get().getViewerLocation());
                updateLayoutParams();
                updateMatrix();
                updateShape();
                OverlayWindow.attach(mWindowManager, mFrameView, mLayoutParams);
            }
        }

        /**
         * Removes the overlay, closes its dialogs and clears the current frame. Safe to call when
         * the view is not attached.
         */
        public void dismiss() {
            mFrameSize = null;
            mLayoutParams.flags &= ~WindowManager.LayoutParams.FLAG_SECURE;
            mFrameView.setImageBitmap(null);

            if (mSettingsDialog != null) {
                OverlayWindow.dismiss(mSettingsDialog);
                mSettingsDialog = null;
            }

            if (mDialog != null) {
                OverlayWindow.dismiss(mDialog);
                mDialog = null;
            }

            if (mFrame != null) {
                mFrame.clear();
                mFrame = null;
            }

            OverlayWindow.detach(mWindowManager, mFrameView);
        }

        private void updateView() {
            updateLayoutParams();
            updateMatrix();
            updateShape();
            OverlayWindow.update(mWindowManager, mFrameView, mLayoutParams);
        }

        private void updateMatrix() {
            final Matrix matrix = new Matrix();
            if (mFrameSize != null && mFrameSize.getWidth() > 0 && mFrameSize.getHeight() > 0) {
                matrix.setScale((float) mLayoutParams.width / mFrameSize.getWidth(),
                    (float) mLayoutParams.height / mFrameSize.getHeight());
            }

            if (!Objects.equals(mMatrix, matrix)) {
                mMatrix = matrix;
                mFrameView.setImageMatrix(mMatrix);
            }
        }

        private void updateShape() {
            final float cornerSize;
            if (Preferences.get().isViewerRounded()) {
                final float maxCornerSize = mContext.getResources().getDimension(
                    ResourceUtils.resolveAttribute(mContext, com.google.android.material.R.attr.shapeCornerSizeLarge));
                cornerSize = Math.min(Math.min(mLayoutParams.width, mLayoutParams.height) * CORNER_SIZE_RATIO,
                    maxCornerSize);
            } else {
                cornerSize = 0f;
            }

            final ShapeAppearanceModel shapeAppearanceModel = ShapeAppearanceModel.builder()
                .setAllCorners(CornerFamily.ROUNDED, cornerSize)
                .build();
            mFrameView.setShapeAppearanceModel(shapeAppearanceModel);

            final MaterialShapeDrawable foreground = new MaterialShapeDrawable(shapeAppearanceModel);
            foreground.setFillColor(ContextCompat.getColorStateList(mContext, R.color.screen_share_viewer_foreground));
            mFrameView.setForeground(foreground);
        }

        // Fits the frame into the visible display bounds at the user scale, never below the
        // minimum viewer size, then maps the normalized location onto the allowed center range.
        private void updateLayoutParams() {
            mLayoutParams.alpha = mOpacity / 100f;

            if (Preferences.get().isViewerKeepScreenOn()) {
                mLayoutParams.flags |= WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
            } else {
                mLayoutParams.flags &= ~WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
            }

            getMaxLayoutBounds(mBounds);

            if (mFrameSize == null || mFrameSize.getWidth() <= 0 || mFrameSize.getHeight() <= 0) {
                mLayoutParams.width = 0;
                mLayoutParams.height = 0;
            } else {
                final int minSize = mContext.getResources().getDimensionPixelSize(R.dimen.screen_share_viewer_min_size);
                final float scale = Math.min(mBounds.width() * mScale / 100f / mFrameSize.getWidth(),
                    mBounds.height() * mScale / 100f / mFrameSize.getHeight());
                final float finalScale = NumberUtils.max((float) minSize / mFrameSize.getWidth(),
                    (float) minSize / mFrameSize.getHeight(), scale);
                mLayoutParams.width = (int) (mFrameSize.getWidth() * finalScale);
                mLayoutParams.height = (int) (mFrameSize.getHeight() * finalScale);
            }

            getLocationBounds(mBounds);
            mLayoutParams.x = (int) (mBounds.left + mBounds.width() * mLocation.x);
            mLayoutParams.y = (int) (mBounds.top + mBounds.height() * mLocation.y);
        }

        // Clamps the window position to the allowed center range and stores it normalized.
        private void updateLocation(final int x, final int y) {
            getLocationBounds(mBounds);

            final int finalX = Math.max(mBounds.left, Math.min(x, mBounds.right));
            final int finalY = Math.max(mBounds.top, Math.min(y, mBounds.bottom));
            mLocation.set(mBounds.width() <= 0 ? 0f : (float) (finalX - mBounds.left) / mBounds.width(),
                mBounds.height() <= 0 ? 0f : (float) (finalY - mBounds.top) / mBounds.height());

            if (mLayoutParams.x != finalX || mLayoutParams.y != finalY) {
                mLayoutParams.x = finalX;
                mLayoutParams.y = finalY;
                OverlayWindow.update(mWindowManager, mFrameView, mLayoutParams);
            }
        }

        // The rectangle describes allowed window centers, so it is inset by half the window size.
        private void getLocationBounds(@NonNull final Rect rect) {
            getMaxLayoutBounds(rect);
            rect.left += mLayoutParams.width / 2;
            rect.top += mLayoutParams.height / 2;
            rect.right -= mLayoutParams.width / 2;
            rect.bottom -= mLayoutParams.height / 2;
        }

        // Visible display frame with the origin moved to its center.
        private void getMaxLayoutBounds(@NonNull final Rect rect) {
            mFrameView.getWindowVisibleDisplayFrame(rect);
            rect.offsetTo(0, 0);
            rect.offset(-rect.width() / 2, -rect.height() / 2);
        }

        /**
         * Builds the viewer settings sheet.
         *
         * <p>Opacity, scale and the switches apply immediately; slider values are persisted when
         * the finger is released. The current values are loaded when the sheet is shown, because
         * the same instance is reused.</p>
         */
        @SuppressWarnings("DataFlowIssue")
        @NonNull
        private BottomSheetDialog createSettingsDialog() {
            final BottomSheetDialog settingsDialog = new BottomSheetDialog(mContext);
            settingsDialog.setContentView(R.layout.widget_screen_share_viewer_settings);
            settingsDialog.setDismissWithAnimation(true);
            settingsDialog.getWindow().setType(ServiceProtocol.FLOATING_WINDOW_TYPE);
            settingsDialog.getBehavior().setSkipCollapsed(true);

            final Slider opacitySettingView = settingsDialog.findViewById(R.id.opacity_setting);
            opacitySettingView.setValue(MIN_OPACITY);
            opacitySettingView.setValueFrom(MIN_OPACITY);
            opacitySettingView.setValueTo(MAX_OPACITY);
            opacitySettingView.setStepSize(OPACITY_STEP);
            opacitySettingView.setLabelFormatter(mPercentageLabelFormatter);
            opacitySettingView.addOnChangeListener((slider, value, fromUser) -> {
                if (mOpacity != (int) value) {
                    mOpacity = (int) value;
                    updateLayoutParams();
                    OverlayWindow.update(mWindowManager, mFrameView, mLayoutParams);
                }
            });
            opacitySettingView.addOnSliderTouchListener(new Slider.OnSliderTouchListener() {
                @Override
                public void onStartTrackingTouch(@NonNull Slider slider) {
                }

                @Override
                public void onStopTrackingTouch(@NonNull Slider slider) {
                    Preferences.get().setViewerOpacity(mOpacity);
                }
            });

            final Slider scaleSettingView = settingsDialog.findViewById(R.id.scale_setting);
            scaleSettingView.setValue(MIN_SCALE);
            scaleSettingView.setValueFrom(MIN_SCALE);
            scaleSettingView.setValueTo(MAX_SCALE);
            scaleSettingView.setStepSize(SCALE_STEP);
            scaleSettingView.setLabelFormatter(mPercentageLabelFormatter);
            scaleSettingView.addOnChangeListener((slider, value, fromUser) -> {
                if (mScale != (int) value) {
                    mScale = (int) value;
                    updateView();
                }
            });
            scaleSettingView.addOnSliderTouchListener(new Slider.OnSliderTouchListener() {
                @Override
                public void onStartTrackingTouch(@NonNull Slider slider) {
                }

                @Override
                public void onStopTrackingTouch(@NonNull Slider slider) {
                    Preferences.get().setViewerScale(mScale);
                }
            });

            final MaterialSwitch keepScreenOnSettingView = settingsDialog.findViewById(R.id.keep_screen_on_setting);
            keepScreenOnSettingView.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (Preferences.get().isViewerKeepScreenOn() != isChecked) {
                    Preferences.get().setViewerKeepScreenOn(isChecked);
                    updateLayoutParams();
                    OverlayWindow.update(mWindowManager, mFrameView, mLayoutParams);
                }
            });

            final MaterialSwitch roundedSettingView = settingsDialog.findViewById(R.id.rounded_setting);
            roundedSettingView.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (Preferences.get().isViewerRounded() != isChecked) {
                    Preferences.get().setViewerRounded(isChecked);
                    updateShape();
                }
            });

            settingsDialog.setOnShowListener(dialog -> {
                settingsDialog.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
                opacitySettingView.setValue(mOpacity);
                scaleSettingView.setValue(mScale);
                keepScreenOnSettingView.setChecked(Preferences.get().isViewerKeepScreenOn());
                keepScreenOnSettingView.jumpDrawablesToCurrentState();
                roundedSettingView.setChecked(Preferences.get().isViewerRounded());
                roundedSettingView.jumpDrawablesToCurrentState();
            });

            return settingsDialog;
        }

        /**
         * Builds the frame view: bitmap filtering, re-layout on display configuration changes and
         * the drag gesture on top of the tap gestures detected by the gesture detector.
         *
         * <p>Dragging is armed by a long press; releasing the finger persists the normalized
         * position.</p>
         */
        @NonNull
        private ShapeableImageView createFrameView() {
            final ShapeableImageView frameView = new ShapeableImageView(mContext) {
                // Smooths the bitmap when it is scaled to the window size.
                @NonNull
                private final DrawFilter mDrawFilter =
                    new PaintFlagsDrawFilter(0, Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

                @Override
                protected void onDraw(Canvas canvas) {
                    canvas.setDrawFilter(mDrawFilter);
                    super.onDraw(canvas);
                }

                @Override
                protected void onConfigurationChanged(Configuration newConfig) {
                    super.onConfigurationChanged(newConfig);
                    // Bounds change with rotation and multi-window; re-fit after the new
                    // configuration has been applied.
                    post(() -> updateView());
                }

                // Drag uses raw screen coordinates because the window itself moves under the
                // finger.
                @SuppressLint("ClickableViewAccessibility")
                @Override
                public boolean onTouchEvent(@NonNull MotionEvent event) {
                    switch (event.getActionMasked()) {
                        case MotionEvent.ACTION_MOVE:
                            if (mFrameView.isActivated()) {
                                updateLocation((int) (event.getRawX() + mDragOffset.x),
                                    (int) (event.getRawY() + mDragOffset.y));
                            }
                            break;
                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_CANCEL:
                            if (mFrameView.isActivated()) {
                                mFrameView.setActivated(false);
                                Preferences.get().setViewerLocation(mLocation);
                            }
                            break;
                        default:
                            break;
                    }

                    return mGestureDetector.onTouchEvent(event) || super.onTouchEvent(event);
                }
            };

            frameView.setAdjustViewBounds(true);
            frameView.setScaleType(ImageView.ScaleType.FIT_XY);
            frameView.setElevation(mContext.getResources().getDimension(R.dimen.screen_share_viewer_elevation));
            MaterialShapeUtils.setParentAbsoluteElevation(frameView);

            return frameView;
        }
    }
}
