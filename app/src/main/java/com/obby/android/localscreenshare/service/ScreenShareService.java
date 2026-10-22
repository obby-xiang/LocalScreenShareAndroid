package com.obby.android.localscreenshare.service;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AppOpsManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.AudioManager;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
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
import com.obby.android.localscreenshare.authorization.ApprovalOutcome;
import com.obby.android.localscreenshare.authorization.ApprovalRequest;
import com.obby.android.localscreenshare.authorization.AuthorizationGate;
import com.obby.android.localscreenshare.authorization.AuthorizationPolicy;
import com.obby.android.localscreenshare.authorization.store.AuthorizationStore;
import com.obby.android.localscreenshare.R;
import com.obby.android.localscreenshare.core.audio.AacEncoder;
import com.obby.android.localscreenshare.core.audio.AudioAvailability;
import com.obby.android.localscreenshare.core.audio.AudioCaptureException;
import com.obby.android.localscreenshare.core.audio.AudioCapturePolicy;
import com.obby.android.localscreenshare.core.audio.AudioHub;
import com.obby.android.localscreenshare.core.audio.AudioPipeline;
import com.obby.android.localscreenshare.core.audio.AudioSendStats;
import com.obby.android.localscreenshare.core.audio.AudioSessionState;
import com.obby.android.localscreenshare.core.model.AudioAccessUnit;
import com.obby.android.localscreenshare.core.model.AudioDescriptor;
import com.obby.android.localscreenshare.core.audio.mediacodec.AndroidAudioEncoderBridge;
import com.obby.android.localscreenshare.core.audio.mediacodec.PlaybackCaptureSource;
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
import com.obby.android.localscreenshare.core.stream.RuntimeState;
import com.obby.android.localscreenshare.core.stream.SessionMetricsSnapshot;
import com.obby.android.localscreenshare.core.stream.StreamHub;
import com.obby.android.localscreenshare.core.stream.StreamQueueLimits;
import com.obby.android.localscreenshare.core.stream.StreamRuntime;
import com.obby.android.localscreenshare.core.stream.ThermalThrottlePolicy;
import com.obby.android.localscreenshare.core.stream.ViewerCapturePolicy;
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
import com.obby.android.localscreenshare.identity.tls.TlsMaterial;
import com.obby.android.localscreenshare.identity.tls.TlsMaterialHolder;
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
import java.util.List;
import java.util.Objects;
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
 * {@code lss-service-image-reader} thread, keeps only the newest image and throttles frames to the
 * configured rate from the image timestamp. The reader lock covers only what the {@link Image}
 * lifetime requires: the identity check, the throttle decision, acquisition and the RGBA copy into
 * a buffer private to the capture thread. JPEG compression and the frame submission run outside the
 * lock, so a resize or a teardown never waits for an encode, and a generation token checked before
 * submission drops frames that outlived the reader which produced them.</p>
 *
 * <p><b>Viewer-driven capture.</b> A running encoder and display would otherwise keep composing and
 * encoding for an empty audience, so capture follows the viewer count: a one-second watch detaches
 * the display from its output surface once the runtime published its first configuration and no
 * client is attached, and re-attaches it with a join-point request when a client arrives. The
 * encoder stays allocated, so the published descriptor remains valid and the resume costs no
 * reconfiguration. Audio follows the same rule through its own viewer watch.</p>
 *
 * <p><b>Threading.</b> Service callbacks, server listeners, preference changes and all state
 * except the capture state run on the main thread. The capture listener runs on the image reader
 * thread; {@code mImageReaderLock} serializes image acquisition, reader replacement and teardown,
 * so an encode never holds up a resize and no frame is acquired from a closed reader. The server
 * start runs on the {@code lss-server-control} thread, because binding the port and preparing TLS
 * material reach the AndroidKeyStore and blocked the main thread for seconds when the identity key
 * was unusable (N-t6-2); everything after the start returns to the main thread. Apart from that task
 * and the capture thread's frame submission, {@code mServer} is accessed only on the main
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

    // Separate channel because an approval prompt is urgent: the default channel is low importance
    // by design and would never surface as a heads-up notification.
    private static final String NOTIFICATION_CHANNEL_APPROVAL_ID = "lss-authorization";

    private static final String VIRTUAL_DISPLAY_NAME = "LocalScreenShare";

    private static final String IMAGE_READER_HANDLER_THREAD_NAME = "lss-service-image-reader";

    private static final int NOTIFICATION_ID = 1;

    private static final int NOTIFICATION_ID_APPROVAL = 2;

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

    // Cadence of the viewer watch that pauses and resumes audio capture. One second is far below
    // the time a viewer needs to receive and play its first audio frame, and it avoids a new
    // session callback inside StreamHub, which the audio plan keeps unchanged.
    private static final long AUDIO_VIEWER_WATCH_INTERVAL_MS = 1000L;

    // Cadence of the viewer watch that pauses and resumes video capture. The same one-second poll
    // is used because the hub has no session-count callback; the cost is one main-thread no-op per
    // second while a share is running, and the first frame after a resume waits at most this long.
    private static final long VIDEO_VIEWER_WATCH_INTERVAL_MS = 1000L;

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

    // Refreshes everything that names this device when the identity key changes under a running
    // session; the notification is posted because the listener runs on a keystore thread.
    @NonNull
    private final TlsMaterialHolder.IdentityChangeListener mLocalIdentityListener =
        (previousFingerprint, material) -> mMainHandler.post(
            () -> onLocalIdentityChanged(previousFingerprint, material));

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
            logAudioStats();
            if (++mMetricsLogCount % DEVICE_BLOCK_EVERY_N_LOGS == 0) {
                Log.i(mTag, MetricsFormatter.toDeviceLine(MetricsFormatter.Role.SERVER,
                    mDeviceProfileSampler.get().sample()));
            }
            mMainHandler.postDelayed(mMetricsLogRunnable, UPDATE_METRICS_INTERVAL_MS);
        }
    };

    // Keeps audio capture in step with the viewer count: the first viewer starts it and the last
    // one releases the recorder, so a share nobody watches does not hold the capture device.
    @NonNull
    private final Runnable mAudioViewerWatchRunnable = new Runnable() {
        @Override
        public void run() {
            mMainHandler.removeCallbacks(mAudioViewerWatchRunnable);

            if (mAudioHub == null || !Preferences.get().isAudioEnabled()) {
                return;
            }

            syncAudioCapture();
            mMainHandler.postDelayed(mAudioViewerWatchRunnable, AUDIO_VIEWER_WATCH_INTERVAL_MS);
        }
    };

    // Keeps video capture in step with the viewer count. It is separate from the audio watch
    // because it has to run on every API level and regardless of the sound switch, while the audio
    // watch only exists while playback capture can run at all.
    @NonNull
    private final Runnable mVideoViewerWatchRunnable = new Runnable() {
        @Override
        public void run() {
            mMainHandler.removeCallbacks(mVideoViewerWatchRunnable);

            if (mVirtualDisplay == null || mStreamRuntime == null) {
                return;
            }

            syncVideoCapture();
            mMainHandler.postDelayed(mVideoViewerWatchRunnable, VIDEO_VIEWER_WATCH_INTERVAL_MS);
        }
    };

    // Media stream wiring: the runtime owns the encoder and the hub owns per-client sessions. All
    // are assembled on start and torn down before the capture resources are released.
    @Nullable
    private CodecRegistry mCodecRegistry;

    // Volatile because the capture thread snapshots it under the reader lock before encoding, and
    // teardown clears it on the main thread without that lock. The snapshot is dereferenced only
    // after the generation check, so a frame from a retired session cannot reach a new runtime.
    @Nullable
    private volatile StreamRuntime mStreamRuntime;

    @Nullable
    private StreamHub mStreamHub;

    // Optional audio fan-out of the same session; created with the stream pipeline and detached
    // with it. Null means this session carries no audio.
    @Nullable
    private AudioHub mAudioHub;

    // Capture pipeline of the shared sound; null while audio is off or unavailable.
    @Nullable
    private AudioPipeline mAudioPipeline;

    // Why sound is not being shared, for the notification; AVAILABLE while it is.
    @NonNull
    private AudioAvailability mAudioAvailability = AudioAvailability.AVAILABLE;

    // Lifecycle of the audio track; the notification, the switch and this state must agree.
    @NonNull
    private AudioSessionState mAudioSessionState = AudioSessionState.OFF;

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

    // Generation of the reader currently feeding the capture thread; guarded by mImageReaderLock so
    // it always matches mImageReader. A frame keeps its snapshot after leaving the lock and is
    // dropped if the generation moved on while it was being encoded.
    private long mProjectionToken;

    // Invalidates in-flight frames when the reader is replaced or the capture path is torn down.
    @NonNull
    private final ProjectionGeneration mProjectionGeneration = new ProjectionGeneration();

    // Main-thread state; resized on configuration changes.
    @Nullable
    private VirtualDisplay mVirtualDisplay;

    // True while the capture display has no surface because no viewer is attached. Volatile
    // because the capture thread and the encoder-switch path both read it. Paused capture keeps
    // the encoder allocated but stops every composition and frame, which is where the heat comes
    // from; it never outlives the session that paused it.
    private volatile boolean mIsCapturePaused;

    // Floating duration chip; added on start and removed on teardown.
    @Nullable
    private ScreenShareChip mScreenShareChip;

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

    // Thermal status source; non-null only between onCreate and onDestroy, and only on API 29+.
    @Nullable
    private ThermalMonitor mThermalMonitor;

    // Thermal tier currently applied to the capture parameters. NONE means the user's stored values
    // apply; a hotter tier lowers what is applied without touching the preferences.
    @NonNull
    private ThermalThrottlePolicy.Tier mThermalTier = ThermalThrottlePolicy.Tier.NONE;

    // Last status reported by the monitor; kept for the log line even when the tier does not change.
    private int mThermalStatus = ThermalThrottlePolicy.STATUS_NONE;

    // Badge of the most recent first-contact viewer; cleared when no viewer is connected.
    @Nullable
    private String mNewPeerBadge;

    private long mOutboundDataRate;

    // Effective projection values, written on the main thread by the preference observer and the
    // thermal tier. The capture thread applies the JPEG quality and frame rate from the published
    // snapshot instead of reading these fields.
    private boolean mIsProjectionSecure;

    private int mProjectionScale;

    private int mProjectionFrameRate;

    // Snapshot the capture thread applies per frame. Published on the main thread from
    // buildProjectionSettings(), so the JPEG path no longer reads the mutable preference fields
    // directly and one frame cannot mix a new quality with an old frame rate.
    @Nullable
    private volatile ProjectionSettings mCaptureSettings;

    // True for the H.264 surface path, false for the JPEG fallback path; read at start.
    private boolean mIsH264;

    private final String mTag = "ScreenShareService@" + hashCode();

    // Serializes image acquisition with reader replacement and teardown. It also guards the frame
    // timestamp and the generation token.
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
            case ServiceProtocol.MSG_AUTHORIZATION_DECISION:
                onAuthorizationDecision(msg.arg1, outcomeOf(msg.arg2));
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

    // Viewing authorization gate; built in onCreate() because it opens the preference file, which
    // must not happen in a field initializer (component construction rule, naming conventions 8).
    @Nullable
    private AuthorizationGate mAuthorizationGate;

    // The request the user was last asked about; main thread only.
    @Nullable
    private ApprovalRequest mPendingApproval;

    // Gate callbacks arrive on a gRPC transport thread; everything visible happens on main.
    @NonNull
    private final AuthorizationGate.Listener mApprovalListener = new AuthorizationGate.Listener() {
        @Override
        public void onApprovalRequested(@NonNull final ApprovalRequest request) {
            // Logged here rather than only on the main thread: this is the one point that tells a
            // prompt the service never showed apart from a prompt the gate never asked for.
            Log.i(mTag, "mApprovalListener.onApprovalRequested: gate asked for a prompt"
                + ", requestId = " + request.getRequestId()
                + ", verifiedIdentity = " + request.hasVerifiedIdentity());
            mMainHandler.post(() -> ScreenShareService.this.onApprovalRequested(request));
        }

        @Override
        public void onApprovalResolved(@NonNull final ApprovalRequest request,
                                       @NonNull final ApprovalOutcome outcome) {
            mMainHandler.post(() -> onApprovalFinished(request));
        }

        @Override
        public void onApprovalCancelled(@NonNull final ApprovalRequest request) {
            mMainHandler.post(() -> onApprovalFinished(request));
        }
    };

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

    // Runs on the image reader thread. The reader lock covers only what the Image lifetime
    // requires: identity check, throttle decision, acquisition and the pixel copy. The JPEG encode
    // and the submission run outside it, validated against the generation that was current under
    // the lock, so a resize or a teardown never waits for an encode.
    @NonNull
    private final ImageReader.OnImageAvailableListener mOnImageAvailableListener =
        new ImageReader.OnImageAvailableListener() {
            // Capture-thread private pixel buffer, reused across frames and readers of this
            // session. Teardown does not touch it: the capture thread owns it and the collector
            // reclaims it together with the listener.
            @Nullable
            private Bitmap mCaptureBitmap;

            @SuppressLint("RestrictedApi")
            @Override
            public void onImageAvailable(ImageReader reader) {
                if (mImageReader != reader) {
                    return;
                }

                final ProjectionSettings settings = mCaptureSettings;
                if (settings == null) {
                    return;
                }

                final long generation;
                final StreamRuntime runtime;
                final long timestampNs;
                final Bitmap bitmap;

                synchronized (mImageReaderLock) {
                    if (mImageReader != reader) {
                        return;
                    }
                    generation = mProjectionToken;
                    runtime = mStreamRuntime;

                    try (final Image image = reader.acquireLatestImage()) {
                        if (image == null) {
                            return;
                        }

                        // Frame-rate throttle from the published snapshot, so a thermal cap
                        // reaches the JPEG path without rebuilding the reader.
                        if (!CaptureThrottlePolicy.shouldAccept(image.getTimestamp(),
                            mFrameTimestamp, settings.getFrameRate())) {
                            return;
                        }
                        mFrameTimestamp = image.getTimestamp();
                        timestampNs = image.getTimestamp();

                        // Reuse the buffer; only a size change allocates a new bitmap.
                        if (mCaptureBitmap == null
                            || mCaptureBitmap.getWidth() != image.getWidth()
                            || mCaptureBitmap.getHeight() != image.getHeight()) {
                            if (mCaptureBitmap != null) {
                                mCaptureBitmap.recycle();
                            }
                            mCaptureBitmap = Bitmap.createBitmap(image.getWidth(), image.getHeight(),
                                Bitmap.Config.ARGB_8888);
                        }

                        // Plane 0 holds the packed RGBA data; the row stride can be wider than the
                        // image width. The copy stays under the lock because the image is closed
                        // when this block exits.
                        final Image.Plane plane = image.getPlanes()[0];
                        plane.getBuffer().rewind();
                        ImageProcessingUtil.copyByteBufferToBitmap(mCaptureBitmap, plane.getBuffer(),
                            plane.getRowStride());
                        bitmap = mCaptureBitmap;
                    }
                }

                final byte[] data;
                try (final ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, settings.getJpegQuality(), outputStream);
                    data = outputStream.toByteArray();
                } catch (IOException e) {
                    return;
                }

                // A frame that outlived its reader is dropped; a runtime of the next session never
                // sees the previous session's pixels.
                if (runtime == null || !mProjectionGeneration.isCurrent(generation)) {
                    return;
                }
                runtime.submitFrame(timestampNs / 1000L, data);
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
                onIdentityResetCommand();
            } else if (ServiceProtocol.ACTION_AUTHORIZATION_DECISION.equals(intent.getAction())) {
                // The answer comes from an approval-notification button; the id and the answer are
                // extras, and the receiver only resolves requests the gate still knows.
                onAuthorizationDecision(intent.getLongExtra(
                        ServiceProtocol.EXTRA_AUTHORIZATION_REQUEST_ID, -1L),
                    outcomeOf(intent.getStringExtra(ServiceProtocol.EXTRA_AUTHORIZATION_OUTCOME)));
            }
        }
    };

    // Applies projection settings changed from the UI. A null key refreshes every projection
    // value and the capture size; a single key updates only what it affects. Quality and security
    // are read by the next encoded frame, the scale resizes the display, the bitrate is hot-applied
    // and the frame rate and key-frame interval rebuild the encoder because the encoder only reads
    // them when it is created. Every capture-relevant change republishes the snapshot the capture
    // thread reads.
    @NonNull
    private final Preferences.Observer mPreferencesObserver = key -> {
        if (key == null) {
            mIsProjectionSecure = Preferences.get().isProjectionSecure();
            mProjectionScale = Preferences.get().getProjectionScale();
            mProjectionFrameRate = Preferences.get().getProjectionFrameRate();
            buildProjectionSettings();
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
                // Republish: the capture thread applies the JPEG quality from the snapshot, and
                // the JPEG path has no encoder rebuild that would rebuild it.
                buildProjectionSettings();
                break;
            case Preferences.KEY_PROJECTION_FRAME_RATE:
                // Republish as well: the JPEG throttle reads the snapshot, while the H.264 path
                // picks the rate up in the rebuild below.
                mProjectionFrameRate = Preferences.get().getProjectionFrameRate();
                buildProjectionSettings();
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
            case Preferences.KEY_AUDIO_ENABLED:
                // The switch is the single source of intent: turning it on makes capture possible,
                // turning it off stops it and releases the recorder. Whether capture actually runs
                // is decided by the presence of a viewer, so a share nobody watches stays silent
                // and reports that reason instead of holding the capture device.
                stopAudioPipeline();
                mAudioAvailability = AudioAvailability.AVAILABLE;
                if (Preferences.get().isAudioEnabled()) {
                    startAudioViewerWatch();
                } else {
                    stopAudioViewerWatch();
                    updateNotification();
                }
                break;
            case Preferences.KEY_THERMAL_THROTTLE:
                // The switch is the single source of intent: turning it off restores the user's
                // frame rate and capture scale immediately, turning it on applies what the device
                // reports right now. The stored projection values are never touched either way.
                onThermalThrottleSettingChanged();
                break;
            case Preferences.KEY_METRICS_ENABLED:
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
        if (mVirtualDisplay == null || mStreamRuntime == null || mIsCapturePaused) {
            return;
        }
        final Surface surface = mStreamRuntime.getInputSurface();
        if (surface != null) {
            mVirtualDisplay.setSurface(surface);
        }
    }

    /**
     * Points the virtual display at the image reader surface of the JPEG path.
     *
     * <p>The reader and its surface survive a pause, so resuming only re-attaches the existing
     * surface. The lock keeps the call ordered against a resize that replaces the reader.</p>
     */
    private void bindImageReaderSurface() {
        synchronized (mImageReaderLock) {
            if (mVirtualDisplay == null || mImageReader == null) {
                return;
            }
            mVirtualDisplay.setSurface(mImageReader.getSurface());
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
        intentFilter.addAction(ServiceProtocol.ACTION_AUTHORIZATION_DECISION);
        ContextCompat.registerReceiver(this, mBroadcastReceiver, intentFilter, ContextCompat.RECEIVER_NOT_EXPORTED);
        TlsMaterialHolder.get().addIdentityChangeListener(mLocalIdentityListener);

        // The gate reads the switch on every decision, so toggling it takes effect immediately and
        // a switched-off feature never touches the stored authorizations.
        mAuthorizationGate = new AuthorizationGate(AuthorizationStore.get(),
            () -> Preferences.get().isAuthorizationEnabled(),
            TimeSource.MONOTONIC::nowMs, mApprovalListener);

        mIsProjectionSecure = Preferences.get().isProjectionSecure();
        mProjectionScale = Preferences.get().getProjectionScale();
        mProjectionFrameRate = Preferences.get().getProjectionFrameRate();
        mProjectionSizeAlignment = EncoderAlignmentProbe.probe(H264Codec.MIME);
        Log.i(mTag, "onCreate: encoder size alignment "
            + mProjectionSizeAlignment.getWidthAlignment() + "x"
            + mProjectionSizeAlignment.getHeightAlignment());
        Preferences.get().addObserver(mPreferencesObserver);

        // The thermal tier has to be known before the stream pipeline is built, so the first
        // encoder configuration already carries the values the device allows.
        startThermalMonitor();
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
        TlsMaterialHolder.get().removeIdentityChangeListener(mLocalIdentityListener);
        Preferences.get().removeObserver(mPreferencesObserver);
        mMainHandler.removeCallbacks(mEncoderRebuildRunnable);
        mPendingApproval = null;
        NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID_APPROVAL);
        stopThermalMonitor();
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
        server.setAuthorizationGate(mAuthorizationGate);
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

        // Audio is the optional track: it follows the viewer count, so it starts with the first
        // viewer and never prevents the share from running.
        startAudioViewerWatch();

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
            mProjectionToken = mProjectionGeneration.begin();
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

        // Video follows the same rule as audio: capture starts with the display, and the watch
        // pauses it as soon as the first published configuration exists and no viewer is attached.
        startVideoViewerWatch();

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

        // Audio stops before the projection it borrows is released.
        stopAudioViewerWatch();
        stopAudioPipeline();
        mAudioAvailability = AudioAvailability.AVAILABLE;

        // The watch is stopped before the display it drives is released.
        stopVideoViewerWatch();
        mIsCapturePaused = false;

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
        // The generation is invalidated first so a frame that already left the lock is dropped
        // instead of reaching the runtime of the next session.
        synchronized (mImageReaderLock) {
            mProjectionGeneration.invalidate();
            mFrameTimestamp = -1L;

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
     * <p>Called after {@link IdentityManager#reset()} through the package-scoped reset broadcast, and
     * after an identity change was detected while the session was running. The capture pipeline and
     * the gRPC server keep running; only the announced identity changes.</p>
     */
    private void restartAnnouncements() {
        Log.i(mTag, "restartAnnouncements: restart announcements after an identity change");
        stopAnnouncements();
        startAnnouncements();
    }

    /**
     * Applies a user-requested identity reset to the running session.
     *
     * <p>The activity already replaced the key; this only re-reads it, because the TLS material of
     * the session still describes the old one. The read and the certificate signature block, so they
     * run on the control thread. The identity-change listener refreshes the published server info,
     * the announcement and the notification from the new material, which is also what keeps the
     * {@code Register} summary and the handshake certificate on the same identity (N3).</p>
     */
    private void onIdentityResetCommand() {
        try {
            mServerControlExecutor.execute(() -> {
                try {
                    // Detects the replaced key and publishes the new material to the session.
                    TlsMaterialHolder.get().material();
                } catch (IdentityUnavailableException e) {
                    // The old identity stays visible, so the user can still compare fingerprints;
                    // say so instead of silently keeping a stale value.
                    Log.e(mTag, "onIdentityResetCommand: TLS material rebuild failed", e);
                    mMainHandler.post(() -> {
                        restartAnnouncements();
                        updateNotification();
                    });
                }
            });
        } catch (RuntimeException e) {
            // The executor is shut down during teardown; a reset that late changes nothing.
            Log.w(mTag, "onIdentityResetCommand: service is stopping, reset not applied", e);
        }
    }

    /**
     * Rebinds the running session to a replaced identity key.
     *
     * <p>The key is the identity: the announcement, the {@code Register} summary, the NSD record and
     * the handshake certificate all name it, and the notification shows its fingerprint. When the
     * keystore hands out a different key mid-session — a reset, or a replacement the keystore forced
     * — every one of them has to follow immediately, or peers keep a value that no certificate can
     * prove any more.</p>
     */
    private void onLocalIdentityChanged(@NonNull final String previousFingerprint,
        @NonNull final TlsMaterial material) {
        Log.e(mTag, "onLocalIdentityChanged: the identity key changed while running, "
            + "previousFingerprint = " + previousFingerprint + ", fingerprint = "
            + material.getFingerprint());
        if (mServer != null) {
            mServer.onIdentityChanged(material);
        }
        restartAnnouncements();
        updateNotification();
    }

    /**
     * Builds the announcement template of the running session.
     *
     * <p>The fingerprint is deliberately absent and the device name is only present when the user
     * enabled name sharing. The port is the bound gRPC port, which is what a peer uses to call
     * {@code Register}. The id comes from the TLS material of the session, so the announced device is
     * always the one the handshake certificate proves; the material is read from the cache because
     * this runs on the main thread and must not block on the keystore.</p>
     *
     * <p>The transport fields state the transport, nothing else: {@code secure} is true while TLS is
     * on and only the hidden plaintext override turns it off. Whether the shared picture is private
     * is a display protection on the viewer side and travels with the stream descriptor instead
     * (N1).</p>
     */
    @Nullable
    private Announcement buildAnnouncement() {
        final ServerInfo serverInfo = mServerInfo;
        if (serverInfo == null || serverInfo.getPort() <= 0) {
            return null;
        }
        final TlsMaterial material = TlsMaterialHolder.get().getCachedMaterial();
        if (material == null) {
            // The server start builds the material before announcements begin; without it there is no
            // identity to advertise, and naming a different one would be worse than staying quiet.
            Log.w(mTag, "buildAnnouncement: TLS material unavailable");
            return null;
        }
        final DeviceIdentity identity = material.toDeviceIdentity();

        final DiscoverySettings settings = Preferences.get().getDiscoverySettings();
        final boolean isSecureTransport = !Preferences.get().isTlsAllowPlaintext();
        final long nowMs = System.currentTimeMillis();
        return Announcement.builder()
            .id(identity.getId())
            .name(Preferences.get().isShareNameEnabled() ? Preferences.get().getDeviceAlias() : null)
            .role(DeviceRole.SERVER)
            .state(ShareState.LIVE)
            .join(settings.visibilityPolicy(nowMs).joinPolicy(nowMs))
            .secure(isSecureTransport)
            .auth(isSecureTransport ? AuthMode.MTLS : AuthMode.NONE)
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

            // Recreate the reader at the new size, then hand the display the new surface. A paused
            // capture keeps the display detached; the resume path binds the new reader surface.
            // The new generation drops a frame that was still being encoded for the old reader.
            mImageReader.close();
            mImageReader = ImageReader.newInstance(mProjectionSize.getWidth(), mProjectionSize.getHeight(),
                PixelFormat.RGBA_8888, IMAGE_READER_MAX_IMAGES);
            mFrameTimestamp = -1L;
            mProjectionToken = mProjectionGeneration.begin();
            mImageReader.setOnImageAvailableListener(mOnImageAvailableListener, mImageReaderHandler);
            if (!mIsCapturePaused) {
                mVirtualDisplay.setSurface(mImageReader.getSurface());
            }
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

            // A request that is still waiting is replayed, so an activity opened while the prompt
            // is up shows the same three answers instead of nothing.
            final ApprovalRequest pending = mPendingApproval;
            if (pending != null) {
                notifyAuthorizationRequested(messenger, pending);
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

    // The request travels as three display strings plus its id: the activity only renders it, and
    // no identity type has to cross the messenger boundary.
    private void notifyAuthorizationRequested(@NonNull final Messenger messenger,
                                              @NonNull final ApprovalRequest request) {
        final Message message = Message.obtain(null, ServiceProtocol.MSG_AUTHORIZATION_REQUEST);
        final Bundle data = new Bundle();
        data.putLong(ServiceProtocol.EXTRA_AUTHORIZATION_REQUEST_ID, request.getRequestId());
        data.putString(ServiceProtocol.EXTRA_AUTHORIZATION_DEVICE_NAME,
            approvalDeviceName(request));
        data.putString(ServiceProtocol.EXTRA_AUTHORIZATION_DEVICE_ID, approvalDeviceShortId(request));
        data.putString(ServiceProtocol.EXTRA_AUTHORIZATION_FINGERPRINT,
            FingerprintFormatter.toShortDisplay(request.getFingerprint()));
        message.setData(data);
        try {
            messenger.send(message);
        } catch (RemoteException e) {
            // ignored
        }
    }

    private void notifyAuthorizationFinished(@NonNull final Messenger messenger,
                                             final long requestId) {
        final Message message = Message.obtain(null, ServiceProtocol.MSG_AUTHORIZATION_FINISHED);
        message.arg1 = (int) requestId;
        try {
            messenger.send(message);
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
     * Starts the viewer watch that pauses and resumes audio capture; idempotent.
     *
     * <p>It runs immediately so turning the switch on starts capture within the same loop turn, then
     * once per second while the switch is on. A poll keeps the audio plan's promise that
     * {@code StreamHub} stays unchanged: the hub has no session-count callback, and the existing
     * statistics listener fires only when the byte counters change, which is not a viewer change.</p>
     *
     * <p>Below API 29 nothing can be captured, so the watch is not started and the start path
     * publishes that reason once instead; watching a viewer count there would only blame the
     * viewer.</p>
     */
    private void startAudioViewerWatch() {
        mMainHandler.removeCallbacks(mAudioViewerWatchRunnable);
        if (AudioAvailability.forApiLevel(Build.VERSION.SDK_INT) != AudioAvailability.AVAILABLE) {
            startAudioPipeline();
            return;
        }
        mMainHandler.post(mAudioViewerWatchRunnable);
    }

    /**
     * Stops the viewer watch; idempotent.
     */
    private void stopAudioViewerWatch() {
        mMainHandler.removeCallbacks(mAudioViewerWatchRunnable);
    }

    /**
     * Applies the viewer rule to audio capture: no viewer means no capture, the first viewer starts
     * it again.
     *
     * <p>Runs on the main thread, from the switch path and from the viewer check, so the decision,
     * the recorder it controls and the notification it publishes never interleave. The decision
     * itself is {@link AudioCapturePolicy}, which keeps the rule covered by JVM tests; this method
     * only performs it and publishes the reason that matches what the recorder is doing.</p>
     */
    private void syncAudioCapture() {
        if (mAudioHub == null || mMediaProjection == null) {
            return;
        }
        final int viewers = mStreamHub != null ? mStreamHub.getSessionCount() : 0;
        switch (AudioCapturePolicy.decide(viewers, Preferences.get().isAudioEnabled(),
                mAudioSessionState, mAudioAvailability)) {
            case START:
                mAudioAvailability = AudioAvailability.AVAILABLE;
                startAudioPipeline();
                break;
            case STOP:
                stopAudioPipeline();
                mAudioAvailability = AudioAvailability.NO_VIEWERS;
                Log.i(mTag, "syncAudioCapture: audio capture paused, no viewer is connected");
                updateNotification();
                break;
            case PAUSED:
                mAudioAvailability = AudioAvailability.NO_VIEWERS;
                Log.i(mTag, "syncAudioCapture: audio is waiting for a viewer");
                updateNotification();
                break;
            case NONE:
            default:
                break;
        }
    }

    /**
     * Starts the viewer watch that pauses and resumes video capture; idempotent.
     *
     * <p>Started once the capture display exists, because the watch can only pause a display that
     * is already composing. It polls the hub's session count instead of subscribing to a callback:
     * the hub intentionally has no session listener, and the existing statistics listener fires on
     * byte-counter changes, which a viewer leaving does not always produce.</p>
     */
    private void startVideoViewerWatch() {
        mMainHandler.removeCallbacks(mVideoViewerWatchRunnable);
        mMainHandler.post(mVideoViewerWatchRunnable);
    }

    /**
     * Stops the viewer watch; idempotent.
     */
    private void stopVideoViewerWatch() {
        mMainHandler.removeCallbacks(mVideoViewerWatchRunnable);
    }

    /**
     * Applies the viewer rule to video capture: an unwatched share stops composing and encoding,
     * the first viewer starts both again.
     *
     * <p>Runs on the main thread. The decision is {@link ViewerCapturePolicy}, which keeps the rule
     * covered by JVM tests; this method only performs it. Pausing detaches the capture display from
     * its output surface, so the platform stops composing the screen and the encoder receives no
     * input; the encoder itself stays allocated, which is what makes the resume a re-attach instead
     * of a reconfiguration and keeps the published descriptor valid for a joining client.</p>
     */
    private void syncVideoCapture() {
        final StreamRuntime runtime = mStreamRuntime;
        final StreamHub hub = mStreamHub;
        if (runtime == null || hub == null || mVirtualDisplay == null) {
            return;
        }
        switch (ViewerCapturePolicy.decide(hub.getSessionCount(), mIsCapturePaused,
                runtime.getState() == RuntimeState.READY)) {
            case PAUSE:
                pauseCapture();
                break;
            case RESUME:
                resumeCapture();
                break;
            case NONE:
            default:
                break;
        }
    }

    /**
     * Detaches the capture display from its output surface because no viewer is attached.
     *
     * <p>The pause flag is only raised after the detach succeeded, so a platform that refuses the
     * call is retried by the next watch turn instead of leaving a display that keeps composing
     * behind a state that claims it stopped.</p>
     */
    private void pauseCapture() {
        try {
            mVirtualDisplay.setSurface(null);
        } catch (RuntimeException e) {
            Log.w(mTag, "pauseCapture: detaching the capture surface failed", e);
            return;
        }
        mIsCapturePaused = true;
        Log.i(mTag, "capture: event=pause reason=no_viewer sessions=0");
    }

    /**
     * Re-attaches the capture display and asks for a random-access point.
     *
     * <p>The display is pointed at the current encoder input (H.264) or image reader surface
     * (JPEG). The join-point request makes the first unit after the resume an IDR, so the viewer
     * that triggered the resume does not wait for the next scheduled key frame.</p>
     */
    private void resumeCapture() {
        if (mVirtualDisplay == null) {
            return;
        }
        mIsCapturePaused = false;
        if (mIsH264) {
            bindEncoderInputSurface();
        } else {
            bindImageReaderSurface();
        }
        if (mStreamRuntime != null) {
            mStreamRuntime.requestJoinPoint();
        }
        Log.i(mTag, "capture: event=resume reason=viewer_joined");
    }

    /**
     * Starts playback-audio capture when the user asked for it and the device can.
     *
     * <p>Every precondition failure is recorded as an {@link AudioAvailability} instead of an
     * exception: the share keeps running without sound and the notification explains why. The
     * capture reuses the projection that was already granted, so enabling sound never shows a
     * second consent dialog; the only extra requirement is the recording permission.</p>
     *
     * <p>Sample rate negotiation follows the platform's own advice: the device's native output rate
     * first, then 48 kHz, then 44.1 kHz, which is the only rate the platform guarantees. A failed
     * attempt releases its recorder before the next one starts.</p>
     */
    private void startAudioPipeline() {
        if (mAudioPipeline != null || mAudioHub == null || mMediaProjection == null) {
            return;
        }
        if (!Preferences.get().isAudioEnabled()) {
            mAudioAvailability = AudioAvailability.AVAILABLE;
            return;
        }
        transitionAudioState(AudioSessionState.STARTING);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // Playback capture exists from Q onwards. The check stays explicit because lint cannot
            // see the SDK gate inside forCaptureStart, and the classifier still owns the reason.
            markAudioUnavailable(AudioAvailability.forApiLevel(Build.VERSION.SDK_INT));
            return;
        }
        final boolean isPermissionGranted =
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                        == PackageManager.PERMISSION_GRANTED;
        final AudioAvailability startable = AudioAvailability.forCaptureStart(
                Build.VERSION.SDK_INT, isPermissionGranted, isRecordAudioAppOpIgnored());
        if (startable != AudioAvailability.AVAILABLE) {
            // Playback capture exists from Q onwards, and a granted permission whose app-op is
            // still ignored (adb revoke followed by grant) records nothing; both are published as
            // the reason they really are instead of as a device failure.
            markAudioUnavailable(startable);
            return;
        }
        final AudioHub hub = mAudioHub;
        final AudioPipeline.Listener listener = new AudioPipeline.Listener() {
            @Override
            public void onDescriptor(final AudioDescriptor descriptor) {
                hub.onDescriptor(descriptor);
            }

            @Override
            public void onAccessUnit(final AudioAccessUnit accessUnit) {
                hub.onAccessUnit(accessUnit);
            }

            @Override
            public void onUnavailable(final AudioCaptureException failure) {
                mMainHandler.post(() -> {
                    stopAudioPipeline();
                    markAudioUnavailable(AudioAvailability.fromFailure(failure.getReason()));
                });
            }
        };
        final int channels = Preferences.get().getAudioChannels();
        final int bitrate = Preferences.get().getAudioBitrate();
        for (final int sampleRate : outputSampleRates()) {
            final AudioPipeline pipeline = new AudioPipeline(
                    new PlaybackCaptureSource(mMediaProjection),
                    new AacEncoder(new AndroidAudioEncoderBridge()), listener);
            try {
                pipeline.start(1, sampleRate, channels, bitrate);
                mAudioPipeline = pipeline;
                mAudioAvailability = AudioAvailability.AVAILABLE;
                transitionAudioState(AudioSessionState.ACTIVE);
                Log.i(mTag, "startAudioPipeline: capturing at " + sampleRate + " Hz, "
                        + channels + " ch, " + bitrate + " bps");
                updateNotification();
                return;
            } catch (AudioCaptureException | RuntimeException e) {
                pipeline.stop();
                Log.w(mTag, "startAudioPipeline: " + sampleRate + " Hz failed", e);
            }
        }
        markAudioUnavailable(AudioAvailability.CAPTURE_FAILED);
    }

    /**
     * Returns whether the platform currently ignores the recording permission at the app-op level.
     *
     * <p>A runtime permission and the app-op are two gates: {@code adb shell pm revoke} followed by
     * {@code pm grant} leaves the permission granted while the app-op stays {@code ignore}, and a
     * playback capture opened in that state records silence or fails to initialize. Only the mode
     * is read - {@code noteOp} would itself record usage and can throw - and any failure is treated
     * as "not ignored", so an OEM that hides the mode keeps the previous behaviour.</p>
     *
     * <p>Read-only and side-effect free, called on the main thread before a capture attempt.</p>
     */
    private boolean isRecordAudioAppOpIgnored() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return false;
        }
        try {
            final AppOpsManager appOps = getSystemService(AppOpsManager.class);
            if (appOps == null) {
                return false;
            }
            return appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_RECORD_AUDIO,
                    Process.myUid(), getPackageName()) == AppOpsManager.MODE_IGNORED;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Stops the capture pipeline and releases the recorder; idempotent.
     */
    private void stopAudioPipeline() {
        final AudioPipeline pipeline = mAudioPipeline;
        mAudioPipeline = null;
        if (pipeline != null) {
            pipeline.stop();
        }
        transitionAudioState(AudioSessionState.OFF);
    }

    /**
     * Returns the sample rates to try, most preferred first, without duplicates.
     */
    @NonNull
    private int[] outputSampleRates() {
        final int nativeRate;
        try {
            final AudioManager manager = getSystemService(AudioManager.class);
            final String value = manager == null
                    ? null : manager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE);
            nativeRate = value == null ? 0 : Integer.parseInt(value);
        } catch (RuntimeException e) {
            return new int[]{48_000, 44_100};
        }
        if (nativeRate == 44_100) {
            return new int[]{44_100, 48_000};
        }
        if (nativeRate > 0 && nativeRate != 48_000) {
            return new int[]{nativeRate, 48_000, 44_100};
        }
        return new int[]{48_000, 44_100};
    }

    /**
     * Records why sound is unavailable and publishes it in the notification.
     *
     * <p>Audio degradation never stops the service: the screen keeps being shared, and the reason
     * stays visible until the user changes the switch.</p>
     */
    private void markAudioUnavailable(@NonNull final AudioAvailability availability) {
        mAudioAvailability = availability;
        transitionAudioState(AudioSessionState.UNAVAILABLE);
        Log.w(mTag, "audio unavailable: " + availability + " (retryable = "
                + availability.isRetryable() + ")");
        updateNotification();
    }

    /**
     * Moves the audio lifecycle to {@code next}, refusing illegal transitions.
     *
     * <p>Refusing instead of asserting keeps a race (toggle off while capture opens) from reaching
     * a state the switch does not describe; the refusal is logged once so it can be diagnosed.</p>
     */
    private void transitionAudioState(@NonNull final AudioSessionState next) {
        if (mAudioSessionState == next) {
            return;
        }
        if (!mAudioSessionState.canTransitionTo(next)) {
            Log.w(mTag, "audio state transition refused: " + mAudioSessionState + " -> " + next);
            return;
        }
        Log.i(mTag, "audio state: " + mAudioSessionState + " -> " + next);
        mAudioSessionState = next;
    }

    /**
     * Writes one audio summary line per metrics interval.
     *
     * <p>The per-client sessions own their counters, so this line aggregates them instead of
     * touching each session: sent frames, dropped frames and the current state answer "is the
     * viewer getting sound" without a device.
     */
    private void logAudioStats() {
        final AudioHub hub = mAudioHub;
        if (hub == null) {
            return;
        }
        final AudioSendStats stats = hub.getAggregateStats();
        Log.i(mTag, "audio: state = " + mAudioSessionState + ", reason = " + mAudioAvailability
                + ", sessions = " + hub.getSessionCount()
                + ", queued = " + stats.getQueuedFrames()
                + ", sent = " + stats.getSentFrames()
                + ", dropped = " + stats.getDroppedFrames());
    }

    /**
     * Starts watching the platform thermal status where the platform exposes one.
     *
     * <p>The SDK gate is explicit because lint cannot see through a helper that would classify
     * support (the audio start path learned that the hard way). Below API 29 no status exists, so
     * the ladder stays at the user's settings and nothing is logged beyond one informational line;
     * on API 29+ a missing thermal service only disables the ladder, never the share.</p>
     *
     * <p>The status that is already current is applied here, synchronously: the first encoder
     * configuration is created in {@link #onStartCommand} from the effective values, which runs
     * before the listener's main-thread post, so a device that was hot before the share started
     * would otherwise keep the unthrottled parameters until its next status transition.</p>
     */
    private void startThermalMonitor() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.i(mTag, "startThermalMonitor: no thermal status below API 29");
            return;
        }
        final ThermalMonitor monitor = new ThermalMonitor(this, this::onThermalStatusReported);
        if (!monitor.start()) {
            Log.w(mTag, "startThermalMonitor: thermal status listener is unavailable");
            return;
        }
        mThermalMonitor = monitor;
        onThermalStatusChanged(monitor.getCurrentStatus());
    }

    /**
     * Stops watching the thermal status; idempotent.
     *
     * <p>The SDK gate is explicit so lint can see that the call is only reachable on API 29+,
     * where the monitor is created in the first place.</p>
     */
    private void stopThermalMonitor() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return;
        }
        final ThermalMonitor monitor = mThermalMonitor;
        mThermalMonitor = null;
        if (monitor != null) {
            monitor.stop();
        }
    }

    /**
     * Hops a thermal notification onto the main thread, where the tier and its actions live.
     */
    private void onThermalStatusReported(final int status) {
        mMainHandler.post(() -> onThermalStatusChanged(status));
    }

    /**
     * Recomputes the tier for a reported status and applies it when it changed.
     *
     * <p>Only a tier change is acted on: {@code LIGHT} to {@code MODERATE} is still the band in
     * which the user's settings apply, and an unchanged tier rebuilds nothing.</p>
     */
    private void onThermalStatusChanged(final int status) {
        mThermalStatus = status;
        final ThermalThrottlePolicy.Tier next = Preferences.get().isThermalThrottleEnabled()
            ? ThermalThrottlePolicy.tierFor(status) : ThermalThrottlePolicy.Tier.NONE;
        applyThermalTier(next, next == ThermalThrottlePolicy.Tier.NONE ? "recover" : "throttle");
    }

    /**
     * Applies the switch: turning it off restores the user's settings at once, turning it on
     * applies the status the device reports right now.
     */
    private void onThermalThrottleSettingChanged() {
        if (!Preferences.get().isThermalThrottleEnabled()) {
            applyThermalTier(ThermalThrottlePolicy.Tier.NONE, "disabled");
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // No thermal status exists here, so switching the toggle on changes nothing.
            return;
        }
        final ThermalMonitor monitor = mThermalMonitor;
        if (monitor != null) {
            onThermalStatusChanged(monitor.getCurrentStatus());
        }
    }

    /**
     * Moves the applied capture parameters to {@code tier} and publishes the change.
     *
     * <p>The preferences are never written: the tier only changes what
     * {@link #effectiveProjectionFrameRate()} and {@link #effectiveProjectionScale()} return, so
     * the user's values come back the moment the tier returns to {@link
     * ThermalThrottlePolicy.Tier#NONE}. A size change goes through the ordinary resize path, which
     * switches the encoder before it resizes the display; a frame-rate-only change rebuilds the
     * encoder, because the frame-rate hint is read when the encoder is configured. Both run on the
     * main thread, so two thermal transitions cannot race for one configuration epoch.</p>
     */
    private void applyThermalTier(@NonNull final ThermalThrottlePolicy.Tier tier,
                                  @NonNull final String event) {
        if (tier == mThermalTier) {
            return;
        }
        mThermalTier = tier;
        Log.i(mTag, "thermal: event=" + event + " status=" + mThermalStatus + " tier=" + tier
            + " frameRate=" + effectiveProjectionFrameRate() + "/" + mProjectionFrameRate
            + " scale=" + effectiveProjectionScale() + "/" + mProjectionScale);

        // Republish before the rebuild decision: the JPEG throttle reads the forced frame rate from
        // the snapshot and has no encoder rebuild that would refresh it.
        buildProjectionSettings();
        if (mServer != null) {
            updateNotification();
        }
        if (mStreamRuntime == null || mVirtualDisplay == null) {
            // The start path reads the effective values, so a tier that changed while stopped is
            // already part of the next session.
            return;
        }
        if (!Objects.equals(mProjectionSize, getProjectionSize())) {
            updateProjectionSize();
        } else {
            scheduleEncoderRebuild();
        }
    }

    /**
     * Returns the frame rate the capture may use: the user's value capped by the thermal tier.
     */
    private int effectiveProjectionFrameRate() {
        return ThermalThrottlePolicy.capFrameRate(mThermalTier, mProjectionFrameRate);
    }

    /**
     * Returns the capture percentage the display may use: the user's value capped by the thermal
     * tier.
     */
    private int effectiveProjectionScale() {
        return ThermalThrottlePolicy.capScalePercent(mThermalTier, mProjectionScale);
    }

    /**
     * Returns the validated projection settings snapshot read from the preferences.
     *
     * <p>The frame rate and the capture percentage are the effective values: the user's stored
     * choice lowered by the thermal tier, so the encoder is configured with what the device allows
     * right now without the stored preference ever changing.</p>
     *
     * <p>Building also publishes the snapshot for the capture thread, which applies the JPEG
     * quality and the frame rate from it. Every path that changes one of those values calls this
     * method, so the capture thread never has to read the mutable preference fields.</p>
     */
    @NonNull
    private ProjectionSettings buildProjectionSettings() {
        final ProjectionSettings settings = ProjectionSettings.of(
            Preferences.get().getProjectionBitrate(),
            effectiveProjectionFrameRate(), Preferences.get().getProjectionIdrIntervalMs(),
            effectiveProjectionScale(), Preferences.get().getProjectionQuality(),
            Preferences.get().getProjectionCodec());
        mCaptureSettings = settings;
        return settings;
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
     * Maximum window bounds of the current display, scaled by the effective capture percentage and
     * aligned to the encoder's size step.
     *
     * <p>The percentage is the user's value lowered by the thermal tier, so a device that is hot
     * enough to halve the capture area gets the smaller geometry on both the display and the
     * encoder. The alignment is applied here, before anything uses the size, so the virtual display,
     * the descriptor and the encoder all get the same geometry. A percentage can therefore land on
     * an odd size such as 885x1918 without reaching the encoder, which is what made the hardware
     * encoder refuse the configuration in the 2026-10-21 device test.</p>
     */
    @NonNull
    private Size getProjectionSize() {
        final Rect windowBounds = WindowUtils.getMaximumWindowBounds(this);
        final float scale = effectiveProjectionScale() / 100f;
        return new Size(
            mProjectionSizeAlignment.alignWidth((int) (windowBounds.width() * scale)),
            mProjectionSizeAlignment.alignHeight((int) (windowBounds.height() * scale)));
    }

    private void createNotificationChannel() {
        final NotificationChannelCompat notificationChannel =
            new NotificationChannelCompat.Builder(NOTIFICATION_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(getString(R.string.service_notification_channel_name))
                .build();
        final NotificationChannelCompat approvalChannel =
            new NotificationChannelCompat.Builder(NOTIFICATION_CHANNEL_APPROVAL_ID,
                NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName(getString(R.string.authorization_notification_channel_name))
                .setDescription(getString(R.string.authorization_notification_channel_description))
                .build();
        NotificationManagerCompat.from(this)
            .createNotificationChannel(notificationChannel);
        NotificationManagerCompat.from(this).createNotificationChannel(approvalChannel);
    }

    /**
     * Shows the approval prompt for one waiting viewer.
     *
     * <p>The prompt is a high-importance notification, not a dialog, because the person sharing may
     * be in another app or with the screen locked; the buttons carry the three answers and the
     * content intent opens the activity, which shows the same three answers as a dialog. The
     * fingerprint is formatted for a human glance; the full value stays available in the settings
     * sheet of the viewer, which is what the person can compare against.</p>
     */
    @SuppressLint("MissingPermission")
    private void showAuthorizationNotification(@NonNull final ApprovalRequest request) {
        final String name = approvalDeviceName(request);
        final String shortId = approvalDeviceShortId(request);
        final String fingerprint = FingerprintFormatter.toShortDisplay(request.getFingerprint());
        final int timeoutSeconds = (int) (AuthorizationPolicy.APPROVAL_TIMEOUT_MS / 1000L);
        final NotificationCompat.Builder builder =
            new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_APPROVAL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(getString(R.string.authorization_notification_title, name))
                .setContentText(getString(R.string.authorization_notification_text, shortId,
                    fingerprint, timeoutSeconds))
                .setStyle(new NotificationCompat.BigTextStyle().bigText(
                    getString(R.string.authorization_notification_text, shortId, fingerprint,
                        timeoutSeconds)))
                .setContentIntent(PendingIntent.getActivity(this, 0,
                    new Intent(this, MainActivity.class),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setOngoing(true)
                .setOnlyAlertOnce(true);
        final ApprovalOutcome[] outcomes = {
            ApprovalOutcome.ALLOW_ONCE, ApprovalOutcome.ALLOW_ALWAYS, ApprovalOutcome.DENY};
        final int[] labels = {
            R.string.authorization_action_allow_once, R.string.authorization_action_allow_always,
            R.string.authorization_action_deny};
        for (int i = 0; i < outcomes.length; i++) {
            builder.addAction(0, getString(labels[i]),
                authorizationDecisionIntent(request.getRequestId(), outcomes[i], i));
        }
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID_APPROVAL, builder.build());
    }

    // Each answer needs its own PendingIntent: extras are not part of a PendingIntent's identity,
    // so three intents with the same action would otherwise collapse into one.
    @NonNull
    private PendingIntent authorizationDecisionIntent(final long requestId,
                                                     @NonNull final ApprovalOutcome outcome,
                                                     final int slot) {
        final Intent intent = new Intent(ServiceProtocol.ACTION_AUTHORIZATION_DECISION)
            .setPackage(getPackageName())
            .putExtra(ServiceProtocol.EXTRA_AUTHORIZATION_REQUEST_ID, requestId)
            .putExtra(ServiceProtocol.EXTRA_AUTHORIZATION_OUTCOME, outcome.name());
        return PendingIntent.getBroadcast(this, (int) (requestId * 4L + slot), intent,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    // Main thread. A new waiting viewer replaces the current prompt; the notification buttons stay
    // usable because each of them carries its own request id.
    private void onApprovalRequested(@NonNull final ApprovalRequest request) {
        Log.i(mTag, "onApprovalRequested: a viewer is waiting for approval, identities = "
            + request.hasVerifiedIdentity());
        mPendingApproval = request;
        showAuthorizationNotification(request);
        mClientMessengers.forEach(messenger -> notifyAuthorizationRequested(messenger, request));
    }

    // Main thread. The prompt is withdrawn whatever the outcome was: answered, expired or the
    // waiting call went away.
    private void onApprovalFinished(@NonNull final ApprovalRequest request) {
        final ApprovalRequest pending = mPendingApproval;
        if (pending != null && pending.getRequestId() == request.getRequestId()) {
            mPendingApproval = null;
        }
        NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID_APPROVAL);
        mClientMessengers.forEach(
            messenger -> notifyAuthorizationFinished(messenger, request.getRequestId()));
    }

    // Main thread. Answers arriving from the notification or from the activity dialog both land
    // here; the gate ignores an unknown or already settled request.
    private void onAuthorizationDecision(final long requestId,
                                         @Nullable final ApprovalOutcome outcome) {
        final AuthorizationGate gate = mAuthorizationGate;
        if (gate == null || outcome == null) {
            Log.w(mTag, "onAuthorizationDecision: ignoring an unusable decision");
            return;
        }
        final boolean isApplied = gate.resolve(requestId, outcome);
        Log.i(mTag, "onAuthorizationDecision: outcome = " + outcome + ", requestId = " + requestId
            + ", applied = " + isApplied);
    }

    @NonNull
    private String approvalDeviceName(@NonNull final ApprovalRequest request) {
        final String name = request.getDeviceName();
        return name.isEmpty() ? getString(R.string.authorization_unknown_device) : name;
    }

    @NonNull
    private static String approvalDeviceShortId(@NonNull final ApprovalRequest request) {
        final String deviceId = request.getDeviceId();
        return deviceId == null ? "" : deviceId.substring(0,
            Math.min(DeviceIdentity.SHORT_ID_LENGTH, deviceId.length()));
    }

    @Nullable
    private static ApprovalOutcome outcomeOf(@Nullable final String name) {
        if (name == null) {
            return null;
        }
        for (final ApprovalOutcome outcome : ApprovalOutcome.values()) {
            if (outcome.name().equals(name)) {
                return outcome;
            }
        }
        return null;
    }

    @Nullable
    private static ApprovalOutcome outcomeOf(final int ordinal) {
        final ApprovalOutcome[] outcomes = ApprovalOutcome.values();
        return ordinal >= 0 && ordinal < outcomes.length ? outcomes[ordinal] : null;
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
     * Returns the notification's sub-text: the thermal override, the new-peer badge, the audio
     * reason and the fingerprint guide, whichever apply. Null when none does.
     */
    @Nullable
    private String notificationSubText() {
        final String fingerprint = localFingerprintText();
        String details = mNewPeerBadge;
        if (fingerprint != null) {
            details = details == null ? fingerprint : details + " · " + fingerprint;
        }

        // The thermal line is the only visible sign of a temporary override, so it is prepended
        // while a tier is active and disappears as soon as the device cools down.
        final String thermal = thermalStatusText();
        if (thermal != null) {
            details = details == null ? thermal : thermal + " · " + details;
        }

        if (mAudioAvailability != AudioAvailability.AVAILABLE) {
            final String audio = audioReasonText(mAudioAvailability);
            if (audio != null) {
                details = details == null ? audio : audio + " · " + details;
            }
        }
        return details;
    }

    /**
     * Resolves the active thermal tier to a localized line, or null when no tier is active.
     */
    @Nullable
    private String thermalStatusText() {
        switch (mThermalTier) {
            case REDUCED:
                return getString(R.string.service_notification_thermal_reduced,
                    ThermalThrottlePolicy.THROTTLED_FRAME_RATE_LIMIT);
            case MINIMAL:
                return getString(R.string.service_notification_thermal_minimal,
                    ThermalThrottlePolicy.THROTTLED_FRAME_RATE_LIMIT,
                    ThermalThrottlePolicy.MINIMAL_SCALE_LIMIT_PERCENT);
            case NONE:
            default:
                return null;
        }
    }

    /**
     * Resolves the explanation of an unavailable audio state to a resource.
     *
     * <p>The enum lives in {@code core/audio} and therefore cannot reference {@code R}; this method
     * is the single place that binds its reasons to the localized strings.</p>
     */
    @Nullable
    private String audioReasonText(@NonNull final AudioAvailability availability) {
        switch (availability) {
            case API_UNSUPPORTED:
                return getString(R.string.audio_unavailable_api_unsupported);
            case PERMISSION_DENIED:
                return getString(R.string.audio_unavailable_permission_denied);
            case CAPTURE_FAILED:
                return getString(R.string.audio_unavailable_capture_failed);
            case ENCODER_FAILED:
                return getString(R.string.audio_unavailable_encoder_failed);
            case NO_VIEWERS:
                return getString(R.string.audio_status_no_viewers);
            case CLIENT_UNSUPPORTED:
                return getString(R.string.audio_status_client_unsupported);
            case AVAILABLE:
            default:
                return null;
        }
    }

    /**
     * Returns the fingerprint guide shown in the share notification, or null when unavailable.
     *
     * <p>The viewer compares this value with the one in its confirmation dialog, so the guide must be
     * the fingerprint of the certificate the handshake presents. It comes from the TLS material of
     * the session; the loaded identity is only a fallback for the moment before the server built its
     * material.</p>
     */
    @Nullable
    private String localFingerprintText() {
        final TlsMaterial material = TlsMaterialHolder.get().getCachedMaterial();
        if (material != null) {
            return getString(R.string.tls_service_notification_fingerprint,
                FingerprintFormatter.toDisplay(material.getFingerprint()));
        }
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
