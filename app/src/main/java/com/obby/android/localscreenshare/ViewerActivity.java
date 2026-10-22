package com.obby.android.localscreenshare;

import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.obby.android.localscreenshare.render.ViewerFitPolicy;
import com.obby.android.localscreenshare.render.ViewerHost;
import com.obby.android.localscreenshare.render.ViewerMenuFactory;
import com.obby.android.localscreenshare.render.ViewerPanelView;
import com.obby.android.localscreenshare.render.ViewerTransitionGeometry;
import com.obby.android.localscreenshare.render.ViewerZoomMenuModel;
import com.obby.android.localscreenshare.render.ZoomMode;
import com.obby.android.localscreenshare.service.ScreenViewerService;
import com.obby.android.localscreenshare.settings.Preferences;
import com.obby.android.localscreenshare.support.ServiceProtocol;

import java.util.List;

/**
 * Full-screen display host of a running viewing session.
 *
 * <p><b>Role.</b> The activity owns no network connection and no decoder. It binds to the running
 * {@link ScreenViewerService}, registers itself as the {@link ViewerHost} that receives the decoder
 * output, and gives that role back when it leaves. Expanding the floating window therefore moves one
 * surface and rebuilds one decoder; the session, its authentication and its stream are untouched.
 * Closing the session is still the notification action, the floating confirmation or the stop
 * button of this activity, all of which end it as before.</p>
 *
 * <p><b>Transition.</b> The service captures the floating window's last frame before the switch and
 * hands it over here. The frame is drawn full-screen at the video's content box and animated from
 * the window's rectangle to that box in {@value #TRANSITION_DURATION_MS} ms, then cross-faded with
 * the live picture when the rebuilt decoder presents its first frame. Collapsing reverses it: the
 * last frame shrinks towards the window's rectangle, the service re-adds the window with the same
 * frame, and the activity finishes. The floating window is only removed once both the animation and
 * the first decoded frame are done, so the switch never shows black.</p>
 *
 * <p><b>Lifecycle.</b> Interaction and configuration changes are handled in place: rotation is a
 * layout change, not an activity recreation, so the texture the decoder writes into survives it.
 * Leaving the activity - back, home or another app - collapses the session back into the floating
 * window; returning binds and expands again. When the session ends while the activity is open, the
 * service reports it and the activity finishes without resurrecting the window.</p>
 */
public class ViewerActivity extends AppCompatActivity implements ViewerHost {

    /**
     * Duration of the snapshot transition in both directions.
     */
    private static final long TRANSITION_DURATION_MS = 200L;

    /**
     * Cross-fade that replaces the snapshot with the live picture.
     */
    private static final long FIRST_FRAME_FADE_MS = 150L;

    @NonNull
    private final String mTag = "ViewerActivity@" + hashCode();

    @Nullable
    private ScreenViewerService.ViewerBinder mBinder;

    @Nullable
    private ViewerPanelView mPanelView;

    @Nullable
    private View mControlsView;

    // Last frame of the outgoing host; shown until this activity presents a decoded frame.
    @Nullable
    private Bitmap mTransitionSnapshot;

    // Rectangle of the floating window relative to the display center; null when unknown.
    @Nullable
    private Rect mOverlayBounds;

    // Frame captured in onPause, when the texture may still exist; used by the collapse path.
    @Nullable
    private Bitmap mPauseSnapshot;

    @Nullable
    private AlertDialog mDialog;

    // Latest stream state pushed by the service; used to fit the content box.
    private int mContentWidth;

    private int mContentHeight;

    private int mRotationDegrees;

    private int mScalePercent;

    @NonNull
    private ZoomMode mZoomMode = ZoomMode.FIT;

    private boolean mIsAttached;

    private boolean mIsCollapsing;

    // True between a successful bindService() and its unbindService(); the connection has to be
    // released even when it never delivered a binder.
    private boolean mIsBound;

    private boolean mIsEntryRunning;

    // A first frame arrived while the entry animation was running; fade the snapshot when it ends.
    private boolean mIsSnapshotPending;

    // Set when the service reports that the session ended; the activity must not show the window.
    private boolean mIsSessionEnded;

    // True between onStart and onStop; a binding that completes after onStop must not attach.
    private boolean mIsStarted;

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (!(service instanceof ScreenViewerService.ViewerBinder)) {
                Log.w(mTag, "onServiceConnected: unexpected binder");
                finish();
                return;
            }
            if (!mIsStarted) {
                // The activity left the foreground before the binding arrived; attaching now would
                // move the decoder output into a window nobody sees.
                return;
            }
            final ScreenViewerService.ViewerBinder binder =
                (ScreenViewerService.ViewerBinder) service;
            mBinder = binder;
            if (!binder.attachHost(ViewerActivity.this)) {
                Log.w(mTag, "onServiceConnected: no running session to show");
                mBinder = null;
                finish();
                return;
            }
            mIsAttached = true;
            mTransitionSnapshot = binder.takeSnapshot();
            mOverlayBounds = binder.getOverlayBounds();
            final ViewerPanelView panel = mPanelView;
            if (panel != null) {
                panel.post(ViewerActivity.this::startEntryTransition);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.i(mTag, "onServiceDisconnected: viewer service gone");
            mBinder = null;
            mIsAttached = false;
            finish();
        }
    };

    /**
     * Builds the layout, installs the immersive window flags and starts the back gesture.
     *
     * @param savedInstanceState ignored: the session lives in the service, not in this activity
     */
    @Override
    protected void onCreate(@Nullable final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // The launch succeeded, however long the bind takes afterwards; this is what keeps the
        // service from reporting a failure on a slow device.
        ScreenViewerService.onViewerActivityCreated();

        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        final WindowInsetsControllerCompat controller =
            WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        controller.hide(WindowInsetsCompat.Type.systemBars());
        controller.setSystemBarsBehavior(
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);

        setContentView(R.layout.activity_viewer);
        mPanelView = findViewById(R.id.viewer_panel);
        mControlsView = findViewById(R.id.viewer_controls);
        final ViewerPanelView panel = mPanelView;

        // The base context is attached by now, so the detector can read the display metrics it
        // needs to resolve tap timeouts. Never a field initialiser: the framework constructs the
        // activity before attachBaseContext, so that route throws and kills the process (see the
        // ban in docs/naming-conventions.md, chapter 8).
        final GestureDetector gestureDetector = createGestureDetector();

        final View collapseButton = findViewById(R.id.viewer_collapse_button);
        collapseButton.setOnClickListener(v -> collapse(true, true));
        final View stopButton = findViewById(R.id.viewer_stop_button);
        stopButton.setOnClickListener(v -> sendBroadcast(
            new Intent(ServiceProtocol.ACTION_STOP_CLIENT_SERVICE).setPackage(getPackageName())));

        panel.getTextureView().setOnTouchListener((v, event) -> {
            gestureDetector.onTouchEvent(event);
            return true;
        });
        panel.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight,
                                         oldBottom) -> applyDisplaySize());

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                collapse(true, true);
            }
        });

        reloadDisplayPreferences();
    }

    /**
     * Builds the tap detector for the picture area.
     *
     * <p>Called from {@link #onCreate(Bundle)} and not from a field initialiser: the framework
     * constructs the activity before attaching its base context, and the detector resolves the
     * display metrics in its constructor, so an initialiser would throw
     * {@code NullPointerException} inside {@code Class.newInstance} and kill the process.</p>
     *
     * @return a detector wired to the activity's tap handling
     */
    @NonNull
    private GestureDetector createGestureDetector() {
        return new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(@NonNull MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(@NonNull MotionEvent e) {
                toggleControls();
                return true;
            }

            @Override
            public boolean onDoubleTap(@NonNull MotionEvent e) {
                showZoomModeDialog();
                return true;
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        mIsStarted = true;
        if (mBinder != null) {
            return;
        }
        final Intent intent = new Intent(this, ScreenViewerService.class);
        if (!bindService(intent, mConnection, 0)) {
            Log.w(mTag, "onStart: no running viewer service");
            finish();
            return;
        }
        mIsBound = true;
    }

    /**
     * Gives the output back to the floating window before the activity leaves the foreground.
     */
    @Override
    protected void onStop() {
        mIsStarted = false;
        if (mIsAttached && !mIsSessionEnded) {
            collapse(false, false);
        }
        if (mIsBound) {
            unbindService(mConnection);
            mIsBound = false;
            mBinder = null;
        }
        super.onStop();
    }

    /**
     * Captures the frame while the activity is still visible.
     *
     * <p>The texture may already be gone by {@link #onStop()}, and the floating window that takes
     * over needs a picture to show until its own decoder presented one.</p>
     */
    @Override
    protected void onPause() {
        super.onPause();
        if (mIsAttached && mPauseSnapshot == null && mPanelView != null) {
            mPauseSnapshot = mPanelView.captureSnapshot();
        }
    }

    @Override
    protected void onDestroy() {
        if (mDialog != null) {
            mDialog.dismiss();
            mDialog = null;
        }
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(@NonNull final Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        applyDisplaySize();
    }

    @NonNull
    @Override
    public android.view.TextureView getTextureView() {
        return mPanelView.getTextureView();
    }

    @Override
    public void setContentGeometry(final int visibleWidth, final int visibleHeight,
                                   final int rotationDegrees) {
        mContentWidth = visibleWidth;
        mContentHeight = visibleHeight;
        mRotationDegrees = rotationDegrees;
        mPanelView.setContentGeometry(visibleWidth, visibleHeight, rotationDegrees);
        applyDisplaySize();
    }

    @Override
    public void setSecure(final boolean secure) {
        if (secure) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
    }

    @Override
    public void setHudLines(@NonNull final List<String> lines) {
        mPanelView.setHudLines(lines);
    }

    @Override
    public void showSnapshot(@Nullable final Bitmap snapshot) {
        mPanelView.showSnapshot(snapshot);
    }

    @Override
    public void hideSnapshot() {
        if (mIsEntryRunning) {
            // The live picture is ready but the frame is still growing; the fade would jump from a
            // scaled snapshot to a full-size one, so it waits for the end of the animation.
            mIsSnapshotPending = true;
            return;
        }
        mPanelView.fadeOutSnapshot(FIRST_FRAME_FADE_MS);
    }

    @Nullable
    @Override
    public Bitmap captureSnapshot() {
        return mPanelView.captureSnapshot();
    }

    @Override
    public void showEncodedFrame(@NonNull final Bitmap frame) {
        mPanelView.showFrame(frame);
    }

    @Override
    public void clearEncodedFrame() {
        mPanelView.clearFrame();
    }

    @Override
    public void getContentBox(@NonNull final Rect out) {
        mPanelView.getContentBox(out);
    }

    @Override
    public void onSessionEnded() {
        Log.i(mTag, "onSessionEnded: the session ended while expanded");
        mIsSessionEnded = true;
        finish();
    }

    /**
     * Fits the content box into the activity: the same fit policy as the window, applied to the
     * whole display, so the zoom preset and the viewing percentage mean the same thing in both
     * hosts.
     */
    private void applyDisplaySize() {
        final ViewerPanelView panel = mPanelView;
        if (panel == null || panel.getWidth() <= 0 || panel.getHeight() <= 0) {
            return;
        }
        if (mContentWidth <= 0 || mContentHeight <= 0) {
            // No descriptor yet: the picture fills the activity until the geometry arrives.
            panel.setDisplaySize(0, 0);
            return;
        }
        final int minSize = getResources()
            .getDimensionPixelSize(R.dimen.screen_share_viewer_min_size);
        final ViewerFitPolicy.Fit fit = ViewerFitPolicy.fit(mContentWidth, mContentHeight,
            mRotationDegrees, panel.getWidth(), panel.getHeight(), mScalePercent, minSize,
            mZoomMode);
        panel.setDisplaySize(fit.getWidth(), fit.getHeight());
    }

    /**
     * Reads the persisted viewer settings that the activity applies.
     */
    private void reloadDisplayPreferences() {
        mScalePercent = Preferences.get().getViewerScale();
        mZoomMode = ZoomMode.fromWire(Preferences.get().getViewerZoomMode());
        if (Preferences.get().isViewerKeepScreenOn()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    /**
     * Animates the captured frame from the floating window's rectangle to the content box.
     */
    private void startEntryTransition() {
        if (!mIsAttached) {
            return;
        }
        mIsEntryRunning = false;
        mIsSnapshotPending = false;
        mPauseSnapshot = null;
        final Bitmap snapshot = mTransitionSnapshot;
        mTransitionSnapshot = null;
        final ViewerPanelView panel = mPanelView;
        final Rect bounds = mOverlayBounds;
        if (snapshot == null || bounds == null || panel == null || panel.getWidth() <= 0
            || panel.getHeight() <= 0) {
            finishEntryTransition();
            return;
        }

        panel.showSnapshot(snapshot);
        // The fit depends on the view size, which is known here; a layout pass that has not run yet
        // would otherwise animate towards the full display instead of the letterboxed box.
        applyDisplaySize();
        final Rect source = toViewRect(bounds);
        final Rect target = new Rect();
        panel.getContentBox(target);
        final ViewerTransitionGeometry.Start start = ViewerTransitionGeometry.compute(
            source.left, source.top, source.width(), source.height(),
            target.left, target.top, target.width(), target.height());
        if (!start.isVisible()) {
            finishEntryTransition();
            return;
        }

        final ImageView view = panel.getSnapshotView();
        view.setScaleX(start.getScaleX());
        view.setScaleY(start.getScaleY());
        view.setTranslationX(start.getTranslationX());
        view.setTranslationY(start.getTranslationY());
        mIsEntryRunning = true;
        view.animate()
            .scaleX(1f)
            .scaleY(1f)
            .translationX(0f)
            .translationY(0f)
            .setDuration(TRANSITION_DURATION_MS)
            .setInterpolator(new DecelerateInterpolator())
            .withEndAction(() -> {
                mIsEntryRunning = false;
                if (mIsSnapshotPending) {
                    mIsSnapshotPending = false;
                    panel.fadeOutSnapshot(FIRST_FRAME_FADE_MS);
                }
                finishEntryTransition();
            })
            .start();
    }

    /**
     * Reports the end of the entry phase, which lets the service remove the floating window.
     */
    private void finishEntryTransition() {
        final ScreenViewerService.ViewerBinder binder = mBinder;
        if (binder != null && mIsAttached) {
            binder.finishTransition(this);
        }
    }

    /**
     * Gives the decoder output back to the floating window.
     *
     * <p>The last frame is frozen first, so the window that replaces this activity shows exactly
     * what was on screen. A back gesture animates it down to the window's rectangle; leaving for
     * another app skips the animation, because the activity is not visible for most of it.</p>
     *
     * @param animate     whether to shrink the frame towards the window first
     * @param finishAfter whether to finish the activity once the output moved
     */
    private void collapse(final boolean animate, final boolean finishAfter) {
        if (mIsCollapsing) {
            if (finishAfter && !isFinishing()) {
                finish();
            }
            return;
        }
        final ScreenViewerService.ViewerBinder binder = mBinder;
        final ViewerPanelView panel = mPanelView;
        if (binder == null || !mIsAttached || panel == null) {
            if (finishAfter && !isFinishing()) {
                finish();
            }
            return;
        }
        mIsCollapsing = true;
        Bitmap captured = panel.captureSnapshot();
        if (captured == null) {
            captured = mPauseSnapshot;
        }
        final Bitmap snapshot = captured;
        mPauseSnapshot = null;
        panel.showSnapshot(snapshot);

        final Rect bounds = mOverlayBounds;
        final Rect current = new Rect();
        panel.getContentBox(current);
        if (!animate || bounds == null || current.width() <= 0 || current.height() <= 0) {
            finishCollapse(binder, snapshot, finishAfter);
            return;
        }

        // The snapshot view is laid out at the current content box; the transform that maps it onto
        // the window's rectangle is the animation target.
        final Rect target = toViewRect(bounds);
        final ViewerTransitionGeometry.Start end = ViewerTransitionGeometry.compute(
            target.left, target.top, target.width(), target.height(),
            current.left, current.top, current.width(), current.height());
        if (!end.isVisible()) {
            finishCollapse(binder, snapshot, finishAfter);
            return;
        }

        panel.getSnapshotView().animate()
            .scaleX(end.getScaleX())
            .scaleY(end.getScaleY())
            .translationX(end.getTranslationX())
            .translationY(end.getTranslationY())
            .setDuration(TRANSITION_DURATION_MS)
            .setInterpolator(new AccelerateInterpolator())
            .withEndAction(() -> finishCollapse(binder, snapshot, finishAfter))
            .start();
    }

    /**
     * Hands the output back to the window and optionally finishes the activity.
     */
    private void finishCollapse(@NonNull final ScreenViewerService.ViewerBinder binder,
                                @Nullable final Bitmap snapshot, final boolean finishAfter) {
        binder.detachHost(this, snapshot);
        mIsAttached = false;
        mIsCollapsing = false;
        if (finishAfter && !isFinishing()) {
            finish();
        }
    }

    /**
     * Converts a rectangle expressed relative to the display center into view coordinates.
     *
     * <p>The floating window is centred on the display and positioned by offsets; this activity
     * covers the display, so its own center is that center.</p>
     */
    @NonNull
    private Rect toViewRect(@NonNull final Rect bounds) {
        final ViewerPanelView panel = mPanelView;
        final int centerX = panel.getWidth() / 2;
        final int centerY = panel.getHeight() / 2;
        return new Rect(centerX + bounds.left, centerY + bounds.top,
            centerX + bounds.right, centerY + bounds.bottom);
    }

    /**
     * Shows or hides the small control bar; it stays out of the way while the picture plays.
     */
    private void toggleControls() {
        final View controls = mControlsView;
        if (controls == null) {
            return;
        }
        controls.setVisibility(controls.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
    }

    /**
     * Opens the display menu, the same one the floating window shows.
     *
     * <p>Rows come from {@link ViewerZoomMenuModel}: the fit preset, the actual size, the integer
     * preset, the display-size submenu and the debug-overlay toggle. The trailing row collapses the
     * session back into the floating window.</p>
     */
    private void showZoomModeDialog() {
        final ViewerPanelView panel = mPanelView;
        if (panel == null || panel.getWidth() <= 0 || panel.getHeight() <= 0) {
            return;
        }
        reloadDisplayPreferences();
        final int minSize = getResources()
            .getDimensionPixelSize(R.dimen.screen_share_viewer_min_size);
        final List<ViewerZoomMenuModel.Entry> entries = ViewerZoomMenuModel.build(
            mContentWidth, mContentHeight, mRotationDegrees, panel.getWidth(), panel.getHeight(),
            mScalePercent, minSize, mZoomMode, Preferences.get().isViewerHudEnabled());
        if (mDialog != null) {
            mDialog.dismiss();
        }
        mDialog = ViewerMenuFactory.createZoomDialog(this, entries, mScalePercent,
            R.string.viewer_action_collapse_to_window, false, mMenuListener);
        mDialog.show();
    }

    // Applies the display menu rows; the settings are shared with the floating window.
    @NonNull
    private final ViewerMenuFactory.Listener mMenuListener = new ViewerMenuFactory.Listener() {
        @Override
        public void onZoomModeSelected(@NonNull final ZoomMode mode) {
            if (mZoomMode == mode) {
                return;
            }
            mZoomMode = mode;
            Preferences.get().setViewerZoomMode(mode.wire());
            applyDisplaySize();
        }

        @Override
        public void onScalePercentSelected(final int percent) {
            if (mScalePercent == percent) {
                return;
            }
            mScalePercent = percent;
            Preferences.get().setViewerScale(percent);
            applyDisplaySize();
        }

        @Override
        public void onHudEnabledSelected(final boolean enabled) {
            Preferences.get().setViewerHudEnabled(enabled);
            final ScreenViewerService.ViewerBinder binder = mBinder;
            if (binder != null) {
                binder.refreshHud();
            }
        }

        @Override
        public void onActionSelected() {
            collapse(true, true);
        }
    };
}
