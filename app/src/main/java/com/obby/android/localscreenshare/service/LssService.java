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
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.PointF;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.OverScroller;

import androidx.activity.result.ActivityResult;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.view.ContextThemeWrapper;
import androidx.camera.core.ImageProcessingUtil;
import androidx.core.app.NotificationChannelCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.GestureDetectorCompat;

import com.google.android.material.chip.Chip;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.protobuf.ByteString;
import com.obby.android.localscreenshare.MainActivity;
import com.obby.android.localscreenshare.R;
import com.obby.android.localscreenshare.grpc.screenstream.ScreenFrame;
import com.obby.android.localscreenshare.server.LssServer;
import com.obby.android.localscreenshare.server.LssServerInfo;
import com.obby.android.localscreenshare.server.LssServerInfoListener;
import com.obby.android.localscreenshare.server.LssServerStats;
import com.obby.android.localscreenshare.server.LssServerStatsListener;
import com.obby.android.localscreenshare.support.Constants;
import com.obby.android.localscreenshare.support.Preferences;
import com.obby.android.localscreenshare.utils.WindowUtils;

import org.apache.commons.lang3.time.DurationFormatUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

public class LssService extends Service {
    private static final String NOTIFICATION_CHANNEL_ID = "lss-service";

    private static final String VIRTUAL_DISPLAY_NAME = "LocalScreenShare";

    private static final String IMAGE_READER_HANDLER_THREAD_NAME = "lss-service-image-reader";

    private static final int NOTIFICATION_ID = 1;

    private static final int IMAGE_READER_MAX_IMAGES = 2;

    private static final int SCREEN_FRAME_QUALITY = 85;

    @Nullable
    private LssServer mServer;

    @Nullable
    private MediaProjection mMediaProjection;

    @Nullable
    private HandlerThread mImageReaderHandlerThread;

    @Nullable
    private Handler mImageReaderHandler;

    private volatile ImageReader mImageReader;

    @Nullable
    private VirtualDisplay mVirtualDisplay;

    @Nullable
    private ScreenShareChip mScreenShareChip;

    @Nullable
    private Bitmap mScreenFrameBitmap;

    @Nullable
    private LssServerInfo mServerInfo;

    @Nullable
    private LssServerStats mServerStats;

    private int mConnectionCount;

    private final String mTag = "LssService@" + hashCode();

    @NonNull
    private final Object mImageReaderLock = new Object();

    @NonNull
    private final List<Messenger> mClientMessengers = new CopyOnWriteArrayList<>();

    @NonNull
    private final Messenger mMessenger = new Messenger(new Handler(Looper.getMainLooper(), msg -> {
        switch (msg.what) {
            case Constants.MSG_REGISTER_SERVICE_CLIENT:
                registerServiceClient(msg.replyTo);
                return true;
            case Constants.MSG_UNREGISTER_SERVICE_CLIENT:
                unregisterServiceClient(msg.replyTo);
                return true;
            default:
                return false;
        }
    }));

    @NonNull
    private final LssServerInfoListener mServerInfoListener = this::onServerInfoChanged;

    @NonNull
    private final LssServerStatsListener mServerStatsListener = this::onServerStatsChanged;

    @NonNull
    private final MediaProjection.Callback mMediaProjectionCallback = new MediaProjection.Callback() {
        @Override
        public void onStop() {
            Log.i(mTag, "mMediaProjectionCallback.onStop: media projection stopped");
            stopService();
        }

        @Override
        public void onCapturedContentResize(int width, int height) {
            Log.i(mTag, String.format("mMediaProjectionCallback.onCapturedContentResize: captured content resized"
                + ", width = %d, height = %d", width, height));
        }

        @Override
        public void onCapturedContentVisibilityChanged(boolean isVisible) {
            Log.i(mTag, String.format("mMediaProjectionCallback.onCapturedContentVisibilityChanged: "
                + "captured content visibility changed, isVisible = %b", isVisible));
        }
    };

    @SuppressLint("RestrictedApi")
    @NonNull
    private final ImageReader.OnImageAvailableListener mOnImageAvailableListener = reader -> {
        if (mImageReader != reader) {
            return;
        }

        synchronized (mImageReaderLock) {
            if (mImageReader != reader) {
                return;
            }

            try (final Image image = reader.acquireLatestImage()) {
                if (image == null) {
                    return;
                }

                if (mScreenFrameBitmap == null || mScreenFrameBitmap.getWidth() != image.getWidth()
                    || mScreenFrameBitmap.getHeight() != image.getHeight()) {
                    Optional.ofNullable(mScreenFrameBitmap).ifPresent(Bitmap::recycle);
                    mScreenFrameBitmap =
                        Bitmap.createBitmap(image.getWidth(), image.getHeight(), Bitmap.Config.ARGB_8888);
                }

                final Image.Plane plane = image.getPlanes()[0];
                plane.getBuffer().rewind();
                ImageProcessingUtil.copyByteBufferToBitmap(mScreenFrameBitmap, plane.getBuffer(), plane.getRowStride());

                final byte[] data;
                try (final ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
                    mScreenFrameBitmap.compress(Bitmap.CompressFormat.JPEG, SCREEN_FRAME_QUALITY, outputStream);
                    data = outputStream.toByteArray();
                } catch (IOException e) {
                    return;
                }

                mServer.dispatchScreenFrame(ScreenFrame.newBuilder()
                    .setTimestamp(SystemClock.elapsedRealtimeNanos())
                    .setData(ByteString.copyFrom(data))
                    .build());
            }
        }
    };

    @NonNull
    private final VirtualDisplay.Callback mVirtualDisplayCallback = new VirtualDisplay.Callback() {
        @Override
        public void onPaused() {
            Log.i(mTag, "mVirtualDisplayCallback.onPaused: virtual display paused");
        }

        @Override
        public void onResumed() {
            Log.i(mTag, "mVirtualDisplayCallback.onResumed: virtual display resumed");
        }

        @Override
        public void onStopped() {
            Log.i(mTag, "mVirtualDisplayCallback.onStopped: virtual display stopped");
        }
    };

    @NonNull
    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Constants.ACTION_STOP_LSS_SERVICE.equals(intent.getAction())) {
                stopService();
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(mTag, "onCreate: service created");

        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Constants.ACTION_STOP_LSS_SERVICE);
        ContextCompat.registerReceiver(this, mBroadcastReceiver, intentFilter, ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.i(mTag, "onDestroy: service destroyed");

        unregisterReceiver(mBroadcastReceiver);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        Log.i(mTag, "onBind: service bound");
        return mMessenger.getBinder();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(mTag, String.format("onStartCommand: startId = %d", startId));

        if (mServer != null) {
            Log.w(mTag, "onStartCommand: server already running");
            return START_NOT_STICKY;
        }

        if (intent == null) {
            Log.w(mTag, "onStartCommand: intent is null");
            return START_NOT_STICKY;
        }

        createNotificationChannel();

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            } else {
                startForeground(NOTIFICATION_ID, buildNotification());
            }
        } catch (Exception e) {
            Log.e(mTag, "onStartCommand: start foreground failed", e);
            stopService();
            return START_NOT_STICKY;
        }

        mServer = new LssServer(this);
        mServer.setServerInfoListener(mServerInfoListener);
        mServer.setServerStatsListener(mServerStatsListener);

        try {
            mServer.start();
        } catch (IOException e) {
            Log.e(mTag, "onStartCommand: start server failed", e);
            stopService();
            return START_NOT_STICKY;
        }

        mMediaProjection = getMediaProjection(intent);
        mMediaProjection.registerCallback(mMediaProjectionCallback, new Handler(Looper.getMainLooper()));

        mImageReaderHandlerThread =
            new HandlerThread(IMAGE_READER_HANDLER_THREAD_NAME, Process.THREAD_PRIORITY_BACKGROUND);
        mImageReaderHandlerThread.start();
        mImageReaderHandler = new Handler(mImageReaderHandlerThread.getLooper());

        mImageReader = createImageReader();
        mImageReader.setOnImageAvailableListener(mOnImageAvailableListener, mImageReaderHandler);

        mVirtualDisplay = createVirtualDisplay(mMediaProjection, mImageReader.getSurface(), mVirtualDisplayCallback);

        mScreenShareChip = new ScreenShareChip(this);
        mScreenShareChip.attach();

        mClientMessengers.forEach(this::notifyServerStarted);

        return START_STICKY;
    }

    private void stopService() {
        Log.i(mTag, "stopService: stop service");

        mServerInfo = null;
        mServerStats = null;

        if (mScreenShareChip != null) {
            mScreenShareChip.detach();
            mScreenShareChip = null;
        }

        if (mScreenFrameBitmap != null) {
            mScreenFrameBitmap.recycle();
            mScreenFrameBitmap = null;
        }

        if (mVirtualDisplay != null) {
            mVirtualDisplay.release();
            mVirtualDisplay = null;
        }

        synchronized (mImageReaderLock) {
            if (mImageReader != null) {
                mImageReader.close();
                mImageReader = null;
            }
        }

        if (mImageReaderHandler != null) {
            mImageReaderHandler.removeCallbacksAndMessages(null);
            mImageReaderHandler = null;
        }

        if (mImageReaderHandlerThread != null) {
            mImageReaderHandlerThread.quit();
            mImageReaderHandlerThread = null;
        }

        if (mMediaProjection != null) {
            mMediaProjection.unregisterCallback(mMediaProjectionCallback);
            mMediaProjection.stop();
            mMediaProjection = null;
        }

        if (mServer != null) {
            mServer.stop();
            mServer = null;
        }

        mClientMessengers.forEach(this::notifyServerStopped);

        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void onServerInfoChanged(@NonNull final LssServerInfo serverInfo) {
        mServerInfo = serverInfo;
        mClientMessengers.forEach(this::notifyServerInfoChanged);
    }

    @SuppressLint("MissingPermission")
    private void onServerStatsChanged(@NonNull final LssServerStats serverStats) {
        mServerStats = serverStats;
        mClientMessengers.forEach(this::notifyServerStatsChanged);

        final int connectionCount = mServerStats.getTransports().size();
        if (mConnectionCount != connectionCount) {
            mConnectionCount = connectionCount;
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification());
        }
    }

    private void notifyServerStarted(@NonNull final Messenger messenger) {
        try {
            messenger.send(Message.obtain(null, Constants.MSG_SERVER_STARTED));
        } catch (RemoteException e) {
            // ignored
        }
    }

    private void notifyServerStopped(@NonNull final Messenger messenger) {
        try {
            messenger.send(Message.obtain(null, Constants.MSG_SERVER_STOPPED));
        } catch (RemoteException e) {
            // ignored
        }
    }

    private void notifyServerInfoChanged(@NonNull final Messenger messenger) {
        try {
            messenger.send(Message.obtain(null, Constants.MSG_SERVER_INFO_CHANGED, mServerInfo));
        } catch (RemoteException e) {
            // ignored
        }
    }

    private void notifyServerStatsChanged(@NonNull final Messenger messenger) {
        try {
            messenger.send(Message.obtain(null, Constants.MSG_SERVER_STATS_CHANGED, mServerStats));
        } catch (RemoteException e) {
            // ignored
        }
    }

    private void registerServiceClient(@NonNull final Messenger messenger) {
        Log.i(mTag, "registerServiceClient: register service client");

        if (mClientMessengers.contains(messenger)) {
            Log.w(mTag, "registerServiceClient: client already exists");
        } else {
            mClientMessengers.add(messenger);

            if (mServer == null) {
                notifyServerStopped(messenger);
            } else {
                notifyServerStarted(messenger);
            }

            if (mServerInfo != null) {
                notifyServerInfoChanged(messenger);
            }

            if (mServerStats != null) {
                notifyServerStatsChanged(messenger);
            }
        }
    }

    private void unregisterServiceClient(@NonNull final Messenger messenger) {
        Log.i(mTag, "unregisterServiceClient: unregister service client");

        if (!mClientMessengers.remove(messenger)) {
            Log.w(mTag, "unregisterServiceClient: client does not exist");
        }
    }

    @NonNull
    private VirtualDisplay createVirtualDisplay(@NonNull final MediaProjection mediaProjection,
        @NonNull final Surface surface, @NonNull final VirtualDisplay.Callback callback) {
        final Rect windowBounds = WindowUtils.getMaximumWindowBounds(this);
        return mediaProjection.createVirtualDisplay(VIRTUAL_DISPLAY_NAME, windowBounds.width(), windowBounds.height(),
            Resources.getSystem().getConfiguration().densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY, surface,
            callback, new Handler(Looper.getMainLooper()));
    }

    @NonNull
    private ImageReader createImageReader() {
        final Rect windowBounds = WindowUtils.getMaximumWindowBounds(this);
        return ImageReader.newInstance(windowBounds.width(), windowBounds.height(), PixelFormat.RGBA_8888,
            IMAGE_READER_MAX_IMAGES);
    }

    @SuppressWarnings("DataFlowIssue")
    @NonNull
    private MediaProjection getMediaProjection(@NonNull final Intent intent) {
        final ActivityResult mediaProjectionResult = intent.getParcelableExtra(Constants.EXTRA_MEDIA_PROJECTION_RESULT);
        final MediaProjectionManager mediaProjectionManager = getSystemService(MediaProjectionManager.class);
        return mediaProjectionManager.getMediaProjection(mediaProjectionResult.getResultCode(),
            mediaProjectionResult.getData());
    }

    private void createNotificationChannel() {
        final NotificationChannelCompat notificationChannel =
            new NotificationChannelCompat.Builder(NOTIFICATION_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(getString(R.string.lss_service_notification_channel_name))
                .build();
        NotificationManagerCompat.from(this).createNotificationChannel(notificationChannel);
    }

    @NonNull
    private Notification buildNotification() {
        return new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.lss_service_notification_title))
            .setContentText(getString(R.string.lss_service_notification_text, mConnectionCount))
            .setContentIntent(PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
            .addAction(0, getString(R.string.lss_service_stop_notification_action),
                PendingIntent.getBroadcast(this, 0,
                    new Intent(Constants.ACTION_STOP_LSS_SERVICE).setPackage(getPackageName()),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build();
    }

    private static class ScreenShareChip {
        private static final int WINDOW_TYPE = Build.VERSION.SDK_INT < Build.VERSION_CODES.O
            ? WindowManager.LayoutParams.TYPE_SYSTEM_ALERT : WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;

        private long mAttachTimestamp;

        @NonNull
        private final Context mContext;

        @NonNull
        private final WindowManager mWindowManager;

        @NonNull
        private final Chip mChipView;

        @NonNull
        private final WindowManager.LayoutParams mLayoutParams;

        @NonNull
        private final OverScroller mOverScroller;

        @NonNull
        private final GestureDetectorCompat mGestureDetector;

        @NonNull
        private final Rect mTransitionBounds = new Rect();

        @NonNull
        private final Runnable mTickRunnable = new Runnable() {
            @Override
            public void run() {
                mChipView.removeCallbacks(mTickRunnable);
                updateDuration();
                mChipView.postDelayed(mTickRunnable,
                    1000L - (SystemClock.elapsedRealtime() - mAttachTimestamp) % 1000L);
            }
        };

        @NonNull
        private final Runnable mTransitionRunnable = this::transition;

        @SuppressWarnings("FieldCanBeLocal")
        @NonNull
        private final GestureDetector.OnGestureListener mOnGestureListener =
            new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onDown(@NonNull MotionEvent e) {
                    return true;
                }

                @Override
                public boolean onSingleTapConfirmed(@NonNull MotionEvent e) {
                    final AlertDialog confirmDialog = new MaterialAlertDialogBuilder(mContext)
                        .setTitle(R.string.stop_screen_share_confirm)
                        .setPositiveButton(android.R.string.ok, (dialog, which) -> mContext.sendBroadcast(
                            new Intent(Constants.ACTION_STOP_LSS_SERVICE).setPackage(mContext.getPackageName())))
                        .setNegativeButton(android.R.string.cancel, null)
                        .setCancelable(false)
                        .create();
                    Optional.ofNullable(confirmDialog.getWindow()).ifPresent(window -> window.setType(WINDOW_TYPE));
                    confirmDialog.show();
                    return true;
                }

                @Override
                public boolean onDoubleTapEvent(@NonNull MotionEvent e) {
                    mContext.startActivity(new Intent(mContext, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
                    return true;
                }
            };

        private ScreenShareChip(@NonNull final Context context) {
            mContext = new ContextThemeWrapper(context, R.style.ScreenShareChipTheme);
            mWindowManager = mContext.getSystemService(WindowManager.class);
            mChipView = createChipView(mContext);
            mLayoutParams = new WindowManager.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, WINDOW_TYPE, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT);
            mLayoutParams.gravity = Gravity.START | Gravity.TOP;
            mOverScroller = new OverScroller(mContext);
            mGestureDetector = new GestureDetectorCompat(mContext, mOnGestureListener);
        }

        public void attach() {
            mAttachTimestamp = SystemClock.elapsedRealtime();
            mWindowManager.addView(mChipView, mLayoutParams);
            restorePosition();
            updateDuration();
            mChipView.post(mTickRunnable);
        }

        public void detach() {
            mChipView.removeCallbacks(mTickRunnable);
            mChipView.removeCallbacks(mTransitionRunnable);
            mWindowManager.removeViewImmediate(mChipView);
        }

        private void restorePosition() {
            getTransitionBounds(mTransitionBounds);
            final PointF position = Preferences.get().getServiceChipPosition();
            updatePosition((int) (mTransitionBounds.left + mTransitionBounds.width() * position.x),
                (int) (mTransitionBounds.top + mTransitionBounds.height() * position.y));
            transition();
        }

        private void transition() {
            mChipView.removeCallbacks(mTransitionRunnable);

            if (mOverScroller.computeScrollOffset()) {
                updatePosition(mOverScroller.getCurrX(), mOverScroller.getCurrY());
            } else {
                getTransitionBounds(mTransitionBounds);
                final int transitionX = mLayoutParams.x < mTransitionBounds.centerX() ? mTransitionBounds.left
                    : mTransitionBounds.right;
                final int transitionY = Math.max(mTransitionBounds.top,
                    Math.min(mLayoutParams.y, mTransitionBounds.bottom));

                Preferences.get().setServiceChipPosition(new PointF(
                    (float) (transitionX - mTransitionBounds.left) / mTransitionBounds.width(),
                    (float) (transitionY - mTransitionBounds.top) / mTransitionBounds.height()));

                if (mLayoutParams.x == transitionX && mLayoutParams.y == transitionY) {
                    return;
                }

                mOverScroller.startScroll(mLayoutParams.x, mLayoutParams.y, transitionX - mLayoutParams.x,
                    transitionY - mLayoutParams.y);
            }

            mChipView.post(mTransitionRunnable);
        }

        private void updatePosition(final int x, final int y) {
            getTransitionBounds(mTransitionBounds);
            mLayoutParams.x = Math.max(mTransitionBounds.left, Math.min(x, mTransitionBounds.right));
            mLayoutParams.y = Math.max(mTransitionBounds.top, Math.min(y, mTransitionBounds.bottom));
            mWindowManager.updateViewLayout(mChipView, mLayoutParams);
        }

        private void updateDuration() {
            mChipView.setText(DurationFormatUtils.formatDuration(SystemClock.elapsedRealtime() - mAttachTimestamp,
                "[HH:]mm:ss"));
        }

        private void getTransitionBounds(@NonNull final Rect rect) {
            mChipView.getWindowVisibleDisplayFrame(rect);
            rect.offsetTo(0, 0);

            final int horizontalMargin = mChipView.getResources()
                .getDimensionPixelSize(R.dimen.screen_share_chip_horizontal_margin);
            final int verticalMargin = mChipView.getResources()
                .getDimensionPixelSize(R.dimen.screen_share_chip_vertical_margin);
            rect.left += horizontalMargin;
            rect.top += verticalMargin;

            final int width;
            final int height;
            if (mChipView.getWidth() > 0 && mChipView.getHeight() > 0) {
                width = mChipView.getWidth();
                height = mChipView.getHeight();
            } else {
                mChipView.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                width = mChipView.getMeasuredWidth();
                height = mChipView.getMeasuredHeight();
            }

            rect.right -= width + horizontalMargin;
            rect.bottom -= height + verticalMargin;
        }

        @NonNull
        private Chip createChipView(@NonNull final Context context) {
            final Chip chipView = new Chip(context) {
                @NonNull
                private final PointF mTouchOffset = new PointF();

                @Override
                protected void onConfigurationChanged(Configuration newConfig) {
                    super.onConfigurationChanged(newConfig);
                    restorePosition();
                }

                @SuppressWarnings("SpellCheckingInspection")
                @Override
                protected void onSizeChanged(int w, int h, int oldw, int oldh) {
                    super.onSizeChanged(w, h, oldw, oldh);
                    restorePosition();
                }

                @SuppressLint("ClickableViewAccessibility")
                @Override
                public boolean onTouchEvent(@NonNull MotionEvent event) {
                    switch (event.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            mChipView.removeCallbacks(mTransitionRunnable);
                            mOverScroller.forceFinished(true);
                            mTouchOffset.set(mLayoutParams.x - event.getRawX(), mLayoutParams.y - event.getRawY());
                            break;
                        case MotionEvent.ACTION_MOVE:
                            updatePosition((int) (event.getRawX() + mTouchOffset.x),
                                (int) (event.getRawY() + mTouchOffset.y));
                            break;
                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_CANCEL:
                            transition();
                            break;
                        default:
                            break;
                    }

                    return mGestureDetector.onTouchEvent(event) || super.onTouchEvent(event);
                }
            };

            chipView.setChipIconResource(R.drawable.ic_screen_share_chip);
            chipView.setChipIconTint(MaterialColors.getColorStateListOrNull(context,
                com.google.android.material.R.attr.colorOnError));
            chipView.setTextColor(MaterialColors.getColorStateListOrNull(context,
                com.google.android.material.R.attr.colorOnError));
            chipView.setChipBackgroundColor(MaterialColors.getColorStateListOrNull(context,
                com.google.android.material.R.attr.colorError));

            return chipView;
        }
    }
}
