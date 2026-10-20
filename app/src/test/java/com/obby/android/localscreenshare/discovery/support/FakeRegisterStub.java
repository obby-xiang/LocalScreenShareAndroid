package com.obby.android.localscreenshare.discovery.support;

import androidx.annotation.NonNull;

import com.obby.android.localscreenshare.discovery.model.DeviceAddress;
import com.obby.android.localscreenshare.discovery.register.RegisterStub;
import com.obby.android.localscreenshare.grpc.screenstream.RegisterRequest;
import com.obby.android.localscreenshare.grpc.screenstream.RegisterResponse;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link RegisterStub} that records every call and lets the test decide its outcome.
 *
 * <p>Nothing is called back until the test asks for it, which is what makes the retry and timeout
 * state machine deterministic: advancing the fake scheduler produces the next attempt, and the
 * test completes exactly the attempt it wants to inspect.</p>
 */
public final class FakeRegisterStub implements RegisterStub {

    @NonNull
    private final List<Call> mCalls = new ArrayList<>();

    @Override
    public void register(@NonNull final DeviceAddress address, @NonNull final RegisterRequest request,
        final long timeoutMs, @NonNull final Callback callback) {
        mCalls.add(new Call(address, request, timeoutMs, callback));
    }

    /** Returns the number of calls issued so far. */
    public int callCount() {
        return mCalls.size();
    }

    /** Returns the last issued call, or null when none was made. */
    public Call last() {
        return mCalls.isEmpty() ? null : mCalls.get(mCalls.size() - 1);
    }

    /** Returns every issued call in order. */
    @NonNull
    public List<Call> calls() {
        return mCalls;
    }

    /** Completes the last call with a response. */
    public void respondLast(@NonNull final RegisterResponse response) {
        last().callback.onResponse(response);
    }

    /** Fails the last call. */
    public void failLast(@NonNull final String reason) {
        last().callback.onFailure(reason);
    }

    /** One recorded call. */
    public static final class Call {
        @NonNull
        public final DeviceAddress address;

        @NonNull
        public final RegisterRequest request;

        public final long timeoutMs;

        @NonNull
        public final Callback callback;

        private Call(@NonNull final DeviceAddress address, @NonNull final RegisterRequest request,
            final long timeoutMs, @NonNull final Callback callback) {
            this.address = address;
            this.request = request;
            this.timeoutMs = timeoutMs;
            this.callback = callback;
        }
    }
}
