package com.obby.android.localscreenshare.discovery.scan;

import androidx.annotation.NonNull;

import com.obby.android.localscreenshare.discovery.model.DeviceAddress;
import com.obby.android.localscreenshare.discovery.model.DiscoveryChannel;
import com.obby.android.localscreenshare.discovery.register.RegisterProtoMapper;
import com.obby.android.localscreenshare.discovery.register.RegisterStub;
import com.obby.android.localscreenshare.grpc.screenstream.DeviceSummary;
import com.obby.android.localscreenshare.grpc.screenstream.RegisterRequest;
import com.obby.android.localscreenshare.grpc.screenstream.RegisterResponse;
import com.obby.android.localscreenshare.transport.ScreenStreamProtoMapper;

import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * {@link AddressProbe} over one unary Register call.
 *
 * <p>Scan and manual probes deliberately do not go through {@link
 * com.obby.android.localscreenshare.discovery.register.RegisterClient}: that client retries,
 * which is right for an announcement but multiplies traffic when hundreds of addresses are
 * probed. One probe is one request with the caller's deadline, sent with {@code announces=false}
 * so a scan cannot trigger reverse Register traffic on every host that happens to answer.</p>
 *
 * <p>Success is the same criterion as the handshake: accepted, compatible version and a usable
 * peer identity.</p>
 */
public final class RegisterAddressProbe implements AddressProbe {

    @NonNull
    private final RegisterStub mStub;

    @NonNull
    private final Supplier<DeviceSummary> mSelfSummarySupplier;

    @NonNull
    private final DiscoveryChannel mChannel;

    /**
     * Creates the probe.
     *
     * @param stub one-call transport
     * @param selfSummarySupplier this device's Register summary; may return null when unavailable
     * @param channel discovery channel recorded on the probed address
     */
    public RegisterAddressProbe(@NonNull final RegisterStub stub,
        @NonNull final Supplier<DeviceSummary> selfSummarySupplier,
        @NonNull final DiscoveryChannel channel) {
        mStub = Objects.requireNonNull(stub, "stub");
        mSelfSummarySupplier = Objects.requireNonNull(selfSummarySupplier, "selfSummarySupplier");
        mChannel = Objects.requireNonNull(channel, "channel");
    }

    @Override
    @NonNull
    public ProbeResult probe(@NonNull final String host, final int port, final long timeoutMs) {
        final DeviceSummary self = mSelfSummarySupplier.get();
        if (self == null) {
            return ProbeResult.failed(host, port, "identity_unavailable");
        }
        if (port <= 0 || port > 0xFFFF) {
            return ProbeResult.failed(host, port, "invalid_port");
        }

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<RegisterResponse> response = new AtomicReference<>();
        final AtomicReference<String> failure = new AtomicReference<>();
        final RegisterRequest request = RegisterRequest.newBuilder()
            .setProtocolVersion(ScreenStreamProtoMapper.PROTOCOL_VERSION)
            .setAnnounces(false)
            .setSelf(self)
            .build();
        try {
            mStub.register(new DeviceAddress(host, port, mChannel), request, timeoutMs,
                new RegisterStub.Callback() {
                    @Override
                    public void onResponse(@NonNull final RegisterResponse value) {
                        response.set(value);
                        latch.countDown();
                    }

                    @Override
                    public void onFailure(@NonNull final String reason) {
                        failure.set(reason);
                        latch.countDown();
                    }
                });
        } catch (RuntimeException e) {
            return ProbeResult.failed(host, port, "call_failed");
        }

        try {
            // The stub applies the same deadline; the extra window only covers callback delivery.
            if (!latch.await(timeoutMs + 200L, TimeUnit.MILLISECONDS)) {
                return ProbeResult.failed(host, port, "timeout");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ProbeResult.failed(host, port, "interrupted");
        }

        final RegisterResponse value = response.get();
        if (value == null) {
            final String reason = failure.get();
            return ProbeResult.failed(host, port, reason == null ? "timeout" : reason);
        }
        final String rejection = RegisterProtoMapper.rejectionReason(value);
        if (rejection != null) {
            return ProbeResult.failed(host, port, rejection);
        }
        return ProbeResult.confirmed(value.getSelf(), host, port);
    }
}
