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
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PaintFlagsDrawFilter;
import android.graphics.PixelFormat;
import android.graphics.PointF;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
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
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.widget.ImageView;

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
import com.obby.android.localscreenshare.R;
import com.obby.android.localscreenshare.client.ClientMediaReceiver;
import com.obby.android.localscreenshare.client.ClientSecureListener;
import com.obby.android.localscreenshare.client.ScreenShareClient;
import com.obby.android.localscreenshare.client.ClientObserver;
import com.obby.android.localscreenshare.client.ClientStats;
import com.obby.android.localscreenshare.client.ClientStatsListener;
import com.obby.android.localscreenshare.core.client.ClientMediaSession;
import com.obby.android.localscreenshare.core.client.ClientStateListener;
import com.obby.android.localscreenshare.core.codec.CodecIds;
import com.obby.android.localscreenshare.core.codec.CodecRegistry;
import com.obby.android.localscreenshare.core.codec.h264.H264Codec;
import com.obby.android.localscreenshare.core.model.ClientSessionKey;
import com.obby.android.localscreenshare.core.model.ClientState;
import com.obby.android.localscreenshare.core.model.StreamKey;
import com.obby.android.localscreenshare.core.stream.ClientCapabilities;
import com.obby.android.localscreenshare.core.util.TimeSource;
import com.obby.android.localscreenshare.discovery.favorite.FavoriteStore;
import com.obby.android.localscreenshare.discovery.model.DeviceAddress;
import com.obby.android.localscreenshare.discovery.model.DeviceInfo;
import com.obby.android.localscreenshare.render.SurfaceRenderTarget;
import com.obby.android.localscreenshare.settings.Preferences;
import com.obby.android.localscreenshare.support.MutableReference;
import com.obby.android.localscreenshare.support.ResourceUtils;
import com.obby.android.localscreenshare.support.ServiceProtocol;

import org.apache.commons.lang3.math.NumberUtils;
import org.apache.commons.lang3.mutable.MutableBoolean;

import java.text.NumberFormat;
import java.util.Collections;
import java.util.Objects;

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
 * target replaces it. {@link #stopService()} tears down the client, the overlays and the
 * foreground notification and calls {@link #stopSelf()}; {@link #onDestroy()} only unregisters the
 * stop receiver.</p>
 *
 * <p><b>Connection state.</b> The surface viewer is mounted when the session starts so its texture
 * view acquires a surface and the decoder can be created; the loading overlay is shown on top until
 * the first frame arrives. The first frame drives the session into {@code STREAMING}, which dismisses
 * the loading overlay; a later disconnect, or a failed first connection, closes the client, shows a
 * floating confirmation and offers a retry. The retry creates a new client because an
 * {@link ScreenShareClient} is single-use. Cancelling the confirmation broadcasts
 * {@link ServiceProtocol#ACTION_STOP_CLIENT_SERVICE}.</p>
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

    // Target of the current session; null while stopped.
    @Nullable
    private DeviceInfo mServiceInfo;

    // Single-use client; replaced by a new instance on retry and nulled on stop.
    @Nullable
    private ScreenShareClient mClient;

    // Last stats sample received on the main thread.
    @Nullable
    private ClientStats mClientStats;

    // Overlay shown while connecting; the same instance is reused across retries.
    @Nullable
    private ScreenShareLoading mScreenShareLoading;

    // Overlay shown once frames arrive; created on the first connect.
    @Nullable
    private ScreenShareViewer mScreenShareViewer;

    // Media session wiring: the registry resolves the H.264 decoder, the session drives it, and
    // the receiver feeds it from the wire on the client's dedicated decode thread.
    @Nullable
    private CodecRegistry mCodecRegistry;

    @Nullable
    private StreamKey mStreamKey;

    @Nullable
    private SurfaceRenderTarget mSurfaceRenderTarget;

    @Nullable
    private ClientMediaSession mClientMediaSession;

    @Nullable
    private ClientMediaReceiver mClientMediaReceiver;

    // Surface viewer mounted as soon as the session starts so its texture view exposes a surface;
    // created on the first media-session start and reused across retries.
    @Nullable
    private SurfaceShareViewer mSurfaceShareViewer;

    // Disconnect confirmation; created on demand and dismissed on stop.
    @Nullable
    private AlertDialog mDialog;

    // Distinguishes a lost connection from a failed first connection when choosing the message.
    private boolean mIsConnected;

    // Last rate rendered into the notification; the notification is rebuilt only on change.
    private long mInboundDataRate;

    private final String mTag = "ScreenViewerService@" + hashCode();

    // Main-thread delivery for decode-thread callbacks that touch the UI.
    @NonNull
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    @NonNull
    private final ClientStatsListener mClientStatsListener = this::onClientStatsChanged;

    // Client callbacks are delivered on the main thread; see ScreenShareClient.
    @NonNull
    private final ClientObserver mClientObserver = new ClientObserver() {
        @Override
        public void onConnected() {
            mIsConnected = true;
            // The address that just worked becomes the first favorite probe next time.
            if (mServiceInfo != null) {
                FavoriteStore.get().recordSuccess(mServiceInfo.getId(),
                    hostOf(mServiceInfo), portOf(mServiceInfo),
                    TimeSource.MONOTONIC.nowMs());
            }
        }

        @SuppressWarnings("DataFlowIssue")
        @Override
        public void onDisconnected() {
            // A session that was connected reports a reconnect prompt; a failed first connection
            // reports a retry prompt.
            final int messageResId = mIsConnected ? R.string.screen_share_reconnect_confirm
                : R.string.screen_share_retry_connect_confirm;
            mIsConnected = false;
            mClientStats = null;

            updateInboundDataRate(0L);

            if (mDialog != null) {
                mDialog.dismiss();
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

            if (mClient != null) {
                mClient.stop();
                mClient = null;
            }

            // An ScreenShareClient cannot be restarted, so a retry builds a new instance and shows the
            // loading overlay again. The confirmation is a floating window because the activity
            // may not be visible.
            final Context themedContext = new ContextThemeWrapper(ScreenViewerService.this, R.style.Theme_ScreenShare);
            mDialog = new MaterialAlertDialogBuilder(themedContext)
                .setMessage(HtmlCompat.fromHtml(getString(messageResId, nameOf(mServiceInfo)),
                    HtmlCompat.FROM_HTML_MODE_LEGACY))
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    final ClientMediaReceiver receiver = startMediaSession();
                    mClient = new ScreenShareClient(hostOf(mServiceInfo), portOf(mServiceInfo));
                    mClient.setClientStatsListener(mClientStatsListener);
                    mScreenShareLoading.show();
                    mClient.start(mClientObserver, receiver);
                })
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> sendBroadcast(
                    new Intent(ServiceProtocol.ACTION_STOP_CLIENT_SERVICE).setPackage(getPackageName())))
                .setCancelable(false)
                .create();
            mDialog.getWindow().setType(ServiceProtocol.FLOATING_WINDOW_TYPE);
            mDialog.show();
        }
    };

    // Runs on the client's decode thread; UI work is posted back to the main thread.
    @NonNull
    private final ClientStateListener mClientStateListener = new ClientStateListener() {
        @Override
        public void onStateChanged(@NonNull ClientState previous, @NonNull ClientState current) {
            if (current != ClientState.STREAMING) {
                return;
            }
            mMainHandler.post(() -> {
                if (mScreenShareLoading != null) {
                    mScreenShareLoading.dismiss();
                }
                if (mSurfaceShareViewer != null) {
                    mSurfaceShareViewer.show();
                }
            });
        }
    };

    // Runs on the client's decode thread; applies the secure flag on the main thread.
    @NonNull
    private final ClientSecureListener mClientSecureListener = new ClientSecureListener() {
        @Override
        public void onSecureChanged(final boolean secure) {
            mMainHandler.post(() -> {
                if (mSurfaceShareViewer != null) {
                    mSurfaceShareViewer.setSecure(secure);
                }
            });
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

        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(ServiceProtocol.ACTION_STOP_CLIENT_SERVICE);
        ContextCompat.registerReceiver(this, mBroadcastReceiver, intentFilter, ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    /**
     * Unregisters the stop receiver; the session itself was already released by
     * {@link #stopService()}.
     */
    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.i(mTag, "onDestroy: service destroyed");

        unregisterReceiver(mBroadcastReceiver);
    }

    /**
     * Returns {@code null}: the client service is start-only and is never bound.
     */
    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
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
            mDialog.dismiss();
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
        mClient = new ScreenShareClient(hostOf(mServiceInfo), portOf(mServiceInfo));
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
     * Releases the client, the overlays and the foreground notification, then stops the service.
     *
     * <p>Every step null-checks its target so a partially started session can take this path as
     * well. The client is stopped before it is dropped, and {@link #stopSelf()} delivers
     * {@link #onDestroy()} afterwards.</p>
     */
    private void stopService() {
        mServiceInfo = null;
        mIsConnected = false;
        mClientStats = null;
        mInboundDataRate = 0L;

        if (mDialog != null) {
            mDialog.dismiss();
            mDialog = null;
        }

        if (mScreenShareLoading != null) {
            mScreenShareLoading.dismiss();
            mScreenShareLoading = null;
        }

        teardownMediaSession();

        if (mSurfaceShareViewer != null) {
            mSurfaceShareViewer.dismiss();
            mSurfaceShareViewer = null;
        }

        if (mClient != null) {
            mClient.stop();
            mClient = null;
        }

        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        stopSelf();
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
     * Builds the client media session for the current target.
     *
     * <p>The registry resolves the H.264 decoder; capabilities are pinned to H.264 until runtime
     * probing and JPEG fallback land. The surface render target wraps the viewer's texture view,
     * the receiver drives the session from wire messages on the client's decode thread, and the
     * surface viewer is mounted immediately so its texture view exposes the surface the decoder
     * needs.</p>
     */
    @NonNull
    private ClientMediaReceiver startMediaSession() {
        if (mCodecRegistry == null) {
            mCodecRegistry = CodecRegistry.withDefaults();
            mCodecRegistry.register(H264Codec.androidRegistration());
        }
        if (mStreamKey == null) {
            mStreamKey = new StreamKey("remote", "screen");
        }
        if (mSurfaceShareViewer == null) {
            mSurfaceShareViewer = new SurfaceShareViewer(this, mServiceInfo);
        }
        mSurfaceRenderTarget = new SurfaceRenderTarget(mSurfaceShareViewer.getTextureView(),
                () -> {
                    // A (re)available surface lets a resynchronizing session retry its decoder.
                    final ClientMediaSession session = mClientMediaSession;
                    if (session != null) {
                        session.retryConfigure();
                    }
                });
        final ClientMediaSession session = new ClientMediaSession(
                new ClientSessionKey(mStreamKey, "local"), mCodecRegistry,
                new ClientCapabilities(Collections.singleton(CodecIds.H264)),
                mSurfaceRenderTarget, mClientStateListener);
        mClientMediaSession = session;
        mClientMediaReceiver = new ClientMediaReceiver(mStreamKey, session, mClientSecureListener);

        // Mount the surface viewer now so its texture view acquires a surface; the H.264 decoder
        // cannot be created until a surface exists, and STREAMING (which used to trigger the mount)
        // is only reached once the decoder starts producing frames.
        mSurfaceShareViewer.show();

        return mClientMediaReceiver;
    }

    /**
     * Closes the media session and releases the surface target; idempotent.
     */
    private void teardownMediaSession() {
        if (mClientMediaReceiver != null) {
            mClientMediaReceiver.close();
            mClientMediaReceiver = null;
        }
        mClientMediaSession = null;
        if (mSurfaceRenderTarget != null) {
            mSurfaceRenderTarget.release();
            mSurfaceRenderTarget = null;
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
                mDialog.show();
            });
        }

        public void show() {
            if (mLoadingView.getParent() == null) {
                mWindowManager.addView(mLoadingView, mLayoutParams);
            }
        }

        public void dismiss() {
            if (mDialog != null) {
                mDialog.dismiss();
                mDialog = null;
            }

            if (mLoadingView.getParent() != null) {
                mWindowManager.removeViewImmediate(mLoadingView);
            }
        }
    }

    /**
     * Floating, draggable surface viewer for H.264 frames.
     *
     * <p>Hosts a {@link TextureView} that the decoder renders into directly, so no per-frame
     * callback is needed. Created and used on the main thread. A long press starts dragging and a
     * single tap opens the disconnect confirmation. Opacity, scale, corner rounding and the
     * normalized location come from the persisted viewer settings; the secure flag maps to
     * {@code FLAG_SECURE}. The round-rect outline mirrors the shape of the bitmap viewer this
     * window replaced. Stream-aspect sizing and the settings sheet are refined with the
     * window-surface work.</p>
     */
    private static class SurfaceShareViewer {
        // Corner radius as a fraction of the shorter window side, matching the bitmap viewer.
        private static final float CORNER_SIZE_RATIO = 1 / 16f;

        @NonNull
        private final Context mContext;

        @NonNull
        private final DeviceInfo mServiceInfo;

        @NonNull
        private final WindowManager mWindowManager;

        @NonNull
        private final TextureView mTextureView;

        @NonNull
        private final WindowManager.LayoutParams mLayoutParams;

        @NonNull
        private final GestureDetector mGestureDetector;

        @NonNull
        private final PointF mLocation = new PointF();

        @NonNull
        private final Rect mBounds = new Rect();

        @NonNull
        private final PointF mDragOffset = new PointF();

        @Nullable
        private AlertDialog mDialog;

        private int mOpacity;

        private int mScale;

        // Corner radius of the round-rect outline; 0 disables outline clipping.
        private float mCornerSize;

        private SurfaceShareViewer(@NonNull final Context context,
                                   @NonNull final DeviceInfo serviceInfo) {
            mContext = new ContextThemeWrapper(context, R.style.Theme_ScreenShare);
            mServiceInfo = serviceInfo;
            mWindowManager = mContext.getSystemService(WindowManager.class);
            mTextureView = new TextureView(mContext);
            mTextureView.setOpaque(false);
            // The outline is evaluated against the current view bounds, so the clipped corners
            // follow the window size while the radius keeps the bitmap viewer's ratio.
            mTextureView.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(@NonNull final View view, @NonNull final Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), mCornerSize);
                }
            });
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
                public void onLongPress(@NonNull MotionEvent e) {
                    mTextureView.setActivated(true);
                    mDragOffset.set(mLayoutParams.x - e.getRawX(), mLayoutParams.y - e.getRawY());
                }
            });
            mTextureView.setOnTouchListener((v, event) -> {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_MOVE:
                        if (mTextureView.isActivated()) {
                            updateLocation((int) (event.getRawX() + mDragOffset.x),
                                (int) (event.getRawY() + mDragOffset.y));
                        }
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (mTextureView.isActivated()) {
                            mTextureView.setActivated(false);
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

        @NonNull
        TextureView getTextureView() {
            return mTextureView;
        }

        void show() {
            if (mTextureView.getParent() == null) {
                mOpacity = Preferences.get().getViewerOpacity();
                mScale = Preferences.get().getViewerScale();
                mLocation.set(Preferences.get().getViewerLocation());
                updateLayoutParams();
                updateShape();
                mWindowManager.addView(mTextureView, mLayoutParams);
            }
        }

        /**
         * Applies the rounded-corner setting to the window outline.
         *
         * <p>The radius is a fixed fraction of the shorter window side, capped by the theme's
         * large corner size, as the bitmap viewer did. Outline clipping is disabled when rounding
         * is turned off or the window has no measurable size yet.</p>
         */
        private void updateShape() {
            if (!Preferences.get().isViewerRounded()) {
                mCornerSize = 0f;
                mTextureView.setClipToOutline(false);
                mTextureView.invalidateOutline();
                return;
            }

            final float maxCornerSize = mContext.getResources().getDimension(
                ResourceUtils.resolveAttribute(mContext, com.google.android.material.R.attr.shapeCornerSizeLarge));
            mCornerSize = Math.min(
                Math.min(mLayoutParams.width, mLayoutParams.height) * CORNER_SIZE_RATIO, maxCornerSize);

            mTextureView.setClipToOutline(mCornerSize > 0f);
            mTextureView.invalidateOutline();
        }

        void dismiss() {
            mLayoutParams.flags &= ~WindowManager.LayoutParams.FLAG_SECURE;
            if (mDialog != null) {
                mDialog.dismiss();
                mDialog = null;
            }
            if (mTextureView.getParent() != null) {
                mWindowManager.removeViewImmediate(mTextureView);
            }
        }

        void setSecure(final boolean secure) {
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
            if (mTextureView.getParent() != null) {
                mWindowManager.updateViewLayout(mTextureView, mLayoutParams);
            }
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
            mDialog.show();
        }

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
            mLayoutParams.width = Math.max(minSize, (int) (mBounds.width() * mScale / 100f));
            mLayoutParams.height = Math.max(minSize, (int) (mBounds.height() * mScale / 100f));

            getLocationBounds(mBounds);
            mLayoutParams.x = (int) (mBounds.left + mBounds.width() * mLocation.x);
            mLayoutParams.y = (int) (mBounds.top + mBounds.height() * mLocation.y);
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
                mWindowManager.updateViewLayout(mTextureView, mLayoutParams);
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
            mTextureView.getWindowVisibleDisplayFrame(rect);
            if (rect.isEmpty()) {
                // The texture view is not attached yet (mounted before its first layout pass), so
                // the window frame is unavailable; size the initial window from the display metrics.
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
                    mDialog.show();
                    return true;
                }

                @Override
                public boolean onDoubleTap(@NonNull MotionEvent e) {
                    if (mSettingsDialog == null) {
                        mSettingsDialog = createSettingsDialog();
                    }
                    mSettingsDialog.show();
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
                mWindowManager.updateViewLayout(mFrameView, mLayoutParams);
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
                mWindowManager.addView(mFrameView, mLayoutParams);
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
                mSettingsDialog.dismiss();
                mSettingsDialog = null;
            }

            if (mDialog != null) {
                mDialog.dismiss();
                mDialog = null;
            }

            if (mFrame != null) {
                mFrame.clear();
                mFrame = null;
            }

            if (mFrameView.getParent() != null) {
                mWindowManager.removeViewImmediate(mFrameView);
            }
        }

        private void updateView() {
            updateLayoutParams();
            updateMatrix();
            updateShape();
            mWindowManager.updateViewLayout(mFrameView, mLayoutParams);
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
                mWindowManager.updateViewLayout(mFrameView, mLayoutParams);
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
                    mWindowManager.updateViewLayout(mFrameView, mLayoutParams);
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
                    mWindowManager.updateViewLayout(mFrameView, mLayoutParams);
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
