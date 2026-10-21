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
import android.view.Surface;
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
import com.obby.android.localscreenshare.MainActivity;
import com.obby.android.localscreenshare.R;
import com.obby.android.localscreenshare.core.audio.AudioHub;
import com.obby.android.localscreenshare.core.codec.CodecIds;
import com.obby.android.localscreenshare.core.codec.CodecRegistry;
import com.obby.android.localscreenshare.core.codec.EncoderSizeAlignment;
import com.obby.android.localscreenshare.core.codec.h264.H264Codec;
import com.obby.android.localscreenshare.core.codec.mediacodec.EncoderAlignmentProbe;
import com.obby.android.localscreenshare.core.metrics.DeviceProfileSampler;
import com.obby.android.localscreenshare.core.metrics.MetricsFormatter;
import com.obby.android.localscreenshare.core.metrics.PipelineMetrics;
import com.obby.android.localscreenshare.core.metrics.RateSampler;
import com.obby.android.localscreenshare.core.model.BitstreamFormat;
import com.obby.android.localscreenshare.core.model.StreamDescriptor;
import com.obby.android.localscreenshare.core.model.StreamKey;
import com.obby.android.localscreenshare.core.model.StreamParameters;
import com.obby.android.localscreenshare.core.stream.SessionMetricsSnapshot;
import com.obby.android.localscreenshare.core.stream.StreamHub;
import com.obby.android.localscreenshare.core.stream.StreamQueueLimits;
import com.obby.android.localscreenshare.core.stream.StreamRuntime;
import com.obby.android.localscreenshare.core.util.NanoClock;
import com.obby.android.localscreenshare.core.util.TimeSource;
import com.obby.android.localscreenshare.discovery.DiscoveryManager;
import com.obby.android.localscreenshare.discovery.model.AuthMode;
import com.obby.android.localscreenshare.discovery.model.DeviceRole;
import com.obby.android.localscreenshare.discovery.model.ShareState;
import com.obby.android.localscreenshare.discovery.nsd.NsdConstants;
import com.obby.android.localscreenshare.discovery.udp.Announcement;
import com.obby.android.localscreenshare.discovery.udp.MulticastAnnouncer;
import com.obby.android.localscreenshare.discovery.udp.MulticastConfig;
import com.obby.android.localscreenshare.discovery.udp.MulticastLockManager;
import com.obby.android.localscreenshare.discovery.udp.MulticastTransport;
import com.obby.android.localscreenshare.discovery.udp.PerInterfaceMulticastSocketFactory;
import com.obby.android.localscreenshare.discovery.udp.SocketMulticastTransport;
import com.obby.android.localscreenshare.identity.DeviceIdentity;
import com.obby.android.localscreenshare.identity.IdentityManager;
import com.obby.android.localscreenshare.identity.IdentityUnavailableException;
import com.obby.android.localscreenshare.server.ScreenShareServer;
import com.obby.android.localscreenshare.server.ServerInfo;
import com.obby.android.localscreenshare.server.ServerInfoListener;
import com.obby.android.localscreenshare.server.ServerStats;
import com.obby.android.localscreenshare.server.ServerStatsListener;
import com.obby.android.localscreenshare.settings.DiscoverySettings;
import com.obby.android.localscreenshare.settings.Preferences;
import com.obby.android.localscreenshare.settings.ProjectionSettings;
import com.obby.android.localscreenshare.settings.VisibilityPolicy;
import com.obby.android.localscreenshare.trust.FingerprintFormatter;
import com.obby.android.localscreenshare.support.DelayedScheduler;
import com.obby.android.localscreenshare.support.ServiceProtocol;
import com.obby.android.localscreenshare.support.WindowUtils;
import com.obby.android.localscreenshare.transport.ScreenStreamProtoMapper;

import org.apache.commons.lang3.concurrent.BasicThreadFactory;
import org.apache.commons.lang3.time.DurationFormatUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Foreground service that captures the screen and serves it to clients through {@link ScreenShareServer}.
 *
 * <p><b>Lifecycle.</b> The service is started, not bound, by {@link MainActivity} right after the
 * user grants screen capture; the consent result travels in
 * {@link ServiceProtocol#EXTRA_MEDIA_PROJECTION_RESULT}. {@link #onCreate()} registers the stop-command
 * receiver, snapshots the projection preferences and subscribes the preference observer.
 * {@link #onStartCommand(Intent, int, int)} then promotes the service to the foreground with the
 * media-projection type, obtains the {@link MediaProjection} from the consent result, builds the
 * capture pipeline and hands the server start to the control thread; the start completes with the
 * virtual display and the floating chip on the main thread. A null intent, a second start while the
 * server is starting or running, or a failure to promote the service to the foreground, to build the
 * pipeline or to start the server is rejected with {@code START_NOT_STICKY}; a successful start
 * returns {@code START_STICKY}. {@link #stopService()} tears the pipeline down in reverse order and
 * calls {@link #stopSelf()}; {@link #onDestroy()} unregisters the receiver, the preference observer
 * and the control thread.</p>
 *
 * <p><b>Capture pipeline.</b> The projection renders into a {@link VirtualDisplay} backed by an
 * {@link ImageReader} surface. The image-available listener runs on the dedicated
 * {@code lss-service-image-reader} thread, keeps only the newest image, throttles frames to the
 * configured frame rate using the image timestamp, copies the RGBA plane into a reusable
 * {@link Bitmap}, compresses it to JPEG with the configured quality and hands the
 * {@link ScreenFrame} to {@link ScreenShareServer#postScreenFrame}. The security flag travels with each
 * frame.</p>
 *
 * <p><b>Threading.</b> Service callbacks, server listeners, preference changes and all state
 * except the capture state run on the main thread. The capture listener runs on the image reader
 * thread; {@code mImageReaderLock} serializes image acquisition, reader replacement and teardown
 * so no frame is posted once the reader has been closed. The server start runs on the
 * {@code lss-server-control} thread, because binding the port and preparing TLS material reach the
 * AndroidKeyStore and blocked the main thread for seconds when the identity key was unusable
 * (N-t6-2); everything after the start returns to the main thread. Apart from that task and the
 * capture thread's {@code postScreenFrame} call, {@code mServer} is accessed only on the main
 * thread.</p>
 *
 * <p><b>Clients.</b> Activities bind to the service with {@code BIND_AUTO_CREATE} and register a
 * {@link Messenger} as {@code replyTo}; the service keeps the registered messengers in a
 * copy-on-write list and replays the current server state on registration. Notifications are sent
 * from the main thread and a {@link RemoteException} means the client is gone, so it is
 * ignored.</p>
 *
 * <p><b>Overlay.</b> While running, a {@link ScreenShareChip} shows the elapsed time and offers
 * stop and open-app actions. It is added during start and removed during teardown and needs the
 * overlay permission that {@link MainActivity} checks before starting the service.</p>
 */
public class ScreenShareService extends Service {
    private static final String NOTIFICATION_CHANNEL_ID = "lss-service";

    private static final String VIRTUAL_DISPLAY_NAME = "LocalScreenShare";

    private static final String IMAGE_READER_HANDLER_THREAD_NAME = "lss-service-image-reader";

    private static final int NOTIFICATION_ID = 1;

    private static final int IMAGE_READER_MAX_IMAGES = 2;

    // Merges a burst of encoder-affecting preference changes into one rebuild.
    private static final long ENCODER_REBUILD_DEBOUNCE_MS = 300L;

    // Cadence of the structured metric log line; slower than the three-second UI statistics
    // because the line is meant for log capture, not for a live display.
    private static final long UPDATE_METRICS_INTERVAL_MS = 5000L;

    // The device block is logged every other metric line, that is every ten seconds.
    private static final int DEVICE_BLOCK_EVERY_N_LOGS = 2;

    // Attempts per encoder switch; the second one covers a transient codec resource shortage
    // without turning a permanently invalid request into an endless rebuild loop.
    private static final int ENCODER_SWITCH_MAX_ATTEMPTS = 2;

    // Main-thread state; non-null only between a successful start and stopService().
    @Nullable
    private ScreenShareServer mServer;

    // Decides who stops the server when a stop arrives while the start is still running.
    @NonNull
    private final ServerStartCoordinator mServerStartCoordinator = new ServerStartCoordinator();

    // Serializes the server start and stop away from the main thread. A start that blocks in the
    // keystore must not hold up the main thread, and a stop queued behind it must not race it.
    @NonNull
    private final ExecutorService mServerControlExecutor = Executors.newSingleThreadExecutor(
        new BasicThreadFactory.Builder().namingPattern("lss-server-control").build());

    // Multicast announcer; non-null only while the server advertises itself.
    @Nullable
    private MulticastAnnouncer mAnnouncer;

    // Main-thread handler for the temporary-visibility expiry and the encoder rebuild.
    @NonNull
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    @NonNull
    private final Runnable mVisibilityExpiryRunnable = this::restartAnnouncements;

    // Debounced encoder rebuild raised by frame-rate and key-frame-interval changes.
    @NonNull
    private final Runnable mEncoderRebuildRunnable = this::rebuildEncoder;

    // Formats one structured metric line every UPDATE_METRICS_INTERVAL_MS while measuring; runs on
    // the main thread next to the three-second UI statistics, so no extra thread is created.
    @NonNull
    private final Runnable mMetricsLogRunnable = new Runnable() {
        @Override
        public void run() {
            mMainHandler.removeCallbacks(mMetricsLogRunnable);

            final PipelineMetrics metrics = mMetrics;
            if (metrics == null || !metrics.isEnabled()) {
                return;
            }

            final StreamHub hub = mStreamHub;
            metrics.setGauge(PipelineMetrics.Gauge.PEER_COUNT,
                hub != null ? hub.getSessionCount() : 0);

            // One warning per service instance: a suppressed probe means the encoder timestamps and
            // the monotonic clock disagree, so the value would be meaningless rather than absent.
            if (!mCapToOutSuppressed
                && metrics.getGauge(PipelineMetrics.Gauge.CAP_TO_OUT_SUPPRESSED) == 1L) {
                mCapToOutSuppressed = true;
                Log.w(mTag, "mMetricsLogRunnable: enc.cap2out disabled, encoder timestamps do not"
                    + " share the monotonic clock base");
            }

            Log.i(mTag, MetricsFormatter.toLogLine(MetricsFormatter.Role.SERVER,
                metrics.snapshot(UPDATE_METRICS_INTERVAL_MS), null,
                mRateSampler != null ? mRateSampler.sample() : null));
            logSessionMetrics(hub);
            if (++mMetricsLogCount % DEVICE_BLOCK_EVERY_N_LOGS == 0) {
                Log.i(mTag, MetricsFormatter.toDeviceLine(MetricsFormatter.Role.SERVER,
                    mDeviceProfileSampler.get().sample()));
            }
            mMainHandler.postDelayed(mMetricsLogRunnable, UPDATE_METRICS_INTERVAL_MS);
        }
    };

    // Media stream wiring: the runtime owns the encoder and the hub owns per-client sessions. All
    // are assembled on start and torn down before the capture resources are released.
    @Nullable
    private CodecRegistry mCodecRegistry;

    @Nullable
    private StreamRuntime mStreamRuntime;

    @Nullable
    private StreamHub mStreamHub;

    // Optional audio fan-out of the same session; created with the stream pipeline and detached
    // with it. Null means this session carries no audio.
    @Nullable
    private AudioHub mAudioHub;

    @Nullable
    private StreamKey mStreamKey;

    // Main-thread handle on the projection; released by stopService().
    @Nullable
    private MediaProjection mMediaProjection;

    // Current capture size in pixels; null while stopped.
    @Nullable
    private Size mProjectionSize;

    // Size step the encoder accepts; probed once per service instance so the capture size, the
    // virtual display and the coded size share one geometry.
    @NonNull
    private EncoderSizeAlignment mProjectionSizeAlignment = EncoderSizeAlignment.EVEN;

    // Capture percentage the current mProjectionSize was derived from; restored when the encoder
    // rejects a new size, so a rejected value never stays persisted as if it were applied.
    private int mAppliedProjectionScale;

    // Dedicated thread for image-available callbacks; started before the reader and quit during
    // teardown.
    @Nullable
    private HandlerThread mImageReaderHandlerThread;

    @Nullable
    private Handler mImageReaderHandler;

    // Volatile because the capture thread compares it before taking mImageReaderLock; replaced
    // under that lock on resize and teardown.
    private volatile ImageReader mImageReader;

    // Timestamp of the last accepted frame in nanoseconds, or -1 to accept the next frame without
    // throttling; guarded by mImageReaderLock.
    private volatile long mFrameTimestamp;

    // Main-thread state; resized on configuration changes.
    @Nullable
    private VirtualDisplay mVirtualDisplay;

    // Floating duration chip; added on start and removed on teardown.
    @Nullable
    private ScreenShareChip mScreenShareChip;

    // Reused ARGB_8888 buffer for the current capture size; guarded by mImageReaderLock.
    @Nullable
    private Bitmap mScreenFrameBitmap;

    // Last values received from the server on the main thread; replayed to newly registered
    // clients.
    @Nullable
    private ServerInfo mServerInfo;

    @Nullable
    private ServerStats mServerStats;

    // Metric scope of the encode/send pipeline; null while the service is stopped. A disabled
    // preference yields PipelineMetrics.NONE, so no window and no snapshot exists at all.
    @Nullable
    private PipelineMetrics mMetrics;

    // Reader-owned baseline of the encoder output counters; the counter itself stays cumulative, so
    // the five-second line reports this interval instead of the average since the scope was created.
    @Nullable
    private RateSampler mRateSampler;

    // Set once the capture-to-output probe suppressed itself, so the warning is logged once.
    private boolean mCapToOutSuppressed;

    // Device readings emitted with the metric lines; platform lookups live in the service layer.
    // Created on the first sample: a field initialiser runs before attachBaseContext, and the
    // sampler reads the context in its constructor.
    @NonNull
    private final LazyContextValue<DeviceProfileSampler> mDeviceProfileSampler =
        new LazyContextValue<>(this::getApplicationContext, AndroidDeviceProfileSampler::new);

    // Counts metric log lines so the device block is emitted every ten seconds.
    private int mMetricsLogCount;

    // Last values rendered into the notification; used to skip redundant notification updates.
    private int mConnectionCount;

    // Badge of the most recent first-contact viewer; cleared when no viewer is connected.
    @Nullable
    private String mNewPeerBadge;

    private long mOutboundDataRate;

    // Projection settings, written on the main thread by the preference observer. The capture
    // thread reads the quality, security and frame-rate values while encoding each frame.
    private boolean mIsProjectionSecure;

    private int mProjectionScale;

    private int mProjectionQuality;

    private int mProjectionFrameRate;

    // True for the H.264 surface path, false for the JPEG fallback path; read at start.
    private boolean mIsH264;

    private final String mTag = "ScreenShareService@" + hashCode();

    // Serializes image acquisition with reader replacement and teardown. It also guards
    // mScreenFrameBitmap and mFrameTimestamp.
    @NonNull
    private final Object mImageReaderLock = new Object();

    // Registered client messengers; copy-on-write so fan-out can iterate while a client removes
    // itself. Mutated on the main thread only.
    @NonNull
    private final List<Messenger> mClientMessengers = new CopyOnWriteArrayList<>();

    // Receives client registrations on the main thread.
    @NonNull
    private final Messenger mMessenger = new Messenger(new Handler(Looper.getMainLooper(), msg -> {
        switch (msg.what) {
            case ServiceProtocol.MSG_REGISTER_SERVICE_CLIENT:
                registerServiceClient(msg.replyTo);
                return true;
            case ServiceProtocol.MSG_UNREGISTER_SERVICE_CLIENT:
                unregisterServiceClient(msg.replyTo);
                return true;
            default:
                return false;
        }
    }));

    // ScreenShareServer invokes the statistics listener on the main thread. The info listener is
    // additionally reached from the control thread, because the first server info is published
    // inside start(), so it hops to the main thread before touching any state.
    @NonNull
    private final ServerInfoListener mServerInfoListener =
        serverInfo -> mMainHandler.post(() -> onServerInfoChanged(serverInfo));

    @NonNull
    private final ServerStatsListener mServerStatsListener = this::onServerStatsChanged;

    // Registered on the main looper. onStop is reported when the user revokes capture or the
    // system ends it; the resize and visibility callbacks are informational only.
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

    // Runs on the image reader thread. The identity check is repeated under the lock so a callback
    // for a reader that has already been replaced becomes a no-op.
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

                        // Frame-rate throttle: drop images that arrive before the configured
                        // interval since the last accepted frame. Timestamps are nanoseconds.
                        if (mFrameTimestamp >= 0L
                            && image.getTimestamp() - mFrameTimestamp < mNanosPerSecond / mProjectionFrameRate) {
                            return;
                        }

                        mFrameTimestamp = image.getTimestamp();

                        // Reuse the buffer; only a size change allocates a new bitmap.
                        if (mScreenFrameBitmap == null || mScreenFrameBitmap.getWidth() != image.getWidth()
                            || mScreenFrameBitmap.getHeight() != image.getHeight()) {
                            Optional.ofNullable(mScreenFrameBitmap).ifPresent(Bitmap::recycle);
                            mScreenFrameBitmap =
                                Bitmap.createBitmap(image.getWidth(), image.getHeight(), Bitmap.Config.ARGB_8888);
                        }

                        // Plane 0 holds the packed RGBA data; the row stride can be wider than the
                        // image width.
                        final Image.Plane plane = image.getPlanes()[0];
                        plane.getBuffer().rewind();
                        ImageProcessingUtil.copyByteBufferToBitmap(mScreenFrameBitmap, plane.getBuffer(),
                            plane.getRowStride());

                        // JPEG frames are submitted through the frame-input encoder; the quality
                        // comes from the current preference snapshot.
                        final byte[] data;
                        try (final ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
                            mScreenFrameBitmap.compress(Bitmap.CompressFormat.JPEG, mProjectionQuality, outputStream);
                            data = outputStream.toByteArray();
                        } catch (IOException e) {
                            return;
                        }

                        // Holding mImageReaderLock keeps this call ahead of teardown, which closes
                        // the reader under the same lock. The runtime accepts calls from any thread.
                        mStreamRuntime.submitFrame(mFrameTimestamp / 1000L, data);
                    }
                }
            }
        };

    // Display state notifications only; the capture state is driven by the image reader.
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

    // Handles the stop command and restarts announcements after an identity reset. Both actions
    // are package-scoped and delivered to a not-exported receiver.
    @NonNull
    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ServiceProtocol.ACTION_STOP_SERVICE.equals(intent.getAction())) {
                stopService();
            } else if (ServiceProtocol.ACTION_IDENTITY_RESET.equals(intent.getAction())) {
                restartAnnouncements();
                // The notification carries the fingerprint guide of this device. It has to show the
                // new identity immediately, because the user may compare the two values right after
                // the reset (N-t6-3); waiting for the next statistics or peer event left the old
                // fingerprint there until sharing was restarted.
                updateNotification();
            }
        }
    };

    // Applies projection settings changed from the UI. A null key refreshes every projection
    // value and the capture size; a single key updates only what it affects. Quality and security
    // are read by the next encoded frame, the scale resizes the display, the bitrate is hot-applied
    // and the frame rate and key-frame interval rebuild the encoder because the encoder only reads
    // them when it is created.
    @NonNull
    private final Preferences.Observer mPreferencesObserver = key -> {
        if (key == null) {
            mIsProjectionSecure = Preferences.get().isProjectionSecure();
            mProjectionScale = Preferences.get().getProjectionScale();
            mProjectionQuality = Preferences.get().getProjectionQuality();
            mProjectionFrameRate = Preferences.get().getProjectionFrameRate();
            updateProjectionSize();
            scheduleEncoderRebuild();
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
                scheduleEncoderRebuild();
                break;
            case Preferences.KEY_PROJECTION_IDR_INTERVAL:
                scheduleEncoderRebuild();
                break;
            case Preferences.KEY_PROJECTION_BITRATE:
                // Bitrate is hot-applied; frame rate and IDR interval only change on a rebuild.
                if (mStreamRuntime != null) {
                    mStreamRuntime.updateParameters(buildProjectionSettings().toStreamParameters());
                }
                break;
            case Preferences.KEY_PROJECTION_CODEC:
                // Deliberately not applied: switching the codec needs a new capture path and a
                // client that can display it (JPEG), which this build does not have. The stored
                // value takes effect on the next capture session start.
                Log.i(mTag, "mPreferencesObserver: codec change applies on the next start");
                break;
            case Preferences.KEY_MULTICAST_ANNOUNCE_ENABLED:
            case Preferences.KEY_MULTICAST_GROUP:
            case Preferences.KEY_MULTICAST_PORT:
            case Preferences.KEY_DISCOVERY_REFRESH_TIER:
            case Preferences.KEY_DISCOVERY_VISIBILITY:
            case Preferences.KEY_DISCOVERY_VISIBILITY_UNTIL:
            case Preferences.KEY_DISCOVERY_COMPAT_ONLY:
            case Preferences.KEY_SHARE_NAME_ENABLED:
                // Announcement settings reach the transport directly, so the announcer is
                // restarted; the running capture and the media stream are untouched. The mDNS
                // record is re-published because its TXT carries the join policy and the optional
                // display name.
                restartAnnouncements();
                if (mServer != null) {
                    mServer.onDiscoverySettingsChanged();
                }
                break;
            default:
                break;
        }
    };

    /**
     * Schedules one encoder rebuild after the current burst of preference changes.
     *
     * <p>Every frame-rate or key-frame-interval change would otherwise rebuild the encoder on its
     * own, and each rebuild costs a new configuration epoch plus a client resynchronization. The
     * main-thread handler merges changes that arrive within the debounce window, and because both
     * this path and the size path run on the main thread, two rebuilds can never race for the same
     * configuration epoch.</p>
     */
    private void scheduleEncoderRebuild() {
        mMainHandler.removeCallbacks(mEncoderRebuildRunnable);
        mMainHandler.postDelayed(mEncoderRebuildRunnable, ENCODER_REBUILD_DEBOUNCE_MS);
    }

    /**
     * Rebuilds the encoder with the current parameters and re-points the capture display.
     *
     * <p>Only the H.264 path has an encoder to rebuild; the JPEG path resizes the reader instead.
     * Does nothing while capture is stopped. A failed switch keeps the previous encoder active, so
     * the picture continues with the old frame rate and key-frame interval, and one bounded retry
     * follows immediately because the common failure is a transient codec resource shortage.</p>
     */
    private void rebuildEncoder() {
        if (!mIsH264 || mStreamRuntime == null || mVirtualDisplay == null
            || mProjectionSize == null) {
            return;
        }

        if (switchEncoderToCurrentSettings()) {
            Log.i(mTag, "rebuildEncoder: encoder rebuilt for the new parameters");
        } else {
            Log.w(mTag, "rebuildEncoder: encoder rebuild failed after "
                + ENCODER_SWITCH_MAX_ATTEMPTS + " attempts");
        }
    }

    /**
     * Switches the running encoder to a descriptor that carries the current projection geometry and
     * parameters, then points the capture display at the encoder's input surface.
     *
     * <p>Order is fixed: the runtime parameters are replaced first so the new encoder is created
     * with them, then the descriptor advances by one epoch, then the display is handed the new
     * input surface. The display is re-pointed in every outcome, including a failed switch, because
     * a display that keeps rendering into the surface of an encoder that was already told to stop
     * produces a permanent stream of {@code dequeueBuffer -19} errors and no frames. A failed
     * attempt is retried at most {@link #ENCODER_SWITCH_MAX_ATTEMPTS}-1 more times; a failed switch
     * does not advance the epoch, so the retry asks for the same epoch the first attempt used.</p>
     *
     * @return {@code true} when the runtime adopted the new descriptor
     */
    private boolean switchEncoderToCurrentSettings() {
        return switchEncoderToSettings(mProjectionSize);
    }

    /**
     * Switches the running encoder to the given capture size with the current parameters.
     *
     * <p>Used by a size change, which has to create the encoder for the new geometry before the
     * display is resized: the size is passed explicitly instead of being read from
     * {@link #mProjectionSize}, which still holds the applied size until the switch succeeds.</p>
     *
     * @param size capture size the new descriptor must carry; must not be null
     * @return {@code true} when the runtime adopted the new descriptor
     */
    private boolean switchEncoderToSettings(@NonNull final Size size) {
        boolean switched = false;
        for (int attempt = 1; attempt <= ENCODER_SWITCH_MAX_ATTEMPTS && !switched; attempt++) {
            try {
                mStreamRuntime.updateParameters(buildProjectionSettings().toStreamParameters());
                mStreamRuntime.switchDescriptor(
                    buildRequestedDescriptor(mStreamRuntime.getConfigEpoch() + 1, size));
                switched = true;
            } catch (RuntimeException e) {
                Log.w(mTag, "switchEncoderToCurrentSettings: encoder switch failed (attempt "
                    + attempt + ')', e);
            }
        }
        bindEncoderInputSurface();
        return switched;
    }

    /**
     * Points the virtual display at the input surface of the runtime's current encoder.
     *
     * <p>Called after every switch attempt so the display never keeps a surface that the runtime
     * no longer feeds. A runtime without a usable surface leaves the display untouched rather than
     * detaching it: an unusable runtime cannot capture anyway, and detaching the display would also
     * lose the platform's own recovery path.</p>
     */
    private void bindEncoderInputSurface() {
        if (mVirtualDisplay == null || mStreamRuntime == null) {
            return;
        }
        final Surface surface = mStreamRuntime.getInputSurface();
        if (surface != null) {
            mVirtualDisplay.setSurface(surface);
        }
    }

    /**
     * Registers the stop receiver, snapshots the projection preferences and subscribes the
     * preference observer that applies later changes.
     */
    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(mTag, "onCreate: service created");

        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(ServiceProtocol.ACTION_STOP_SERVICE);
        intentFilter.addAction(ServiceProtocol.ACTION_IDENTITY_RESET);
        ContextCompat.registerReceiver(this, mBroadcastReceiver, intentFilter, ContextCompat.RECEIVER_NOT_EXPORTED);

        mIsProjectionSecure = Preferences.get().isProjectionSecure();
        mProjectionScale = Preferences.get().getProjectionScale();
        mProjectionQuality = Preferences.get().getProjectionQuality();
        mProjectionFrameRate = Preferences.get().getProjectionFrameRate();
        mProjectionSizeAlignment = EncoderAlignmentProbe.probe(H264Codec.MIME);
        Log.i(mTag, "onCreate: encoder size alignment "
            + mProjectionSizeAlignment.getWidthAlignment() + "x"
            + mProjectionSizeAlignment.getHeightAlignment());
        Preferences.get().addObserver(mPreferencesObserver);
    }

    /**
     * Unregisters the receiver and the preference observer; the capture pipeline was already
     * released by {@link #stopService()}.
     */
    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.i(mTag, "onDestroy: service destroyed");

        unregisterReceiver(mBroadcastReceiver);
        Preferences.get().removeObserver(mPreferencesObserver);
        mMainHandler.removeCallbacks(mEncoderRebuildRunnable);
        stopAnnouncements();
        // Queued work still runs; a start in flight finishes and stops its own server.
        mServerControlExecutor.shutdown();
    }

    /**
     * Re-applies the capture size after a rotation or a window size change.
     */
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateProjectionSize();
    }

    /**
     * Exposes the client messenger. Binding does not start the server: a bound-only instance
     * reports the stopped state.
     */
    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        Log.i(mTag, "onBind: service bound");
        return mMessenger.getBinder();
    }

    /**
     * Starts the server and the capture pipeline, then shows the floating chip.
     *
     * <p>Requires the consent result in {@link ServiceProtocol#EXTRA_MEDIA_PROJECTION_RESULT}; a null
     * intent cannot carry it, so a system restart is rejected with {@code START_NOT_STICKY}. A
     * second start while the server is starting or running is ignored for the same reason. The
     * foreground notification is posted before the projection is used, and a failure while promoting
     * the foreground service or building the stream pipeline tears the partially built state down
     * through {@link #stopService()}. The server itself starts on the control thread and its
     * completion comes back to the main thread (see {@link #startServer(ScreenShareServer)}).</p>
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(mTag, String.format("onStartCommand: startId = %d", startId));

        final long startGeneration = mServerStartCoordinator.beginStart();
        if (startGeneration == ServerStartCoordinator.NO_GENERATION) {
            Log.w(mTag, "onStartCommand: server already starting or running");
            return START_NOT_STICKY;
        }

        if (intent == null) {
            Log.w(mTag, "onStartCommand: intent is null");
            mServerStartCoordinator.beginStop();
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

        // The consent result is used once; the callback is registered on the main looper so
        // teardown triggered by the system stays on the main thread.
        mMediaProjection = getMediaProjection(intent);
        mMediaProjection.registerCallback(mMediaProjectionCallback, new Handler(Looper.getMainLooper()));
        mProjectionSize = getProjectionSize();
        mAppliedProjectionScale = mProjectionScale;

        // Assemble the media stream: an H.264 runtime feeding a shared hub that the server fans out
        // to per-client sessions. The encoder starts before the display renders into its surface.
        try {
            startStreamPipeline();
        } catch (RuntimeException e) {
            Log.e(mTag, "onStartCommand: start stream pipeline failed", e);
            Toast.makeText(this, R.string.start_service_failed, Toast.LENGTH_LONG).show();
            stopService();
            return START_NOT_STICKY;
        }

        // The server binds the configured port and starts advertising over NSD; its listeners
        // are invoked on the main thread. The discovery store is shared with the activity side so
        // Register-learned peers appear in the device list.
        final ScreenShareServer server = new ScreenShareServer(this);
        server.setServerInfoListener(mServerInfoListener);
        server.setServerStatsListener(mServerStatsListener);
        server.setPeerTrustListener(new ScreenShareServer.PeerTrustListener() {
            @Override
            public void onNewPeerPinned(final String deviceId, final String fingerprint) {
                mMainHandler.post(() -> {
                    mNewPeerBadge = getString(R.string.tls_new_device_badge,
                        deviceId.substring(0,
                            Math.min(DeviceIdentity.SHORT_ID_LENGTH, deviceId.length())));
                    updateNotification();
                });
            }

            @Override
            public void onPeerRejected(final String deviceId, final String reason) {
                mMainHandler.post(() -> Toast.makeText(ScreenShareService.this,
                    getString(R.string.tls_peer_rejected_toast, deviceId.substring(0,
                        Math.min(DeviceIdentity.SHORT_ID_LENGTH, deviceId.length()))),
                    Toast.LENGTH_LONG).show());
            }
        });
        server.setStream(mStreamHub, mStreamRuntime);
        server.setAudioHub(mAudioHub);
        server.setSecure(mIsProjectionSecure);
        server.setDeviceStore(DiscoveryManager.get().getDeviceStore());
        mServer = server;

        // Binding the port and preparing TLS material reach the AndroidKeyStore and have to stay
        // off the main thread: with an unusable identity key the keystore call blocked for 5.4 s
        // and the system raised ANR (N-t6-2). The visible order is unchanged, because everything
        // after the start is posted back to the main thread.
        mServerControlExecutor.execute(() -> startServer(server, startGeneration));

        return START_STICKY;
    }

    /**
     * Starts the server on the control thread and hands the outcome back to the main thread.
     *
     * <p>Runs off the main thread because {@link ScreenShareServer#start()} opens the keystore to
     * sign the TLS certificate and binds the gRPC port. The main-thread continuation is
     * {@link #onServerStarted(ScreenShareServer)} on success and
     * {@link #onServerStartFailed(ScreenShareServer)} otherwise. A stop that arrived while this was
     * running has already forgotten the server, so this method stops it itself; a server that is
     * starting is not reachable by peers yet and rejects calls until it is ready.</p>
     *
     * <p>Must not touch service state other than through {@link #mServerStartCoordinator}: every
     * field owned by the main thread stays with the continuation.</p>
     *
     * @param server           server of the session that claimed the start
     * @param startGeneration  token returned by the matching {@code beginStart()}
     */
    private void startServer(@NonNull final ScreenShareServer server, final long startGeneration) {
        boolean isStarted = false;
        try {
            server.start();
            isStarted = true;
        } catch (IOException | RuntimeException e) {
            Log.e(mTag, "startServer: start server failed", e);
        }

        if (!mServerStartCoordinator.finishStart(startGeneration)) {
            Log.i(mTag, "startServer: stopped while starting, stopping the server");
            server.stop();
            return;
        }
        mMainHandler.post(isStarted
            ? () -> onServerStarted(server)
            : () -> onServerStartFailed(server));
    }

    /**
     * Completes the start on the main thread: announcements, capture display, chip, clients.
     *
     * <p>The order is the same as a synchronous start. The server info listener was posted before
     * this continuation, so {@link #mServerInfo} is already set when the announcer is built.</p>
     */
    private void onServerStarted(@NonNull final ScreenShareServer server) {
        if (mServer != server) {
            Log.i(mTag, "onServerStarted: session already stopped");
            return;
        }

        startAnnouncements();

        // The virtual display renders the captured screen into the codec's input: the encoder
        // surface for H.264, or an image reader feeding the frame-input JPEG path.
        final Surface displaySurface;
        if (mIsH264) {
            displaySurface = mStreamRuntime.getInputSurface();
        } else {
            mImageReaderHandlerThread = new HandlerThread(IMAGE_READER_HANDLER_THREAD_NAME,
                Process.THREAD_PRIORITY_BACKGROUND);
            mImageReaderHandlerThread.start();
            mImageReaderHandler = new Handler(mImageReaderHandlerThread.getLooper());
            mImageReader = ImageReader.newInstance(mProjectionSize.getWidth(), mProjectionSize.getHeight(),
                PixelFormat.RGBA_8888, IMAGE_READER_MAX_IMAGES);
            mFrameTimestamp = -1L;
            mImageReader.setOnImageAvailableListener(mOnImageAvailableListener, mImageReaderHandler);
            displaySurface = mImageReader.getSurface();
        }
        mVirtualDisplay = mMediaProjection.createVirtualDisplay(VIRTUAL_DISPLAY_NAME, mProjectionSize.getWidth(),
            mProjectionSize.getHeight(), getResources().getConfiguration().densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
            displaySurface, mVirtualDisplayCallback, new Handler(Looper.getMainLooper()));

        // The chip is only shown once the pipeline is complete.
        mScreenShareChip = new ScreenShareChip(this);
        mScreenShareChip.show();

        // Bound activities learn about the new state only after everything is in place.
        mClientMessengers.forEach(this::notifyServerStarted);
    }

    /**
     * Tears the session down when the server could not be started on the control thread.
     */
    private void onServerStartFailed(@NonNull final ScreenShareServer server) {
        if (mServer != server) {
            Log.i(mTag, "onServerStartFailed: session already stopped");
            return;
        }
        Toast.makeText(this, R.string.start_service_failed, Toast.LENGTH_LONG).show();
        stopService();
    }

    /**
     * Releases the capture pipeline, the server and the foreground notification, then stops the
     * service.
     *
     * <p>Runs on the main thread and is reachable from the stop broadcast, the chip and the system
     * projection callback. The order matters: the overlay and the display stop first, then the
     * image reader and its thread, then the projection, and the server last, so no frame is
     * accepted while the server is going away.</p>
     */
    private void stopService() {
        Log.i(mTag, "stopService: stop service");

        mServerInfo = null;
        mProjectionSize = null;
        mServerStats = null;
        mConnectionCount = 0;
        mOutboundDataRate = 0L;

        mMainHandler.removeCallbacks(mMetricsLogRunnable);
        mMetrics = null;
        mRateSampler = null;
        mMetricsLogCount = 0;

        // Stop producing UI first so the chip never outlives the running session.
        if (mScreenShareChip != null) {
            mScreenShareChip.dismiss();
            mScreenShareChip = null;
        }

        // Stop the encoder before releasing the capture surface, so no frame is produced while the
        // display is being torn down. Detaching the hub's sessions closes their transports.
        if (mStreamRuntime != null) {
            mStreamRuntime.close();
            mStreamRuntime = null;
        }
        if (mStreamHub != null && mStreamKey != null) {
            mStreamHub.unregister(mStreamKey);
            mStreamHub = null;
        }
        mAudioHub = null;
        mStreamKey = null;
        mCodecRegistry = null;

        if (mVirtualDisplay != null) {
            mVirtualDisplay.release();
            mVirtualDisplay = null;
        }

        // Closing the reader under the lock waits for an in-flight capture and rejects later ones.
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

        // Unregister first so the stop callback does not re-enter this method.
        if (mMediaProjection != null) {
            mMediaProjection.unregisterCallback(mMediaProjectionCallback);
            mMediaProjection.stop();
            mMediaProjection = null;
        }

        // The goodbye burst goes out before the server stops, so it still carries the announced
        // port; the announcer closes its sockets after the last goodbye.
        stopAnnouncements();

        if (mServer != null) {
            final ScreenShareServer server = mServer;
            mServer = null;
            if (mServerStartCoordinator.beginStop()) {
                server.stop();
            } else {
                // The start is still running on the control thread; it stops the server as soon as
                // start() returns, which keeps the port bound only for the rest of that call.
                Log.i(mTag, "stopService: server start is in flight, it stops the server itself");
            }
        } else {
            mServerStartCoordinator.beginStop();
        }

        mClientMessengers.forEach(this::notifyServerStopped);

        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    /**
     * Starts the multicast announcer for the running server.
     *
     * <p>Runs on the main thread after the server bound its port. The group and port come from the
     * preferences, a disabled announcement toggle or an unusable group leaves the server
     * discoverable over NSD only. The announcement content is captured per session: an identity
     * reset stops this announcer (which says goodbye with the old identity) and starts a new one,
     * so peers never see one identity's goodbye carrying another identity's key.</p>
     */
    private void startAnnouncements() {
        final DiscoverySettings settings = Preferences.get().getDiscoverySettings();
        final long nowMs = System.currentTimeMillis();
        mMainHandler.removeCallbacks(mVisibilityExpiryRunnable);
        if (mAnnouncer != null || mServerInfo == null || !settings.isMulticastChannelEnabled()
            || !settings.visibilityPolicy(nowMs).isAnnouncing(nowMs)) {
            return;
        }
        final Announcement announcement = buildAnnouncement();
        if (announcement == null) {
            Log.w(mTag, "startAnnouncements: announcement not available");
            return;
        }

        final MulticastConfig config;
        try {
            config = new MulticastConfig(settings.getMulticastGroup(), settings.getMulticastPort());
        } catch (IllegalArgumentException e) {
            Log.w(mTag, "startAnnouncements: invalid multicast configuration", e);
            return;
        }

        final MulticastLockManager lockManager = MulticastLockManager.get();
        final MulticastTransport transport = new SocketMulticastTransport(
            new PerInterfaceMulticastSocketFactory(), config,
            DelayedScheduler.newSingleThread("lss-announce-socket"));
        lockManager.acquire();
        transport.start(null);

        mAnnouncer = new MulticastAnnouncer(new MulticastAnnouncer.Sender() {
            @Override
            public void send(@NonNull final byte[] payload) {
                transport.send(payload);
            }

            @Override
            public void close() {
                transport.close();
                lockManager.release();
            }
        }, DelayedScheduler.newSingleThread("lss-announcer"), () -> announcement,
            new Random(), settings.getRefreshTier().getHeartbeatActiveMs(),
            settings.getRefreshTier().getHeartbeatIdleMs());
        mAnnouncer.setLive(true);
        mAnnouncer.start();
        scheduleVisibilityExpiry(settings);
        Log.i(mTag, "startAnnouncements: multicast announcements started");
    }

    /**
     * Stops advertising when the temporary visibility window ends.
     *
     * <p>The timer is armed from the persisted expiry, so a restart of the app resumes the same
     * remaining window instead of restarting it. When the timer fires, the announcer is restarted:
     * the now-hidden preset stops it and sends the goodbye burst.</p>
     */
    private void scheduleVisibilityExpiry(@NonNull final DiscoverySettings settings) {
        final long nowMs = System.currentTimeMillis();
        final VisibilityPolicy policy = settings.visibilityPolicy(nowMs);
        if (policy.getVisibility() != VisibilityPolicy.Visibility.VISIBLE_10_MIN
            || policy.isExpired(nowMs)) {
            return;
        }
        mMainHandler.postDelayed(mVisibilityExpiryRunnable, policy.getExpiresAtMs() - nowMs + 100L);
    }

    /**
     * Stops the announcer; it sends its goodbye burst and releases the sockets and the lock.
     */
    private void stopAnnouncements() {
        mMainHandler.removeCallbacks(mVisibilityExpiryRunnable);
        if (mAnnouncer == null) {
            return;
        }
        mAnnouncer.stop();
        mAnnouncer = null;
        Log.i(mTag, "stopAnnouncements: multicast announcements stopped");
    }

    /**
     * Stops the current announcer and starts a fresh one with the new identity.
     *
     * <p>Called after {@link IdentityManager#reset()} through the package-scoped reset broadcast.
     * The capture pipeline and the gRPC server keep running; only the announced identity changes.</p>
     */
    private void restartAnnouncements() {
        Log.i(mTag, "restartAnnouncements: restart announcements after identity reset");
        stopAnnouncements();
        startAnnouncements();
    }

    /**
     * Builds the announcement template of the running session.
     *
     * <p>The fingerprint is deliberately absent and the device name is only present when the user
     * enabled name sharing. The port is the bound gRPC port, which is what a peer uses to call
     * {@code Register}.</p>
     */
    @Nullable
    private Announcement buildAnnouncement() {
        final ServerInfo serverInfo = mServerInfo;
        if (serverInfo == null || serverInfo.getPort() <= 0) {
            return null;
        }
        final DeviceIdentity identity;
        try {
            identity = IdentityManager.get().getIdentity();
        } catch (IdentityUnavailableException e) {
            Log.w(mTag, "buildAnnouncement: device identity unavailable");
            return null;
        }

        final DiscoverySettings settings = Preferences.get().getDiscoverySettings();
        final long nowMs = System.currentTimeMillis();
        return Announcement.builder()
            .id(identity.getId())
            .name(Preferences.get().isShareNameEnabled() ? Preferences.get().getDeviceAlias() : null)
            .role(DeviceRole.SERVER)
            .state(ShareState.LIVE)
            .join(settings.visibilityPolicy(nowMs).joinPolicy(nowMs))
            .secure(mIsProjectionSecure)
            .auth(AuthMode.NONE)
            .protocolVersion(String.valueOf(ScreenStreamProtoMapper.PROTOCOL_VERSION))
            .metadataVersion(1)
            .capabilities(settings.capabilities())
            .port(serverInfo.getPort())
            .platform(NsdConstants.DEVICE_PLATFORM_ANDROID)
            .build();
    }

    // Replays the latest server info to every registered client. Posted from the control thread,
    // so a session that was torn down meanwhile is dropped instead of resurrected.
    private void onServerInfoChanged(@NonNull final ServerInfo serverInfo) {
        if (mServer == null) {
            Log.i(mTag, "onServerInfoChanged: session already stopped");
            return;
        }
        mServerInfo = serverInfo;
        mClientMessengers.forEach(this::notifyServerInfoChanged);
    }

    // Forwards stats and updates the notification only when the displayed counters change.
    @SuppressLint("MissingPermission")
    private void onServerStatsChanged(@NonNull final ServerStats serverStats) {
        mServerStats = serverStats;
        mClientMessengers.forEach(this::notifyServerStatsChanged);

        final int connectionCount = mServerStats.getTransports().size();
        if (connectionCount == 0) {
            mNewPeerBadge = null;
        }
        if (mConnectionCount == connectionCount && mOutboundDataRate == mServerStats.getOutboundDataRate()) {
            return;
        }

        mConnectionCount = connectionCount;
        mOutboundDataRate = mServerStats.getOutboundDataRate();
        updateNotification();
    }

    /**
     * Recomputes the capture size from the window bounds and the scale preference and applies it.
     *
     * <p><b>Order.</b> The virtual display and the encoder must use the same geometry, so the
     * encoder is switched to the new size first and the display is resized only after the encoder
     * accepted it (see {@link ProjectionSizeCoordinator}). A rotation or a scale change whose size
     * the encoder rejects therefore leaves the display untouched and the stream keeps its previous
     * geometry, instead of shrinking the display under an encoder that still encodes the old size,
     * which squashed the viewer picture and left a black band in the 2026-10-21 device test.</p>
     *
     * <p>On the JPEG path there is no encoder that could reject the size: the reader is recreated
     * under the lock because an {@code ImageReader} size is fixed at creation. Does nothing while
     * stopped.</p>
     */
    private void updateProjectionSize() {
        if (mProjectionSize == null || mVirtualDisplay == null) {
            return;
        }

        final Size size = getProjectionSize();
        if (Objects.equals(mProjectionSize, size)) {
            // Another percentage can round onto the size already in use; it is applied as is.
            mAppliedProjectionScale = mProjectionScale;
            return;
        }

        if (mIsH264 && mStreamRuntime != null) {
            final ProjectionSizeCoordinator.Outcome outcome = ProjectionSizeCoordinator.apply(
                size.getWidth(), size.getHeight(),
                (width, height) -> switchEncoderToSettings(new Size(width, height)),
                this::resizeCaptureDisplay);
            if (outcome == ProjectionSizeCoordinator.Outcome.REJECTED) {
                onProjectionSizeRejected(size);
                return;
            }
            mProjectionSize = size;
            mAppliedProjectionScale = mProjectionScale;
            return;
        }

        ProjectionSizeCoordinator.apply(size.getWidth(), size.getHeight(), null,
            this::resizeCaptureDisplay);
        mProjectionSize = size;
        mAppliedProjectionScale = mProjectionScale;

        synchronized (mImageReaderLock) {
            if (mImageReader == null) {
                return;
            }

            // Recreate the reader at the new size, then hand the display the new surface.
            mImageReader.close();
            mImageReader = ImageReader.newInstance(mProjectionSize.getWidth(), mProjectionSize.getHeight(),
                PixelFormat.RGBA_8888, IMAGE_READER_MAX_IMAGES);
            mFrameTimestamp = -1L;
            mImageReader.setOnImageAvailableListener(mOnImageAvailableListener, mImageReaderHandler);
            mVirtualDisplay.setSurface(mImageReader.getSurface());
        }
    }

    /**
     * Resizes the capture display to the applied capture size.
     *
     * <p>A rotation changes the usable bounds, and the display would otherwise keep composing the
     * new screen content at the old resolution and let the encoder surface stretch it.</p>
     */
    private void resizeCaptureDisplay(final int width, final int height) {
        if (mVirtualDisplay == null) {
            return;
        }
        mVirtualDisplay.resize(width, height, getResources().getConfiguration().densityDpi);
    }

    /**
     * Reports a capture size the encoder refused and keeps the preferences honest.
     *
     * <p>The size itself needs no rollback: the switch runs before the display is resized, so the
     * display and the encoder still use the previous geometry. What must not stay behind is the
     * stored percentage, which would otherwise read as applied while the stream keeps the old size.
     * The applied percentage is written back so the settings sheet and the stored value match what
     * the viewer actually receives, and the user is told on screen that the value was not applied.
     * Writing the same value back is a no-op, which also keeps the preference observer from looping.
     *
     * @param requested size the encoder rejected
     */
    private void onProjectionSizeRejected(@NonNull final Size requested) {
        Log.e(mTag, "updateProjectionSize: encoder rejected " + requested.getWidth() + 'x'
            + requested.getHeight() + ", capture stays " + mProjectionSize.getWidth() + 'x'
            + mProjectionSize.getHeight() + " (scale " + mAppliedProjectionScale + "%)");
        Toast.makeText(this, getString(R.string.projection_size_rejected,
            mProjectionSize.getWidth(), mProjectionSize.getHeight()), Toast.LENGTH_LONG).show();
        if (mProjectionScale != mAppliedProjectionScale) {
            Preferences.get().setProjectionScale(mAppliedProjectionScale);
        }
    }

    // Called on the main thread. The current state is replayed so a new client never has to wait
    // for the next server event.
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

    // Notifications are sent from the main thread; a dead client drops the message, so the
    // failure is ignored.
    private void notifyServerStarted(@NonNull final Messenger messenger) {
        try {
            messenger.send(Message.obtain(null, ServiceProtocol.MSG_SERVER_STARTED));
        } catch (RemoteException e) {
            // ignored
        }
    }

    private void notifyServerStopped(@NonNull final Messenger messenger) {
        try {
            messenger.send(Message.obtain(null, ServiceProtocol.MSG_SERVER_STOPPED));
        } catch (RemoteException e) {
            // ignored
        }
    }

    private void notifyServerInfoChanged(@NonNull final Messenger messenger) {
        try {
            messenger.send(Message.obtain(null, ServiceProtocol.MSG_SERVER_INFO_CHANGED, mServerInfo));
        } catch (RemoteException e) {
            // ignored
        }
    }

    private void notifyServerStatsChanged(@NonNull final Messenger messenger) {
        try {
            messenger.send(Message.obtain(null, ServiceProtocol.MSG_SERVER_STATS_CHANGED, mServerStats));
        } catch (RemoteException e) {
            // ignored
        }
    }

    /**
     * Consumes the consent result carried by the start intent.
     *
     * <p>The extra is required and must come from a positive consent; the start path only reaches
     * here after {@link MainActivity} received {@code RESULT_OK}.</p>
     */
    @SuppressWarnings("DataFlowIssue")
    @NonNull
    private MediaProjection getMediaProjection(@NonNull final Intent intent) {
        final ActivityResult mediaProjectionResult = intent.getParcelableExtra(ServiceProtocol.EXTRA_MEDIA_PROJECTION_RESULT);
        final MediaProjectionManager mediaProjectionManager = getSystemService(MediaProjectionManager.class);
        return mediaProjectionManager.getMediaProjection(mediaProjectionResult.getResultCode(),
            mediaProjectionResult.getData());
    }

    /**
     * Builds the codec registry, the stream runtime and the stream hub, then starts the encoder.
     *
     * <p>The registry registers JPEG by default and adds H.264 explicitly; H.264 is the primary
     * capture mode and requires an input surface, which the virtual display is pointed at once this
     * method returns. The stream identity uses the configured service id as its session instance.
     * Parameters are the validated projection preferences, so an out-of-range stored value never
     * reaches the encoder.</p>
     */
    private void startStreamPipeline() {
        mIsH264 = !"jpeg".equals(Preferences.get().getProjectionCodec());

        mMetrics = Preferences.get().isMetricsEnabled()
            ? PipelineMetrics.create(NanoClock.MONOTONIC)
            : PipelineMetrics.NONE;
        mCapToOutSuppressed = false;

        mCodecRegistry = CodecRegistry.withDefaults();
        if (mIsH264) {
            mCodecRegistry.register(H264Codec.androidRegistration(mMetrics, NanoClock.MONOTONIC));
        }

        mStreamKey = new StreamKey(Preferences.get().getServiceId(), "screen");
        final StreamParameters parameters = buildProjectionSettings().toStreamParameters();

        mStreamRuntime = new StreamRuntime(mCodecRegistry, mStreamKey,
                buildRequestedDescriptor(1), parameters, TimeSource.MONOTONIC,
                StreamRuntime.DEFAULT_JOIN_POINT_MIN_INTERVAL_MS, mMetrics);
        mStreamHub = new StreamHub(mCodecRegistry, StreamQueueLimits.defaults(),
                TimeSource.MONOTONIC, mMetrics);
        mStreamHub.register(mStreamRuntime);
        mStreamHub.activate(mStreamKey);
        mAudioHub = new AudioHub();
        mStreamRuntime.start();

        // The sampler closes an interval over the encoder output counters; created with the scope so
        // the first five-second line already reports one full period.
        mRateSampler = mMetrics.isEnabled() ? new RateSampler(mMetrics) : null;

        if (mMetrics.isEnabled()) {
            mMainHandler.postDelayed(mMetricsLogRunnable, UPDATE_METRICS_INTERVAL_MS);
        }
    }

    /**
     * Writes one metric line per attached send session.
     *
     * <p>A session records into its own scope, so the process-wide line can never show its send
     * duration, queue residency or queue depth. Reading those scopes here, on the aggregation
     * thread, keeps the probe path lock free: nothing is registered and nothing is written while
     * frames move. A session without a single sample yet is skipped so an idle viewer does not
     * produce a line that says nothing.</p>
     */
    private void logSessionMetrics(@Nullable final StreamHub hub) {
        if (hub == null) {
            return;
        }
        for (final SessionMetricsSnapshot session
            : hub.snapshotSessionMetrics(UPDATE_METRICS_INTERVAL_MS)) {
            if (session.getMetrics().isEmpty()) {
                continue;
            }
            Log.i(mTag, MetricsFormatter.toSessionLogLine(session.getMetrics(),
                session.getClientIdTail()));
        }
    }

    /**
     * Returns the validated projection settings snapshot read from the preferences.
     */
    @NonNull
    private ProjectionSettings buildProjectionSettings() {
        return ProjectionSettings.of(Preferences.get().getProjectionBitrate(),
            Preferences.get().getProjectionFrameRate(), Preferences.get().getProjectionIdrIntervalMs(),
            Preferences.get().getProjectionScale(), Preferences.get().getProjectionQuality(),
            Preferences.get().getProjectionCodec());
    }

    /**
     * Builds the requested descriptor for the current codec mode and capture size.
     */
    @NonNull
    private StreamDescriptor buildRequestedDescriptor(final int configEpoch) {
        return buildRequestedDescriptor(configEpoch, mProjectionSize);
    }

    /**
     * Builds the requested descriptor for the given capture size.
     *
     * <p>The coded and visible size are both the capture size: the split exists only for codecs
     * that pad coded dimensions, and this build renders the aligned capture size one to one, so
     * the viewer never has to crop or stretch it.</p>
     *
     * @param configEpoch configuration epoch the descriptor advances to
     * @param size        capture size in pixels, already aligned to the encoder
     */
    @NonNull
    private StreamDescriptor buildRequestedDescriptor(final int configEpoch,
                                                     @NonNull final Size size) {
        final int width = size.getWidth();
        final int height = size.getHeight();
        if (mIsH264) {
            return StreamDescriptor.builder(mStreamKey, configEpoch, CodecIds.H264,
                            H264Codec.MIME, BitstreamFormat.ANNEX_B)
                    .codedSize(width, height)
                    .visibleSize(width, height)
                    .build();
        }
        return StreamDescriptor.builder(mStreamKey, configEpoch, CodecIds.JPEG, "image/jpeg",
                        BitstreamFormat.JPEG_FRAME)
                .codedSize(width, height)
                .visibleSize(width, height)
                .build();
    }

    /**
     * Maximum window bounds of the current display, scaled by the projection scale preference and
     * aligned to the encoder's size step.
     *
     * <p>The alignment is applied here, before anything uses the size, so the virtual display, the
     * descriptor and the encoder all get the same geometry. A percentage can therefore land on an
     * odd size such as 885x1918 without reaching the encoder, which is what made the hardware
     * encoder refuse the configuration in the 2026-10-21 device test.</p>
     */
    @NonNull
    private Size getProjectionSize() {
        final Rect windowBounds = WindowUtils.getMaximumWindowBounds(this);
        final float scale = mProjectionScale / 100f;
        return new Size(
            mProjectionSizeAlignment.alignWidth((int) (windowBounds.width() * scale)),
            mProjectionSizeAlignment.alignHeight((int) (windowBounds.height() * scale)));
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
        final NotificationCompat.Builder builder =
            new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(getString(R.string.service_notification_title))
                .setContentText(getString(R.string.service_notification_text, mConnectionCount,
                    Formatter.formatFileSize(this, mOutboundDataRate)))
                .setContentIntent(PendingIntent.getActivity(this, 0,
                    new Intent(this, MainActivity.class),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
                .addAction(0, getString(R.string.service_stop_notification_action),
                    PendingIntent.getBroadcast(this, 0,
                        new Intent(ServiceProtocol.ACTION_STOP_SERVICE).setPackage(getPackageName()),
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setOngoing(true)
                .setOnlyAlertOnce(true);
        final String details = notificationSubText();
        if (details != null) {
            builder.setSubText(details);
        }
        return builder.build();
    }

    @SuppressLint("MissingPermission")
    private void updateNotification() {
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification());
    }

    /**
     * Returns the badge and fingerprint guide, or null when neither is available.
     */
    @Nullable
    private String notificationSubText() {
        final String fingerprint = localFingerprintText();
        if (mNewPeerBadge == null) {
            return fingerprint;
        }
        return fingerprint == null ? mNewPeerBadge : mNewPeerBadge + " · " + fingerprint;
    }

    /**
     * Returns the fingerprint guide shown in the share notification, or null when unavailable.
     *
     * <p>The viewer compares this value with the one in its confirmation dialog, so the guide must
     * be the canonical identity fingerprint rendered for a person, never a stored announcement.</p>
     */
    @Nullable
    private String localFingerprintText() {
        try {
            return getString(R.string.tls_service_notification_fingerprint,
                FingerprintFormatter.toDisplay(
                    IdentityManager.get().getIdentity().getFingerprint()));
        } catch (IdentityUnavailableException e) {
            return null;
        }
    }

    /**
     * Floating chip that reports the capture duration while the service runs.
     *
     * <p>All members run on the main thread. {@link #show()} records the start time, applies the
     * stored normalized position, adds the overlay window and starts the one-second ticker;
     * {@link #dismiss()} removes the window and unsubscribes the preference observer. The chip
     * snaps to the nearest horizontal edge after a drag and persists its normalized position. A
     * single tap opens the stop confirmation, a double tap brings {@link MainActivity} to the
     * front.</p>
     */
    private static class ScreenShareChip {
        // elapsedRealtime() when the chip was shown; the duration is derived from it.
        private long mStartTimestamp;

        // Snapshot of the keep-screen-on preference; also updated by the preference observer.
        private boolean mIsKeepScreenOn;

        // Stop confirmation, created on demand as a floating window.
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

        // Animates the snap to the nearest horizontal edge.
        @NonNull
        private final OverScroller mOverScroller;

        @NonNull
        private final GestureDetector mGestureDetector;

        // Scratch rectangle reused by the bounds calculations.
        @NonNull
        private final Rect mLocationBounds = new Rect();

        // Re-aligns itself to the next whole second so the displayed duration ticks steadily.
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

        // Only the keep-screen-on preference affects the chip.
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
                            .setMessage(R.string.screen_share_stop_confirm)
                            .setPositiveButton(android.R.string.ok, (dialog, which) -> mContext.sendBroadcast(
                                new Intent(ServiceProtocol.ACTION_STOP_SERVICE).setPackage(mContext.getPackageName())))
                            .setNegativeButton(android.R.string.cancel, null)
                            .setCancelable(false)
                            .create();
                        mDialog.getWindow().setType(ServiceProtocol.FLOATING_WINDOW_TYPE);
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
            mContext = new ContextThemeWrapper(context, R.style.ThemeOverlay_ScreenShare_Chip);
            mWindowManager = mContext.getSystemService(WindowManager.class);
            mChipView = createChipView();
            mLayoutParams = new WindowManager.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, ServiceProtocol.FLOATING_WINDOW_TYPE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
            mLayoutParams.gravity = Gravity.CENTER;
            mOverScroller = new OverScroller(mContext);
            mGestureDetector = new GestureDetector(mContext, mOnGestureListener);
        }

        /**
         * Adds the overlay window and starts the ticker. Must be called on the main thread after
         * the overlay permission has been granted.
         */
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

        /**
         * Stops the ticker, removes the window and unsubscribes the preference observer.
         */
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

        // Animates the chip to the nearest horizontal edge and stores the normalized position once
        // the scroll settles. Each pass re-posts itself while a scroll is active or is started.
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

        // Maps the stored normalized position onto the current bounds and applies keep-screen-on.
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

        // Re-applies the stored normalized position after the bounds change.
        private void updateLocation() {
            final Point location = getLocation();
            updateLocation(location.x, location.y);
            adjustLocation();
        }

        // Clamps the window position to the allowed center range.
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

        // Maps the normalized preference to window coordinates for the current bounds.
        @NonNull
        private Point getLocation() {
            getLocationBounds(mLocationBounds);
            final PointF location = Preferences.get().getServiceChipLocation();
            return new Point((int) (mLocationBounds.left + mLocationBounds.width() * location.x),
                (int) (mLocationBounds.top + mLocationBounds.height() * location.y));
        }

        // Returns the allowed window centers: the visible display frame moved to its center and
        // inset by half the measured chip size plus the configured margins. Before the first layout
        // the chip is measured with unspecified specs.
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

        /**
         * Builds the chip view and wires its drag handling: movement follows the finger, release
         * snaps to the nearest edge, and taps are delegated to the gesture detector.
         */
        @NonNull
        private Chip createChipView() {
            final Chip chipView = new Chip(mContext) {
                @NonNull
                private final PointF mTouchOffset = new PointF();

                @Override
                protected void onConfigurationChanged(Configuration newConfig) {
                    super.onConfigurationChanged(newConfig);
                    // Bounds change with rotation and multi-window; re-clamp after the new
                    // configuration has been applied.
                    post(() -> updateLocation());
                }

                @SuppressWarnings("SpellCheckingInspection")
                @Override
                protected void onSizeChanged(int w, int h, int oldw, int oldh) {
                    super.onSizeChanged(w, h, oldw, oldh);
                    updateLocation();
                }

                // Raw coordinates are used because the window moves under the finger.
                @SuppressLint("ClickableViewAccessibility")
                @Override
                public boolean onTouchEvent(@NonNull MotionEvent event) {
                    switch (event.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            // Cancel any pending snap so the drag starts from the current position.
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
