package com.obby.android.localscreenshare.discovery.register;

import androidx.annotation.NonNull;

import com.obby.android.localscreenshare.discovery.model.DeviceAddress;
import com.obby.android.localscreenshare.grpc.screenstream.RegisterRequest;
import com.obby.android.localscreenshare.grpc.screenstream.RegisterResponse;
import com.obby.android.localscreenshare.grpc.screenstream.ScreenStreamServiceGrpc;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.okhttp.OkHttpChannelBuilder;
import io.grpc.stub.StreamObserver;

/**
 * {@link RegisterStub} over a plaintext gRPC channel.
 *
 * <p>A channel is created per call and shut down when the call completes, which keeps discovery
 * free of long-lived connections to devices that may never be contacted again. Register traffic is
 * one unary request per merge window, so the channel setup cost is irrelevant next to the media
 * channel that is opened only after the user picks a device.</p>
 */
public final class GrpcRegisterStub implements RegisterStub {

    @NonNull
    @Override
    public void register(@NonNull final DeviceAddress address, @NonNull final RegisterRequest request,
        final long timeoutMs, @NonNull final Callback callback) {
        final ManagedChannel channel;
        try {
            channel = OkHttpChannelBuilder.forAddress(unbracket(address.getHost()), address.getPort())
                .usePlaintext()
                .build();
        } catch (RuntimeException e) {
            callback.onFailure("channel_failed");
            return;
        }

        final AtomicBoolean completed = new AtomicBoolean();
        try {
            ScreenStreamServiceGrpc.newStub(channel)
                .withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS)
                .register(request, new StreamObserver<RegisterResponse>() {
                    @Override
                    public void onNext(final RegisterResponse response) {
                        if (completed.compareAndSet(false, true)) {
                            callback.onResponse(response);
                        }
                    }

                    @Override
                    public void onError(final Throwable t) {
                        channel.shutdownNow();
                        if (completed.compareAndSet(false, true)) {
                            callback.onFailure(Status.fromThrowable(t).getCode().name());
                        }
                    }

                    @Override
                    public void onCompleted() {
                        channel.shutdownNow();
                        if (completed.compareAndSet(false, true)) {
                            callback.onFailure("no_response");
                        }
                    }
                });
        } catch (RuntimeException e) {
            channel.shutdownNow();
            if (completed.compareAndSet(false, true)) {
                callback.onFailure("call_failed");
            }
        }
    }

    @NonNull
    private static String unbracket(@NonNull final String host) {
        return host.startsWith("[") && host.endsWith("]")
            ? host.substring(1, host.length() - 1) : host;
    }
}
