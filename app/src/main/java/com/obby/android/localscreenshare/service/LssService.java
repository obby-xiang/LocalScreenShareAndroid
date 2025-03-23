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
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
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
import android.os.Messenger;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;

import androidx.activity.result.ActivityResult;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.core.ImageProcessingUtil;
import androidx.core.app.NotificationChannelCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

import com.google.protobuf.ByteString;
import com.obby.android.localscreenshare.MainActivity;
import com.obby.android.localscreenshare.R;
import com.obby.android.localscreenshare.grpc.screenstream.ScreenFrame;
import com.obby.android.localscreenshare.server.LssServer;
import com.obby.android.localscreenshare.support.Constants;
import com.obby.android.localscreenshare.utils.WindowUtils;

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
    private MediaProjection mMediaProjection;

    @Nullable
    private HandlerThread mImageReaderHandlerThread;

    @Nullable
    private Handler mImageReaderHandler;

    private volatile ImageReader mImageReader;

    @Nullable
    private VirtualDisplay mVirtualDisplay;

    @Nullable
    private LssServer mServer;

    @Nullable
    private Bitmap mScreenFrameBitmap;

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

        return START_STICKY;
    }

    private void stopService() {
        Log.i(mTag, "stopService: stop service");

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

        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void registerServiceClient(@NonNull final Messenger messenger) {
        Log.i(mTag, "registerServiceClient: register service client");

        if (mClientMessengers.contains(messenger)) {
            Log.w(mTag, "registerServiceClient: client already exists");
        } else {
            mClientMessengers.add(messenger);
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
            .setContentText(getString(R.string.lss_service_notification_text, 0))
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
}
