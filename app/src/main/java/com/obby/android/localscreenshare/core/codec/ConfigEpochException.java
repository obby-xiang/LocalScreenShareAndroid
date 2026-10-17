package com.obby.android.localscreenshare.core.codec;

/**
 * Raised when an access unit's configuration epoch does not match the decoder's
 * current configuration epoch.
 */
public final class ConfigEpochException extends IllegalArgumentException {

    private final int expected;
    private final int actual;

    public ConfigEpochException(int expected, int actual) {
        super("config epoch mismatch: expected=" + expected + ", actual=" + actual);
        this.expected = expected;
        this.actual = actual;
    }

    public int getExpected() {
        return expected;
    }

    public int getActual() {
        return actual;
    }
}
