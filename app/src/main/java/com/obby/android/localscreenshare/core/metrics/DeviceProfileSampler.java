package com.obby.android.localscreenshare.core.metrics;

/**
 * Supplies one {@link DeviceProfile} reading.
 *
 * <p>The interface carries only numbers, so the formatting and the absence handling stay testable
 * on the JVM while the platform lookups live in the service layer. Implementations must be cheap
 * enough to call every ten seconds and must never throw: a missing reading becomes
 * {@link DeviceProfile#UNKNOWN} instead of a failure.</p>
 */
public interface DeviceProfileSampler {

    /**
     * Takes one reading.
     *
     * @return the reading, never null
     */
    DeviceProfile sample();
}
