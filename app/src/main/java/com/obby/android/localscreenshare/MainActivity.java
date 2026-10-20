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
import android.text.InputType;
import android.text.format.Formatter;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
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
import com.obby.android.localscreenshare.discovery.DeviceListModel;
import com.obby.android.localscreenshare.discovery.DiscoverySettings;
import com.obby.android.localscreenshare.discovery.LssServiceDiscoveryManager;
import com.obby.android.localscreenshare.discovery.VisibilityPolicy;
import com.obby.android.localscreenshare.discovery.favorite.FavoriteDevice;
import com.obby.android.localscreenshare.discovery.favorite.FavoriteStore;
import com.obby.android.localscreenshare.discovery.manual.ManualAddressProvider;
import com.obby.android.localscreenshare.discovery.model.DeviceAddress;
import com.obby.android.localscreenshare.discovery.model.LssDeviceInfo;
import com.obby.android.localscreenshare.discovery.store.DeviceStoreListener;
import com.obby.android.localscreenshare.grpc.screenstream.DeviceSummary;
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
import java.util.Collections;
import java.util.List;
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

    private MaterialButton mDiscoveryManualAddView;

    // True once the empty list has been empty long enough to offer the manual entry.
    private boolean mShowManualDiscoveryHint;

    @NonNull
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    // Device id row inside the settings sheet; null until the sheet is created.
    @Nullable
    private MaterialTextView mDeviceIdSettingView;

    private final String mTag = "MainActivity@" + hashCode();

    // Latest store snapshot; the adapter renders it through DeviceListModel.
    @NonNull
    private List<LssDeviceInfo> mDevices = Collections.emptyList();

    // Discovery rows; a click starts the client service for that device.
    @NonNull
    private final DiscoveryListAdapter mDiscoveryListAdapter = new DiscoveryListAdapter() {
        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            super.onBindViewHolder(holder, position);
            final DeviceListModel.Row row = getItem(position);
            final LssDeviceInfo item = row.getDevice();
            if (row.getType() != DeviceListModel.RowType.DEVICE || item == null) {
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
        public void submitList(@Nullable List<DeviceListModel.Row> list) {
            submitList(list, null);
        }

        @Override
        public void submitList(@Nullable List<DeviceListModel.Row> list, @Nullable Runnable commitCallback) {
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
            ? Collections.emptyList() : DeviceListModel.rows(devices));
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
                LssServiceDiscoveryManager.get().addDeviceListener(mDeviceListener);
                mMainHandler.removeCallbacks(mDiscoveryHintRunnable);
                mMainHandler.postDelayed(mDiscoveryHintRunnable,
                    Constants.DISCOVERY_EMPTY_MANUAL_HINT_MS);
            } else {
                LssServiceDiscoveryManager.get().removeDeviceListener(mDeviceListener);
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
        bindService(new Intent(this, LssService.class), mServiceConnection, Context.BIND_AUTO_CREATE);

        if (Preferences.get().isDiscoveryEnabled()) {
            LssServiceDiscoveryManager.get().addDeviceListener(mDeviceListener);
            mMainHandler.removeCallbacks(mDiscoveryHintRunnable);
            mMainHandler.postDelayed(mDiscoveryHintRunnable,
                Constants.DISCOVERY_EMPTY_MANUAL_HINT_MS);
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

        LssServiceDiscoveryManager.get().removeDeviceListener(mDeviceListener);
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
        final DeviceListModel.EmptyState state = DeviceListModel.emptyState(
            Preferences.get().isDiscoveryEnabled(), mShowManualDiscoveryHint);
        if (state == DeviceListModel.EmptyState.DISABLED) {
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

        final boolean offerManualEntry = state == DeviceListModel.EmptyState.NOT_FOUND;
        mDiscoveryListPlaceholderView.setText(offerManualEntry
            ? R.string.discovery_empty_manual_hint : R.string.searching_services);
        mDiscoveryListPlaceholderView.setClickable(offerManualEntry);
        mDiscoveryListPlaceholderView.setVisibility(View.VISIBLE);
        mDiscoveryListView.setVisibility(View.GONE);
    }

    /**
     * Offers the favorite action of one discovered device.
     *
     * <p>A long press opens this menu; the action label reflects the current state. The favorite
     * flag is already part of {@link LssDeviceInfo}, so the store snapshot refresh after the change
     * updates the badge without a manual rebind.</p>
     */
    private void showDiscoveryItemMenu(@NonNull final LssDeviceInfo item) {
        final boolean isFavorite = LssServiceDiscoveryManager.get().isFavorite(item.getId());
        new MaterialAlertDialogBuilder(this)
            .setTitle(item.displayName())
            .setPositiveButton(isFavorite
                    ? R.string.discovery_remove_favorite : R.string.discovery_add_favorite,
                (dialog, which) -> toggleFavorite(item))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void toggleFavorite(@NonNull final LssDeviceInfo item) {
        final boolean isFavorite = LssServiceDiscoveryManager.get().isFavorite(item.getId());
        LssServiceDiscoveryManager.get().setFavorite(item.getId(), !isFavorite);
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
        final boolean accepted = LssServiceDiscoveryManager.get().probeManualAddress(input,
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
                    Toast.makeText(MainActivity.this, R.string.discovery_manual_failed,
                        Toast.LENGTH_SHORT).show();
                }
            });
        if (accepted) {
            Toast.makeText(this, R.string.discovery_manual_adding, Toast.LENGTH_SHORT).show();
        }
        return accepted;
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
     *
     * <p>The device is passed whole: the client service uses its primary address, so the address
     * choice (confirmed first, IPv4 before IPv6) is made once, in the store.</p>
     */
    private void startScreenShareViewer(@NonNull final LssDeviceInfo serviceInfo) {
        final FavoriteDevice favorite = FavoriteStore.get().find(serviceInfo.getId());
        if (favorite != null && FavoriteStore.checkIdentity(favorite.getFingerprint(),
            serviceInfo.getFingerprint()) == FavoriteStore.IdentityStatus.CHANGED) {
            // Never dial a saved identity that now presents a different fingerprint without an
            // explicit acknowledgement; accepting rebinds the favorite to the new key.
            new MaterialAlertDialogBuilder(this)
                .setTitle(serviceInfo.displayName())
                .setMessage(R.string.discovery_identity_changed_connect)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    LssServiceDiscoveryManager.get().confirmFavoriteIdentity(
                        serviceInfo.getId(), serviceInfo.getFingerprint());
                    startScreenShareViewerConfirmed(serviceInfo);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
            return;
        }
        startScreenShareViewerConfirmed(serviceInfo);
    }

    private void startScreenShareViewerConfirmed(@NonNull final LssDeviceInfo serviceInfo) {
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
            qualitySettingView.setValue(Preferences.get().getProjectionQuality());
            scaleSettingView.setValue(Preferences.get().getProjectionScale());
            frameRateSettingView.setValue(Preferences.get().getProjectionFrameRate());
            keepScreenOnSettingView.setChecked(Preferences.get().isProjectionKeepScreenOn());
            keepScreenOnSettingView.jumpDrawablesToCurrentState();
            secureSettingView.setChecked(Preferences.get().isProjectionSecure());
            secureSettingView.jumpDrawablesToCurrentState();
            portSettingView.setValue(Preferences.get().getServerPort());
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
     * <p>View types come from {@link DeviceListModel.RowType}: a section header, a device or the
     * empty hint. A device's item identity is its store id, so an entry that gains an address or a
     * new presence is rebound instead of being recreated; content equality is the value equality of
     * the immutable device description.</p>
     */
    private static class DiscoveryListAdapter
        extends ListAdapter<DeviceListModel.Row, RecyclerView.ViewHolder> {

        private DiscoveryListAdapter() {
            super(new DiffUtil.ItemCallback<>() {
                @Override
                public boolean areItemsTheSame(@NonNull DeviceListModel.Row oldItem,
                    @NonNull DeviceListModel.Row newItem) {
                    if (oldItem.getType() != newItem.getType()) {
                        return false;
                    }
                    final LssDeviceInfo oldDevice = oldItem.getDevice();
                    final LssDeviceInfo newDevice = newItem.getDevice();
                    if (oldDevice == null || newDevice == null) {
                        return true;
                    }
                    return oldDevice.getId().equals(newDevice.getId());
                }

                @Override
                public boolean areContentsTheSame(@NonNull DeviceListModel.Row oldItem,
                    @NonNull DeviceListModel.Row newItem) {
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
            final DeviceListModel.Row row = getItem(position);
            if (holder instanceof DiscoveryHeaderViewHolder) {
                ((DiscoveryHeaderViewHolder) holder).update(row.getType());
                return;
            }
            final LssDeviceInfo device = row.getDevice();
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

        public void update(@NonNull final DeviceListModel.RowType type) {
            mHeaderView.setText(type == DeviceListModel.RowType.HEADER_CLIENTS
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

        public void update(@NonNull final LssDeviceInfo item) {
            mNameView.setText(item.displayName());
            final DeviceAddress address = item.primaryAddress();
            mAddressView.setText(address == null ? ""
                : mAddressView.getResources().getString(R.string.service_address,
                    address.getHost(), address.getPort()));

            mStatusView.setText(statusTextResource(DeviceListModel.status(item)));
            // A stale or unreachable device stays visible but is drawn dimmed, which distinguishes
            // "was here, not answering" from "answering now".
            itemView.setAlpha(DeviceListModel.isDimmed(item) ? 0.5f : 1f);

            if (!item.isFavorite()) {
                mFavoriteView.setVisibility(View.GONE);
            } else {
                mFavoriteView.setText(item.isIdentityChanged()
                    ? R.string.discovery_identity_changed_badge : R.string.discovery_favorite_badge);
                mFavoriteView.setVisibility(View.VISIBLE);
            }
        }

        private static int statusTextResource(@NonNull final DeviceListModel.DeviceStatus status) {
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
