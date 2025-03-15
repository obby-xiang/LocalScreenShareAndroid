package com.obby.android.localscreenshare;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.obby.android.localscreenshare.service.LssService;
import com.obby.android.localscreenshare.support.Constants;

public class MainActivity extends AppCompatActivity {
    private boolean mIsServiceBound;

    @Nullable
    private Messenger mServiceMessenger;

    private final String mTag = "MainActivity@" + hashCode();

    @NonNull
    private final Messenger mMessenger = new Messenger(new Handler(Looper.getMainLooper(), msg -> false));

    @NonNull
    private final ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (!mIsServiceBound) {
                return;
            }

            Log.i(mTag, String.format("mServiceConnection.onServiceConnected: service connected, name = %s", name));
            mServiceMessenger = new Messenger(service);
            registerServiceClient();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.i(mTag, String.format("mServiceConnection.onServiceDisconnected: service disconnected, name = %s",
                name));
            mServiceMessenger = null;
        }
    };

    @NonNull
    private final ActivityResultLauncher<Intent> mScreenCaptureLauncher = registerForActivityResult(
        new ActivityResultContracts.StartActivityForResult(),
        result -> {
            if (result.getResultCode() == Activity.RESULT_OK) {
                ContextCompat.startForegroundService(this, new Intent(this, LssService.class)
                    .putExtra(Constants.EXTRA_MEDIA_PROJECTION_RESULT, result));
            }
        }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        Log.i(mTag, "onStart: activity started");

        mIsServiceBound = true;
        bindService(new Intent(this, LssService.class), mServiceConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onStop() {
        super.onStop();
        Log.i(mTag, "onStop: activity stopped");

        unregisterServiceClient();
        mIsServiceBound = false;
        mServiceMessenger = null;
        unbindService(mServiceConnection);
    }

    private void requestScreenCapture() {
        final MediaProjectionManager mediaProjectionManager = getSystemService(MediaProjectionManager.class);
        mScreenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent());
    }

    private void registerServiceClient() {
        if (mServiceMessenger == null) {
            return;
        }

        try {
            final Message message = Message.obtain(null, Constants.MSG_REGISTER_SERVICE_CLIENT);
            message.replyTo = mMessenger;
            mServiceMessenger.send(message);
        } catch (RemoteException e) {
            // ignored
        }
    }

    private void unregisterServiceClient() {
        if (mServiceMessenger == null) {
            return;
        }

        try {
            final Message message = Message.obtain(null, Constants.MSG_UNREGISTER_SERVICE_CLIENT);
            message.replyTo = mMessenger;
            mServiceMessenger.send(message);
        } catch (RemoteException e) {
            // ignored
        }
    }
}
