package com.obby.android.localscreenshare.discovery.scan;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * Address-scan budget, manual probe deadline and empty-result hint timing of the discovery
 * pipeline.
 *
 * <p>The scan bounds how many probes run at once and how long one interface scan may take; the
 * manual probe deadline applies to an address the user typed or a favorite. The hint delay is how
 * long an empty list waits before it offers manual address entry.</p>
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ScanConstants {
    /** Maximum probes in flight during one scan. */
    public static final int SCAN_MAX_CONCURRENCY = 50;

    /** Budget of one interface scan, in milliseconds. */
    public static final long SCAN_BUDGET_MS = 8_000L;

    /** Delay after which an empty list offers the manual address entry. */
    public static final long DISCOVERY_EMPTY_MANUAL_HINT_MS = 8_000L;

    /** Deadline of one manual address or favorite probe, in milliseconds. */
    public static final long MANUAL_PROBE_TIMEOUT_MS = 2_000L;
}
