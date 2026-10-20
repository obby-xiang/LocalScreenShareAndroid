package com.obby.android.localscreenshare;

import android.app.Application;
import android.util.Log;

/**
 * Application entry point and process-wide singleton holder.
 *
 * <p>The instance is assigned in {@link #onCreate()}, which runs before the first activity or
 * service callback of this process, so component code can rely on {@link #get()} from any
 * lifecycle callback. {@link com.obby.android.localscreenshare.settings.Preferences} and the
 * discovery manager use it to reach application resources. {@link #getLabel()} resolves the
 * user-visible application label for text that explains a permission request.</p>
 */
public class App extends Application {
    // Process-wide instance; null only before onCreate() has returned.
    private static App sApp;

    private final String mTag = "App@" + hashCode();

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(mTag, "onCreate: application created");
        // Published after the base context is attached, so callers of get() always reach a
        // usable Context.
        sApp = this;
    }

    /**
     * Returns the process-wide instance.
     *
     * <p>Non-null in every component callback; only code running before {@link #onCreate()} can
     * observe null.</p>
     */
    public static App get() {
        return sApp;
    }

    /**
     * Resolves the application label from the package manager.
     *
     * <p>Used to name the app in permission explanations; the lookup is synchronous.</p>
     */
    public static CharSequence getLabel() {
        return sApp.getApplicationInfo().loadLabel(sApp.getPackageManager());
    }
}
