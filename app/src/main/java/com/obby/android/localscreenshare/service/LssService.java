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
import android.graphics.PixelFormat;
import android.graphics.Point;
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
import android.text.format.Formatter;
import android.util.Log;
import android.util.Size;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.OverScroller;
import android.widget.Toast;

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
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

public class LssService extends Service {
    private static final String NOTIFICATION_CHANNEL_ID = "lss-service";

    private static final String VIRTUAL_DISPLAY_NAME = "LocalScreenShare";

    private static final String IMAGE_READER_HANDLER_THREAD_NAME = "lss-service-image-reader";

    private static final int NOTIFICATION_ID = 1;

    private static final int IMAGE_READER_MAX_IMAGES = 2;

    @Nullable
    private LssServer mServer;

    @Nullable
    private MediaProjection mMediaProjection;

    @Nullable
    private Size mProjectionSize;

    @Nullable
    private HandlerThread mImageReaderHandlerThread;

    @Nullable
    private Handler mImageReaderHandler;

    private volatile ImageReader mImageReader;

    private volatile long mFrameTimestamp;

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

    private long mOutboundDataRate;

    private boolean mIsProjectionSecure;

    private int mProjectionScale;

    private int mProjectionQuality;

    private int mProjectionFrameRate;

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

    @NonNull
    private final ImageReader.OnImageAvailableListener mOnImageAvailableListener =
        new ImageReader.OnImageAvailableListener() {
            private final long mNanosPerSecond = Duration.ofSeconds(1L).toNanos();

            @SuppressLint("RestrictedApi")
            @Override
            public void onImageAvailable(ImageReader reader) {
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

                        if (mFrameTimestamp >= 0L
                            && image.getTimestamp() - mFrameTimestamp < mNanosPerSecond / mProjectionFrameRate) {
                            return;
                        }

                        mFrameTimestamp = image.getTimestamp();

                        if (mScreenFrameBitmap == null || mScreenFrameBitmap.getWidth() != image.getWidth()
                            || mScreenFrameBitmap.getHeight() != image.getHeight()) {
                            Optional.ofNullable(mScreenFrameBitmap).ifPresent(Bitmap::recycle);
                            mScreenFrameBitmap =
                                Bitmap.createBitmap(image.getWidth(), image.getHeight(), Bitmap.Config.ARGB_8888);
                        }

                        final Image.Plane plane = image.getPlanes()[0];
                        plane.getBuffer().rewind();
                        ImageProcessingUtil.copyByteBufferToBitmap(mScreenFrameBitmap, plane.getBuffer(),
                            plane.getRowStride());

                        final byte[] data;
                        try (final ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
                            mScreenFrameBitmap.compress(Bitmap.CompressFormat.JPEG, mProjectionQuality, outputStream);
                            data = outputStream.toByteArray();
                        } catch (IOException e) {
                            return;
                        }

                        mServer.postScreenFrame(ScreenFrame.newBuilder()
                            .setTimestamp(mFrameTimestamp)
                            .setData(ByteString.copyFrom(data))
                            .setSecure(mIsProjectionSecure)
                            .build());
                    }
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
            if (Constants.ACTION_STOP_SERVICE.equals(intent.getAction())) {
                stopService();
            }
        }
    };

    @NonNull
    private final Preferences.Observer mPreferencesObserver = key -> {
        if (key == null) {
            mIsProjectionSecure = Preferences.get().isProjectionSecure();
            mProjectionScale = Preferences.get().getProjectionScale();
            mProjectionQuality = Preferences.get().getProjectionQuality();
            mProjectionFrameRate = Preferences.get().getProjectionFrameRate();
            updateProjectionSize();
            return;
        }

        switch (key) {
            case Preferences.KEY_PROJECTION_SECURE:
                mIsProjectionSecure = Preferences.get().isProjectionSecure();
                break;
            case Preferences.KEY_PROJECTION_SCALE:
                mProjectionScale = Preferences.get().getProjectionScale();
                updateProjectionSize();
                break;
            case Preferences.KEY_PROJECTION_QUALITY:
                mProjectionQuality = Preferences.get().getProjectionQuality();
                break;
            case Preferences.KEY_PROJECTION_FRAME_RATE:
                mProjectionFrameRate = Preferences.get().getProjectionFrameRate();
                break;
            default:
                break;
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(mTag, "onCreate: service created");

        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Constants.ACTION_STOP_SERVICE);
        ContextCompat.registerReceiver(this, mBroadcastReceiver, intentFilter, ContextCompat.RECEIVER_NOT_EXPORTED);

        mIsProjectionSecure = Preferences.get().isProjectionSecure();
        mProjectionScale = Preferences.get().getProjectionScale();
        mProjectionQuality = Preferences.get().getProjectionQuality();
        mProjectionFrameRate = Preferences.get().getProjectionFrameRate();
        Preferences.get().addObserver(mPreferencesObserver);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.i(mTag, "onDestroy: service destroyed");

        unregisterReceiver(mBroadcastReceiver);
        Preferences.get().removeObserver(mPreferencesObserver);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateProjectionSize();
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
            Toast.makeText(this, R.string.start_service_failed, Toast.LENGTH_LONG).show();
            stopService();
            return START_NOT_STICKY;
        }

        mMediaProjection = getMediaProjection(intent);
        mMediaProjection.registerCallback(mMediaProjectionCallback, new Handler(Looper.getMainLooper()));
        mProjectionSize = getProjectionSize();

        mImageReaderHandlerThread =
            new HandlerThread(IMAGE_READER_HANDLER_THREAD_NAME, Process.THREAD_PRIORITY_BACKGROUND);
        mImageReaderHandlerThread.start();
        mImageReaderHandler = new Handler(mImageReaderHandlerThread.getLooper());

        mImageReader = ImageReader.newInstance(mProjectionSize.getWidth(), mProjectionSize.getHeight(),
            PixelFormat.RGBA_8888, IMAGE_READER_MAX_IMAGES);
        mFrameTimestamp = -1L;
        mImageReader.setOnImageAvailableListener(mOnImageAvailableListener, mImageReaderHandler);

        mVirtualDisplay = mMediaProjection.createVirtualDisplay(VIRTUAL_DISPLAY_NAME, mProjectionSize.getWidth(),
            mProjectionSize.getHeight(), getResources().getConfiguration().densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
            mImageReader.getSurface(), mVirtualDisplayCallback, new Handler(Looper.getMainLooper()));

        mScreenShareChip = new ScreenShareChip(this);
        mScreenShareChip.show();

        mClientMessengers.forEach(this::notifyServerStarted);

        return START_STICKY;
    }

    private void stopService() {
        Log.i(mTag, "stopService: stop service");

        mServerInfo = null;
        mProjectionSize = null;
        mServerStats = null;
        mConnectionCount = 0;
        mOutboundDataRate = 0L;

        if (mScreenShareChip != null) {
            mScreenShareChip.dismiss();
            mScreenShareChip = null;
        }

        if (mVirtualDisplay != null) {
            mVirtualDisplay.release();
            mVirtualDisplay = null;
        }

        synchronized (mImageReaderLock) {
            mFrameTimestamp = -1L;

            if (mScreenFrameBitmap != null) {
                mScreenFrameBitmap.recycle();
                mScreenFrameBitmap = null;
            }

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
        if (mConnectionCount == connectionCount && mOutboundDataRate == mServerStats.getOutboundDataRate()) {
            return;
        }

        mConnectionCount = connectionCount;
        mOutboundDataRate = mServerStats.getOutboundDataRate();
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification());
    }

    private void updateProjectionSize() {
        if (mProjectionSize == null || mVirtualDisplay == null) {
            return;
        }

        final Size size = getProjectionSize();
        if (Objects.equals(mProjectionSize, size)) {
            return;
        }

        mProjectionSize = size;
        mVirtualDisplay.resize(mProjectionSize.getWidth(), mProjectionSize.getHeight(),
            getResources().getConfiguration().densityDpi);

        synchronized (mImageReaderLock) {
            if (mImageReader == null) {
                return;
            }

            mImageReader.close();
            mImageReader = ImageReader.newInstance(mProjectionSize.getWidth(), mProjectionSize.getHeight(),
                PixelFormat.RGBA_8888, IMAGE_READER_MAX_IMAGES);
            mFrameTimestamp = -1L;
            mImageReader.setOnImageAvailableListener(mOnImageAvailableListener, mImageReaderHandler);
            mVirtualDisplay.setSurface(mImageReader.getSurface());
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

    @SuppressWarnings("DataFlowIssue")
    @NonNull
    private MediaProjection getMediaProjection(@NonNull final Intent intent) {
        final ActivityResult mediaProjectionResult = intent.getParcelableExtra(Constants.EXTRA_MEDIA_PROJECTION_RESULT);
        final MediaProjectionManager mediaProjectionManager = getSystemService(MediaProjectionManager.class);
        return mediaProjectionManager.getMediaProjection(mediaProjectionResult.getResultCode(),
            mediaProjectionResult.getData());
    }

    @NonNull
    private Size getProjectionSize() {
        final Rect windowBounds = WindowUtils.getMaximumWindowBounds(this);
        final float scale = mProjectionScale / 100f;
        return new Size((int) (windowBounds.width() * scale), (int) (windowBounds.height() * scale));
    }

    private void createNotificationChannel() {
        final NotificationChannelCompat notificationChannel =
            new NotificationChannelCompat.Builder(NOTIFICATION_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(getString(R.string.service_notification_channel_name))
                .build();
        NotificationManagerCompat.from(this).createNotificationChannel(notificationChannel);
    }

    @NonNull
    private Notification buildNotification() {
        return new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.service_notification_title))
            .setContentText(getString(R.string.service_notification_text, mConnectionCount,
                Formatter.formatFileSize(this, mOutboundDataRate)))
            .setContentIntent(PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
            .addAction(0, getString(R.string.service_stop_notification_action), PendingIntent.getBroadcast(this, 0,
                new Intent(Constants.ACTION_STOP_SERVICE).setPackage(getPackageName()),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build();
    }

    private static class ScreenShareChip {
        private long mStartTimestamp;

        private boolean mIsKeepScreenOn;

        @Nullable
        private AlertDialog mDialog;

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
        private final GestureDetector mGestureDetector;

        @NonNull
        private final Rect mLocationBounds = new Rect();

        @NonNull
        private final Runnable mTickRunnable = new Runnable() {
            @Override
            public void run() {
                mChipView.removeCallbacks(mTickRunnable);
                updateDuration();
                mChipView.postDelayed(mTickRunnable, 1000L - (SystemClock.elapsedRealtime() - mStartTimestamp) % 1000L);
            }
        };

        @NonNull
        private final Runnable mAdjustLocationRunnable = this::adjustLocation;

        @NonNull
        private final Preferences.Observer mPreferencesObserver = new Preferences.Observer() {
            @Override
            public void onChanged(@Nullable String key) {
                if (key == null || Preferences.KEY_PROJECTION_KEEP_SCREEN_ON.equals(key)) {
                    mIsKeepScreenOn = Preferences.get().isProjectionKeepScreenOn();
                    updateLayoutParams();
                    mWindowManager.updateViewLayout(mChipView, mLayoutParams);
                }
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
                            .setMessage(R.string.stop_screen_share_confirm)
                            .setPositiveButton(android.R.string.ok, (dialog, which) -> mContext.sendBroadcast(
                                new Intent(Constants.ACTION_STOP_SERVICE).setPackage(mContext.getPackageName())))
                            .setNegativeButton(android.R.string.cancel, null)
                            .setCancelable(false)
                            .create();
                        mDialog.getWindow().setType(Constants.FLOATING_WINDOW_TYPE);
                    }
                    mDialog.show();
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
            mChipView = createChipView();
            mLayoutParams = new WindowManager.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, Constants.FLOATING_WINDOW_TYPE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
            mLayoutParams.gravity = Gravity.CENTER;
            mOverScroller = new OverScroller(mContext);
            mGestureDetector = new GestureDetector(mContext, mOnGestureListener);
        }

        public void show() {
            mStartTimestamp = SystemClock.elapsedRealtime();
            mIsKeepScreenOn = Preferences.get().isProjectionKeepScreenOn();
            updateDuration();

            updateLayoutParams();
            mWindowManager.addView(mChipView, mLayoutParams);

            adjustLocation();
            mChipView.post(mTickRunnable);

            Preferences.get().addObserver(mPreferencesObserver);
        }

        public void dismiss() {
            mChipView.removeCallbacks(mTickRunnable);
            mChipView.removeCallbacks(mAdjustLocationRunnable);
            mWindowManager.removeViewImmediate(mChipView);
            Preferences.get().removeObserver(mPreferencesObserver);

            if (mDialog != null) {
                mDialog.dismiss();
                mDialog = null;
            }
        }

        private void adjustLocation() {
            mChipView.removeCallbacks(mAdjustLocationRunnable);

            if (mOverScroller.computeScrollOffset()) {
                updateLocation(mOverScroller.getCurrX(), mOverScroller.getCurrY());
            } else {
                getLocationBounds(mLocationBounds);
                final int locationX = mLayoutParams.x < mLocationBounds.centerX() ? mLocationBounds.left
                    : mLocationBounds.right;
                final int locationY = Math.max(mLocationBounds.top, Math.min(mLayoutParams.y, mLocationBounds.bottom));

                Preferences.get().setServiceChipLocation(new PointF(mLocationBounds.width() <= 0 ? 0f
                    : (float) (locationX - mLocationBounds.left) / mLocationBounds.width(),
                    mLocationBounds.height() <= 0 ? 0f
                        : (float) (locationY - mLocationBounds.top) / mLocationBounds.height()));

                if (mLayoutParams.x == locationX && mLayoutParams.y == locationY) {
                    return;
                }

                mOverScroller.startScroll(mLayoutParams.x, mLayoutParams.y, locationX - mLayoutParams.x,
                    locationY - mLayoutParams.y);
            }

            mChipView.post(mAdjustLocationRunnable);
        }

        private void updateLayoutParams() {
            if (mIsKeepScreenOn) {
                mLayoutParams.flags |= WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
            } else {
                mLayoutParams.flags &= ~WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
            }

            final Point location = getLocation();
            mLayoutParams.x = location.x;
            mLayoutParams.y = location.y;
        }

        private void updateLocation() {
            final Point location = getLocation();
            updateLocation(location.x, location.y);
            adjustLocation();
        }

        private void updateLocation(final int x, final int y) {
            getLocationBounds(mLocationBounds);

            final int finalX = Math.max(mLocationBounds.left, Math.min(x, mLocationBounds.right));
            final int finalY = Math.max(mLocationBounds.top, Math.min(y, mLocationBounds.bottom));
            if (mLayoutParams.x != finalX || mLayoutParams.y != finalY) {
                mLayoutParams.x = finalX;
                mLayoutParams.y = finalY;
                mWindowManager.updateViewLayout(mChipView, mLayoutParams);
            }
        }

        private void updateDuration() {
            mChipView.setText(DurationFormatUtils.formatDuration(SystemClock.elapsedRealtime() - mStartTimestamp,
                "[HH:]mm:ss"));
        }

        @NonNull
        private Point getLocation() {
            getLocationBounds(mLocationBounds);
            final PointF location = Preferences.get().getServiceChipLocation();
            return new Point((int) (mLocationBounds.left + mLocationBounds.width() * location.x),
                (int) (mLocationBounds.top + mLocationBounds.height() * location.y));
        }

        private void getLocationBounds(@NonNull final Rect rect) {
            mChipView.getWindowVisibleDisplayFrame(rect);
            rect.offsetTo(0, 0);
            rect.offset(-rect.width() / 2, -rect.height() / 2);

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

            final int horizontalMargin = mChipView.getResources()
                .getDimensionPixelSize(R.dimen.screen_share_chip_horizontal_margin);
            final int verticalMargin = mChipView.getResources()
                .getDimensionPixelSize(R.dimen.screen_share_chip_vertical_margin);
            rect.left += width / 2 + horizontalMargin;
            rect.top += height / 2 + verticalMargin;
            rect.right -= width / 2 + horizontalMargin;
            rect.bottom -= height / 2 + verticalMargin;
        }

        @NonNull
        private Chip createChipView() {
            final Chip chipView = new Chip(mContext) {
                @NonNull
                private final PointF mTouchOffset = new PointF();

                @Override
                protected void onConfigurationChanged(Configuration newConfig) {
                    super.onConfigurationChanged(newConfig);
                    post(() -> updateLocation());
                }

                @SuppressWarnings("SpellCheckingInspection")
                @Override
                protected void onSizeChanged(int w, int h, int oldw, int oldh) {
                    super.onSizeChanged(w, h, oldw, oldh);
                    updateLocation();
                }

                @SuppressLint("ClickableViewAccessibility")
                @Override
                public boolean onTouchEvent(@NonNull MotionEvent event) {
                    switch (event.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            mChipView.removeCallbacks(mAdjustLocationRunnable);
                            mOverScroller.forceFinished(true);
                            mTouchOffset.set(mLayoutParams.x - event.getRawX(), mLayoutParams.y - event.getRawY());
                            break;
                        case MotionEvent.ACTION_MOVE:
                            updateLocation((int) (event.getRawX() + mTouchOffset.x),
                                (int) (event.getRawY() + mTouchOffset.y));
                            break;
                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_CANCEL:
                            adjustLocation();
                            break;
                        default:
                            break;
                    }

                    return mGestureDetector.onTouchEvent(event) || super.onTouchEvent(event);
                }
            };

            chipView.setChipIconResource(R.drawable.ic_screen_share_chip);
            chipView.setChipIconTint(MaterialColors.getColorStateListOrNull(mContext,
                com.google.android.material.R.attr.colorOnError));
            chipView.setTextColor(MaterialColors.getColorStateListOrNull(mContext,
                com.google.android.material.R.attr.colorOnError));
            chipView.setChipBackgroundColor(MaterialColors.getColorStateListOrNull(mContext,
                androidx.appcompat.R.attr.colorError));

            return chipView;
        }
    }
}
