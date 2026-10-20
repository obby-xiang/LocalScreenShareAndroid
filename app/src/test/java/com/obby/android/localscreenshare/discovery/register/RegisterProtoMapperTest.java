package com.obby.android.localscreenshare.discovery.register;

import com.obby.android.localscreenshare.discovery.model.AuthMode;
import com.obby.android.localscreenshare.discovery.model.DeviceAddress;
import com.obby.android.localscreenshare.discovery.model.DeviceRole;
import com.obby.android.localscreenshare.discovery.model.DiscoveryChannel;
import com.obby.android.localscreenshare.discovery.model.JoinPolicy;
import com.obby.android.localscreenshare.discovery.model.DeviceInfo;
import com.obby.android.localscreenshare.discovery.model.ShareState;
import com.obby.android.localscreenshare.discovery.provider.DeviceObservation;
import com.obby.android.localscreenshare.grpc.screenstream.DeviceSummary;
import com.obby.android.localscreenshare.grpc.screenstream.RegisterResponse;
import com.obby.android.localscreenshare.transport.ScreenStreamProtoMapper;

import org.junit.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class RegisterProtoMapperTest {

    private static final String DEVICE_ID = "L7Q3K9M2F4X8B6N1C5V0Z3T7R2";

    private static final String FINGERPRINT = "fingerprint-value";

    @Test
    public void summaryCarriesTheDiscoveryFieldsAndAddresses() {
        DeviceSummary summary = RegisterProtoMapper.toSummary(device());

        assertEquals(DEVICE_ID, summary.getDeviceId());
        assertEquals(FINGERPRINT, summary.getFingerprint());
        assertEquals("Living room", summary.getName());
        assertEquals("server", summary.getRole());
        assertEquals("live", summary.getState());
        assertEquals("approval", summary.getJoin());
        assertTrue(summary.getSecure());
        assertEquals("none", summary.getAuth());
        assertEquals(2, summary.getMetadataVersion());
        assertEquals(3L, summary.getCapabilities());
        assertEquals(1, summary.getAddressesCount());
        assertEquals("10.0.0.5", summary.getAddresses(0).getHost());
        assertEquals(8080, summary.getAddresses(0).getPort());
        assertEquals(DeviceAddress.FAMILY_IPV4, summary.getAddresses(0).getFamily());
    }

    @Test
    public void summaryOmitsAbsentOptionalFields() {
        DeviceInfo device = DeviceInfo.builder()
            .id(DEVICE_ID)
            .protocolVersion("1.0")
            .capabilities(1)
            .build();

        DeviceSummary summary = RegisterProtoMapper.toSummary(device);

        assertEquals("", summary.getFingerprint());
        assertEquals("", summary.getName());
        assertEquals(0, summary.getAddressesCount());
    }

    @Test
    public void observationCarriesTheIdentityAndTheCallAddress() {
        DeviceObservation observation = RegisterProtoMapper.toObservation(
            RegisterProtoMapper.toSummary(device()), DiscoveryChannel.MULTICAST, "10.0.0.9", 8080, null);

        assertEquals(DEVICE_ID, observation.getId());
        assertEquals(FINGERPRINT, observation.getFingerprint());
        assertEquals("Living room", observation.getName());
        assertEquals(DeviceRole.SERVER, observation.getRole());
        assertEquals(ShareState.LIVE, observation.getState());
        assertEquals(JoinPolicy.APPROVAL, observation.getJoin());
        assertEquals(AuthMode.NONE, observation.getAuth());
        assertTrue(observation.getSecure());
        assertEquals("10.0.0.9", observation.getHost());
        assertEquals(Integer.valueOf(8080), observation.getPort());
        assertNull(observation.getLastSeenMs());
        assertFalse(observation.isGoodbye());
    }

    @Test
    public void addressesFromTheSummaryBecomeDiscoveryAddressesAndInvalidOnesAreSkipped() {
        DeviceSummary summary = DeviceSummary.newBuilder()
            .setDeviceId(DEVICE_ID)
            .addAddresses(address("10.0.0.5", 8080))
            .addAddresses(address("", 8080))
            .addAddresses(address("10.0.0.6", 0))
            .build();

        List<DeviceAddress> addresses = RegisterProtoMapper.toAddresses(summary, DiscoveryChannel.MULTICAST);

        assertEquals(1, addresses.size());
        assertEquals("10.0.0.5", addresses.get(0).getHost());
        assertEquals(DiscoveryChannel.MULTICAST, addresses.get(0).getChannel());
    }

    @Test
    public void acceptedResponseWithIdentityProvesReachability() {
        RegisterResponse response = RegisterResponse.newBuilder()
            .setProtocolVersion(ScreenStreamProtoMapper.PROTOCOL_VERSION)
            .setAccepted(true)
            .setSelf(DeviceSummary.newBuilder().setDeviceId(DEVICE_ID).build())
            .build();

        assertNull(RegisterProtoMapper.rejectionReason(response));
    }

    @Test
    public void rejectionStopsTheHandshakeWithItsReason() {
        assertEquals("rate_limited", RegisterProtoMapper.rejectionReason(RegisterResponse.newBuilder()
            .setAccepted(false)
            .setRejectReason("rate_limited")
            .build()));
        assertEquals("rejected", RegisterProtoMapper.rejectionReason(RegisterResponse.newBuilder()
            .setAccepted(false)
            .build()));
        assertEquals("no_response", RegisterProtoMapper.rejectionReason(null));
    }

    @Test
    public void acceptedResponseWithoutASelfIdIsNotAConfirmation() {
        RegisterResponse response = RegisterResponse.newBuilder()
            .setProtocolVersion(1)
            .setAccepted(true)
            .build();

        assertEquals("missing_identity", RegisterProtoMapper.rejectionReason(response));
    }

    @Test
    public void versionZeroMeansTheFirstVersionAndNewerVersionsAreIncompatible() {
        assertEquals(ScreenStreamProtoMapper.PROTOCOL_VERSION, RegisterProtoMapper.normalizeVersion(0));
        assertTrue(RegisterProtoMapper.isVersionCompatible(0));
        assertTrue(RegisterProtoMapper.isVersionCompatible(1));
        assertFalse(RegisterProtoMapper.isVersionCompatible(2));

        RegisterResponse future = RegisterResponse.newBuilder()
            .setProtocolVersion(2)
            .setAccepted(true)
            .setSelf(DeviceSummary.newBuilder().setDeviceId(DEVICE_ID).build())
            .build();
        assertEquals("unsupported_version", RegisterProtoMapper.rejectionReason(future));
    }

    @Test
    public void observationFromAnAcceptedSelfKeepsThePeerIdentity() {
        DeviceSummary self = DeviceSummary.newBuilder()
            .setDeviceId(DEVICE_ID)
            .setFingerprint(FINGERPRINT)
            .setRole("server")
            .setState("live")
            .build();

        DeviceObservation observation =
            RegisterProtoMapper.toObservation(self, DiscoveryChannel.MULTICAST, "10.0.0.5", 8080, 5_000L);

        assertNotNull(observation.getFingerprint());
        assertEquals(Long.valueOf(5_000L), observation.getLastSeenMs());
        assertTrue(observation.hasAddress());
    }

    private static com.obby.android.localscreenshare.grpc.screenstream.DeviceAddress address(String host,
        int port) {
        return com.obby.android.localscreenshare.grpc.screenstream.DeviceAddress.newBuilder()
            .setHost(host)
            .setPort(port)
            .setFamily(DeviceAddress.FAMILY_IPV4)
            .build();
    }

    private static DeviceInfo device() {
        return DeviceInfo.builder()
            .id(DEVICE_ID)
            .fingerprint(FINGERPRINT)
            .name("Living room")
            .role(DeviceRole.SERVER)
            .state(ShareState.LIVE)
            .join(JoinPolicy.APPROVAL)
            .secure(true)
            .auth(AuthMode.NONE)
            .protocolVersion("1.0")
            .metadataVersion(2)
            .capabilities(3)
            .addresses(Collections.singletonList(
                new DeviceAddress("10.0.0.5", 8080, DiscoveryChannel.MULTICAST)))
            .build();
    }
}
