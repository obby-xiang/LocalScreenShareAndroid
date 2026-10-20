package com.obby.android.localscreenshare.settings;

import com.obby.android.localscreenshare.discovery.model.JoinPolicy;
import com.obby.android.localscreenshare.discovery.nsd.NsdConstants;
import com.obby.android.localscreenshare.discovery.register.RegisterConstants;
import com.obby.android.localscreenshare.discovery.udp.MulticastConstants;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link DiscoverySettings}.
 */
public class DiscoverySettingsTest {

    @Test
    public void defaultsUseMulticastBalancedRefreshAndVisiblePreset() {
        DiscoverySettings settings = DiscoverySettings.defaults();

        assertTrue(settings.isMulticastEnabled());
        assertEquals(MulticastConstants.MULTICAST_GROUP, settings.getMulticastGroup());
        assertEquals(MulticastConstants.MULTICAST_PORT, settings.getMulticastPort());
        assertEquals(DiscoverySettings.RefreshTier.BALANCED, settings.getRefreshTier());
        assertEquals(VisibilityPolicy.Visibility.VISIBLE, settings.getVisibility());
        assertFalse(settings.isCompatOnly());
        assertTrue(settings.isMulticastChannelEnabled());
        assertTrue(settings.isScanEnabled());
        assertEquals(
            Arrays.asList(NsdConstants.NSD_SERVICE_TYPE, NsdConstants.NSD_SERVICE_TYPE_LEGACY),
            settings.nsdServiceTypes());
        assertEquals(NsdConstants.DEVICE_CAPABILITY_H264 | NsdConstants.DEVICE_CAPABILITY_JPEG,
            settings.capabilities());
    }

    @Test
    public void validGroupAddressIsKept() {
        DiscoverySettings settings = settings("239.255.255.255");

        assertEquals("239.255.255.255", settings.getMulticastGroup());
    }

    @Test
    public void groupOutsideTheMulticastRangeFallsBackToTheDefault() {
        assertEquals(MulticastConstants.MULTICAST_GROUP, settings("223.255.255.255").getMulticastGroup());
        assertEquals(MulticastConstants.MULTICAST_GROUP, settings("240.0.0.1").getMulticastGroup());
        assertEquals(MulticastConstants.MULTICAST_GROUP, settings("192.168.1.1").getMulticastGroup());
    }

    @Test
    public void malformedGroupLiteralFallsBackToTheDefault() {
        assertEquals(MulticastConstants.MULTICAST_GROUP, settings("224.0.0").getMulticastGroup());
        assertEquals(MulticastConstants.MULTICAST_GROUP, settings("224.0.0.256").getMulticastGroup());
        assertEquals(MulticastConstants.MULTICAST_GROUP, settings("224.0.0.a").getMulticastGroup());
        assertEquals(MulticastConstants.MULTICAST_GROUP, settings("  ").getMulticastGroup());
        assertEquals(MulticastConstants.MULTICAST_GROUP, settings(null).getMulticastGroup());
    }

    @Test
    public void groupValidationAcceptsOnlyAdministrativelyScopedLiterals() {
        assertTrue(DiscoverySettings.isValidGroupAddress("224.0.0.0"));
        assertTrue(DiscoverySettings.isValidGroupAddress("224.0.0.167"));
        assertTrue(DiscoverySettings.isValidGroupAddress("239.255.255.255"));
        assertFalse(DiscoverySettings.isValidGroupAddress("223.255.255.255"));
        assertFalse(DiscoverySettings.isValidGroupAddress("240.0.0.0"));
        assertFalse(DiscoverySettings.isValidGroupAddress("example.com"));
        assertFalse(DiscoverySettings.isValidGroupAddress(""));
        assertFalse(DiscoverySettings.isValidGroupAddress(null));
    }

    @Test
    public void portIsValidatedAgainstTheAcceptedRange() {
        assertTrue(DiscoverySettings.isValidPort(DiscoverySettings.MIN_MULTICAST_PORT));
        assertTrue(DiscoverySettings.isValidPort(DiscoverySettings.MAX_MULTICAST_PORT));
        assertFalse(DiscoverySettings.isValidPort(DiscoverySettings.MIN_MULTICAST_PORT - 1));
        assertFalse(DiscoverySettings.isValidPort(DiscoverySettings.MAX_MULTICAST_PORT + 1));

        assertEquals(MulticastConstants.MULTICAST_PORT, port(80).getMulticastPort());
        assertEquals(1234, port(1234).getMulticastPort());
    }

    @Test
    public void refreshTiersCarryTheirDeadlineAndHeartbeatPeriods() {
        DiscoverySettings.RefreshTier lowLatency = DiscoverySettings.RefreshTier.LOW_LATENCY;
        DiscoverySettings.RefreshTier balanced = DiscoverySettings.RefreshTier.BALANCED;
        DiscoverySettings.RefreshTier powerSaver = DiscoverySettings.RefreshTier.POWER_SAVER;

        assertEquals(250L, lowLatency.getRegisterTimeoutMs());
        assertEquals(3_000L, lowLatency.getHeartbeatActiveMs());
        assertEquals(10_000L, lowLatency.getHeartbeatIdleMs());

        assertEquals(RegisterConstants.REGISTER_TIMEOUT_MS, balanced.getRegisterTimeoutMs());
        assertEquals(MulticastConstants.MULTICAST_HEARTBEAT_ACTIVE_MS, balanced.getHeartbeatActiveMs());
        assertEquals(MulticastConstants.MULTICAST_HEARTBEAT_IDLE_MS, balanced.getHeartbeatIdleMs());

        assertEquals(1_000L, powerSaver.getRegisterTimeoutMs());
        assertEquals(10_000L, powerSaver.getHeartbeatActiveMs());
        assertEquals(30_000L, powerSaver.getHeartbeatIdleMs());
    }

    @Test
    public void refreshTierParsingAcceptsWireValuesAndNames() {
        assertEquals(DiscoverySettings.RefreshTier.LOW_LATENCY,
            DiscoverySettings.RefreshTier.fromWire("low_latency"));
        assertEquals(DiscoverySettings.RefreshTier.POWER_SAVER,
            DiscoverySettings.RefreshTier.fromWire("POWER_SAVER"));
        assertEquals(DiscoverySettings.RefreshTier.POWER_SAVER,
            DiscoverySettings.RefreshTier.fromWire("power_saver "));
        assertEquals(DiscoverySettings.RefreshTier.BALANCED,
            DiscoverySettings.RefreshTier.fromWire("unknown"));
        assertEquals(DiscoverySettings.RefreshTier.BALANCED,
            DiscoverySettings.RefreshTier.fromWire(""));
    }

    @Test
    public void compatOnlyDisablesMulticastAndScanAndKeepsOnlyTheLegacyType() {
        DiscoverySettings settings = DiscoverySettings.of(true, MulticastConstants.MULTICAST_GROUP,
            MulticastConstants.MULTICAST_PORT, "balanced", "visible", true);

        assertTrue(settings.isCompatOnly());
        assertFalse(settings.isMulticastChannelEnabled());
        assertFalse(settings.isScanEnabled());
        assertEquals(List.of(NsdConstants.NSD_SERVICE_TYPE_LEGACY), settings.nsdServiceTypes());
        assertEquals(NsdConstants.DEVICE_CAPABILITY_H264, settings.capabilities());
    }

    @Test
    public void disabledMulticastDisablesTheChannelButKeepsDiscoveryAndScan() {
        DiscoverySettings settings = DiscoverySettings.of(false, MulticastConstants.MULTICAST_GROUP,
            MulticastConstants.MULTICAST_PORT, "balanced", "visible", false);

        assertFalse(settings.isMulticastChannelEnabled());
        assertTrue(settings.isScanEnabled());
        assertEquals(2, settings.nsdServiceTypes().size());
    }

    @Test
    public void unknownVisibilityFallsBackToVisible() {
        assertEquals(VisibilityPolicy.Visibility.VISIBLE, visibility(""));
        assertEquals(VisibilityPolicy.Visibility.VISIBLE, visibility("unexpected"));
        assertEquals(VisibilityPolicy.Visibility.HIDDEN, visibility("hidden"));
        assertEquals(VisibilityPolicy.Visibility.FAVORITES, visibility("favorites"));
    }

    @Test
    public void visibilityPolicyIsAnchoredAtTheEvaluationTime() {
        DiscoverySettings settings = DiscoverySettings.of(true, MulticastConstants.MULTICAST_GROUP,
            MulticastConstants.MULTICAST_PORT, "balanced", "visible_10_min", false);

        VisibilityPolicy policy = settings.visibilityPolicy(5_000L);

        assertTrue(policy.isAnnouncing(5_000L));
        assertEquals(JoinPolicy.OPEN, policy.joinPolicy(5_000L));
        assertFalse(policy.isAnnouncing(5_000L + VisibilityPolicy.VISIBLE_DURATION_MS));
    }

    @Test
    public void persistedTemporaryWindowIsRestoredInsteadOfRestarted() {
        DiscoverySettings settings = DiscoverySettings.of(true, MulticastConstants.MULTICAST_GROUP,
            MulticastConstants.MULTICAST_PORT, "balanced", "visible_10_min", 1_000L, false);

        assertEquals(1_000L, settings.getVisibilityExpiresAtMs());
        assertTrue(settings.visibilityPolicy(999L).isAnnouncing(999L));
        assertFalse(settings.visibilityPolicy(1_000L).isAnnouncing(1_000L));
    }

    private static DiscoverySettings settings(String group) {
        return DiscoverySettings.of(true, group, MulticastConstants.MULTICAST_PORT, "balanced", "visible",
            false);
    }

    private static DiscoverySettings port(int port) {
        return DiscoverySettings.of(true, MulticastConstants.MULTICAST_GROUP, port, "balanced", "visible",
            false);
    }

    private static VisibilityPolicy.Visibility visibility(String wire) {
        return DiscoverySettings.of(true, MulticastConstants.MULTICAST_GROUP, MulticastConstants.MULTICAST_PORT,
            "balanced", wire, false).getVisibility();
    }
}
