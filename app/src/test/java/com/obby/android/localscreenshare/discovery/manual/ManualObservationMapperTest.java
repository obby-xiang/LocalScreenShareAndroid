package com.obby.android.localscreenshare.discovery.manual;

import com.obby.android.localscreenshare.core.support.MutableClock;
import com.obby.android.localscreenshare.discovery.model.DeviceInfo;
import com.obby.android.localscreenshare.discovery.model.DiscoveryChannel;
import com.obby.android.localscreenshare.discovery.provider.DeviceObservation;
import com.obby.android.localscreenshare.discovery.store.DeviceStore;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link ManualObservationMapper}.
 *
 * <p>The mapper is the only place a restart turns a stored manual entry back into the row the user
 * sees and the connect gate reads, so the tests pin both halves of the rule: an entry that provably
 * completed a TLS handshake keeps its row connectable, and an entry that proves nothing about TLS
 * keeps being refused. The last two cases drive a real {@link DeviceStore}, because the flag the
 * gate consumes is the one the store projects, not the one on the observation.</p>
 */
public class ManualObservationMapperTest {

    private static final String DEVICE_ID = "L7Q3K9M2F4X8B6N1C5V0Z3T7R2";

    private static final String FINGERPRINT =
        "AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:"
            + "AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89";

    @Test
    public void aFingerprintedEntrySeedsItsIdentityAddressAndTransportClaim() {
        final ManualDevice device = ManualDevice.create(DEVICE_ID, FINGERPRINT, "Kitchen",
            "10.0.0.5", 8080);

        final DeviceObservation observation = ManualObservationMapper.toSeedObservation(device);

        assertEquals(DiscoveryChannel.MANUAL, observation.getSource());
        assertEquals(DEVICE_ID, observation.getId());
        assertEquals(FINGERPRINT, observation.getFingerprint());
        assertEquals("Kitchen", observation.getName());
        assertEquals("10.0.0.5", observation.getHost());
        assertEquals(Integer.valueOf(8080), observation.getPort());
        assertEquals(Boolean.TRUE, observation.getSecure());
        // A seed is not a confirmation: the store still decides reachability.
        assertFalse(observation.isLost());
        assertFalse(observation.isGoodbye());
    }

    @Test
    public void anEntryWithoutAFingerprintMakesNoTransportClaim() {
        final ManualDevice device = ManualDevice.create(DEVICE_ID, null, null, "10.0.0.5", 8080);

        final DeviceObservation observation = ManualObservationMapper.toSeedObservation(device);

        assertNull(observation.getFingerprint());
        assertNull(observation.getSecure());
        assertNull(observation.getName());
        assertEquals("10.0.0.5", observation.getHost());
    }

    @Test
    public void blankOptionalFieldsAreNotInvented() {
        final ManualDevice device = ManualDevice.create(DEVICE_ID, "  ", "   ", "10.0.0.5", 8080);

        final DeviceObservation observation = ManualObservationMapper.toSeedObservation(device);

        assertNull(observation.getFingerprint());
        assertNull(observation.getName());
        assertNull(observation.getSecure());
    }

    @Test
    public void secureClaim_onlyAFingerprintClaimsTls() {
        assertEquals(Boolean.TRUE, ManualObservationMapper.secureClaim(FINGERPRINT));
        assertNull(ManualObservationMapper.secureClaim(null));
        assertNull(ManualObservationMapper.secureClaim(""));
        assertNull(ManualObservationMapper.secureClaim("   "));
    }

    @Test
    public void nullDeviceIsRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> ManualObservationMapper.toSeedObservation(null));
    }

    @Test
    public void aRestoredManualEntryIsConnectableRightAfterARestart() {
        final DeviceStore store = new DeviceStore(new MutableClock(1_000L));

        // What seedManualDevices() does with a restored entry.
        store.onObserved(ManualObservationMapper.toSeedObservation(
            ManualDevice.create(DEVICE_ID, FINGERPRINT, "Kitchen", "10.0.0.5", 8080)));

        final DeviceInfo device = store.device(DEVICE_ID);
        // The connect gate refuses a row with secure == false; a restored manual entry must not be
        // refused while it waits for its first re-probe.
        assertTrue(device.isSecure());
        assertEquals(FINGERPRINT, device.getFingerprint());
    }

    @Test
    public void aRestoredEntryWithoutAFingerprintStaysBlocked() {
        final DeviceStore store = new DeviceStore(new MutableClock(1_000L));

        store.onObserved(ManualObservationMapper.toSeedObservation(
            ManualDevice.create(DEVICE_ID, null, null, "10.0.0.5", 8080)));

        final DeviceInfo device = store.device(DEVICE_ID);
        // Nothing was ever confirmed over TLS, so the row keeps reporting an unencrypted peer and
        // the gate keeps refusing it: the seed never upgrades an unknown capability.
        assertFalse(device.isSecure());
        assertNull(device.getFingerprint());
    }
}
