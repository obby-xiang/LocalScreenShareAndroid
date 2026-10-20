package com.obby.android.localscreenshare.discovery.register;

import androidx.annotation.NonNull;

import com.obby.android.localscreenshare.discovery.model.DeviceAddress;
import com.obby.android.localscreenshare.grpc.screenstream.RegisterRequest;
import com.obby.android.localscreenshare.grpc.screenstream.RegisterResponse;

/**
 * One Register call, abstracted away from the gRPC stub.
 *
 * <p>The interface exists so retry, timeout and deduplication logic can be tested without a
 * network: a fake stub records requests and completes them on demand. Callbacks may be invoked on
 * any thread; {@link RegisterClient} marshals them to its callback executor.</p>
 */
public interface RegisterStub {

    /**
     * Issues one unary call.
     *
     * @param address endpoint to call
     * @param request request body
     * @param timeoutMs deadline applied to the call
     * @param callback receives exactly one response or failure
     */
    void register(@NonNull DeviceAddress address, @NonNull RegisterRequest request, long timeoutMs,
        @NonNull Callback callback);

    /** Completion of one call. */
    interface Callback {
        /** Called with the response, whether accepted or rejected. */
        void onResponse(@NonNull RegisterResponse response);

        /** Called when no response arrived; {@code reason} is a short diagnostic code. */
        void onFailure(@NonNull String reason);
    }
}
