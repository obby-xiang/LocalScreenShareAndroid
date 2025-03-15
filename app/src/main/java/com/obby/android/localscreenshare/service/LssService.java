package com.obby.android.localscreenshare.service;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Messenger;
import android.util.Log;

import androidx.activity.result.ActivityResult;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationChannelCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

import com.obby.android.localscreenshare.MainActivity;
import com.obby.android.localscreenshare.R;
import com.obby.android.localscreenshare.support.Constants;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class LssService extends Service {
    private static final String NOTIFICATION_CHANNEL_ID = "lss_service";

    private static final int NOTIFICATION_ID = 1;

    @Nullable
    private MediaProjection mMediaProjection;

    private final String mTag = "LssService@" + hashCode();

    @NonNull
    private final List<Messenger> mClientMessengers = new CopyOnWriteArrayList<>();

    @NonNull
    private final Messenger mMessenger = new Messenger(new Handler(Looper.getMainLooper(), msg -> {
        switch (msg.what) {
            case Constants.MSG_REGISTER_CLIENT:
                Log.i(mTag, "mMessenger: register client");
                if (mClientMessengers.contains(msg.replyTo)) {
                    Log.w(mTag, "mMessenger: client already exists");
                } else {
                    mClientMessengers.add(msg.replyTo);
                }
                return true;
            case Constants.MSG_UNREGISTER_CLIENT:
                Log.i(mTag, "mMessenger: unregister client");
                if (!mClientMessengers.remove(msg.replyTo)) {
                    Log.w(mTag, "mMessenger: client does not exist");
                }
                return true;
            default:
                return false;
        }
    }));

    @NonNull
    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Constants.ACTION_STOP_LSS_SERVICE.equals(intent.getAction())) {
                ServiceCompat.stopForeground(LssService.this, ServiceCompat.STOP_FOREGROUND_REMOVE);
                stopSelf();
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

    @SuppressWarnings("DataFlowIssue")
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(mTag, String.format("onStartCommand: startId = %d", startId));

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
            stopSelf();
            return START_NOT_STICKY;
        }

        final ActivityResult mediaProjectionResult = intent.getParcelableExtra(Constants.EXTRA_MEDIA_PROJECTION_RESULT);
        final MediaProjectionManager mediaProjectionManager = getSystemService(MediaProjectionManager.class);
        mMediaProjection = mediaProjectionManager.getMediaProjection(mediaProjectionResult.getResultCode(),
            mediaProjectionResult.getData());

        return START_STICKY;
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
                PendingIntent.FLAG_UPDATE_CURRENT))
            .addAction(0, getString(R.string.lss_service_stop_notification_action),
                PendingIntent.getBroadcast(this, 0, new Intent(Constants.ACTION_STOP_LSS_SERVICE),
                    PendingIntent.FLAG_UPDATE_CURRENT))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build();
    }
}
