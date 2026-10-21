package com.obby.android.localscreenshare.discovery.register;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.obby.android.localscreenshare.discovery.model.DeviceAddress;
import com.obby.android.localscreenshare.grpc.screenstream.DeviceSummary;
import com.obby.android.localscreenshare.grpc.screenstream.RegisterRequest;
import com.obby.android.localscreenshare.grpc.screenstream.RegisterResponse;
import com.obby.android.localscreenshare.grpc.screenstream.ScreenStreamServiceGrpc;
import com.obby.android.localscreenshare.identity.IdentityUnavailableException;
import com.obby.android.localscreenshare.identity.tls.TlsCertificate;
import com.obby.android.localscreenshare.identity.tls.TlsMaterialHolder;
import com.obby.android.localscreenshare.identity.tls.TlsNames;
import com.obby.android.localscreenshare.settings.Preferences;
import com.obby.android.localscreenshare.trust.DiscoveryTrustManager;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import io.grpc.ChannelCredentials;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.TlsChannelCredentials;
import io.grpc.okhttp.OkHttpChannelBuilder;
import io.grpc.stub.StreamObserver;

/**
 * {@link RegisterStub} over a pinned-TLS gRPC channel.
 *
 * <p>A channel is created per call and shut down when the call completes, which keeps discovery
 * free of long-lived connections to devices that may never be contacted again. The handshake is
 * encrypted with the same authority as the media channel and presents this device's identity
 * certificate, because the peer requires mutual TLS, but it does not pin: discovery runs before the
 * user selects a device, so there is nothing to compare yet. Instead the certificate is observed,
 * and an accepted answer is only returned when the certificate's SPKI id equals the id the peer
 * claimed; the fingerprint of that certificate then replaces the one in the response body, because
 * only the certificate value is cryptographically bound to the key that answered.</p>
 *
 * <p>When the hidden plaintext override is on, the channel falls back to plaintext and no
 * certificate is observed.</p>
 */
public final class GrpcRegisterStub implements RegisterStub {

    private static final String TAG = "GrpcRegisterStub";

    @NonNull
    @Override
    public void register(@NonNull final DeviceAddress address, @NonNull final RegisterRequest request,
        final long timeoutMs, @NonNull final Callback callback) {
        final AtomicReference<TlsCertificate> peerCertificate = new AtomicReference<>();
        final ManagedChannel channel;
        try {
            channel = buildChannel(address, peerCertificate);
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
                        if (!completed.compareAndSet(false, true)) {
                            return;
                        }
                        final TlsCertificate certificate = peerCertificate.get();
                        final RegisterResponse effective =
                            withObservedFingerprint(response, certificate);
                        if (effective == null) {
                            channel.shutdownNow();
                            callback.onFailure("identity_mismatch");
                            return;
                        }
                        if (certificate != null) {
                            callback.onPeerFingerprint(certificate.getFingerprint());
                        }
                        callback.onResponse(effective);
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
    private static ManagedChannel buildChannel(@NonNull final DeviceAddress address,
                                               @NonNull final AtomicReference<TlsCertificate> peer) {
        final boolean isPlaintext = Preferences.get().isTlsAllowPlaintext();
        final ChannelCredentials credentials;
        if (isPlaintext) {
            credentials = InsecureChannelCredentials.create();
        } else {
            final TlsChannelCredentials.Builder tls = TlsChannelCredentials.newBuilder()
                .trustManager(new DiscoveryTrustManager(peer::set));
            try {
                // The peer requires a client certificate, so the probe presents this device's
                // identity; without it the handshake ends with PEER_DID_NOT_RETURN_A_CERTIFICATE
                // and every probe is answered UNAVAILABLE.
                tls.keyManager(TlsMaterialHolder.get().material().getKeyManager());
            } catch (IdentityUnavailableException e) {
                Log.w(TAG, "buildChannel: identity unavailable, the peer will reject the probe", e);
            }
            credentials = tls.build();
        }
        final OkHttpChannelBuilder builder =
            OkHttpChannelBuilder.forAddress(unbracket(address.getHost()), address.getPort(),
                credentials);
        if (!isPlaintext) {
            builder.overrideAuthority(TlsNames.AUTHORITY);
        }
        return builder.build();
    }

    /**
     * Replaces the response fingerprint with the observed certificate value.
     *
     * @return the effective response, or null when the certificate does not belong to the claimed id
     */
    @Nullable
    static RegisterResponse withObservedFingerprint(
        @NonNull final RegisterResponse response, @Nullable final TlsCertificate certificate) {
        if (certificate == null || !response.getAccepted()) {
            return response;
        }
        final DeviceSummary self = response.getSelf();
        if (self.getDeviceId().isEmpty()) {
            return response;
        }
        if (!self.getDeviceId().equals(certificate.getDeviceId())) {
            return null;
        }
        return response.toBuilder()
            .setSelf(self.toBuilder().setFingerprint(certificate.getFingerprint()))
            .build();
    }

    @NonNull
    private static String unbracket(@NonNull final String host) {
        return host.startsWith("[") && host.endsWith("]")
            ? host.substring(1, host.length() - 1) : host;
    }
}
