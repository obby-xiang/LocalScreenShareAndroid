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
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.PowerManager;
import android.os.RemoteException;
import android.provider.Settings;
import android.text.format.Formatter;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

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
import com.google.android.material.textview.MaterialTextView;
import com.obby.android.localscreenshare.discovery.LssServiceDiscoveryListener;
import com.obby.android.localscreenshare.discovery.LssServiceDiscoveryManager;
import com.obby.android.localscreenshare.discovery.LssServiceInfo;
import com.obby.android.localscreenshare.server.LssServerInfo;
import com.obby.android.localscreenshare.server.LssServerStats;
import com.obby.android.localscreenshare.service.LssClientService;
import com.obby.android.localscreenshare.service.LssService;
import com.obby.android.localscreenshare.support.Constants;
import com.obby.android.localscreenshare.support.Preferences;
import com.obby.android.localscreenshare.utils.IntentUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.IntStream;

public class MainActivity extends AppCompatActivity {
    private static final int SERVICE_STATUS_UNKNOWN = 0;

    private static final int SERVICE_STATUS_ONLINE = 1;

    private static final int SERVICE_STATUS_OFFLINE = 2;

    private boolean mIsServiceBound;

    @Nullable
    private Messenger mServiceMessenger;

    private int mServiceStatus = SERVICE_STATUS_UNKNOWN;

    @Nullable
    private LssServerInfo mServerInfo;

    @Nullable
    private LssServerStats mServerStats;

    @Nullable
    private AlertDialog mDialog;

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

    private final String mTag = "MainActivity@" + hashCode();

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
            mServiceStatus = SERVICE_STATUS_UNKNOWN;
            mServerInfo = null;
            mServerStats = null;
            updateServiceView();
        }
    };

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

    @NonNull
    private final LssServiceDiscoveryListener mServiceDiscoveryListener = mDiscoveryListAdapter::submitList;

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

        mServiceToolbarView.getMenu().findItem(R.id.settings).setOnMenuItemClickListener(item -> {
            if (mServiceSettingsDialog == null) {
                mServiceSettingsDialog = createServiceSettingsDialog();
            }
            mServiceSettingsDialog.show();
            return true;
        });
        mServiceToolbarView.getMenu().findItem(R.id.service).setOnMenuItemClickListener(item -> {
            if (mServiceStatus == SERVICE_STATUS_ONLINE) {
                stopScreenShareService();
            } else {
                startScreenShareService();
            }
            return true;
        });
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

        updateServiceView();
        updateDiscoveryView();
    }

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

    private void startScreenShareService() {
        if (requestScreenShare()) {
            final MediaProjectionManager mediaProjectionManager = getSystemService(MediaProjectionManager.class);
            mScreenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent());
        }
    }

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

    private void startScreenShareViewer(@NonNull final LssServiceInfo serviceInfo) {
        if (requestScreenShare()) {
            ContextCompat.startForegroundService(this, new Intent(this, LssClientService.class)
                .putExtra(Constants.EXTRA_SERVICE_INFO, serviceInfo));
        }
    }

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

        final PowerManager powerManager = getSystemService(PowerManager.class);
        if (!powerManager.isIgnoringBatteryOptimizations(getPackageName())) {
            final Intent intent = IntentUtils.createRequestIgnoreBatteryOptimizationsIntent(this);
            if (getPackageManager().resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null) {
                if (mDialog != null) {
                    mDialog.dismiss();
                }
                mDialog = new MaterialAlertDialogBuilder(this)
                    .setMessage(HtmlCompat.fromHtml(getString(R.string.request_ignore_battery_optimizations,
                        App.getLabel()), HtmlCompat.FROM_HTML_MODE_LEGACY))
                    .setPositiveButton(android.R.string.ok,
                        (dialog, which) -> startActivity(intent))
                    .setNegativeButton(android.R.string.cancel, null)
                    .setCancelable(false)
                    .show();
                return false;
            }
        }

        return true;
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

    @NonNull
    private BottomSheetDialog createServiceSettingsDialog() {
        final BottomSheetDialog settingsDialog = new BottomSheetDialog(this);
        settingsDialog.setContentView(R.layout.widget_screen_share_service_settings);
        settingsDialog.setDismissWithAnimation(true);
        settingsDialog.getBehavior().setSkipCollapsed(true);

        settingsDialog.setOnShowListener(
            dialog -> settingsDialog.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED));

        return settingsDialog;
    }

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
