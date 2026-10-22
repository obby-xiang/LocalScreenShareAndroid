package com.obby.android.localscreenshare;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Rect;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.provider.Settings;
import android.text.InputType;
import android.text.format.Formatter;
import android.util.Log;
import android.util.Size;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.FrameLayout;
import android.widget.NumberPicker;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.view.ContextThemeWrapper;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.splashscreen.SplashScreen;
import androidx.core.text.HtmlCompat;
import androidx.core.view.MenuItemCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.divider.MaterialDividerItemDecoration;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.LabelFormatter;
import com.google.android.material.slider.Slider;
import com.google.android.material.textview.MaterialTextView;
import com.obby.android.localscreenshare.authorization.ApprovalOutcome;
import com.obby.android.localscreenshare.authorization.store.AuthorizationEntry;
import com.obby.android.localscreenshare.authorization.store.AuthorizationStore;
import com.obby.android.localscreenshare.core.audio.AudioAvailability;
import com.obby.android.localscreenshare.core.codec.CodecIds;
import com.obby.android.localscreenshare.core.codec.h264.H264Codec;
import com.obby.android.localscreenshare.core.codec.mediacodec.EncoderSizeLimitProbe;
import com.obby.android.localscreenshare.discovery.DiscoveryManager;
import com.obby.android.localscreenshare.discovery.favorite.FavoriteDevice;
import com.obby.android.localscreenshare.discovery.favorite.FavoriteStore;
import com.obby.android.localscreenshare.discovery.manual.ManualAddressProvider;
import com.obby.android.localscreenshare.discovery.model.DeviceAddress;
import com.obby.android.localscreenshare.discovery.model.DeviceInfo;
import com.obby.android.localscreenshare.discovery.scan.ScanConstants;
import com.obby.android.localscreenshare.identity.DeviceIdentity;
import com.obby.android.localscreenshare.discovery.store.DeviceStoreListener;
import com.obby.android.localscreenshare.discovery.ui.DiscoveryListModel;
import com.obby.android.localscreenshare.grpc.screenstream.DeviceSummary;
import com.obby.android.localscreenshare.identity.IdentityManager;
import com.obby.android.localscreenshare.identity.IdentityUnavailableException;
import com.obby.android.localscreenshare.identity.tls.TlsMaterial;
import com.obby.android.localscreenshare.identity.tls.TlsMaterialHolder;
import com.obby.android.localscreenshare.server.ServerInfo;
import com.obby.android.localscreenshare.server.ServerStats;
import com.obby.android.localscreenshare.service.ScreenShareService;
import com.obby.android.localscreenshare.service.ScreenViewerService;
import com.obby.android.localscreenshare.settings.DiscoverySettings;
import com.obby.android.localscreenshare.settings.Preferences;
import com.obby.android.localscreenshare.settings.ProjectionControlValues;
import com.obby.android.localscreenshare.settings.ProjectionScalePolicy;
import com.obby.android.localscreenshare.settings.ProjectionSettings;
import com.obby.android.localscreenshare.settings.VisibilityPolicy;
import com.obby.android.localscreenshare.support.Constants;
import com.obby.android.localscreenshare.support.IntentUtils;
import com.obby.android.localscreenshare.support.ServiceProtocol;
import com.obby.android.localscreenshare.support.WindowUtils;
import com.obby.android.localscreenshare.trust.FingerprintFormatter;
import com.obby.android.localscreenshare.trust.PinPolicy;
import com.obby.android.localscreenshare.trust.TlsPin;
import com.obby.android.localscreenshare.trust.TlsPinStore;
import com.obby.android.localscreenshare.trust.TofuPolicy;

import java.text.DateFormat;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;

/**
 * Single-activity UI: starts and stops the screen-share service and lists discovered servers.
 *
 * <p><b>Lifecycle.</b> {@link #onCreate(Bundle)} installs the splash screen, inflates the layout,
 * wires the toolbars and the discovery list and renders the initial state. {@link #onStart()}
 * binds to {@link ScreenShareService} with {@code BIND_AUTO_CREATE} and starts discovery when it is
 * enabled; the service connection registers this activity's {@link Messenger} as a client, so the
 * current server state is replayed on registration. {@link #onStop()} unregisters the client,
 * clears the bound flag, unbinds and stops discovery; {@link #onDestroy()} only dismisses dialogs
 * that are still on screen. Binding alone does not start the server, so a bound-only service
 * reports the stopped state.</p>
 *
 * <p><b>Screen capture flow.</b> Starting the share first passes {@link #requestScreenShare()},
 * which checks the notification permission, whether notifications are enabled, and the overlay
 * permission. When a check fails, it starts the system prompt or shows a dialog and returns
 * {@code false}, so the capture consent is requested only after the user retries the action. The
 * consent activity returns through {@link #mScreenCaptureLauncher}; a positive result starts
 * {@link ScreenShareService} in the foreground with the consent result as an extra. Viewing a discovered
 * server follows the same gate and starts {@link ScreenViewerService} with the service info.</p>
 *
 * <p><b>Threading.</b> Everything here runs on the main thread: service messages arrive through a
 * {@link Handler} bound to the main looper and discovery callbacks are already main-thread
 * callbacks. The activity only renders the last received state; the services own the server, the
 * capture pipeline and the client.</p>
 */
public class MainActivity extends AppCompatActivity {
    // Tri-state summary of the bound service: unknown before the first event, online while the
    // server runs, offline after it stops or after the binding is lost.
    private static final int SERVICE_STATUS_UNKNOWN = 0;

    private static final int SERVICE_STATUS_ONLINE = 1;

    private static final int SERVICE_STATUS_OFFLINE = 2;

    private static final int MIN_SERVER_PORT = 1024;

    private static final int MAX_SERVER_PORT = 65535;

    // Reason the discovery probe reports when a peer's certificate does not belong to the device id
    // it claimed; every other reason is a transport failure, not a security finding.
    @NonNull
    private static final String IDENTITY_MISMATCH_REASON = "identity_mismatch";

    // True between onStart() and onStop(); a late onServiceConnected callback after unbinding is
    // ignored.
    private boolean mIsServiceBound;

    // Binder of the bound service; null until connected and after unbinding.
    @Nullable
    private Messenger mServiceMessenger;

    // Last state received from the service, rendered by updateServiceView().
    private int mServiceStatus = SERVICE_STATUS_UNKNOWN;

    // Last values received from the service; null while the server is not running.
    @Nullable
    private ServerInfo mServerInfo;

    @Nullable
    private ServerStats mServerStats;

    // Last confirmation or permission dialog shown; dismissed when replaced and in onDestroy().
    @Nullable
    private AlertDialog mDialog;

    // Device id of the identity probe in flight, or null when none is running. A callback that
    // arrives after the user cancelled the probe, or after another device was selected, is dropped.
    @Nullable
    private String mFingerprintProbeId;

    // Created on first use and reused while the activity lives.
    @Nullable
    private BottomSheetDialog mServiceSettingsDialog;

    // Video settings sheet, opened from the entry row of the service settings sheet; created on
    // first use and reused while the activity lives.
    @Nullable
    private BottomSheetDialog mVideoSettingsDialog;

    // Current-value summary shown next to the video settings entry row; null until the service
    // settings sheet is created.
    @Nullable
    private MaterialTextView mVideoSettingsSummaryView;

    // Note under the authorization switch, rewritten when the switch is off to spell out the risk.
    @Nullable
    private MaterialTextView mAuthorizationSettingNoteView;

    // Summary next to the allowed-devices entry row; null until the sheet is created.
    @Nullable
    private MaterialTextView mAuthorizationDevicesSummaryView;

    // Viewing request the dialog below belongs to, or 0 when no request is being shown.
    private long mAuthorizationRequestId;

    // Blocking dialog of one viewing request; separate from mDialog because a request may arrive
    // while a discovery confirmation is on screen.
    @Nullable
    private AlertDialog mAuthorizationDialog;

    // Allowed-devices dialog; rebuilt after every revocation so the list never shows a stale row.
    @Nullable
    private AlertDialog mAuthorizedDevicesDialog;

    // Controls of the video settings sheet; null until the sheet is created. They are kept so a
    // preference written by the service while the sheet is open refreshes the control that shows
    // it, instead of only the summary next to the entry row.
    @Nullable
    private View mQualitySettingRow;

    @Nullable
    private Slider mQualitySettingView;

    @Nullable
    private Slider mScaleSettingView;

    @Nullable
    private Slider mFrameRateSettingView;

    @Nullable
    private Slider mBitrateSettingView;

    @Nullable
    private View mBitrateSettingRow;

    @Nullable
    private Slider mIdrIntervalSettingView;

    @Nullable
    private View mIdrIntervalSettingRow;

    // Two mutually exclusive codec options; the preference observer refreshes it like every other
    // control, so a change written elsewhere (a reset, another sheet) shows up here too.
    @Nullable
    private MaterialButtonToggleGroup mCodecToggleGroup;

    // Sliders the user currently has a finger on; a preference refresh never moves a slider while
    // it is being dragged.
    @NonNull
    private final Set<Slider> mTrackingSliders = new HashSet<>();

    private MaterialToolbar mServiceToolbarView;

    private MaterialTextView mServiceStatusView;

    private MaterialTextView mServiceNameView;

    private MaterialTextView mServiceAddressView;

    private MaterialButton mServiceStatsView;

    private MaterialToolbar mDiscoveryToolbarView;

    private MaterialTextView mDiscoveryListPlaceholderView;

    private RecyclerView mDiscoveryListView;

    private MaterialButton mDiscoveryManualAddView;

    // True once the empty list has been empty long enough to offer the manual entry.
    private boolean mShowManualDiscoveryHint;

    @NonNull
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    /**
     * Refreshes the video settings views as soon as a projection value is stored.
     *
     * <p>The summary used to be refreshed only when the video sheet was dismissed, so a value the
     * user had just committed stayed invisible for as long as the sheet was open. The sheet writes
     * the preference and the running service applies it immediately, so the summary is rebuilt at
     * that moment instead. The sheet's own controls follow the same event, because the service
     * writes the applied percentage back when the encoder rejects a size: without that refresh the
     * slider stays where the user dragged it until the sheet is closed and reopened. Only keys the
     * controls can show are considered; a null key means a bulk refresh. The refresh runs on the
     * main thread because it touches views.</p>
     */
    @NonNull
    private final Preferences.Observer mProjectionObserver = key -> {
        if (key != null && !isProjectionSummaryKey(key)) {
            return;
        }
        mMainHandler.post(() -> {
            updateVideoSettingsSummary();
            syncVideoSettingsControls();
        });
    };

    /**
     * Returns whether {@code key} carries a value that the video settings summary displays.
     */
    private static boolean isProjectionSummaryKey(@NonNull final String key) {
        switch (key) {
            case Preferences.KEY_PROJECTION_CODEC:
            case Preferences.KEY_PROJECTION_FRAME_RATE:
            case Preferences.KEY_PROJECTION_BITRATE:
            case Preferences.KEY_PROJECTION_SCALE:
            case Preferences.KEY_PROJECTION_IDR_INTERVAL:
            case Preferences.KEY_PROJECTION_QUALITY:
                return true;
            default:
                return false;
        }
    }

    // Device id row inside the settings sheet; null until the sheet is created.
    @Nullable
    private MaterialTextView mDeviceIdSettingView;

    // Key fingerprint row inside the settings sheet; null until the sheet is created.
    @Nullable
    private MaterialTextView mDeviceFingerprintSettingView;

    // Sound-sharing switch inside the settings sheet; null until the sheet is created.
    @Nullable
    private MaterialSwitch mAudioSettingView;

    private final String mTag = "MainActivity@" + hashCode();

    // Latest store snapshot; the adapter renders it through DiscoveryListModel.
    @NonNull
    private List<DeviceInfo> mDevices = Collections.emptyList();

    // Discovery rows; a click starts the client service for that device.
    @NonNull
    private final DiscoveryListAdapter mDiscoveryListAdapter = new DiscoveryListAdapter() {
        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            super.onBindViewHolder(holder, position);
            final DiscoveryListModel.Row row = getItem(position);
            final DeviceInfo item = row.getDevice();
            if (row.getType() != DiscoveryListModel.RowType.DEVICE || item == null) {
                holder.itemView.setOnClickListener(null);
                holder.itemView.setOnLongClickListener(null);
                return;
            }
            holder.itemView.setOnClickListener(v -> startScreenShareViewer(item));
            holder.itemView.setOnLongClickListener(v -> {
                showDiscoveryItemMenu(item);
                return true;
            });
        }

        @Override
        public void submitList(@Nullable List<DiscoveryListModel.Row> list) {
            submitList(list, null);
        }

        @Override
        public void submitList(@Nullable List<DiscoveryListModel.Row> list, @Nullable Runnable commitCallback) {
            // The commit callback runs after the diff is applied; the placeholder and the manual
            // hint mirror the committed list rather than the pending one.
            super.submitList(list, () -> {
                if (!getCurrentList().isEmpty()) {
                    mMainHandler.removeCallbacks(mDiscoveryHintRunnable);
                    mShowManualDiscoveryHint = false;
                }
                updateDiscoveryPlaceholder();
                Optional.ofNullable(commitCallback).ifPresent(Runnable::run);
            });
        }
    };

    // Store snapshots are delivered on the main thread and replace the list wholesale.
    @NonNull
    private final DeviceStoreListener mDeviceListener = devices -> {
        mDevices = devices;
        mDiscoveryListAdapter.submitList(devices.isEmpty()
            ? Collections.emptyList() : DiscoveryListModel.rows(devices));
    };

    // Arms once per discovery start; a result before it fires cancels it.
    @NonNull
    private final Runnable mDiscoveryHintRunnable = () -> {
        mShowManualDiscoveryHint = true;
        updateDiscoveryPlaceholder();
    };

    // Receives server state from the bound service; the handler is bound to the main looper.
    @NonNull
    private final Messenger mMessenger = new Messenger(new Handler(Looper.getMainLooper(), msg -> {
        switch (msg.what) {
            case ServiceProtocol.MSG_SERVER_STARTED:
                onServerStarted();
                return true;
            case ServiceProtocol.MSG_SERVER_STOPPED:
                onServerStopped();
                return true;
            case ServiceProtocol.MSG_SERVER_INFO_CHANGED:
                onServerInfoChanged((ServerInfo) msg.obj);
                return true;
            case ServiceProtocol.MSG_SERVER_STATS_CHANGED:
                onServerStatsChanged((ServerStats) msg.obj);
                return true;
            case ServiceProtocol.MSG_AUTHORIZATION_REQUEST:
                onAuthorizationRequested(msg.getData());
                return true;
            case ServiceProtocol.MSG_AUTHORIZATION_FINISHED:
                onAuthorizationFinished(msg.arg1);
                return true;
            default:
                return false;
        }
    }));

    // Registers this activity as a service client once the binding is established. The callback
    // can still arrive after onStop() has unbound, so mIsServiceBound guards it.
    @NonNull
    private final ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (!mIsServiceBound) {
                return;
            }

            Log.i(mTag, String.format("mServiceConnection.onServiceConnected: service connected, name = %s", name));
            mServiceMessenger = new Messenger(service);
            // Registration makes the service replay the current state to mMessenger.
            registerServiceClient();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            // Only reported when the service process dies, not on a normal unbind.
            Log.i(mTag, String.format("mServiceConnection.onServiceDisconnected: service disconnected, name = %s",
                name));
            mServiceMessenger = null;
            mServiceStatus = SERVICE_STATUS_UNKNOWN;
            mServerInfo = null;
            mServerStats = null;
            updateServiceView();
        }
    };

    // A permanently denied permission cannot be requested again, so point the user to the app
    // notification settings instead.
    @NonNull
    private final ActivityResultLauncher<String> mNotificationPermissionLauncher =
        registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
            if (!isGranted
                && !ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.POST_NOTIFICATIONS)) {
                if (mDialog != null) {
                    mDialog.dismiss();
                }
                mDialog = new MaterialAlertDialogBuilder(this)
                    .setMessage(HtmlCompat.fromHtml(getString(R.string.permission_request_post_notification, App.getLabel()),
                        HtmlCompat.FROM_HTML_MODE_LEGACY))
                    .setPositiveButton(android.R.string.ok,
                        (dialog, which) -> startActivity(IntentUtils.createAppNotificationSettingsIntent(this)))
                    .setNegativeButton(android.R.string.cancel, null)
                    .setCancelable(false)
                    .show();
            }
        });

    // Only RESULT_OK carries a usable projection grant; a cancelled consent leaves everything
    // unchanged. The service is started in the foreground because it immediately captures.
    @NonNull
    private final ActivityResultLauncher<Intent> mScreenCaptureLauncher = registerForActivityResult(
        new ActivityResultContracts.StartActivityForResult(),
        result -> {
            if (result.getResultCode() == Activity.RESULT_OK) {
                ContextCompat.startForegroundService(this, new Intent(this, ScreenShareService.class)
                    .putExtra(ServiceProtocol.EXTRA_MEDIA_PROJECTION_RESULT, result));
            }
        }
    );

    // Recording permission for playback capture. The switch reflects the outcome, so a denial never
    // leaves the UI claiming audio is shared; the microphone is never requested as a substitute.
    @NonNull
    private final ActivityResultLauncher<String> mRecordAudioPermissionLauncher =
        registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
            Preferences.get().setAudioEnabled(isGranted);
            if (mAudioSettingView != null) {
                mAudioSettingView.setChecked(isGranted);
                mAudioSettingView.jumpDrawablesToCurrentState();
            }
            if (!isGranted) {
                Toast.makeText(this, R.string.audio_permission_denied, Toast.LENGTH_LONG).show();
            }
        });

    /**
     * Inflates the layout, wires the toolbars and the discovery list and renders the initial
     * state.
     *
     * <p>The splash screen must be installed before {@code super.onCreate} so it covers the first
     * frame. The service is not bound here; that happens in {@link #onStart()}.</p>
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SplashScreen.installSplashScreen(this);
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        mServiceToolbarView = findViewById(R.id.service_toolbar);
        mServiceStatusView = findViewById(R.id.service_status);
        mServiceNameView = findViewById(R.id.service_name);
        mServiceAddressView = findViewById(R.id.service_address);
        mServiceStatsView = findViewById(R.id.service_stats);
        mDiscoveryToolbarView = findViewById(R.id.discovery_toolbar);
        mDiscoveryListPlaceholderView = findViewById(R.id.discovery_list_placeholder);
        mDiscoveryListView = findViewById(R.id.discovery_list);
        mDiscoveryManualAddView = findViewById(R.id.discovery_manual_add);

        mDiscoveryListView.setAdapter(mDiscoveryListAdapter);
        mDiscoveryListView.setItemAnimator(null);

        mDiscoveryManualAddView.setOnClickListener(v -> showManualAddressDialog());
        mDiscoveryListPlaceholderView.setOnClickListener(v -> {
            if (mShowManualDiscoveryHint) {
                showManualAddressDialog();
            }
        });

        final MaterialDividerItemDecoration discoveryListItemDecoration =
            new MaterialDividerItemDecoration(this, MaterialDividerItemDecoration.VERTICAL);
        discoveryListItemDecoration.setLastItemDecorated(false);
        discoveryListItemDecoration.setDividerColor(Color.TRANSPARENT);
        discoveryListItemDecoration.setDividerThicknessResource(this, R.dimen.discovery_list_item_decoration_thickness);
        mDiscoveryListView.addItemDecoration(discoveryListItemDecoration);

        Arrays.asList(mServiceToolbarView.getMenu(), mDiscoveryToolbarView.getMenu())
            .forEach(menu -> IntStream.range(0, menu.size()).mapToObj(menu::getItem)
                .forEach(menuItem -> MenuItemCompat.setIconTintList(menuItem,
                    MaterialColors.getColorStateListOrNull(this, androidx.appcompat.R.attr.colorPrimary))));

        // The settings sheet is created lazily and reused; every change is written to Preferences.
        mServiceToolbarView.getMenu().findItem(R.id.settings).setOnMenuItemClickListener(item -> {
            if (mServiceSettingsDialog == null) {
                mServiceSettingsDialog = createServiceSettingsDialog();
            }
            mServiceSettingsDialog.show();
            return true;
        });
        // The toggle acts on the last reported state, not on the last user intent.
        mServiceToolbarView.getMenu().findItem(R.id.service).setOnMenuItemClickListener(item -> {
            if (mServiceStatus == SERVICE_STATUS_ONLINE) {
                stopScreenShareService();
            } else {
                startScreenShareService();
            }
            return true;
        });
        // The discovery toggle persists the preference and subscribes or unsubscribes immediately.
        mDiscoveryToolbarView.getMenu().findItem(R.id.search).setOnMenuItemClickListener(item -> {
            final boolean isEnabled = !Preferences.get().isDiscoveryEnabled();
            Preferences.get().setDiscoveryEnabled(isEnabled);
            if (isEnabled) {
                DiscoveryManager.get().addDeviceListener(mDeviceListener);
                mMainHandler.removeCallbacks(mDiscoveryHintRunnable);
                mMainHandler.postDelayed(mDiscoveryHintRunnable,
                    ScanConstants.DISCOVERY_EMPTY_MANUAL_HINT_MS);
            } else {
                DiscoveryManager.get().removeDeviceListener(mDeviceListener);
                mMainHandler.removeCallbacks(mDiscoveryHintRunnable);
                mShowManualDiscoveryHint = false;
                mDiscoveryListAdapter.submitList(null);
            }
            updateDiscoveryView();
            return true;
        });

        // Render before the first service callback arrives so no stale state is shown.
        updateServiceView();
        updateDiscoveryView();

        // The identity is derived from the keystore key; a restore from backup or a lost key
        // changes it, and that must not pass silently.
        checkDeviceIdentity();
    }

    /**
     * Binds to the share service and subscribes to discovery.
     *
     * <p>{@code BIND_AUTO_CREATE} creates the service if it is not running but does not start the
     * server; the connection callback registers this activity, and the service immediately
     * replays its current state. Subscribing to discovery likewise replays the cached snapshot.</p>
     */
    @Override
    protected void onStart() {
        super.onStart();
        Log.i(mTag, "onStart: activity started");

        mIsServiceBound = true;
        bindService(new Intent(this, ScreenShareService.class), mServiceConnection, Context.BIND_AUTO_CREATE);

        Preferences.get().addObserver(mProjectionObserver);

        if (Preferences.get().isDiscoveryEnabled()) {
            DiscoveryManager.get().addDeviceListener(mDeviceListener);
            mMainHandler.removeCallbacks(mDiscoveryHintRunnable);
            mMainHandler.postDelayed(mDiscoveryHintRunnable,
                ScanConstants.DISCOVERY_EMPTY_MANUAL_HINT_MS);
        }
    }

    /**
     * Releases the service client and the discovery subscription.
     *
     * <p>The client is unregistered while the binding is still usable; the bound flag is cleared
     * before unbinding so a late connection callback becomes a no-op.</p>
     */
    @Override
    protected void onStop() {
        super.onStop();
        Log.i(mTag, "onStop: activity stopped");

        unregisterServiceClient();
        mIsServiceBound = false;
        mServiceMessenger = null;
        unbindService(mServiceConnection);

        Preferences.get().removeObserver(mProjectionObserver);

        DiscoveryManager.get().removeDeviceListener(mDeviceListener);
        mMainHandler.removeCallbacks(mDiscoveryHintRunnable);
        mShowManualDiscoveryHint = false;
        mDiscoveryListAdapter.submitList(null);
    }

    /**
     * Dismisses dialogs still attached to this activity; the service binding was already released
     * in {@link #onStop()}.
     */
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mDialog != null) {
            mDialog.dismiss();
            mDialog = null;
        }

        if (mServiceSettingsDialog != null) {
            mServiceSettingsDialog.dismiss();
            mServiceSettingsDialog = null;
        }

        if (mVideoSettingsDialog != null) {
            mVideoSettingsDialog.dismiss();
            mVideoSettingsDialog = null;
        }

        if (mAuthorizationDialog != null) {
            mAuthorizationDialog.dismiss();
            mAuthorizationDialog = null;
        }

        if (mAuthorizedDevicesDialog != null) {
            mAuthorizedDevicesDialog.dismiss();
            mAuthorizedDevicesDialog = null;
        }
    }

    // Server events forwarded by ScreenShareService through mMessenger.
    private void onServerStarted() {
        mServiceStatus = SERVICE_STATUS_ONLINE;
        updateServiceView();
    }

    private void onServerStopped() {
        mServiceStatus = SERVICE_STATUS_OFFLINE;
        mServerInfo = null;
        mServerStats = null;
        updateServiceView();
    }

    private void onServerInfoChanged(@NonNull final ServerInfo serverInfo) {
        mServerInfo = serverInfo;
        updateServiceInfoView();
    }

    private void onServerStatsChanged(@NonNull final ServerStats serverStats) {
        mServerStats = serverStats;
        updateServiceStatsView();
    }

    private void updateServiceView() {
        final boolean isOnline = mServiceStatus == SERVICE_STATUS_ONLINE;
        final MenuItem serviceMenuItem = mServiceToolbarView.getMenu().findItem(R.id.service);
        serviceMenuItem.setIcon(isOnline ? R.drawable.ic_stop_service : R.drawable.ic_start_service);
        serviceMenuItem.setEnabled(mServiceStatus != SERVICE_STATUS_UNKNOWN);

        mServiceStatusView.setText(isOnline ? R.string.service_online : R.string.service_offline);
        mServiceStatusView.setEnabled(isOnline);

        updateServiceInfoView();
        updateServiceStatsView();
    }

    // Falls back to the stored name and port while no server info is available.
    @SuppressLint("StringFormatMatches")
    private void updateServiceInfoView() {
        mServiceNameView.setText(Optional.ofNullable(mServerInfo)
            .map(ServerInfo::getName)
            .orElseGet(() -> Preferences.get().getServiceName()));
        mServiceNameView.setEnabled(mServiceStatus == SERVICE_STATUS_ONLINE);

        final String address = getString(R.string.service_address,
            mServerInfo == null ? Constants.UNKNOWN_HOST_ADDRESS : mServerInfo.getHostAddress(),
            mServerInfo == null ? Preferences.get().getServerPort() : mServerInfo.getPort());
        mServiceAddressView.setText(Preferences.get().isTlsAllowPlaintext()
            ? address + "\n" + getString(R.string.tls_plaintext_warning) : address);
        mServiceAddressView.setEnabled(mServiceStatus == SERVICE_STATUS_ONLINE);
    }

    @SuppressLint("StringFormatMatches")
    private void updateServiceStatsView() {
        final List<ServerStats.TransportStats> transports = mServerStats == null
            ? Collections.emptyList() : mServerStats.getTransports();

        // Viewer rows carry a session block; address rows without a viewer contribute no counters.
        int viewers = 0;
        long queuedUnits = 0L;
        long droppedUnits = 0L;
        long resyncCount = 0L;
        for (final ServerStats.TransportStats transport : transports) {
            if (transport.getClientId() == null) {
                continue;
            }
            viewers++;
            queuedUnits += transport.getQueuedUnits();
            droppedUnits += transport.getDroppedUnits();
            resyncCount += transport.getResyncCount();
        }

        mServiceStatsView.setText(getString(R.string.service_stats,
            transports.size(),
            Formatter.formatFileSize(this, mServerStats == null ? 0L : mServerStats.getOutboundDataRate()))
            + "\n" + getString(R.string.service_viewers, viewers, queuedUnits, droppedUnits,
            resyncCount));
        mServiceStatsView.setEnabled(mServiceStatus == SERVICE_STATUS_ONLINE);
    }

    // Mirrors the persisted discovery preference; disabling also clears the list.
    private void updateDiscoveryView() {
        final MenuItem searchMenuItem = mDiscoveryToolbarView.getMenu().findItem(R.id.search);
        final boolean isEnabled = Preferences.get().isDiscoveryEnabled();
        searchMenuItem.setIcon(isEnabled ? R.drawable.ic_stop_search : R.drawable.ic_start_search);
        mDiscoveryManualAddView.setVisibility(isEnabled ? View.VISIBLE : View.GONE);
        if (!isEnabled) {
            mMainHandler.removeCallbacks(mDiscoveryHintRunnable);
            mShowManualDiscoveryHint = false;
            mDiscoveryListAdapter.submitList(null);
        }
        updateDiscoveryPlaceholder();
    }

    /**
     * Renders the single placeholder under the discovery list.
     *
     * <p>Three states share it: discovery is off, discovery is searching, and the search stayed
     * empty long enough that the manual entry is offered. The last one is clickable, which is why
     * the placeholder carries the click listener instead of a separate empty-state view.</p>
     */
    private void updateDiscoveryPlaceholder() {
        final DiscoveryListModel.EmptyState state = DiscoveryListModel.emptyState(
            Preferences.get().isDiscoveryEnabled(), mShowManualDiscoveryHint);
        if (state == DiscoveryListModel.EmptyState.DISABLED) {
            mDiscoveryListPlaceholderView.setText(R.string.search_services_off);
            mDiscoveryListPlaceholderView.setClickable(false);
            mDiscoveryListPlaceholderView.setVisibility(View.VISIBLE);
            mDiscoveryListView.setVisibility(View.GONE);
            return;
        }

        if (!mDevices.isEmpty()) {
            mDiscoveryListPlaceholderView.setVisibility(View.GONE);
            mDiscoveryListView.setVisibility(View.VISIBLE);
            return;
        }

        final boolean offerManualEntry = state == DiscoveryListModel.EmptyState.NOT_FOUND;
        mDiscoveryListPlaceholderView.setText(offerManualEntry
            ? R.string.discovery_empty_manual_hint : R.string.searching_services);
        mDiscoveryListPlaceholderView.setClickable(offerManualEntry);
        mDiscoveryListPlaceholderView.setVisibility(View.VISIBLE);
        mDiscoveryListView.setVisibility(View.GONE);
    }

    /**
     * Offers the actions of one discovered device.
     *
     * <p>A long press opens this menu; every row states the action it performs, so the manual entry
     * removal and the trust reset stay reachable next to the favorite toggle. The flags the actions
     * depend on are already part of {@link DeviceInfo}, so the store snapshot refresh after a change
     * updates the badge without a manual rebind.</p>
     */
    private void showDiscoveryItemMenu(@NonNull final DeviceInfo item) {
        final boolean isFavorite = DiscoveryManager.get().isFavorite(item.getId());
        final List<Integer> labels = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();
        labels.add(isFavorite
            ? R.string.discovery_remove_favorite : R.string.discovery_add_favorite);
        actions.add(() -> toggleFavorite(item));
        if (item.isManual()) {
            labels.add(R.string.discovery_remove_manual);
            actions.add(() -> removeManualDevice(item));
        }
        if (TlsPinStore.get().find(item.getId()) != null) {
            labels.add(R.string.tls_forget_trust);
            actions.add(() -> forgetTrust(item));
        }

        final CharSequence[] items = new CharSequence[labels.size()];
        for (int i = 0; i < labels.size(); i++) {
            items[i] = getString(labels.get(i));
        }
        new MaterialAlertDialogBuilder(this)
            .setTitle(item.displayName())
            .setItems(items, (dialog, which) -> actions.get(which).run())
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /**
     * Removes a manual entry; the next discovery start no longer restores it.
     */
    private void removeManualDevice(@NonNull final DeviceInfo item) {
        if (DiscoveryManager.get().removeManualDevice(item.getId())) {
            Toast.makeText(this, R.string.discovery_manual_removed, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Removes the stored pin of a device; the next connection asks for confirmation again.
     */
    private void forgetTrust(@NonNull final DeviceInfo item) {
        if (!TlsPinStore.get().unpin(item.getId())) {
            return;
        }
        Toast.makeText(this, R.string.tls_forget_trust_done, Toast.LENGTH_SHORT).show();
    }

    private void toggleFavorite(@NonNull final DeviceInfo item) {
        final boolean isFavorite = DiscoveryManager.get().isFavorite(item.getId());
        DiscoveryManager.get().setFavorite(item.getId(), !isFavorite);
    }

    /**
     * Asks for one address and probes it.
     *
     * <p>The dialog stays open only until the address is accepted for probing; the outcome is a
     * toast, because the probe runs in the background and a successful one adds the device to the
     * list. An unparseable address keeps the dialog open with an inline error.</p>
     */
    private void showManualAddressDialog() {
        final EditText inputView = new EditText(this);
        inputView.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        inputView.setHint(R.string.discovery_manual_hint);
        inputView.setSingleLine(true);

        final FrameLayout container = new FrameLayout(this);
        final int padding = Math.round(24 * getResources().getDisplayMetrics().density);
        container.setPadding(padding, padding / 2, padding, 0);
        container.addView(inputView, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));

        final AlertDialog dialog = new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.discovery_manual_title)
            .setView(container)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            .setOnClickListener(v -> {
                if (probeManualAddress(inputView.getText().toString())) {
                    dialog.dismiss();
                } else {
                    inputView.setError(getString(R.string.discovery_invalid_address));
                }
            }));
        if (mDialog != null) {
            mDialog.dismiss();
        }
        mDialog = dialog;
        dialog.show();
    }

    private boolean probeManualAddress(@NonNull final String input) {
        final boolean accepted = DiscoveryManager.get().probeManualAddress(input,
            new ManualAddressProvider.ProbeCallback() {
                @Override
                public void onConfirmed(@NonNull final String host, final int port,
                    @NonNull final DeviceSummary peer) {
                    Toast.makeText(MainActivity.this, R.string.discovery_manual_added,
                        Toast.LENGTH_SHORT).show();
                }

                @Override
                public void onFailed(@NonNull final String reason) {
                    // A parse failure is shown inline next to the input, not as a toast.
                    if ("invalid_address".equals(reason)) {
                        return;
                    }
                    // A probe that ran out of time is reported as such: it means the address was
                    // not answered in time, which is a different thing to tell the user than an
                    // address that answered and refused.
                    final int messageResId = isProbeTimeout(reason)
                        ? R.string.discovery_manual_timeout : R.string.discovery_manual_failed;
                    Toast.makeText(MainActivity.this, messageResId,
                        Toast.LENGTH_SHORT).show();
                }
            });
        if (accepted) {
            Toast.makeText(this, R.string.discovery_manual_adding, Toast.LENGTH_SHORT).show();
        }
        return accepted;
    }

    /**
     * Returns whether {@code reason} reports a probe that ran out of time.
     *
     * <p>Two layers report the same outcome with different names: the transport reports
     * {@code DEADLINE_EXCEEDED} when the call deadline fires, and the probe reports {@code timeout}
     * when its own wait ends before a callback arrives.</p>
     */
    private static boolean isProbeTimeout(@NonNull final String reason) {
        return "DEADLINE_EXCEEDED".equals(reason) || "timeout".equals(reason);
    }

    /**
     * Loads the device identity and reports a change since the previous run.
     *
     * <p>The identity is always recomputed from the AndroidKeyStore key. A restore from backup
     * brings the stored fingerprint back without the key, and a reset removes the key on purpose;
     * in both cases the derived fingerprint differs from the record, so peers and favorites still
     * bound to the old identity are surfaced to the user instead of the change staying invisible.</p>
     */
    private void checkDeviceIdentity() {
        try {
            if (!IdentityManager.get().getIdentity().isChanged()) {
                return;
            }
        } catch (IdentityUnavailableException e) {
            Log.e(mTag, "checkDeviceIdentity: device identity unavailable", e);
            return;
        }

        if (mDialog != null) {
            mDialog.dismiss();
        }
        mDialog = new MaterialAlertDialogBuilder(this)
            .setMessage(R.string.device_identity_changed)
            .setPositiveButton(android.R.string.ok, null)
            .setCancelable(false)
            .show();
    }

    // Renders the current device id and key fingerprint; the sheet is reused, so both are
    // refreshed on every show. The running session's TLS material is preferred: while a share is
    // running, the values a peer can verify are the ones its certificate carries, and a key that was
    // replaced under the session would otherwise be shown as the one that was loaded at startup.
    private void updateDeviceIdentityView() {
        if (mDeviceIdSettingView == null) {
            return;
        }

        final TlsMaterial material = TlsMaterialHolder.get().getCachedMaterial();
        if (material != null) {
            mDeviceIdSettingView.setText(material.getDeviceId());
            if (mDeviceFingerprintSettingView != null) {
                mDeviceFingerprintSettingView.setText(
                    FingerprintFormatter.toDisplay(material.getFingerprint()));
            }
            return;
        }

        try {
            final com.obby.android.localscreenshare.identity.DeviceIdentity identity =
                IdentityManager.get().getIdentity();
            mDeviceIdSettingView.setText(identity.getId());
            if (mDeviceFingerprintSettingView != null) {
                mDeviceFingerprintSettingView.setText(
                    FingerprintFormatter.toDisplay(identity.getFingerprint()));
            }
        } catch (IdentityUnavailableException e) {
            Log.e(mTag, "updateDeviceIdentityView: device identity unavailable", e);
            mDeviceIdSettingView.setText(R.string.device_identity_unavailable);
            if (mDeviceFingerprintSettingView != null) {
                mDeviceFingerprintSettingView.setText(R.string.device_identity_unavailable);
            }
        }
    }

    /**
     * Asks for confirmation and then replaces the identity key.
     *
     * <p>Every peer that stored this device keeps the old fingerprint, so the discovered list is
     * cleared and the user is told that favorites must be confirmed again. A running share is not
     * restarted: the reset broadcast makes the service rebuild its TLS material, and the server info,
     * the announcement and the notification follow the new identity from there.</p>
     */
    private void resetDeviceIdentity() {
        if (mDialog != null) {
            mDialog.dismiss();
        }

        mDialog = new MaterialAlertDialogBuilder(this)
            .setMessage(R.string.identity_reset_confirm)
            .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                try {
                    IdentityManager.get().reset();
                } catch (IdentityUnavailableException e) {
                    Log.e(mTag, "resetDeviceIdentity: device identity reset failed", e);
                    Toast.makeText(this, R.string.identity_reset_failed, Toast.LENGTH_LONG).show();
                    return;
                }

                DiscoveryManager.get().onIdentityReset();
                sendBroadcast(new Intent(ServiceProtocol.ACTION_IDENTITY_RESET).setPackage(getPackageName()));
                updateDeviceIdentityView();
                Toast.makeText(this, R.string.identity_reset_done, Toast.LENGTH_LONG).show();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .setCancelable(false)
            .show();
    }

    /**
     * Passes the permission gate and launches the system screen-capture consent.
     *
     * <p>Nothing starts until the consent activity returns; on Android 14 and newer the request is
     * restricted to the default display, which matches the single virtual display of the capture
     * pipeline.</p>
     */
    private void startScreenShareService() {
        if (requestScreenShare()) {
            final MediaProjectionManager mediaProjectionManager = getSystemService(MediaProjectionManager.class);
            final Intent intent;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                intent = mediaProjectionManager.createScreenCaptureIntent(
                    MediaProjectionConfig.createConfigForDefaultDisplay());
            } else {
                intent = mediaProjectionManager.createScreenCaptureIntent();
            }
            mScreenCaptureLauncher.launch(intent);
        }
    }

    /**
     * Asks for confirmation and then broadcasts the stop action.
     *
     * <p>The broadcast is the same path used by the service notification and the floating chip.
     * A previously shown dialog is dismissed before the new one is displayed.</p>
     */
    private void stopScreenShareService() {
        if (mDialog != null) {
            mDialog.dismiss();
        }

        // The confirmation is a floating overlay, not an activity window: while a viewer is
        // streaming its APPLICATION_OVERLAY window sits above the activity, so a plain activity
        // dialog would be covered and the stop action would be unreachable from the main screen.
        final Context themedContext = new ContextThemeWrapper(App.get(), R.style.Theme_ScreenShare);
        mDialog = new MaterialAlertDialogBuilder(themedContext)
            .setMessage(R.string.screen_share_stop_confirm)
            .setPositiveButton(android.R.string.ok, (dialog, which) -> sendBroadcast(
                new Intent(ServiceProtocol.ACTION_STOP_SERVICE).setPackage(getPackageName())))
            .setNegativeButton(android.R.string.cancel, null)
            .setCancelable(false)
            .create();
        mDialog.getWindow().setType(ServiceProtocol.FLOATING_WINDOW_TYPE);
        mDialog.show();
    }

    /**
     * Passes the permission gate and starts the client service for the selected server.
     *
     * <p>The device is passed whole: the client service uses its primary address, so the address
     * choice (confirmed first, IPv4 before IPv6) is made once, in the store.</p>
     */
    private void startScreenShareViewer(@NonNull final DeviceInfo serviceInfo) {
        if (Preferences.get().isTlsAllowPlaintext()) {
            startScreenShareViewerWithFavoriteCheck(serviceInfo);
            return;
        }

        if (!serviceInfo.isSecure()) {
            // An unencrypted peer is not dialed at all: the fallback would be exactly the state
            // this change removes, so the row explains the incompatibility instead. This flag is
            // the transport capability of the peer, not its "private screen" setting (N1).
            if (mDialog != null) {
                mDialog.dismiss();
            }
            mDialog = new MaterialAlertDialogBuilder(this)
                .setTitle(serviceInfo.displayName())
                .setMessage(R.string.tls_device_not_encrypted_message)
                .setPositiveButton(android.R.string.ok, null)
                .show();
            return;
        }

        final String fingerprint = serviceInfo.getFingerprint();
        if (fingerprint == null || !FingerprintFormatter.isCanonical(fingerprint)) {
            // The row was only heard: an announcement carries no fingerprint by design. The
            // certificate of a direct handshake is the value to confirm, so fetch it instead of
            // treating "nothing to compare yet" as an identity failure (N2).
            resolveDeviceIdentityThenConnect(serviceInfo);
            return;
        }

        connectWithConfirmedIdentity(serviceInfo, fingerprint);
    }

    /**
     * Learns the certificate identity of a device that was only heard, then connects normally.
     *
     * <p>Multicast announcements carry no fingerprint, so the first selection of a discovered device
     * has nothing to confirm. One {@code Register} call over TLS answers with the identity proven by
     * the certificate the peer presented, and the confirmation path is entered with that value. A
     * real mismatch — a certificate that does not belong to the advertised id — is reported as the
     * security check it is; a device that cannot be reached, or that stops sharing meanwhile, gets a
     * neutral message instead.</p>
     *
     * @param serviceInfo discovered device whose fingerprint is still unknown
     */
    private void resolveDeviceIdentityThenConnect(@NonNull final DeviceInfo serviceInfo) {
        if (mDialog != null) {
            mDialog.dismiss();
        }
        mFingerprintProbeId = serviceInfo.getId();
        mDialog = new MaterialAlertDialogBuilder(this)
            .setTitle(serviceInfo.displayName())
            .setMessage(R.string.tls_identity_probe_message)
            .setNegativeButton(android.R.string.cancel, (dialog, which) -> mFingerprintProbeId = null)
            .setCancelable(true)
            .show();

        DiscoveryManager.get().probeIdentity(serviceInfo,
            new ManualAddressProvider.ProbeCallback() {
                @Override
                public void onConfirmed(@NonNull final String host, final int port,
                    @NonNull final DeviceSummary peer) {
                    if (!serviceInfo.getId().equals(mFingerprintProbeId)) {
                        return;
                    }
                    mFingerprintProbeId = null;
                    dismissDialog();
                    final DeviceInfo confirmed =
                        DiscoveryManager.get().getDeviceStore().device(peer.getDeviceId());
                    if (confirmed == null
                        || confirmed.getFingerprint() == null
                        || !FingerprintFormatter.isCanonical(confirmed.getFingerprint())) {
                        showIdentityProbeFailedDialog(serviceInfo);
                        return;
                    }
                    startScreenShareViewer(confirmed);
                }

                @Override
                public void onFailed(@NonNull final String reason) {
                    if (!serviceInfo.getId().equals(mFingerprintProbeId)) {
                        return;
                    }
                    mFingerprintProbeId = null;
                    dismissDialog();
                    if (IDENTITY_MISMATCH_REASON.equals(reason)) {
                        showIdentityMismatchDialog(serviceInfo);
                    } else {
                        showIdentityProbeFailedDialog(serviceInfo);
                    }
                }
            });
    }

    // Connects to a device whose certificate identity is already known and canonical.
    private void connectWithConfirmedIdentity(@NonNull final DeviceInfo serviceInfo,
                                              @NonNull final String fingerprint) {
        final TlsPin pin = TlsPinStore.get().find(serviceInfo.getId());
        final PinPolicy.Outcome outcome =
            PinPolicy.decide(serviceInfo.getId(), serviceInfo.getId(), fingerprint, pin);
        switch (TofuPolicy.decide(outcome)) {
            case BLOCK:
                showIdentityMismatchDialog(serviceInfo);
                return;
            case CONFIRM_FIRST:
                showFirstConnectDialog(serviceInfo, fingerprint);
                return;
            case CONFIRM_CHANGE:
                showIdentityChangedDialog(serviceInfo, pin, fingerprint);
                return;
            case PROCEED:
            default:
                // The pin is authoritative; a favorite that still carries an older fingerprint is
                // brought in line without asking again.
                confirmFavoriteSilently(serviceInfo);
                startScreenShareViewerConfirmed(serviceInfo);
                return;
        }
    }

    // Plaintext override path: no certificate identity exists, so only the favorite check applies.
    private void startScreenShareViewerWithFavoriteCheck(@NonNull final DeviceInfo serviceInfo) {
        final FavoriteDevice favorite = FavoriteStore.get().find(serviceInfo.getId());
        if (favorite != null && FavoriteStore.checkIdentity(favorite.getFingerprint(),
            serviceInfo.getFingerprint()) == FavoriteStore.IdentityStatus.CHANGED) {
            // Never dial a saved identity that now presents a different fingerprint without an
            // explicit acknowledgement; accepting rebinds the favorite to the new key.
            new MaterialAlertDialogBuilder(this)
                .setTitle(serviceInfo.displayName())
                .setMessage(R.string.discovery_identity_changed_connect)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    DiscoveryManager.get().confirmFavoriteIdentity(
                        serviceInfo.getId(), serviceInfo.getFingerprint());
                    startScreenShareViewerConfirmed(serviceInfo);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
            return;
        }
        startScreenShareViewerConfirmed(serviceInfo);
    }

    /**
     * Confirms the first contact with a device and pins it when the user accepts.
     *
     * <p>The dialog shows the full fingerprint and says explicitly that this is not pairing: the
     * device still decides whether it shares. A different id last seen at the same address is
     * shown as a hint only, because local networks reuse addresses.</p>
     */
    private void showFirstConnectDialog(@NonNull final DeviceInfo serviceInfo,
                                        @NonNull final String fingerprint) {
        final String display = FingerprintFormatter.toDisplay(fingerprint);
        final String message = getString(R.string.tls_first_connect_message, display)
            + addressChangeHint(serviceInfo);

        if (mDialog != null) {
            mDialog.dismiss();
        }
        mDialog = new MaterialAlertDialogBuilder(this)
            .setTitle(serviceInfo.displayName())
            .setMessage(message)
            .setPositiveButton(R.string.tls_trust_device, (dialog, which) -> {
                TlsPinStore.get().pin(serviceInfo.getId(), fingerprint);
                rememberAddress(serviceInfo);
                confirmFavoriteSilently(serviceInfo);
                startScreenShareViewerConfirmed(serviceInfo);
            })
            .setNeutralButton(R.string.tls_copy_fingerprint,
                (dialog, which) -> copyFingerprint(display))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /**
     * Warns that a pinned device now presents another fingerprint.
     *
     * <p>The old and the new value are shown side by side. Leaving the dialog keeps the old pin;
     * only the explicit action rebinds, and that action is logged as a user decision rather than
     * treated as a routine update.</p>
     */
    private void showIdentityChangedDialog(@NonNull final DeviceInfo serviceInfo,
                                           @Nullable final TlsPin pin,
                                           @NonNull final String fingerprint) {
        final String oldDisplay = pin == null ? "" : FingerprintFormatter.toDisplay(
            pin.getFingerprint());
        final String newDisplay = FingerprintFormatter.toDisplay(fingerprint);

        if (mDialog != null) {
            mDialog.dismiss();
        }
        mDialog = new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.tls_identity_changed_title)
            .setMessage(getString(R.string.tls_identity_changed_message, oldDisplay, newDisplay))
            .setPositiveButton(R.string.tls_trust_new_identity, (dialog, which) -> {
                TlsPinStore.get().pin(serviceInfo.getId(), fingerprint);
                rememberAddress(serviceInfo);
                confirmFavoriteSilently(serviceInfo);
                startScreenShareViewerConfirmed(serviceInfo);
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void showIdentityMismatchDialog(@NonNull final DeviceInfo serviceInfo) {
        if (mDialog != null) {
            mDialog.dismiss();
        }
        mDialog = new MaterialAlertDialogBuilder(this)
            .setTitle(serviceInfo.displayName())
            .setMessage(R.string.tls_identity_mismatch_message)
            .setPositiveButton(android.R.string.ok, null)
            .show();
    }

    /**
     * Reports that the certificate of a discovered device could not be read in time.
     *
     * <p>Nothing is wrong with the device's identity here: the probe may have timed out, the device
     * may have stopped sharing, or the network may filter the call. The message says exactly that,
     * because claiming a security problem the code cannot prove is worse than admitting that the
     * check could not run (N2).</p>
     */
    private void showIdentityProbeFailedDialog(@NonNull final DeviceInfo serviceInfo) {
        if (mDialog != null) {
            mDialog.dismiss();
        }
        mDialog = new MaterialAlertDialogBuilder(this)
            .setTitle(serviceInfo.displayName())
            .setMessage(R.string.tls_identity_probe_failed)
            .setPositiveButton(android.R.string.ok, null)
            .show();
    }

    // Dismisses the dialog currently shown, if any.
    private void dismissDialog() {
        if (mDialog != null) {
            mDialog.dismiss();
            mDialog = null;
        }
    }

    @NonNull
    private String addressChangeHint(@NonNull final DeviceInfo serviceInfo) {
        final com.obby.android.localscreenshare.discovery.model.DeviceAddress address =
            serviceInfo.primaryAddress();
        if (address == null) {
            return "";
        }
        final String lastId = TlsPinStore.get().lastDeviceId(address.getHost(), address.getPort());
        if (!TofuPolicy.isAddressChanged(lastId, serviceInfo.getId())) {
            return "";
        }
        return "\n\n" + getString(R.string.tls_address_changed_hint, lastId.substring(0,
            Math.min(8, lastId.length())));
    }

    private void rememberAddress(@NonNull final DeviceInfo serviceInfo) {
        final com.obby.android.localscreenshare.discovery.model.DeviceAddress address =
            serviceInfo.primaryAddress();
        if (address == null) {
            return;
        }
        TlsPinStore.get().rememberAddress(address.getHost(), address.getPort(),
            serviceInfo.getId());
    }

    private void confirmFavoriteSilently(@NonNull final DeviceInfo serviceInfo) {
        final FavoriteDevice favorite = FavoriteStore.get().find(serviceInfo.getId());
        if (favorite != null && FavoriteStore.checkIdentity(favorite.getFingerprint(),
            serviceInfo.getFingerprint()) == FavoriteStore.IdentityStatus.CHANGED) {
            DiscoveryManager.get().confirmFavoriteIdentity(
                serviceInfo.getId(), serviceInfo.getFingerprint());
        }
    }

    private void copyFingerprint(@NonNull final String display) {
        final android.content.ClipboardManager clipboard =
            getSystemService(android.content.ClipboardManager.class);
        if (clipboard == null) {
            return;
        }
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText(
            getString(R.string.tls_local_fingerprint_label), display));
        Toast.makeText(this, R.string.tls_fingerprint_copied, Toast.LENGTH_SHORT).show();
    }

    private void startScreenShareViewerConfirmed(@NonNull final DeviceInfo serviceInfo) {
        if (requestScreenShare()) {
            ContextCompat.startForegroundService(this, new Intent(this, ScreenViewerService.class)
                .putExtra(ServiceProtocol.EXTRA_SERVICE_INFO, serviceInfo));
        }
    }

    /**
     * Checks the notification and overlay prerequisites shared by both services.
     *
     * <p>Returns {@code false} after launching the notification permission request or showing a
     * dialog that sends the user to the relevant settings screen; the requested action is not
     * resumed automatically, so the user repeats it after granting. Returns {@code true} only when
     * every prerequisite is already satisfied.</p>
     */
    private boolean requestScreenShare() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            mNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            return false;
        }

        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            if (mDialog != null) {
                mDialog.dismiss();
            }
            mDialog = new MaterialAlertDialogBuilder(this)
                .setMessage(HtmlCompat.fromHtml(getString(R.string.permission_request_post_notification, App.getLabel()),
                    HtmlCompat.FROM_HTML_MODE_LEGACY))
                .setPositiveButton(android.R.string.ok,
                    (dialog, which) -> startActivity(IntentUtils.createAppNotificationSettingsIntent(this)))
                .setNegativeButton(android.R.string.cancel, null)
                .setCancelable(false)
                .show();
            return false;
        }

        if (!Settings.canDrawOverlays(this)) {
            if (mDialog != null) {
                mDialog.dismiss();
            }
            mDialog = new MaterialAlertDialogBuilder(this)
                .setMessage(HtmlCompat.fromHtml(getString(R.string.permission_request_system_alert_window, App.getLabel()),
                    HtmlCompat.FROM_HTML_MODE_LEGACY))
                .setPositiveButton(android.R.string.ok,
                    (dialog, which) -> startActivity(IntentUtils.createManageOverlayPermissionIntent(this)))
                .setNegativeButton(android.R.string.cancel, null)
                .setCancelable(false)
                .show();
            return false;
        }

        return true;
    }

    // Sends the activity's reply messenger to the service. A dead service drops the message, so
    // the failure is ignored.
    private void registerServiceClient() {
        if (mServiceMessenger == null) {
            return;
        }

        try {
            final Message message = Message.obtain(null, ServiceProtocol.MSG_REGISTER_SERVICE_CLIENT);
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
            final Message message = Message.obtain(null, ServiceProtocol.MSG_UNREGISTER_SERVICE_CLIENT);
            message.replyTo = mMessenger;
            mServiceMessenger.send(message);
        } catch (RemoteException e) {
            // ignored
        }
    }

    /**
     * Builds the service settings sheet.
     *
     * <p>Every control writes its value to {@link Preferences} as soon as it changes, and the
     * running service applies it through its preference observer. The port is the exception: it
     * is read when {@code ScreenShareServer} is constructed, so a changed port applies to the next server
     * start. Current preference values are loaded when the sheet is shown, not when it is built,
     * because the same instance is reused.</p>
     */
    @SuppressWarnings("DataFlowIssue")
    @NonNull
    private BottomSheetDialog createServiceSettingsDialog() {
        final BottomSheetDialog settingsDialog = new BottomSheetDialog(this);
        settingsDialog.setContentView(R.layout.widget_screen_share_service_settings);
        settingsDialog.setDismissWithAnimation(true);
        settingsDialog.getBehavior().setSkipCollapsed(true);

        // Projection encoding controls live in their own sheet; this row is the only entry point
        // and carries the current values so the sheet does not hide them.
        mVideoSettingsSummaryView = settingsDialog.findViewById(R.id.video_settings_summary);
        settingsDialog.findViewById(R.id.video_settings_entry).setOnClickListener(view -> {
            if (mVideoSettingsDialog == null) {
                mVideoSettingsDialog = createVideoSettingsDialog();
            }
            mVideoSettingsDialog.show();
        });

        final MaterialSwitch keepScreenOnSettingView = settingsDialog.findViewById(R.id.keep_screen_on_setting);
        keepScreenOnSettingView.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (Preferences.get().isProjectionKeepScreenOn() != isChecked) {
                Preferences.get().setProjectionKeepScreenOn(isChecked);
            }
        });

        final MaterialSwitch secureSettingView = settingsDialog.findViewById(R.id.secure_setting);
        secureSettingView.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (Preferences.get().isProjectionSecure() != isChecked) {
                Preferences.get().setProjectionSecure(isChecked);
            }
        });

        // Sound sharing: the switch is the single source of intent, the permission is requested on
        // the way in, and an unsupported API level disables the row instead of pretending to work.
        final boolean audioSupported = AudioAvailability.forApiLevel(Build.VERSION.SDK_INT)
            == AudioAvailability.AVAILABLE;
        final MaterialSwitch audioSettingView = settingsDialog.findViewById(R.id.audio_setting);
        final MaterialTextView audioStatusView =
            settingsDialog.findViewById(R.id.audio_status_setting);
        mAudioSettingView = audioSettingView;
        audioSettingView.setEnabled(audioSupported);
        if (!audioSupported) {
            audioSettingView.setText(R.string.audio_setting_disabled_label);
            audioStatusView.setText(R.string.audio_unavailable_api_unsupported);
            audioStatusView.setVisibility(View.VISIBLE);
        }
        audioSettingView.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!isChecked) {
                Preferences.get().setAudioEnabled(false);
                return;
            }
            if (!audioSupported) {
                buttonView.setChecked(false);
                return;
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
                Preferences.get().setAudioEnabled(true);
            } else {
                mRecordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
            }
        });

        final MaterialSwitch authorizationSettingView =
            settingsDialog.findViewById(R.id.authorization_setting);
        mAuthorizationSettingNoteView =
            settingsDialog.findViewById(R.id.authorization_setting_note);
        authorizationSettingView.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (Preferences.get().isAuthorizationEnabled() != isChecked) {
                Preferences.get().setAuthorizationEnabled(isChecked);
                updateAuthorizationSettingNote();
            }
        });

        mAuthorizationDevicesSummaryView =
            settingsDialog.findViewById(R.id.authorization_devices_summary);
        settingsDialog.findViewById(R.id.authorization_devices_entry)
            .setOnClickListener(view -> showAuthorizedDevicesDialog());
        updateAuthorizationDevicesSummary();

        final NumberPicker portSettingView = settingsDialog.findViewById(R.id.port_setting);
        portSettingView.setValue(MIN_SERVER_PORT);
        portSettingView.setMinValue(MIN_SERVER_PORT);
        portSettingView.setMaxValue(MAX_SERVER_PORT);
        portSettingView.setOnValueChangedListener((picker, oldVal, newVal) -> {
            if (Preferences.get().getServerPort() != newVal) {
                Preferences.get().setServerPort(newVal);
                updateServiceInfoView();
            }
        });

        mDeviceIdSettingView = settingsDialog.findViewById(R.id.device_id_setting);
        mDeviceFingerprintSettingView =
            settingsDialog.findViewById(R.id.device_fingerprint_setting);
        final MaterialButton resetDeviceIdentityView =
            settingsDialog.findViewById(R.id.identity_reset_setting);
        resetDeviceIdentityView.setOnClickListener(view -> resetDeviceIdentity());

        final MaterialSwitch multicastEnabledView =
            settingsDialog.findViewById(R.id.multicast_enabled_setting);
        multicastEnabledView.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (Preferences.get().isMulticastAnnounceEnabled() != isChecked) {
                Preferences.get().setMulticastAnnounceEnabled(isChecked);
            }
        });

        final EditText multicastGroupView = settingsDialog.findViewById(R.id.multicast_group_setting);
        // The group is free text, so it is committed when the field loses focus or the sheet closes
        // rather than on every keystroke.
        multicastGroupView.setOnFocusChangeListener((view, hasFocus) -> {
            if (!hasFocus) {
                commitMulticastGroup(multicastGroupView);
            }
        });
        settingsDialog.setOnDismissListener(dialog -> commitMulticastGroup(multicastGroupView));

        final NumberPicker multicastPortView = settingsDialog.findViewById(R.id.multicast_port_setting);
        multicastPortView.setMinValue(DiscoverySettings.MIN_MULTICAST_PORT);
        multicastPortView.setMaxValue(DiscoverySettings.MAX_MULTICAST_PORT);
        multicastPortView.setOnValueChangedListener((picker, oldVal, newVal) -> {
            if (Preferences.get().getMulticastPort() != newVal) {
                Preferences.get().setMulticastPort(newVal);
            }
        });

        final DiscoverySettings.RefreshTier[] refreshTiers = DiscoverySettings.RefreshTier.values();
        final NumberPicker refreshView = settingsDialog.findViewById(R.id.discovery_refresh_setting);
        refreshView.setMinValue(0);
        refreshView.setMaxValue(refreshTiers.length - 1);
        refreshView.setDisplayedValues(new String[]{
            getString(R.string.discovery_refresh_low_latency),
            getString(R.string.discovery_refresh_balanced),
            getString(R.string.discovery_refresh_power_saver)});
        refreshView.setOnValueChangedListener((picker, oldVal, newVal) -> {
            final String wire = refreshTiers[newVal].wire();
            if (!wire.equals(Preferences.get().getDiscoveryRefreshTier())) {
                Preferences.get().setDiscoveryRefreshTier(wire);
            }
        });

        final VisibilityPolicy.Visibility[] visibilities = VisibilityPolicy.Visibility.values();
        final NumberPicker visibilityView = settingsDialog.findViewById(R.id.discovery_visibility_setting);
        visibilityView.setMinValue(0);
        visibilityView.setMaxValue(visibilities.length - 1);
        visibilityView.setDisplayedValues(new String[]{
            getString(R.string.discovery_visibility_hidden),
            getString(R.string.discovery_visibility_favorites),
            getString(R.string.discovery_visibility_visible),
            getString(R.string.discovery_visibility_visible_10_min)});
        visibilityView.setOnValueChangedListener((picker, oldVal, newVal) -> {
            if (!visibilities[newVal].wire().equals(Preferences.get().getDiscoveryVisibility())) {
                applyVisibility(visibilities[newVal]);
            }
        });

        final MaterialSwitch compatOnlyView =
            settingsDialog.findViewById(R.id.discovery_compat_only_setting);
        compatOnlyView.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (Preferences.get().isDiscoveryCompatOnly() != isChecked) {
                Preferences.get().setDiscoveryCompatOnly(isChecked);
            }
        });

        final MaterialSwitch shareNameView = settingsDialog.findViewById(R.id.share_name_setting);
        shareNameView.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (Preferences.get().isShareNameEnabled() != isChecked) {
                Preferences.get().setShareNameEnabled(isChecked);
            }
        });

        settingsDialog.setOnShowListener(dialog -> {
            settingsDialog.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
            updateVideoSettingsSummary();
            keepScreenOnSettingView.setChecked(Preferences.get().isProjectionKeepScreenOn());
            keepScreenOnSettingView.jumpDrawablesToCurrentState();
            secureSettingView.setChecked(Preferences.get().isProjectionSecure());
            secureSettingView.jumpDrawablesToCurrentState();
            audioSettingView.setChecked(audioSupported && Preferences.get().isAudioEnabled());
            audioSettingView.jumpDrawablesToCurrentState();
            portSettingView.setValue(Preferences.get().getServerPort());
            authorizationSettingView.setChecked(Preferences.get().isAuthorizationEnabled());
            authorizationSettingView.jumpDrawablesToCurrentState();
            updateAuthorizationSettingNote();
            updateAuthorizationDevicesSummary();
            updateDeviceIdentityView();

            final DiscoverySettings settings = Preferences.get().getDiscoverySettings();
            multicastEnabledView.setChecked(settings.isMulticastEnabled());
            multicastEnabledView.jumpDrawablesToCurrentState();
            multicastGroupView.setText(settings.getMulticastGroup());
            multicastPortView.setValue(settings.getMulticastPort());
            refreshView.setValue(settings.getRefreshTier().ordinal());
            visibilityView.setValue(settings.getVisibility().ordinal());
            compatOnlyView.setChecked(settings.isCompatOnly());
            compatOnlyView.jumpDrawablesToCurrentState();
            shareNameView.setChecked(Preferences.get().isShareNameEnabled());
            shareNameView.jumpDrawablesToCurrentState();
        });

        return settingsDialog;
    }

    /**
     * Builds the video encoding sheet.
     *
     * <p>The projection controls moved here from the service settings sheet, so there is exactly
     * one place that writes each preference. Values are persisted when a slider is released: a
     * drag would otherwise persist dozens of intermediate values, and the frame rate and key-frame
     * interval each cost an encoder rebuild.</p>
     *
     * <p><b>Codec rows.</b> The codec is a two-option choice, and the controls that only one codec
     * understands follow it: the JPEG quality row appears for JPEG, while the bitrate and key-frame
     * interval rows are H.264 only, because the JPEG encoder ignores live parameters and every JPEG
     * frame is independently decodable. A codec change takes effect the next time sharing starts;
     * a running capture session keeps the encoder it was built for, which is what the note under
     * the buttons says.</p>
     *
     * <p>The capture percentage slider starts at the minimum the current display and encoder can
     * use, so it never offers a percentage whose capture size the encoder refuses (see
     * {@link ProjectionScalePolicy}). While the sheet is open its controls follow every projection
     * preference change, including the percentage the service writes back after a rejection (see
     * {@link #syncVideoSettingsControls()}).</p>
     */
    @SuppressWarnings("DataFlowIssue")
    @NonNull
    private BottomSheetDialog createVideoSettingsDialog() {
        final BottomSheetDialog settingsDialog = new BottomSheetDialog(this);
        settingsDialog.setContentView(R.layout.widget_screen_share_video_settings);
        settingsDialog.setDismissWithAnimation(true);
        settingsDialog.getBehavior().setSkipCollapsed(true);

        mQualitySettingRow = settingsDialog.findViewById(R.id.quality_setting_row);
        final Slider qualitySettingView = settingsDialog.findViewById(R.id.quality_setting);
        mQualitySettingView = qualitySettingView;
        qualitySettingView.setValue(ProjectionSettings.MIN_JPEG_QUALITY);
        qualitySettingView.setValueFrom(ProjectionSettings.MIN_JPEG_QUALITY);
        qualitySettingView.setValueTo(ProjectionSettings.MAX_JPEG_QUALITY);
        qualitySettingView.setStepSize(ProjectionControlValues.QUALITY_STEP);
        qualitySettingView.addOnSliderTouchListener(new Slider.OnSliderTouchListener() {
            @Override
            public void onStartTrackingTouch(@NonNull Slider slider) {
                mTrackingSliders.add(slider);
            }

            @Override
            public void onStopTrackingTouch(@NonNull Slider slider) {
                mTrackingSliders.remove(slider);
                final int quality = (int) slider.getValue();
                if (Preferences.get().getProjectionQuality() != quality) {
                    Preferences.get().setProjectionQuality(quality);
                }
            }
        });

        final Slider scaleSettingView = settingsDialog.findViewById(R.id.scale_setting);
        mScaleSettingView = scaleSettingView;
        scaleSettingView.setValue(ProjectionSettings.MIN_SCALE_PERCENT);
        scaleSettingView.setValueFrom(ProjectionSettings.MIN_SCALE_PERCENT);
        scaleSettingView.setValueTo(ProjectionSettings.MAX_SCALE_PERCENT);
        scaleSettingView.setStepSize(ProjectionControlValues.SCALE_STEP_PERCENT);
        scaleSettingView.setLabelFormatter(new LabelFormatter() {
            @NonNull
            @Override
            public String getFormattedValue(float value) {
                return NumberFormat.getPercentInstance().format(value / 100f);
            }
        });
        scaleSettingView.addOnSliderTouchListener(new Slider.OnSliderTouchListener() {
            @Override
            public void onStartTrackingTouch(@NonNull Slider slider) {
                mTrackingSliders.add(slider);
            }

            @Override
            public void onStopTrackingTouch(@NonNull Slider slider) {
                mTrackingSliders.remove(slider);
                final int scale = (int) slider.getValue();
                if (Preferences.get().getProjectionScale() != scale) {
                    Preferences.get().setProjectionScale(scale);
                }
            }
        });

        final Slider frameRateSettingView = settingsDialog.findViewById(R.id.frame_rate_setting);
        mFrameRateSettingView = frameRateSettingView;
        frameRateSettingView.setValue(ProjectionSettings.MIN_FRAME_RATE);
        frameRateSettingView.setValueFrom(ProjectionSettings.MIN_FRAME_RATE);
        frameRateSettingView.setValueTo(ProjectionSettings.MAX_FRAME_RATE);
        frameRateSettingView.setStepSize(ProjectionControlValues.FRAME_RATE_STEP);
        frameRateSettingView.setLabelFormatter(new LabelFormatter() {
            @NonNull
            @Override
            public String getFormattedValue(float value) {
                return getString(R.string.projection_frame_rate_value_label, (int) value);
            }
        });
        frameRateSettingView.addOnSliderTouchListener(new Slider.OnSliderTouchListener() {
            @Override
            public void onStartTrackingTouch(@NonNull Slider slider) {
                mTrackingSliders.add(slider);
            }

            @Override
            public void onStopTrackingTouch(@NonNull Slider slider) {
                mTrackingSliders.remove(slider);
                final int frameRate = (int) slider.getValue();
                if (Preferences.get().getProjectionFrameRate() != frameRate) {
                    Preferences.get().setProjectionFrameRate(frameRate);
                }
            }
        });

        final Slider bitrateSettingView = settingsDialog.findViewById(R.id.bitrate_setting);
        mBitrateSettingView = bitrateSettingView;
        mBitrateSettingRow = settingsDialog.findViewById(R.id.bitrate_setting_row);
        bitrateSettingView.setValue(ProjectionControlValues.MIN_BITRATE_MBPS);
        bitrateSettingView.setValueFrom(ProjectionControlValues.MIN_BITRATE_MBPS);
        bitrateSettingView.setValueTo(ProjectionControlValues.MAX_BITRATE_MBPS);
        bitrateSettingView.setStepSize(ProjectionControlValues.BITRATE_STEP_MBPS);
        bitrateSettingView.setLabelFormatter(new LabelFormatter() {
            @NonNull
            @Override
            public String getFormattedValue(float value) {
                return getString(R.string.projection_bitrate_value_label,
                    formatNumber(value, 1));
            }
        });
        bitrateSettingView.addOnSliderTouchListener(new Slider.OnSliderTouchListener() {
            @Override
            public void onStartTrackingTouch(@NonNull Slider slider) {
                mTrackingSliders.add(slider);
            }

            @Override
            public void onStopTrackingTouch(@NonNull Slider slider) {
                mTrackingSliders.remove(slider);
                final int bitrate = toBitrateBps(slider.getValue());
                if (Preferences.get().getProjectionBitrate() != bitrate) {
                    Preferences.get().setProjectionBitrate(bitrate);
                }
            }
        });

        final Slider idrIntervalSettingView = settingsDialog.findViewById(R.id.idr_interval_setting);
        mIdrIntervalSettingView = idrIntervalSettingView;
        mIdrIntervalSettingRow = settingsDialog.findViewById(R.id.idr_interval_setting_row);
        idrIntervalSettingView.setValue(ProjectionSettings.MIN_IDR_INTERVAL_MS / 1000f);
        idrIntervalSettingView.setValueFrom(ProjectionSettings.MIN_IDR_INTERVAL_MS / 1000f);
        idrIntervalSettingView.setValueTo(ProjectionSettings.MAX_IDR_INTERVAL_MS / 1000f);
        idrIntervalSettingView.setStepSize(ProjectionControlValues.IDR_INTERVAL_STEP_SECONDS);
        idrIntervalSettingView.setLabelFormatter(new LabelFormatter() {
            @NonNull
            @Override
            public String getFormattedValue(float value) {
                return getString(R.string.projection_idr_interval_value_label, (int) value);
            }
        });
        idrIntervalSettingView.addOnSliderTouchListener(new Slider.OnSliderTouchListener() {
            @Override
            public void onStartTrackingTouch(@NonNull Slider slider) {
                mTrackingSliders.add(slider);
            }

            @Override
            public void onStopTrackingTouch(@NonNull Slider slider) {
                mTrackingSliders.remove(slider);
                final int intervalMs = toIdrIntervalMs(slider.getValue());
                if (Preferences.get().getProjectionIdrIntervalMs() != intervalMs) {
                    Preferences.get().setProjectionIdrIntervalMs(intervalMs);
                }
            }
        });

        mCodecToggleGroup = settingsDialog.findViewById(R.id.codec_toggle_group);
        mCodecToggleGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            // Only the newly checked button writes, so the pair produces one preference write.
            if (!isChecked) {
                return;
            }
            final String codec = checkedId == R.id.codec_h264_option
                ? CodecIds.H264
                : CodecIds.JPEG;
            if (!codec.equals(Preferences.get().getProjectionCodec())) {
                // The mode is read when a capture session starts; a running session keeps encoding
                // with the codec it was built for.
                Preferences.get().setProjectionCodec(codec);
            }
        });

        // Thermal throttling is a temporary override of the two sliders above, never a stored
        // video value, so it lives at the bottom of the sheet with its own explanation.
        final MaterialSwitch thermalThrottleSettingView =
            settingsDialog.findViewById(R.id.thermal_throttle_setting);
        thermalThrottleSettingView.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (Preferences.get().isThermalThrottleEnabled() != isChecked) {
                Preferences.get().setThermalThrottleEnabled(isChecked);
            }
        });

        settingsDialog.setOnShowListener(dialog -> {
            settingsDialog.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
            applyVideoSettingsControls();
            thermalThrottleSettingView.setChecked(Preferences.get().isThermalThrottleEnabled());
            thermalThrottleSettingView.jumpDrawablesToCurrentState();
        });
        settingsDialog.setOnDismissListener(dialog -> {
            mTrackingSliders.clear();
            updateVideoSettingsSummary();
        });

        return settingsDialog;
    }

    /**
     * Refreshes the open video settings sheet after a projection preference changed.
     *
     * <p>The sheet may be closed or not created yet when the observer fires, in which case there is
     * nothing to refresh; the values are applied from the preferences on the next show anyway.</p>
     */
    private void syncVideoSettingsControls() {
        if (mVideoSettingsDialog == null || !mVideoSettingsDialog.isShowing()) {
            return;
        }
        applyVideoSettingsControls();
    }

    /**
     * Applies the stored projection values to the video settings controls.
     *
     * <p>Called when the sheet is shown and again on every projection preference change while it
     * is open. The refresh matters most for the capture percentage: when the encoder refuses a
     * size, the service writes the percentage it kept back into the preferences, and the slider has
     * to move to that value while the user is still looking at it instead of only after the sheet
     * is reopened. A slider the user is currently dragging is skipped, because the drag owns the
     * value until the finger is lifted; moving it under the finger would fight the gesture, and the
     * release handler would then persist a value the user never chose. A stored percentage below
     * the current usable minimum is displayed at that minimum, which is the lowest value the slider
     * still offers.</p>
     */
    private void applyVideoSettingsControls() {
        final int minimumScalePercent = getMinimumProjectionScalePercent();
        final ProjectionControlValues values =
            ProjectionControlValues.of(currentProjectionSettings(), minimumScalePercent);

        if (mQualitySettingRow != null) {
            mQualitySettingRow.setVisibility(values.isH264() ? View.GONE : View.VISIBLE);
        }
        if (mBitrateSettingRow != null) {
            mBitrateSettingRow.setVisibility(
                values.isBitrateApplicable() ? View.VISIBLE : View.GONE);
        }
        if (mIdrIntervalSettingRow != null) {
            mIdrIntervalSettingRow.setVisibility(
                values.isIdrIntervalApplicable() ? View.VISIBLE : View.GONE);
        }
        if (mQualitySettingView != null && !mTrackingSliders.contains(mQualitySettingView)) {
            mQualitySettingView.setValue(values.getJpegQuality());
        }
        if (mScaleSettingView != null && !mTrackingSliders.contains(mScaleSettingView)) {
            // A lower bound equal to the upper bound would leave a single-valued slider, which the
            // widget rejects; that only happens when even 100% is below the encoder minimum, and
            // no percentage helps then.
            if (minimumScalePercent < ProjectionSettings.MAX_SCALE_PERCENT) {
                mScaleSettingView.setValueFrom(minimumScalePercent);
            }
            mScaleSettingView.setValue(values.getScalePercent());
        }
        if (mFrameRateSettingView != null && !mTrackingSliders.contains(mFrameRateSettingView)) {
            mFrameRateSettingView.setValue(values.getFrameRate());
        }
        if (mBitrateSettingView != null && !mTrackingSliders.contains(mBitrateSettingView)) {
            mBitrateSettingView.setValue(values.getBitrateMbps());
        }
        if (mIdrIntervalSettingView != null
            && !mTrackingSliders.contains(mIdrIntervalSettingView)) {
            mIdrIntervalSettingView.setValue(values.getIdrIntervalSeconds());
        }
        if (mCodecToggleGroup != null) {
            final int checkedId = values.isH264()
                ? R.id.codec_h264_option
                : R.id.codec_jpeg_option;
            // check() notifies the listener, which writes the preference back only when the value
            // really differs, so a refresh loop cannot form.
            if (mCodecToggleGroup.getCheckedButtonId() != checkedId) {
                mCodecToggleGroup.check(checkedId);
            }
        }
    }

    /**
     * Returns the lowest capture percentage the current display and encoder can use.
     *
     * <p>The encoder minimum comes from {@link EncoderSizeLimitProbe} and falls back to the generic
     * floor inside {@link ProjectionScalePolicy} when the platform does not report it. The
     * reference bounds are the maximum window bounds the service derives the capture size from, so
     * the sheet cannot offer a percentage that the service would turn into a size the encoder
     * refuses. The bounds are read again on every call because a rotation changes them.</p>
     */
    private int getMinimumProjectionScalePercent() {
        final Size minimumSize = EncoderSizeLimitProbe.probeMinimumSize(H264Codec.MIME);
        final Rect bounds = WindowUtils.getMaximumWindowBounds(this);
        final int minimumPercent = ProjectionScalePolicy.getMinimumPercent(
            minimumSize == null ? 0 : minimumSize.getWidth(),
            minimumSize == null ? 0 : minimumSize.getHeight(),
            bounds.width(), bounds.height(), ProjectionControlValues.SCALE_STEP_PERCENT);
        Log.i(mTag, "getMinimumProjectionScalePercent: " + minimumPercent + "% (encoder minimum "
            + (minimumSize == null
                ? "unknown"
                : minimumSize.getWidth() + "x" + minimumSize.getHeight())
            + ", window " + bounds.width() + 'x' + bounds.height() + ')');
        return minimumPercent;
    }

    /**
     * Returns the validated projection settings snapshot read from the preferences.
     */
    @NonNull
    private ProjectionSettings currentProjectionSettings() {
        return ProjectionSettings.of(Preferences.get().getProjectionBitrate(),
            Preferences.get().getProjectionFrameRate(), Preferences.get().getProjectionIdrIntervalMs(),
            Preferences.get().getProjectionScale(), Preferences.get().getProjectionQuality(),
            Preferences.get().getProjectionCodec());
    }

    /**
     * Renders the current codec, frame rate and bitrate next to the video settings entry row.
     */
    private void updateVideoSettingsSummary() {
        if (mVideoSettingsSummaryView == null) {
            return;
        }
        final ProjectionSettings settings = currentProjectionSettings();
        mVideoSettingsSummaryView.setText(getString(R.string.video_settings_summary,
            getString(settings.isH264()
                ? R.string.projection_codec_value_h264
                : R.string.projection_codec_value_jpeg),
            settings.getFrameRate(),
            getString(R.string.projection_bitrate_value_label,
                formatNumber(settings.getBitrateBps() / 1_000_000d, 1))));
    }

    /**
     * Rewrites the note under the authorization switch.
     *
     * <p>With the feature off the note states the risk plainly instead of describing the feature:
     * the switch is the only thing standing between the network and the screen, so turning it off
     * has to read as a decision, not as a preference.</p>
     */
    private void updateAuthorizationSettingNote() {
        if (mAuthorizationSettingNoteView == null) {
            return;
        }
        mAuthorizationSettingNoteView.setText(Preferences.get().isAuthorizationEnabled()
            ? R.string.authorization_setting_note
            : R.string.authorization_setting_note_off);
    }

    /**
     * Renders how many devices may watch without asking.
     */
    private void updateAuthorizationDevicesSummary() {
        if (mAuthorizationDevicesSummaryView == null) {
            return;
        }
        mAuthorizationDevicesSummaryView.setText(
            getString(R.string.authorization_devices_summary,
                AuthorizationStore.get().entries().size()));
    }

    /**
     * Shows the three answers to one waiting viewing request.
     *
     * <p>The service posts the request from its gate; the same answers are on the approval
     * notification, so a request is actionable whether the activity is in front or the phone is
     * locked. Answering sends one message back; the service owns the gate and the notification.</p>
     */
    private void onAuthorizationRequested(@NonNull final Bundle data) {
        final long requestId = data.getLong(ServiceProtocol.EXTRA_AUTHORIZATION_REQUEST_ID, -1L);
        if (requestId < 0L) {
            return;
        }
        final String name = data.getString(ServiceProtocol.EXTRA_AUTHORIZATION_DEVICE_NAME,
            getString(R.string.authorization_unknown_device));
        final String deviceId = data.getString(ServiceProtocol.EXTRA_AUTHORIZATION_DEVICE_ID, "");
        final String fingerprint = data.getString(
            ServiceProtocol.EXTRA_AUTHORIZATION_FINGERPRINT, "");

        if (mAuthorizationDialog != null) {
            mAuthorizationDialog.dismiss();
            mAuthorizationDialog = null;
        }
        mAuthorizationRequestId = requestId;
        mAuthorizationDialog = new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.authorization_dialog_title)
            .setMessage(HtmlCompat.fromHtml(getString(R.string.authorization_dialog_message,
                name, deviceId, fingerprint), HtmlCompat.FROM_HTML_MODE_LEGACY))
            .setPositiveButton(R.string.authorization_action_allow_once,
                (dialog, which) -> sendAuthorizationDecision(requestId, ApprovalOutcome.ALLOW_ONCE))
            .setNeutralButton(R.string.authorization_action_allow_always,
                (dialog, which) -> sendAuthorizationDecision(requestId, ApprovalOutcome.ALLOW_ALWAYS))
            .setNegativeButton(R.string.authorization_action_deny,
                (dialog, which) -> sendAuthorizationDecision(requestId, ApprovalOutcome.DENY))
            .setCancelable(false)
            .show();
    }

    /**
     * Drops the request dialog once its request was answered, expired or cancelled.
     */
    private void onAuthorizationFinished(final int requestId) {
        if (mAuthorizationDialog != null && mAuthorizationRequestId == requestId) {
            mAuthorizationDialog.dismiss();
            mAuthorizationDialog = null;
            mAuthorizationRequestId = 0L;
        }
        // "Always allow" writes the store from the service, so the count may have changed.
        updateAuthorizationDevicesSummary();
    }

    /**
     * Sends one answer to the service that owns the approval gate.
     */
    private void sendAuthorizationDecision(final long requestId,
                                          @NonNull final ApprovalOutcome outcome) {
        if (mAuthorizationDialog != null) {
            mAuthorizationDialog.dismiss();
            mAuthorizationDialog = null;
        }
        mAuthorizationRequestId = 0L;
        if (mServiceMessenger == null) {
            // Without a binding the notification buttons are still available; nothing is decided
            // here so the request is not lost.
            Log.w(mTag, "sendAuthorizationDecision: service is not bound");
            return;
        }
        final Message message = Message.obtain(null, ServiceProtocol.MSG_AUTHORIZATION_DECISION);
        message.arg1 = (int) requestId;
        message.arg2 = outcome.ordinal();
        try {
            mServiceMessenger.send(message);
        } catch (RemoteException e) {
            Log.w(mTag, "sendAuthorizationDecision: send failed", e);
        }
    }

    /**
     * Lists the devices that may watch without asking and revokes them one at a time.
     *
     * <p>A revocation is immediate: the running stream of that device ends on its next drain, which
     * is a few milliseconds away, because the gate is consulted there and not on reconnect only.</p>
     */
    private void showAuthorizedDevicesDialog() {
        if (mAuthorizedDevicesDialog != null) {
            mAuthorizedDevicesDialog.dismiss();
            mAuthorizedDevicesDialog = null;
        }
        final View content = getLayoutInflater().inflate(R.layout.dialog_authorized_devices, null);
        final LinearLayout container = content.findViewById(R.id.authorized_devices_container);
        final View emptyView = content.findViewById(R.id.authorized_devices_empty);
        final List<AuthorizationEntry> entries = AuthorizationStore.get().entries();
        emptyView.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);

        for (final AuthorizationEntry entry : entries) {
            final View row = getLayoutInflater().inflate(R.layout.item_authorized_device, container,
                false);
            final MaterialTextView nameView = row.findViewById(R.id.authorized_device_name);
            final MaterialTextView detailsView = row.findViewById(R.id.authorized_device_details);
            final String name = entry.getDeviceName().isEmpty()
                ? getString(R.string.authorization_unknown_device) : entry.getDeviceName();
            nameView.setText(name);
            detailsView.setText(getString(R.string.authorization_device_details,
                entry.getDeviceId().substring(0,
                    Math.min(DeviceIdentity.SHORT_ID_LENGTH, entry.getDeviceId().length())),
                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                    .format(new Date(entry.getGrantedAtMs()))));
            row.findViewById(R.id.authorized_device_revoke).setOnClickListener(
                view -> confirmRevokeAuthorization(entry, name));
            container.addView(row);
        }

        mAuthorizedDevicesDialog = new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.authorization_devices_title)
            .setView(content)
            .setPositiveButton(android.R.string.ok, null)
            .create();
        mAuthorizedDevicesDialog.show();
    }

    /**
     * Confirms and applies one revocation, then rebuilds the list so the row disappears at once.
     */
    private void confirmRevokeAuthorization(@NonNull final AuthorizationEntry entry,
                                            @NonNull final String name) {
        new MaterialAlertDialogBuilder(this)
            .setMessage(getString(R.string.authorization_revoke_confirm, name))
            .setPositiveButton(R.string.authorization_revoke_action, (dialog, which) -> {
                AuthorizationStore.get().revoke(entry.getDeviceId());
                Toast.makeText(this, getString(R.string.authorization_revoked_toast, name),
                    Toast.LENGTH_LONG).show();
                updateAuthorizationDevicesSummary();
                showAuthorizedDevicesDialog();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /**
     * Formats {@code value} with at most {@code maxFractionDigits} fraction digits.
     */
    @NonNull
    private static String formatNumber(final double value, final int maxFractionDigits) {
        final NumberFormat format = NumberFormat.getNumberInstance();
        format.setMaximumFractionDigits(maxFractionDigits);
        return format.format(value);
    }

    /**
     * Converts a slider value in megabits per second to bits per second.
     */
    private static int toBitrateBps(final float megabitsPerSecond) {
        return Math.round(megabitsPerSecond * 1_000_000f);
    }

    /**
     * Converts a slider value in seconds to a key-frame interval in milliseconds.
     */
    private static int toIdrIntervalMs(final float seconds) {
        return Math.round(seconds * 1000f);
    }

    /**
     * Validates and persists the multicast group typed by the user.
     *
     * <p>An invalid literal shows an inline error and leaves the stored value untouched, so a
     * half-typed address never replaces the group the channel is using.</p>
     */
    private void commitMulticastGroup(@NonNull final EditText view) {
        final String group = view.getText().toString().trim();
        if (!DiscoverySettings.isValidGroupAddress(group)) {
            view.setError(getString(R.string.discovery_invalid_multicast_group));
            return;
        }
        view.setError(null);
        if (!group.equals(Preferences.get().getMulticastGroup())) {
            Preferences.get().setMulticastGroup(group);
        }
    }

    /**
     * Persists a visibility preset, anchoring the temporary window at this moment.
     *
     * <p>The expiry is written together with the preset, so a restart of the app restores the same
     * remaining window.</p>
     */
    private void applyVisibility(@NonNull final VisibilityPolicy.Visibility visibility) {
        final VisibilityPolicy policy = VisibilityPolicy.forVisibility(visibility,
            System.currentTimeMillis());
        Preferences.get().setDiscoveryVisibility(visibility.wire(), policy.getExpiresAtMs());
    }

    /**
     * List adapter for discovered devices.
     *
     * <p>View types come from {@link DiscoveryListModel.RowType}: a section header, a device or the
     * empty hint. A device's item identity is its store id, so an entry that gains an address or a
     * new presence is rebound instead of being recreated; content equality is the value equality of
     * the immutable device description.</p>
     */
    private static class DiscoveryListAdapter
        extends ListAdapter<DiscoveryListModel.Row, RecyclerView.ViewHolder> {

        private DiscoveryListAdapter() {
            super(new DiffUtil.ItemCallback<>() {
                @Override
                public boolean areItemsTheSame(@NonNull DiscoveryListModel.Row oldItem,
                    @NonNull DiscoveryListModel.Row newItem) {
                    if (oldItem.getType() != newItem.getType()) {
                        return false;
                    }
                    final DeviceInfo oldDevice = oldItem.getDevice();
                    final DeviceInfo newDevice = newItem.getDevice();
                    if (oldDevice == null || newDevice == null) {
                        return true;
                    }
                    return oldDevice.getId().equals(newDevice.getId());
                }

                @Override
                public boolean areContentsTheSame(@NonNull DiscoveryListModel.Row oldItem,
                    @NonNull DiscoveryListModel.Row newItem) {
                    return oldItem.equals(newItem);
                }
            });
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            final LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            if (viewType == ViewType.HEADER.ordinal()) {
                return new DiscoveryHeaderViewHolder(
                    inflater.inflate(R.layout.item_screen_share_discovery_header, parent, false));
            }
            return new DiscoveryListViewHolder(
                inflater.inflate(R.layout.item_screen_share_discovery, parent, false));
        }

        @Override
        public int getItemViewType(final int position) {
            switch (getItem(position).getType()) {
                case HEADER_SERVERS:
                case HEADER_CLIENTS:
                    return ViewType.HEADER.ordinal();
                case EMPTY_HINT:
                case DEVICE:
                default:
                    return ViewType.DEVICE.ordinal();
            }
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            final DiscoveryListModel.Row row = getItem(position);
            if (holder instanceof DiscoveryHeaderViewHolder) {
                ((DiscoveryHeaderViewHolder) holder).update(row.getType());
                return;
            }
            final DeviceInfo device = row.getDevice();
            if (device != null) {
                ((DiscoveryListViewHolder) holder).update(device);
            }
        }
    }

    // Two layouts only: a section header and a device row.
    private enum ViewType {
        HEADER,
        DEVICE
    }

    private static class DiscoveryHeaderViewHolder extends RecyclerView.ViewHolder {
        @NonNull
        private final MaterialTextView mHeaderView;

        private DiscoveryHeaderViewHolder(@NonNull final View itemView) {
            super(itemView);
            mHeaderView = itemView.findViewById(R.id.header);
        }

        public void update(@NonNull final DiscoveryListModel.RowType type) {
            mHeaderView.setText(type == DiscoveryListModel.RowType.HEADER_CLIENTS
                ? R.string.discovery_section_clients : R.string.discovery_section_servers);
        }
    }

    private static class DiscoveryListViewHolder extends RecyclerView.ViewHolder {
        @NonNull
        private final MaterialTextView mNameView;

        @NonNull
        private final MaterialTextView mAddressView;

        @NonNull
        private final MaterialTextView mStatusView;

        @NonNull
        private final MaterialTextView mFavoriteView;

        private DiscoveryListViewHolder(@NonNull View itemView) {
            super(itemView);
            mNameView = itemView.findViewById(R.id.name);
            mAddressView = itemView.findViewById(R.id.address);
            mStatusView = itemView.findViewById(R.id.status);
            mFavoriteView = itemView.findViewById(R.id.favorite);
        }

        public void update(@NonNull final DeviceInfo item) {
            mNameView.setText(item.displayName());
            final DeviceAddress address = item.primaryAddress();
            final String addressText = address == null ? ""
                : mAddressView.getResources().getString(R.string.service_address,
                    address.getHost(), address.getPort());
            mAddressView.setText(item.isSecure() ? addressText
                : addressText + " · " + mAddressView.getResources().getString(
                    R.string.tls_device_not_encrypted));

            mStatusView.setText(statusTextResource(DiscoveryListModel.status(item)));
            // A stale or unreachable device stays visible but is drawn dimmed, which distinguishes
            // "was here, not answering" from "answering now".
            itemView.setAlpha(DiscoveryListModel.isDimmed(item) ? 0.5f : 1f);

            if (!item.isFavorite()) {
                mFavoriteView.setVisibility(View.GONE);
            } else {
                mFavoriteView.setText(item.isIdentityChanged()
                    ? R.string.discovery_identity_changed_badge : R.string.discovery_favorite_badge);
                mFavoriteView.setVisibility(View.VISIBLE);
            }
        }

        private static int statusTextResource(@NonNull final DiscoveryListModel.DeviceStatus status) {
            switch (status) {
                case LIVE:
                    return R.string.discovery_status_live;
                case NEEDS_APPROVAL:
                    return R.string.discovery_status_needs_approval;
                case CLOSED:
                    return R.string.discovery_status_closed;
                case UNREACHABLE:
                    return R.string.discovery_status_unreachable;
                case IDLE:
                default:
                    return R.string.discovery_status_idle;
            }
        }
    }
}
