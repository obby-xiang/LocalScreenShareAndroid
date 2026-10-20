package com.obby.android.localscreenshare.discovery.scan;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.obby.android.localscreenshare.grpc.screenstream.DeviceSummary;

/**
 * Outcome of one single-shot address probe.
 *
 * <p>Unlike a Register handshake with retries, a probe is one request with one deadline: a scan
 * touches hundreds of addresses and must not multiply the traffic. A result either carries the
 * peer summary of an accepted Register, or a short reason. The type is immutable so probe
 * implementations can pass it across threads.</p>
 */
public final class ProbeResult {

    private final boolean mConfirmed;

    @Nullable
    private final DeviceSummary mSummary;

    private final String mHost;

    private final int mPort;

    private final String mReason;

    private ProbeResult(final boolean confirmed, @Nullable final DeviceSummary summary,
        @NonNull final String host, final int port, @NonNull final String reason) {
        mConfirmed = confirmed;
        mSummary = summary;
        mHost = host;
        mPort = port;
        mReason = reason;
    }

    /** Creates a successful result carrying the peer summary. */
    @NonNull
    public static ProbeResult confirmed(@NonNull final DeviceSummary summary,
        @NonNull final String host, final int port) {
        return new ProbeResult(true, summary, host, port, "");
    }

    /** Creates a failed result; {@code reason} is a short diagnostic code. */
    @NonNull
    public static ProbeResult failed(@NonNull final String host, final int port,
        @NonNull final String reason) {
        return new ProbeResult(false, null, host, port, reason);
    }

    /** True when the remote accepted the Register and returned its identity. */
    public boolean isConfirmed() {
        return mConfirmed;
    }

    /** Returns the peer summary, or null when the probe failed. */
    @Nullable
    public DeviceSummary getSummary() {
        return mSummary;
    }

    @NonNull
    public String getHost() {
        return mHost;
    }

    public int getPort() {
        return mPort;
    }

    /** Returns the failure reason, or an empty string on success. */
    @NonNull
    public String getReason() {
        return mReason;
    }
}
