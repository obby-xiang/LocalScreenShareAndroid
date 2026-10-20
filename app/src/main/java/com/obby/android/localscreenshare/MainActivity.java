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
import android.text.format.Formatter;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.NumberPicker;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
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
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.divider.MaterialDividerItemDecoration;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.LabelFormatter;
import com.google.android.material.slider.Slider;
import com.google.android.material.textview.MaterialTextView;
import com.obby.android.localscreenshare.discovery.LssServiceDiscoveryListener;
import com.obby.android.localscreenshare.discovery.LssServiceDiscoveryManager;
import com.obby.android.localscreenshare.discovery.LssServiceInfo;
import com.obby.android.localscreenshare.identity.IdentityManager;
import com.obby.android.localscreenshare.identity.IdentityUnavailableException;
import com.obby.android.localscreenshare.server.LssServerInfo;
import com.obby.android.localscreenshare.server.LssServerStats;
import com.obby.android.localscreenshare.service.LssClientService;
import com.obby.android.localscreenshare.service.LssService;
import com.obby.android.localscreenshare.support.Constants;
import com.obby.android.localscreenshare.support.Preferences;
import com.obby.android.localscreenshare.utils.IntentUtils;

import java.text.NumberFormat;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.IntStream;

/**
 * Single-activity UI: starts and stops the screen-share service and lists discovered servers.
 *
 * <p><b>Lifecycle.</b> {@link #onCreate(Bundle)} installs the splash screen, inflates the layout,
 * wires the toolbars and the discovery list and renders the initial state. {@link #onStart()}
 * binds to {@link LssService} with {@code BIND_AUTO_CREATE} and starts discovery when it is
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
 * {@link LssService} in the foreground with the consent result as an extra. Viewing a discovered
 * server follows the same gate and starts {@link LssClientService} with the service info.</p>
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

    private static final int MIN_PROJECTION_QUALITY = 0;

    private static final int MAX_PROJECTION_QUALITY = 100;

    private static final int PROJECTION_QUALITY_STEP = 1;

    private static final int MIN_PROJECTION_SCALE = 5;

    private static final int MAX_PROJECTION_SCALE = 100;

    private static final int PROJECTION_SCALE_STEP = 1;

    private static final int MIN_PROJECTION_FRAME_RATE = 1;

    private static final int MAX_PROJECTION_FRAME_RATE = 120;

    private static final int PROJECTION_FRAME_RATE_STEP = 1;

    private static final int MIN_SERVER_PORT = 1024;

    private static final int MAX_SERVER_PORT = 65535;

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
    private LssServerInfo mServerInfo;

    @Nullable
    private LssServerStats mServerStats;

    // Last confirmation or permission dialog shown; dismissed when replaced and in onDestroy().
    @Nullable
    private AlertDialog mDialog;

    // Created on first use and reused while the activity lives.
    @Nullable
    private BottomSheetDialog mServiceSettingsDialog;

    private MaterialToolbar mServiceToolbarView;

    private MaterialTextView mServiceStatusView;

    private MaterialTextView mServiceNameView;

    private MaterialTextView mServiceAddressView;

    private MaterialButton mServiceStatsView;

    private MaterialToolbar mDiscoveryToolbarView;

    private MaterialTextView mDiscoveryListPlaceholderView;

    private RecyclerView mDiscoveryListView;

    // Device id row inside the settings sheet; null until the sheet is created.
    @Nullable
    private MaterialTextView mDeviceIdSettingView;

    private final String mTag = "MainActivity@" + hashCode();

    // Discovery entries; a click starts the client service for that server.
    @NonNull
    private final DiscoveryListAdapter mDiscoveryListAdapter = new DiscoveryListAdapter() {
        @Override
        public void onBindViewHolder(@NonNull DiscoveryListViewHolder holder, int position) {
            super.onBindViewHolder(holder, position);
            holder.itemView.setOnClickListener(v -> startScreenShareViewer(getItem(position)));
        }

        @Override
        public void submitList(@Nullable List<LssServiceInfo> list) {
            submitList(list, null);
        }

        @Override
        public void submitList(@Nullable List<LssServiceInfo> list, @Nullable Runnable commitCallback) {
            // The commit callback runs after the diff is applied; the placeholder mirrors the
            // committed list rather than the pending one, and only while discovery is enabled.
            super.submitList(list, () -> {
                if (Preferences.get().isDiscoveryEnabled()) {
                    if (getCurrentList().isEmpty()) {
                        mDiscoveryListPlaceholderView.setText(R.string.searching_services);
                        mDiscoveryListPlaceholderView.setVisibility(View.VISIBLE);
                        mDiscoveryListView.setVisibility(View.GONE);
                    } else {
                        mDiscoveryListPlaceholderView.setVisibility(View.GONE);
                        mDiscoveryListView.setVisibility(View.VISIBLE);
                    }
                }
                Optional.ofNullable(commitCallback).ifPresent(Runnable::run);
            });
        }
    };

    // Receives server state from the bound service; the handler is bound to the main looper.
    @NonNull
    private final Messenger mMessenger = new Messenger(new Handler(Looper.getMainLooper(), msg -> {
        switch (msg.what) {
            case Constants.MSG_SERVER_STARTED:
                onServerStarted();
                return true;
            case Constants.MSG_SERVER_STOPPED:
                onServerStopped();
                return true;
            case Constants.MSG_SERVER_INFO_CHANGED:
                onServerInfoChanged((LssServerInfo) msg.obj);
                return true;
            case Constants.MSG_SERVER_STATS_CHANGED:
                onServerStatsChanged((LssServerStats) msg.obj);
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
                    .setMessage(HtmlCompat.fromHtml(getString(R.string.request_post_notification, App.getLabel()),
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
                ContextCompat.startForegroundService(this, new Intent(this, LssService.class)
                    .putExtra(Constants.EXTRA_MEDIA_PROJECTION_RESULT, result));
            }
        }
    );

    // Discovery snapshots are delivered on the main thread and replace the list wholesale.
    @NonNull
    private final LssServiceDiscoveryListener mServiceDiscoveryListener = mDiscoveryListAdapter::submitList;

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

        mDiscoveryListView.setAdapter(mDiscoveryListAdapter);
        mDiscoveryListView.setItemAnimator(null);

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
                LssServiceDiscoveryManager.get().discoverServices(mServiceDiscoveryListener);
            } else {
                LssServiceDiscoveryManager.get().stopServiceDiscovery(mServiceDiscoveryListener);
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
        bindService(new Intent(this, LssService.class), mServiceConnection, Context.BIND_AUTO_CREATE);

        if (Preferences.get().isDiscoveryEnabled()) {
            LssServiceDiscoveryManager.get().discoverServices(mServiceDiscoveryListener);
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

        LssServiceDiscoveryManager.get().stopServiceDiscovery(mServiceDiscoveryListener);
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
    }

    // Server events forwarded by LssService through mMessenger.
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

    private void onServerInfoChanged(@NonNull final LssServerInfo serverInfo) {
        mServerInfo = serverInfo;
        updateServiceInfoView();
    }

    private void onServerStatsChanged(@NonNull final LssServerStats serverStats) {
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
            .map(LssServerInfo::getName)
            .orElseGet(() -> Preferences.get().getServiceName()));
        mServiceNameView.setEnabled(mServiceStatus == SERVICE_STATUS_ONLINE);

        mServiceAddressView.setText(getString(R.string.service_address,
            mServerInfo == null ? Constants.UNKNOWN_HOST_ADDRESS : mServerInfo.getHostAddress(),
            mServerInfo == null ? Preferences.get().getServerPort() : mServerInfo.getPort()));
        mServiceAddressView.setEnabled(mServiceStatus == SERVICE_STATUS_ONLINE);
    }

    @SuppressLint("StringFormatMatches")
    private void updateServiceStatsView() {
        mServiceStatsView.setText(getString(R.string.service_stats,
            mServerStats == null ? 0 : mServerStats.getTransports().size(),
            Formatter.formatFileSize(this, mServerStats == null ? 0L : mServerStats.getOutboundDataRate())));
        mServiceStatsView.setEnabled(mServiceStatus == SERVICE_STATUS_ONLINE);
    }

    // Mirrors the persisted discovery preference; disabling also clears the list.
    private void updateDiscoveryView() {
        final MenuItem searchMenuItem = mDiscoveryToolbarView.getMenu().findItem(R.id.search);
        if (Preferences.get().isDiscoveryEnabled()) {
            searchMenuItem.setIcon(R.drawable.ic_stop_search);
            if (mDiscoveryListAdapter.getCurrentList().isEmpty()) {
                mDiscoveryListPlaceholderView.setText(R.string.searching_services);
                mDiscoveryListPlaceholderView.setVisibility(View.VISIBLE);
                mDiscoveryListView.setVisibility(View.GONE);
            } else {
                mDiscoveryListPlaceholderView.setVisibility(View.GONE);
                mDiscoveryListView.setVisibility(View.VISIBLE);
            }
        } else {
            searchMenuItem.setIcon(R.drawable.ic_start_search);
            mDiscoveryListAdapter.submitList(null);
            mDiscoveryListPlaceholderView.setText(R.string.search_services_off);
            mDiscoveryListPlaceholderView.setVisibility(View.VISIBLE);
            mDiscoveryListView.setVisibility(View.GONE);
        }
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

    // Renders the current device id; the sheet is reused, so the text is refreshed on every show.
    private void updateDeviceIdentityView() {
        if (mDeviceIdSettingView == null) {
            return;
        }

        try {
            mDeviceIdSettingView.setText(IdentityManager.get().getIdentity().getId());
        } catch (IdentityUnavailableException e) {
            Log.e(mTag, "updateDeviceIdentityView: device identity unavailable", e);
            mDeviceIdSettingView.setText(R.string.device_identity_unavailable);
        }
    }

    /**
     * Asks for confirmation and then replaces the identity key.
     *
     * <p>Every peer that stored this device keeps the old fingerprint, so the discovered list is
     * cleared and the user is told that favorites must be confirmed again. A running share keeps
     * advertising the identity it started with until it is restarted.</p>
     */
    private void resetDeviceIdentity() {
        if (mDialog != null) {
            mDialog.dismiss();
        }

        mDialog = new MaterialAlertDialogBuilder(this)
            .setMessage(R.string.reset_device_identity_confirm)
            .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                try {
                    IdentityManager.get().reset();
                } catch (IdentityUnavailableException e) {
                    Log.e(mTag, "resetDeviceIdentity: device identity reset failed", e);
                    Toast.makeText(this, R.string.reset_device_identity_failed, Toast.LENGTH_LONG).show();
                    return;
                }

                LssServiceDiscoveryManager.get().onIdentityReset();
                sendBroadcast(new Intent(Constants.ACTION_IDENTITY_RESET).setPackage(getPackageName()));
                updateDeviceIdentityView();
                Toast.makeText(this, R.string.reset_device_identity_done, Toast.LENGTH_LONG).show();
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

        mDialog = new MaterialAlertDialogBuilder(this)
            .setMessage(R.string.stop_screen_share_confirm)
            .setPositiveButton(android.R.string.ok, (dialog, which) -> sendBroadcast(
                new Intent(Constants.ACTION_STOP_SERVICE).setPackage(getPackageName())))
            .setNegativeButton(android.R.string.cancel, null)
            .setCancelable(false)
            .create();
        mDialog.show();
    }

    /**
     * Passes the permission gate and starts the client service for the selected server.
     */
    private void startScreenShareViewer(@NonNull final LssServiceInfo serviceInfo) {
        if (requestScreenShare()) {
            ContextCompat.startForegroundService(this, new Intent(this, LssClientService.class)
                .putExtra(Constants.EXTRA_SERVICE_INFO, serviceInfo));
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
                .setMessage(HtmlCompat.fromHtml(getString(R.string.request_post_notification, App.getLabel()),
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
                .setMessage(HtmlCompat.fromHtml(getString(R.string.request_system_alert_window, App.getLabel()),
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

    /**
     * Builds the service settings sheet.
     *
     * <p>Every control writes its value to {@link Preferences} as soon as it changes, and the
     * running service applies it through its preference observer. The port is the exception: it
     * is read when {@code LssServer} is constructed, so a changed port applies to the next server
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

        final Slider qualitySettingView = settingsDialog.findViewById(R.id.quality_setting);
        qualitySettingView.setValue(MIN_PROJECTION_QUALITY);
        qualitySettingView.setValueFrom(MIN_PROJECTION_QUALITY);
        qualitySettingView.setValueTo(MAX_PROJECTION_QUALITY);
        qualitySettingView.setStepSize(PROJECTION_QUALITY_STEP);
        qualitySettingView.addOnChangeListener((slider, value, fromUser) -> {
            if (Preferences.get().getProjectionQuality() != (int) value) {
                Preferences.get().setProjectionQuality((int) value);
            }
        });

        final Slider scaleSettingView = settingsDialog.findViewById(R.id.scale_setting);
        scaleSettingView.setValue(MIN_PROJECTION_SCALE);
        scaleSettingView.setValueFrom(MIN_PROJECTION_SCALE);
        scaleSettingView.setValueTo(MAX_PROJECTION_SCALE);
        scaleSettingView.setStepSize(PROJECTION_SCALE_STEP);
        scaleSettingView.setLabelFormatter(new LabelFormatter() {
            @NonNull
            @Override
            public String getFormattedValue(float value) {
                return NumberFormat.getPercentInstance().format(value / 100f);
            }
        });
        scaleSettingView.addOnChangeListener((slider, value, fromUser) -> {
            if (Preferences.get().getProjectionScale() != (int) value) {
                Preferences.get().setProjectionScale((int) value);
            }
        });

        final Slider frameRateSettingView = settingsDialog.findViewById(R.id.frame_rate_setting);
        frameRateSettingView.setValue(MIN_PROJECTION_FRAME_RATE);
        frameRateSettingView.setValueFrom(MIN_PROJECTION_FRAME_RATE);
        frameRateSettingView.setValueTo(MAX_PROJECTION_FRAME_RATE);
        frameRateSettingView.setStepSize(PROJECTION_FRAME_RATE_STEP);
        frameRateSettingView.setLabelFormatter(new LabelFormatter() {
            @NonNull
            @Override
            public String getFormattedValue(float value) {
                return getString(R.string.frame_rate_label, (int) value);
            }
        });
        frameRateSettingView.addOnChangeListener((slider, value, fromUser) -> {
            if (Preferences.get().getProjectionFrameRate() != (int) value) {
                Preferences.get().setProjectionFrameRate((int) value);
            }
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
        final MaterialButton resetDeviceIdentityView =
            settingsDialog.findViewById(R.id.reset_device_identity_setting);
        resetDeviceIdentityView.setOnClickListener(view -> resetDeviceIdentity());

        settingsDialog.setOnShowListener(dialog -> {
            settingsDialog.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
            qualitySettingView.setValue(Preferences.get().getProjectionQuality());
            scaleSettingView.setValue(Preferences.get().getProjectionScale());
            frameRateSettingView.setValue(Preferences.get().getProjectionFrameRate());
            keepScreenOnSettingView.setChecked(Preferences.get().isProjectionKeepScreenOn());
            keepScreenOnSettingView.jumpDrawablesToCurrentState();
            secureSettingView.setChecked(Preferences.get().isProjectionSecure());
            secureSettingView.jumpDrawablesToCurrentState();
            portSettingView.setValue(Preferences.get().getServerPort());
            updateDeviceIdentityView();
        });

        return settingsDialog;
    }

    /**
     * List adapter for discovered servers.
     *
     * <p>Item identity is the stable service id, so a re-resolved entry with a new address is
     * rebound instead of replaced; content equality comes from the immutable info object.</p>
     */
    private static class DiscoveryListAdapter extends ListAdapter<LssServiceInfo, DiscoveryListViewHolder> {
        private DiscoveryListAdapter() {
            super(new DiffUtil.ItemCallback<>() {
                @Override
                public boolean areItemsTheSame(@NonNull LssServiceInfo oldItem, @NonNull LssServiceInfo newItem) {
                    return Objects.equals(oldItem.getId(), newItem.getId());
                }

                @Override
                public boolean areContentsTheSame(@NonNull LssServiceInfo oldItem, @NonNull LssServiceInfo newItem) {
                    return Objects.equals(oldItem, newItem);
                }
            });
        }

        @NonNull
        @Override
        public DiscoveryListViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new DiscoveryListViewHolder(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_screen_share_discovery, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull DiscoveryListViewHolder holder, int position) {
            holder.update(getItem(position));
        }
    }

    private static class DiscoveryListViewHolder extends RecyclerView.ViewHolder {
        @NonNull
        private final MaterialTextView mNameView;

        @NonNull
        private final MaterialTextView mAddressView;

        private DiscoveryListViewHolder(@NonNull View itemView) {
            super(itemView);
            mNameView = itemView.findViewById(R.id.name);
            mAddressView = itemView.findViewById(R.id.address);
        }

        public void update(@NonNull final LssServiceInfo item) {
            mNameView.setText(item.getName());
            mAddressView.setText(mAddressView.getResources().getString(R.string.service_address, item.getHostAddress(),
                item.getPort()));
        }
    }
}
