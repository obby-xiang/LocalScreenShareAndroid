package com.obby.android.localscreenshare;

import android.app.Application;
import android.util.Log;

public class App extends Application {
    private static App sApp;

    private final String mTag = "App@" + hashCode();

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(mTag, "onCreate: application created");
        sApp = this;
    }

    public static App get() {
        return sApp;
    }

    public static CharSequence getLabel() {
        return sApp.getApplicationInfo().loadLabel(sApp.getPackageManager());
    }
}
