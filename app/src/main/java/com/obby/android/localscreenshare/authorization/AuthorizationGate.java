package com.obby.android.localscreenshare.authorization;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.obby.android.localscreenshare.authorization.store.AuthorizationEntry;
import com.obby.android.localscreenshare.authorization.store.AuthorizationStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * Holds peers that are waiting for an approval and settles their requests.
 *
 * <p>One gate serves the whole server process. A streaming call asks {@link #isAuthorized} as soon
 * as the peer identity is known; an unauthorized identity is parked with {@link #request} and the
 * call's own drain loop polls {@link #poll} until an answer arrives or the approval window elapses.
 * Parking means the peer never joins the stream hub, so a waiting peer is not a viewer: it has no
 * session, receives no frame, and does not keep the encoder awake.</p>
 *
 * <p>The gate owns the two rules that connect the parts: only "always allow" writes the durable
 * set, and a timeout or a disabled feature never does. It also short-circuits every decision when
 * the feature is off, which is what makes turning the switch off return to the old behaviour
 * without touching stored data.</p>
 *
 * <p>Threading: every method is synchronized, and the listener is always called outside the monitor
 * so a listener that posts to a UI thread cannot deadlock the drain. The clock is injected so the
 * approval window is testable and so production can keep using one monotonic source.</p>
 */
public final class AuthorizationGate {

    /**
     * State of one request as seen by the waiting call.
     */
    public enum State {
        /**
         * No answer yet; the caller keeps waiting.
         */
        PENDING,

        /**
         * Allowed for this call only; nothing was written.
         */
        ALLOWED_ONCE,

        /**
         * Allowed and written to the durable set.
         */
        ALLOWED_ALWAYS,

        /**
         * Refused by the server user.
         */
        DENIED,

        /**
         * The approval window elapsed.
         */
        TIMED_OUT
    }

    /**
     * Receives the approval lifecycle so the service can show and withdraw its prompt.
     */
    public interface Listener {
        /**
         * Called when a peer starts waiting.
         */
        void onApprovalRequested(@NonNull ApprovalRequest request);

        /**
         * Called when a request was answered, refused or timed out.
         */
        void onApprovalResolved(@NonNull ApprovalRequest request,
                                @NonNull ApprovalOutcome outcome);

        /**
         * Called when the waiting call went away before an answer; the prompt must be withdrawn.
         */
        void onApprovalCancelled(@NonNull ApprovalRequest request);

        /**
         * Listener that ignores every event.
         */
        Listener NO_OP = new Listener() {
            @Override
            public void onApprovalRequested(@NonNull final ApprovalRequest request) {
            }

            @Override
            public void onApprovalResolved(@NonNull final ApprovalRequest request,
                                           @NonNull final ApprovalOutcome outcome) {
            }

            @Override
            public void onApprovalCancelled(@NonNull final ApprovalRequest request) {
            }
        };
    }

    @NonNull
    private final AuthorizationStore mStore;

    @NonNull
    private final BooleanSupplier mIsEnabled;

    @NonNull
    private final LongSupplier mClockMs;

    @NonNull
    private final Listener mListener;

    private final long mTimeoutMs;

    // Insertion ordered so a prompt can show the oldest waiting request first.
    @NonNull
    private final Map<Long, Pending> mPending = new LinkedHashMap<>();

    private long mNextRequestId = 1L;

    /**
     * Creates a gate with the production approval window.
     *
     * @param store     durable authorization set; required
     * @param isEnabled reads the feature switch on every decision; required
     * @param clockMs   monotonic clock in milliseconds; required
     * @param listener  receiver of the approval lifecycle; required
     * @throws IllegalArgumentException if any argument is null
     */
    public AuthorizationGate(@NonNull final AuthorizationStore store,
                             @NonNull final BooleanSupplier isEnabled,
                             @NonNull final LongSupplier clockMs,
                             @NonNull final Listener listener) {
        this(store, isEnabled, clockMs, listener, AuthorizationPolicy.APPROVAL_TIMEOUT_MS);
    }

    /**
     * Creates a gate with an explicit approval window.
     *
     * @param store     durable authorization set; required
     * @param isEnabled reads the feature switch on every decision; required
     * @param clockMs   monotonic clock in milliseconds; required
     * @param listener  receiver of the approval lifecycle; required
     * @param timeoutMs approval window in milliseconds; must be positive
     * @throws IllegalArgumentException if any argument is null or the window is not positive
     */
    public AuthorizationGate(@NonNull final AuthorizationStore store,
                             @NonNull final BooleanSupplier isEnabled,
                             @NonNull final LongSupplier clockMs,
                             @NonNull final Listener listener, final long timeoutMs) {
        if (timeoutMs <= 0L) {
            throw new IllegalArgumentException("timeoutMs must be positive");
        }
        mStore = Objects.requireNonNull(store, "store");
        mIsEnabled = Objects.requireNonNull(isEnabled, "isEnabled");
        mClockMs = Objects.requireNonNull(clockMs, "clockMs");
        mListener = Objects.requireNonNull(listener, "listener");
        mTimeoutMs = timeoutMs;
    }

    /**
     * Returns whether the feature is currently on.
     */
    public boolean isEnabled() {
        return mIsEnabled.getAsBoolean();
    }

    /**
     * Returns whether this identity may watch without asking.
     *
     * <p>True when the feature is off (the legacy behaviour) or when the durable set holds the same
     * id with the same fingerprint.</p>
     */
    public boolean isAuthorized(@Nullable final String deviceId,
                                @Nullable final String fingerprint) {
        final AuthorizationEntry grant = mStore.find(deviceId);
        final boolean isGranted = grant != null
            && AuthorizationPolicy.isGrantValid(grant.getDeviceId(), grant.getFingerprint(),
                deviceId, fingerprint);
        return AuthorizationPolicy.decide(isEnabled(), isGranted)
            == AuthorizationPolicy.Verdict.ALLOW;
    }

    /**
     * Parks one peer and asks the listener to prompt the user.
     *
     * <p>Callers check {@link #isAuthorized} first; when a peer is authorized after all (a race with
     * a concurrent approval of the same identity) the request resolves to
     * {@link State#ALLOWED_ALWAYS} on the first poll and no prompt is shown.</p>
     *
     * @return the request id to poll
     */
    public long request(@Nullable final String deviceId, @Nullable final String fingerprint,
                        @Nullable final String deviceName) {
        final ApprovalRequest request = new ApprovalRequest(mNextRequestId++,
            deviceId, fingerprint, deviceName, mClockMs.getAsLong());
        boolean isPromptNeeded;
        synchronized (this) {
            final Pending pending = new Pending(request);
            // A peer that became authorized in the meantime (a concurrent approval of the same
            // identity) is let through without a prompt; the request still answers on the next poll.
            pending.state = isAuthorized(request.getDeviceId(), request.getFingerprint())
                ? State.ALLOWED_ALWAYS : null;
            mPending.put(request.getRequestId(), pending);
            isPromptNeeded = pending.state == null;
        }
        if (isPromptNeeded) {
            mListener.onApprovalRequested(request);
        }
        return request.getRequestId();
    }

    /**
     * Returns the state of one request, settling it when the window elapsed or the feature was
     * switched off meanwhile.
     *
     * <p>An unknown request id reports {@link State#TIMED_OUT}: a request that was cancelled or
     * already settled must never turn into a grant.</p>
     */
    @NonNull
    public State poll(final long requestId) {
        ApprovalRequest settledRequest = null;
        ApprovalOutcome settledOutcome = null;
        final State state;
        synchronized (this) {
            final Pending pending = mPending.get(requestId);
            if (pending == null) {
                return State.TIMED_OUT;
            }
            if (pending.state == null && !isEnabled()) {
                // The switch went off while the peer waited: the legacy behaviour applies.
                pending.state = State.ALLOWED_ONCE;
                settledRequest = pending.request;
                settledOutcome = ApprovalOutcome.ALLOW_ONCE;
            }
            if (pending.state == null
                && mClockMs.getAsLong() - pending.request.getRequestedAtMs() >= mTimeoutMs) {
                pending.state = State.TIMED_OUT;
                settledRequest = pending.request;
                settledOutcome = ApprovalOutcome.TIMEOUT;
            }
            if (pending.state == null) {
                return State.PENDING;
            }
            mPending.remove(requestId);
            state = pending.state;
        }
        if (settledRequest != null) {
            mListener.onApprovalResolved(settledRequest, settledOutcome);
        }
        return state;
    }

    /**
     * Answers one waiting request.
     *
     * <p>An "always allow" answer persists the identity, unless the call has no verified identity,
     * in which case it degrades to {@link ApprovalOutcome#ALLOW_ONCE}: there is nothing durable to
     * bind the permission to.</p>
     *
     * @return true when the answer was applied; false for an unknown or already settled request
     */
    public boolean resolve(final long requestId, @NonNull final ApprovalOutcome outcome) {
        Objects.requireNonNull(outcome, "outcome");
        ApprovalRequest resolvedRequest;
        synchronized (this) {
            final Pending pending = mPending.get(requestId);
            if (pending == null || pending.state != null) {
                return false;
            }
            pending.state = applyOutcome(pending.request, outcome);
            resolvedRequest = pending.request;
        }
        mListener.onApprovalResolved(resolvedRequest, outcome);
        return true;
    }

    /**
     * Forgets one waiting request without an answer; called when the call went away.
     */
    public void cancel(final long requestId) {
        final ApprovalRequest cancelled;
        synchronized (this) {
            final Pending pending = mPending.remove(requestId);
            if (pending == null || pending.state != null) {
                return;
            }
            cancelled = pending.request;
        }
        mListener.onApprovalCancelled(cancelled);
    }

    /**
     * Returns the requests that are still waiting, oldest first.
     */
    @NonNull
    public synchronized List<ApprovalRequest> pendingRequests() {
        final List<ApprovalRequest> requests = new ArrayList<>(mPending.size());
        for (final Pending pending : mPending.values()) {
            if (pending.state == null) {
                requests.add(pending.request);
            }
        }
        return requests;
    }

    /**
     * Returns the number of requests that are still waiting.
     */
    public synchronized int getPendingCount() {
        return pendingRequests().size();
    }

    // Applies one answer; called under the monitor. Persisting happens here so the gate stays the
    // single owner of the "who may be remembered" rule.
    @NonNull
    private State applyOutcome(@NonNull final ApprovalRequest request,
                               @NonNull final ApprovalOutcome outcome) {
        switch (outcome) {
            case ALLOW_ALWAYS:
                if (AuthorizationPolicy.shouldPersist(outcome, request.hasVerifiedIdentity())) {
                    // The cast is guarded by hasVerifiedIdentity(); keeping the null checks in one
                    // place is what makes the annotation honest.
                    mStore.authorize(request.getDeviceId(), request.getFingerprint(),
                        request.getDeviceName(), mClockMs.getAsLong());
                    return State.ALLOWED_ALWAYS;
                }
                return State.ALLOWED_ONCE;
            case ALLOW_ONCE:
                return State.ALLOWED_ONCE;
            case DENY:
            case TIMEOUT:
            default:
                return State.DENIED;
        }
    }

    // One waiting request; state is null until an answer, a timeout or a cancellation settles it.
    private static final class Pending {
        @NonNull
        private final ApprovalRequest request;

        private State state;

        private Pending(@NonNull final ApprovalRequest request) {
            this.request = request;
        }
    }
}
