package com.obby.android.localscreenshare.core.codec;

/**
 * Raised when a configuration epoch does not match the epoch required by the current operation.
 *
 * <p>Unchecked and an {@link IllegalArgumentException} because a mismatched epoch is a caller
 * error. It is used, for example, when a descriptor switch supplies a duplicate or skipped epoch
 * instead of exactly the next one; both the expected and the actual value are retained for
 * diagnostics.</p>
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
