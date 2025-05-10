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
import android.os.IBinder;
import android.text.format.Formatter;
import android.util.Log;
import android.util.Size;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
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
import com.obby.android.localscreenshare.client.LssClient;
import com.obby.android.localscreenshare.client.LssClientObserver;
import com.obby.android.localscreenshare.client.LssClientStats;
import com.obby.android.localscreenshare.client.LssClientStatsListener;
import com.obby.android.localscreenshare.discovery.LssServiceInfo;
import com.obby.android.localscreenshare.support.Constants;
import com.obby.android.localscreenshare.support.Preferences;
import com.obby.android.localscreenshare.support.Reference;
import com.obby.android.localscreenshare.utils.ResourceUtils;

import org.apache.commons.lang3.math.NumberUtils;
import org.apache.commons.lang3.mutable.MutableBoolean;

import java.text.NumberFormat;
import java.util.Objects;

public class LssClientService extends Service {
    private static final String NOTIFICATION_CHANNEL_ID = "lss-client-service";

    private static final int NOTIFICATION_ID = 2;

    @Nullable
    private LssServiceInfo mServiceInfo;

    @Nullable
    private LssClient mClient;

    @Nullable
    private LssClientStats mClientStats;

    @Nullable
    private ScreenShareLoading mScreenShareLoading;

    @Nullable
    private ScreenShareViewer mScreenShareViewer;

    @Nullable
    private AlertDialog mDialog;

    private boolean mIsConnected;

    private long mInboundDataRate;

    private final String mTag = "LssClientService@" + hashCode();

    @NonNull
    private final LssClientStatsListener mClientStatsListener = this::onClientStatsChanged;

    @NonNull
    private final LssClientObserver mClientObserver = new LssClientObserver() {
        @Override
        public void onConnected() {
            mIsConnected = true;

            if (mScreenShareLoading != null) {
                mScreenShareLoading.dismiss();
            }

            if (mScreenShareViewer == null) {
                mScreenShareViewer = new ScreenShareViewer(LssClientService.this, mServiceInfo);
            }

            mScreenShareViewer.show();
        }

        @SuppressWarnings("DataFlowIssue")
        @Override
        public void onDisconnected() {
            final int messageResId = mIsConnected ? R.string.reconnect_screen_share_confirm
                : R.string.retry_connect_screen_share_confirm;
            mIsConnected = false;
            mClientStats = null;

            updateInboundDataRate(0L);

            if (mDialog != null) {
                mDialog.dismiss();
            }

            if (mScreenShareViewer != null) {
                mScreenShareViewer.dismiss();
            }

            if (mScreenShareLoading != null) {
                mScreenShareLoading.dismiss();
            }

            if (mClient != null) {
                mClient.stop();
                mClient = null;
            }

            final Context themedContext = new ContextThemeWrapper(LssClientService.this, R.style.AppTheme);
            mDialog = new MaterialAlertDialogBuilder(themedContext)
                .setMessage(HtmlCompat.fromHtml(getString(messageResId, mServiceInfo.getName()),
                    HtmlCompat.FROM_HTML_MODE_LEGACY))
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    mClient = new LssClient(mServiceInfo.getHostAddress(), mServiceInfo.getPort());
                    mClient.setClientStatsListener(mClientStatsListener);
                    mScreenShareLoading.show();
                    mClient.start(mClientObserver);
                })
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> sendBroadcast(
                    new Intent(Constants.ACTION_STOP_CLIENT_SERVICE).setPackage(getPackageName())))
                .setCancelable(false)
                .create();
            mDialog.getWindow().setType(Constants.FLOATING_WINDOW_TYPE);
            mDialog.show();
        }

        @Override
        public void onScreenFrameReceived(@NonNull Reference<Bitmap> frame, boolean isSecure) {
            if (mScreenShareViewer != null) {
                mScreenShareViewer.setFrame(frame, isSecure);
            }
        }
    };

    @NonNull
    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Constants.ACTION_STOP_CLIENT_SERVICE.equals(intent.getAction())) {
                stopService();
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(mTag, "onCreate: service created");

        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Constants.ACTION_STOP_CLIENT_SERVICE);
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
        return null;
    }

    @SuppressWarnings("DataFlowIssue")
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(mTag, String.format("onStartCommand: startId = %d", startId));

        if (intent == null) {
            Log.w(mTag, "onStartCommand: intent is null");
            return START_NOT_STICKY;
        }

        final LssServiceInfo serviceInfo = intent.getParcelableExtra(Constants.EXTRA_SERVICE_INFO);
        if (Objects.equals(mServiceInfo, serviceInfo) && mClient != null) {
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

        if (mDialog != null) {
            mDialog.dismiss();
            mDialog = null;
        }

        if (mScreenShareViewer != null) {
            mScreenShareViewer.dismiss();
            mScreenShareViewer = null;
        }

        if (mScreenShareLoading != null) {
            mScreenShareLoading.dismiss();
        }

        if (mClient != null) {
            mClient.stop();
        }

        mClient = new LssClient(mServiceInfo.getHostAddress(), mServiceInfo.getPort());
        mClient.setClientStatsListener(mClientStatsListener);
        mScreenShareLoading = new ScreenShareLoading(this, mServiceInfo);
        mScreenShareLoading.show();
        mClient.start(mClientObserver);

        return START_STICKY;
    }

    private void stopService() {
        mServiceInfo = null;
        mIsConnected = false;
        mClientStats = null;
        mInboundDataRate = 0L;

        if (mDialog != null) {
            mDialog.dismiss();
            mDialog = null;
        }

        if (mScreenShareViewer != null) {
            mScreenShareViewer.dismiss();
            mScreenShareViewer = null;
        }

        if (mScreenShareLoading != null) {
            mScreenShareLoading.dismiss();
            mScreenShareLoading = null;
        }

        if (mClient != null) {
            mClient.stop();
            mClient = null;
        }

        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void onClientStatsChanged(@NonNull final LssClientStats clientStats) {
        mClientStats = clientStats;
        updateInboundDataRate(mClientStats.getInboundDataRate());
    }

    @SuppressLint("MissingPermission")
    private void updateInboundDataRate(final long inboundDataRate) {
        if (mInboundDataRate == inboundDataRate) {
            return;
        }

        mInboundDataRate = inboundDataRate;
        NotificationManagerCompat.from(LssClientService.this).notify(NOTIFICATION_ID, buildNotification());
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
            .setContentTitle(getString(R.string.client_service_notification_title, mServiceInfo.getName()))
            .setContentText(getString(R.string.client_service_notification_text, mServiceInfo.getHostAddress(),
                mServiceInfo.getPort(), Formatter.formatFileSize(this, mInboundDataRate)))
            .setContentIntent(PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
            .addAction(0, getString(R.string.client_service_stop_notification_action),
                PendingIntent.getBroadcast(this, 0,
                    new Intent(Constants.ACTION_STOP_CLIENT_SERVICE).setPackage(getPackageName()),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build();
    }

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
        private ScreenShareLoading(@NonNull final Context context, @NonNull final LssServiceInfo serviceInfo) {
            final Context themedContext = new ContextThemeWrapper(context, R.style.AppTheme);
            mWindowManager = themedContext.getSystemService(WindowManager.class);
            mLoadingView = LayoutInflater.from(themedContext).inflate(R.layout.widget_screen_share_loading, null);
            mLayoutParams = new WindowManager.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, Constants.FLOATING_WINDOW_TYPE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                    | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON, PixelFormat.TRANSLUCENT);
            mLayoutParams.gravity = Gravity.CENTER;

            mLoadingView.setOnClickListener(v -> {
                if (mDialog == null) {
                    mDialog = new MaterialAlertDialogBuilder(themedContext)
                        .setMessage(HtmlCompat.fromHtml(themedContext.getString(
                                R.string.disconnect_screen_share_confirm, serviceInfo.getName()),
                            HtmlCompat.FROM_HTML_MODE_LEGACY))
                        .setPositiveButton(android.R.string.ok, (dialog, which) -> themedContext.sendBroadcast(
                            new Intent(Constants.ACTION_STOP_CLIENT_SERVICE)
                                .setPackage(themedContext.getPackageName())))
                        .setNegativeButton(android.R.string.cancel, null)
                        .setCancelable(false)
                        .create();
                    mDialog.getWindow().setType(Constants.FLOATING_WINDOW_TYPE);
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

    private static class ScreenShareViewer {
        private static final float CORNER_SIZE_RATIO = 1 / 16f;

        private static final int MIN_OPACITY = 50;

        private static final int MAX_OPACITY = 100;

        private static final int OPACITY_STEP = 1;

        private static final int MIN_SCALE = 25;

        private static final int MAX_SCALE = 100;

        private static final int SCALE_STEP = 1;

        private int mOpacity;

        private int mScale;

        @Nullable
        private Reference<Bitmap> mFrame;

        @Nullable
        private Size mFrameSize;

        @Nullable
        private Matrix mMatrix;

        @Nullable
        private AlertDialog mDialog;

        @Nullable
        private BottomSheetDialog mSettingsDialog;

        @NonNull
        private final Context mContext;

        @NonNull
        private final LssServiceInfo mServiceInfo;

        @NonNull
        private final WindowManager mWindowManager;

        @NonNull
        private final ShapeableImageView mFrameView;

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
                            .setMessage(HtmlCompat.fromHtml(mContext.getString(R.string.disconnect_screen_share_confirm,
                                mServiceInfo.getName()), HtmlCompat.FROM_HTML_MODE_LEGACY))
                            .setPositiveButton(android.R.string.ok, (dialog, which) -> mContext.sendBroadcast(
                                new Intent(Constants.ACTION_STOP_CLIENT_SERVICE).setPackage(mContext.getPackageName())))
                            .setNegativeButton(android.R.string.cancel, null)
                            .setCancelable(false)
                            .create();
                        mDialog.getWindow().setType(Constants.FLOATING_WINDOW_TYPE);
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
        private ScreenShareViewer(@NonNull final Context context, @NonNull final LssServiceInfo serviceInfo) {
            mContext = new ContextThemeWrapper(context, R.style.AppTheme);
            mServiceInfo = serviceInfo;
            mWindowManager = mContext.getSystemService(WindowManager.class);
            mFrameView = createFrameView();
            mLayoutParams = new WindowManager.LayoutParams(0, 0, 0, 0, Constants.FLOATING_WINDOW_TYPE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
            mLayoutParams.gravity = Gravity.CENTER;
            mGestureDetector = new GestureDetector(mContext, mOnGestureListener);
        }

        @SuppressWarnings("DataFlowIssue")
        public void setFrame(@NonNull final Reference<Bitmap> frame, boolean isSecure) {
            final Reference<Bitmap> oldFrame = mFrame;
            mFrame = frame;

            final Bitmap bitmap = mFrame.getValue();
            mFrameView.setImageBitmap(bitmap);

            final MutableBoolean shouldUpdate = new MutableBoolean();
            if (mFrameSize == null || mFrameSize.getWidth() != bitmap.getWidth()
                || mFrameSize.getHeight() != bitmap.getHeight()) {
                shouldUpdate.setTrue();
                mFrameSize = new Size(bitmap.getWidth(), bitmap.getHeight());
                updateLayoutParams();
                updateMatrix();
                updateShape();
            }

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

            if (oldFrame != null) {
                oldFrame.clear();
            }
        }

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

        private void getLocationBounds(@NonNull final Rect rect) {
            getMaxLayoutBounds(rect);
            rect.left += mLayoutParams.width / 2;
            rect.top += mLayoutParams.height / 2;
            rect.right -= mLayoutParams.width / 2;
            rect.bottom -= mLayoutParams.height / 2;
        }

        private void getMaxLayoutBounds(@NonNull final Rect rect) {
            mFrameView.getWindowVisibleDisplayFrame(rect);
            rect.offsetTo(0, 0);
            rect.offset(-rect.width() / 2, -rect.height() / 2);
        }

        @SuppressWarnings("DataFlowIssue")
        @NonNull
        private BottomSheetDialog createSettingsDialog() {
            final BottomSheetDialog settingsDialog = new BottomSheetDialog(mContext);
            settingsDialog.setContentView(R.layout.widget_screen_share_viewer_settings);
            settingsDialog.setDismissWithAnimation(true);
            settingsDialog.getWindow().setType(Constants.FLOATING_WINDOW_TYPE);
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

        @NonNull
        private ShapeableImageView createFrameView() {
            final ShapeableImageView frameView = new ShapeableImageView(mContext) {
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
                    post(() -> updateView());
                }

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
