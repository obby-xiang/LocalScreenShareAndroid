package com.obby.android.localscreenshare.service;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Debug;
import android.os.PowerManager;

import androidx.annotation.NonNull;

import com.obby.android.localscreenshare.core.metrics.DeviceProfile;
import com.obby.android.localscreenshare.core.metrics.DeviceProfileSampler;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

/**
 * Reads the device readings that accompany the pipeline metrics.
 *
 * <p><b>What it reads.</b> The platform thermal status, the battery temperature, the allocated
 * native heap and the used Java heap, and the live thread count of the process. These values are
 * context for the eighth question ("why does it get hot") and never attribution: no value here
 * claims to cause a stall.</p>
 *
 * <p><b>Unknowns.</b> The thermal status exists from API 29; below that, and whenever a reading
 * fails, the field reports {@link DeviceProfile#UNKNOWN} instead of a plausible-looking zero. The
 * thread count is read from {@code /proc/self/status}, which is exact on Android and absent on
 * some JVMs, so its failure also becomes unknown.</p>
 *
 * <p><b>Cost.</b> Called every ten seconds on the main thread: one broadcast lookup (the sticky
 * battery intent is already cached by the framework), two heap queries and one small file read.</p>
 */
public final class AndroidDeviceProfileSampler implements DeviceProfileSampler {

    private static final String PROC_STATUS_PATH = "/proc/self/status";

    private static final String THREADS_PREFIX = "Threads:";

    @NonNull
    private final Context mContext;

    /**
     * Creates a sampler bound to the application context.
     *
     * @param context any context of the process; required
     * @throws IllegalArgumentException if {@code context} is null
     */
    public AndroidDeviceProfileSampler(@NonNull final Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        mContext = context.getApplicationContext();
    }

    @Override
    public DeviceProfile sample() {
        return new DeviceProfile(thermalStatus(), batteryTemperatureDeciC(),
            nativeHeapBytes(), javaHeapBytes(), threadCount());
    }

    private long thermalStatus() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return DeviceProfile.UNKNOWN;
        }
        final PowerManager powerManager = mContext.getSystemService(PowerManager.class);
        if (powerManager == null) {
            return DeviceProfile.UNKNOWN;
        }
        try {
            return powerManager.getCurrentThermalStatus();
        } catch (RuntimeException e) {
            return DeviceProfile.UNKNOWN;
        }
    }

    private long batteryTemperatureDeciC() {
        try {
            final Intent battery = mContext.registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (battery == null) {
                return DeviceProfile.UNKNOWN;
            }
            final int temperature =
                battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
            return temperature == Integer.MIN_VALUE ? DeviceProfile.UNKNOWN : temperature;
        } catch (RuntimeException e) {
            return DeviceProfile.UNKNOWN;
        }
    }

    private long nativeHeapBytes() {
        try {
            return Debug.getNativeHeapAllocatedSize();
        } catch (RuntimeException e) {
            return DeviceProfile.UNKNOWN;
        }
    }

    private long javaHeapBytes() {
        final Runtime runtime = Runtime.getRuntime();
        final long used = runtime.totalMemory() - runtime.freeMemory();
        return used < 0L ? DeviceProfile.UNKNOWN : used;
    }

    private long threadCount() {
        try (BufferedReader reader = new BufferedReader(new FileReader(PROC_STATUS_PATH))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith(THREADS_PREFIX)) {
                    return Long.parseLong(line.substring(THREADS_PREFIX.length()).trim());
                }
            }
            return DeviceProfile.UNKNOWN;
        } catch (IOException | NumberFormatException | SecurityException e) {
            return DeviceProfile.UNKNOWN;
        }
    }
}
