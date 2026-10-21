package com.obby.android.localscreenshare.client;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.obby.android.localscreenshare.trust.PinningTrustManager;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import io.grpc.Status;

/**
 * Structured reason a client stream ended.
 *
 * <p>A termination reaches the client as a throwable, but the reason sits in two different places
 * depending on who ended the stream. A rejection decided by the server carries it as the gRPC
 * status description, because the server writes the reason there on purpose
 * ({@code identity_mismatch}, {@code no active stream}, {@code no common codec is supported by all
 * clients}, {@code session taken over by a newer connection}). A rejection decided during the TLS
 * handshake instead arrives as a transport failure whose cause chain holds the
 * {@code CertificateException} the {@link PinningTrustManager} threw, so the status description is
 * generic and only the cause text names the reason.</p>
 *
 * <p>This class resolves both places into one record. It exists so that every termination is
 * logged with its reason, and so the viewer can tell a refused identity apart from an ordinary
 * disconnect instead of offering a retry that can never pass the same check.</p>
 *
 * <p>Pure JVM: no Android type, no I/O and no logging. {@link ScreenShareClient} owns the log line
 * and {@link com.obby.android.localscreenshare.service.ScreenViewerService} owns the dialog.</p>
 */
public final class StreamFailure {

    /**
     * Reason reported for a stream the peer closed normally.
     */
    public static final String REASON_COMPLETED = "completed";

    /**
     * Reasons that mean the peer's identity was refused rather than the path failed.
     *
     * <p>All four come from {@link PinningTrustManager}: the certificate does not belong to the
     * selected device, the pinned fingerprint changed, no pin exists where one is required, or the
     * peer did not present a usable self-signed certificate. Retrying the same connection cannot
     * change any of them, which is what separates this class of failure from a lost connection.</p>
     */
    @NonNull
    private static final List<String> IDENTITY_REASONS = Collections.unmodifiableList(Arrays.asList(
        PinningTrustManager.REASON_IDENTITY_MISMATCH,
        PinningTrustManager.REASON_IDENTITY_CHANGED,
        PinningTrustManager.REASON_PIN_UNKNOWN,
        PinningTrustManager.REASON_INVALID_CHAIN));

    // Bounds the walk of the cause chain: a wrapped certificate error is two or three levels deep,
    // and a self-referencing or cyclic chain must not hang the transport thread that reports it.
    private static final int MAX_CAUSE_DEPTH = 8;

    @NonNull
    private final String mReason;

    @Nullable
    private final Status.Code mStatusCode;

    @Nullable
    private final String mDescription;

    @Nullable
    private final String mCauseChain;

    private final boolean mIsIdentityRejected;

    private StreamFailure(@NonNull final String reason, @Nullable final Status.Code statusCode,
                          @Nullable final String description, @Nullable final String causeChain,
                          final boolean isIdentityRejected) {
        mReason = reason;
        mStatusCode = statusCode;
        mDescription = description;
        mCauseChain = causeChain;
        mIsIdentityRejected = isIdentityRejected;
    }

    /**
     * Resolves a stream error into its reason, gRPC status and cause chain.
     *
     * <p>The identity reasons are matched against the status description first and the cause chain
     * second, because either side can carry them: the server writes one when it refuses a declared
     * identity above TLS, and the trust manager writes one when it refuses the handshake. Any other
     * failure keeps the server-provided description as its reason, and falls back to the status
     * code when the description is empty, so a log line always names something.</p>
     *
     * @param throwable error delivered by the gRPC response observer; required
     */
    @NonNull
    public static StreamFailure from(@NonNull final Throwable throwable) {
        final Status status = Status.fromThrowable(throwable);
        final Status.Code code = status.getCode();
        final String description = trimToNull(status.getDescription());
        final String causeChain = causeChainOf(throwable);
        final String identityReason = findIdentityReason(description, causeChain);
        if (identityReason != null) {
            return new StreamFailure(identityReason, code, description, causeChain, true);
        }
        final String reason = description != null
            ? description : code.name().toLowerCase(Locale.ROOT);
        return new StreamFailure(reason, code, description, causeChain, false);
    }

    /**
     * Creates the failure of a stream the peer closed normally.
     */
    @NonNull
    public static StreamFailure completed() {
        return new StreamFailure(REASON_COMPLETED, null, null, null, false);
    }

    /**
     * Returns whether the peer's identity was refused.
     *
     * <p>True only for the {@link PinningTrustManager} reasons. The viewer uses this to explain a
     * security refusal instead of offering a retry.</p>
     */
    public boolean isIdentityRejected() {
        return mIsIdentityRejected;
    }

    /**
     * Returns the reason code: an identity reason, the server-provided description, the lowercased
     * status code, or {@link #REASON_COMPLETED}.
     */
    @NonNull
    public String getReason() {
        return mReason;
    }

    /**
     * Returns the gRPC status code, or null when the peer closed the stream normally.
     */
    @Nullable
    public Status.Code getStatusCode() {
        return mStatusCode;
    }

    /**
     * Returns the status description the server sent, or null when it sent none.
     */
    @Nullable
    public String getDescription() {
        return mDescription;
    }

    /**
     * Renders the failure as one English log fragment.
     *
     * <p>Shaped for a single log line after a fixed prefix, so a reason can be grepped without
     * parsing free text: the reason code comes first, followed by the transport values that
     * produced it.</p>
     */
    @NonNull
    public String toLogText() {
        final StringBuilder text = new StringBuilder("reason = ").append(mReason)
            .append(", status = ").append(mStatusCode == null ? "none" : mStatusCode.name());
        if (mDescription != null) {
            text.append(", description = ").append(mDescription);
        }
        if (mCauseChain != null) {
            text.append(", cause = ").append(mCauseChain);
        }
        return text.toString();
    }

    @NonNull
    @Override
    public String toString() {
        return toLogText();
    }

    @Nullable
    private static String findIdentityReason(@Nullable final String description,
                                             @Nullable final String causeChain) {
        for (final String identityReason : IDENTITY_REASONS) {
            if (contains(description, identityReason) || contains(causeChain, identityReason)) {
                return identityReason;
            }
        }
        return null;
    }

    private static boolean contains(@Nullable final String text, @NonNull final String needle) {
        return text != null && text.contains(needle);
    }

    /**
     * Renders the chain of causes as {@code SimpleName: message} entries.
     *
     * <p>{@link Status#fromThrowable(Throwable)} keeps the throwing exception in the status cause,
     * and a handshake failure is wrapped at least once more, so the reason is only reachable by
     * walking. A throwable without a cause yields null instead of an empty fragment.</p>
     */
    @Nullable
    private static String causeChainOf(@NonNull final Throwable throwable) {
        final StringBuilder chain = new StringBuilder();
        Throwable current = throwable;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (chain.length() > 0) {
                chain.append(" <- ");
            }
            chain.append(current.getClass().getSimpleName()).append(": ").append(current.getMessage());
            current = current.getCause();
        }
        return chain.length() == 0 ? null : chain.toString();
    }

    @Nullable
    private static String trimToNull(@Nullable final String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim();
    }
}
