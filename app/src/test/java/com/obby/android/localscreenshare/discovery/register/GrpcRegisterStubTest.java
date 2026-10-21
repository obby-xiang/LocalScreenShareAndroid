package com.obby.android.localscreenshare.discovery.register;

import com.obby.android.localscreenshare.grpc.screenstream.DeviceSummary;
import com.obby.android.localscreenshare.grpc.screenstream.RegisterResponse;
import com.obby.android.localscreenshare.identity.tls.TlsMaterial;

import org.junit.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

public class GrpcRegisterStubTest {

    @Test
    public void acceptedResponseCarriesTheCertificateFingerprint() throws Exception {
        final TlsMaterial material = material();
        final RegisterResponse response = accepted(material.getDeviceId(), "body-fingerprint");

        final RegisterResponse effective = GrpcRegisterStub.withObservedFingerprint(response,
            material.getCertificate());

        assertEquals(material.getFingerprint(), effective.getSelf().getFingerprint());
        assertEquals(material.getDeviceId(), effective.getSelf().getDeviceId());
    }

    @Test
    public void certificateOfAnotherIdIsRejected() throws Exception {
        final TlsMaterial material = material();
        final TlsMaterial other = material();
        final RegisterResponse response = accepted(other.getDeviceId(), "body-fingerprint");

        assertNull(GrpcRegisterStub.withObservedFingerprint(response, material.getCertificate()));
    }

    @Test
    public void rejectedResponseIsPassedThroughUnchanged() throws Exception {
        final TlsMaterial material = material();
        final RegisterResponse response = RegisterResponse.newBuilder()
            .setAccepted(false)
            .setRejectReason("rate_limited")
            .setSelf(DeviceSummary.newBuilder()
                .setDeviceId(material.getDeviceId())
                .setFingerprint("body-fingerprint")
                .build())
            .build();

        assertSame(response,
            GrpcRegisterStub.withObservedFingerprint(response, material.getCertificate()));
    }

    @Test
    public void responseWithoutIdentityIsPassedThroughUnchanged() throws Exception {
        final TlsMaterial material = material();
        final RegisterResponse response = RegisterResponse.newBuilder().setAccepted(true).build();

        assertSame(response,
            GrpcRegisterStub.withObservedFingerprint(response, material.getCertificate()));
    }

    @Test
    public void plaintextCallKeepsTheBodyFingerprint() throws Exception {
        final TlsMaterial material = material();
        final RegisterResponse response = accepted(material.getDeviceId(), "body-fingerprint");

        assertSame(response, GrpcRegisterStub.withObservedFingerprint(response, null));
    }

    private static RegisterResponse accepted(final String deviceId, final String fingerprint) {
        return RegisterResponse.newBuilder()
            .setProtocolVersion(1)
            .setAccepted(true)
            .setSelf(DeviceSummary.newBuilder()
                .setDeviceId(deviceId)
                .setFingerprint(fingerprint)
                .build())
            .build();
    }

    private static TlsMaterial material() throws Exception {
        final KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        final KeyPair keyPair = generator.generateKeyPair();
        return TlsMaterial.fromKeyPair(keyPair);
    }
}
