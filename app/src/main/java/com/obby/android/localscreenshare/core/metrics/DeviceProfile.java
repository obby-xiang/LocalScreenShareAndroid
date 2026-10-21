package com.obby.android.localscreenshare.core.metrics;

/**
 * Immutable device-level readings taken next to the pipeline metrics.
 *
 * <p>These values are context, not attribution: they answer "was the device already hot or short of
 * memory while the pipeline stalled", and never claim that a value caused a stall. Every field has
 * a documented unit and an unknown marker, so a reading that the platform does not expose cannot be
 * mistaken for a real zero.</p>
 */
public final class DeviceProfile {

    /**
     * Marker for a reading the platform did not provide.
     */
    public static final long UNKNOWN = Long.MIN_VALUE;

    private final long thermalStatus;

    private final long batteryTemperatureDeciC;

    private final long nativeHeapBytes;

    private final long javaHeapBytes;

    private final long threadCount;

    /**
     * Creates one reading.
     *
     * @param thermalStatus           {@code PowerManager} thermal status, or {@link #UNKNOWN}
     * @param batteryTemperatureDeciC battery temperature in tenths of a degree Celsius, or
     *                                {@link #UNKNOWN}
     * @param nativeHeapBytes         allocated native heap bytes, or {@link #UNKNOWN}
     * @param javaHeapBytes           used Java heap bytes, or {@link #UNKNOWN}
     * @param threadCount             number of live threads in the process, or {@link #UNKNOWN}
     */
    public DeviceProfile(long thermalStatus, long batteryTemperatureDeciC, long nativeHeapBytes,
                         long javaHeapBytes, long threadCount) {
        this.thermalStatus = thermalStatus;
        this.batteryTemperatureDeciC = batteryTemperatureDeciC;
        this.nativeHeapBytes = nativeHeapBytes;
        this.javaHeapBytes = javaHeapBytes;
        this.threadCount = threadCount;
    }

    /**
     * Returns the thermal status, or {@link #UNKNOWN}.
     */
    public long getThermalStatus() {
        return thermalStatus;
    }

    /**
     * Returns the battery temperature in tenths of a degree Celsius, or {@link #UNKNOWN}.
     */
    public long getBatteryTemperatureDeciC() {
        return batteryTemperatureDeciC;
    }

    /**
     * Returns the allocated native heap in bytes, or {@link #UNKNOWN}.
     */
    public long getNativeHeapBytes() {
        return nativeHeapBytes;
    }

    /**
     * Returns the used Java heap in bytes, or {@link #UNKNOWN}.
     */
    public long getJavaHeapBytes() {
        return javaHeapBytes;
    }

    /**
     * Returns the number of live threads in the process, or {@link #UNKNOWN}.
     */
    public long getThreadCount() {
        return threadCount;
    }
}
