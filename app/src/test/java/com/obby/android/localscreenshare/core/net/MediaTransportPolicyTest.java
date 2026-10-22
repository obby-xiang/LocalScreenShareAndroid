package com.obby.android.localscreenshare.core.net;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link MediaTransportPolicy}.
 *
 * <p>The policy is the whole transport decision of a call, so every branch is asserted as a value:
 * what a client declares, what the deployment allows, which tier is enabled, what already failed.
 */
public class MediaTransportPolicyTest {

    private static final Set<MediaTransportKind> ALL =
            Collections.unmodifiableSet(EnumSet.allOf(MediaTransportKind.class));

    @Test
    public void first_clientThatDeclaresNothing_staysOnTheControlPlane() {
        assertEquals(MediaTransportKind.GRPC, MediaTransportPolicy.first(
                Collections.emptySet(), ALL, true));
        assertEquals(MediaTransportKind.GRPC, MediaTransportPolicy.first(
                Collections.emptySet(), MediaTransportPolicy.available(
                        MediaTransportPolicy.Mode.AUTO), true));
    }

    @Test
    public void first_prefersDatagramsThenTheStreamWhenBothAreDeclared() {
        Set<String> offered = offered("tcp", "udp");

        assertEquals(MediaTransportKind.UDP, MediaTransportPolicy.first(offered, ALL, true));
        assertEquals(MediaTransportKind.TCP, MediaTransportPolicy.first(offered, ALL, false));
    }

    @Test
    public void first_skipsAChannelTheClientDidNotDeclare() {
        assertEquals(MediaTransportKind.TCP, MediaTransportPolicy.first(
                offered("tcp"), ALL, true));
        assertEquals(MediaTransportKind.GRPC, MediaTransportPolicy.first(
                offered("udp"), ALL, false));
    }

    @Test
    public void first_neverLeavesAnEmptyDecision() {
        Set<MediaTransportKind> none = Collections.emptySet();

        assertEquals(MediaTransportKind.GRPC, MediaTransportPolicy.first(offered("udp"), none,
                true));
    }

    @Test
    public void first_pinnedToTheControlPlane_ignoresTheClientDeclaration() {
        Set<MediaTransportKind> pinned = MediaTransportPolicy.available(
                MediaTransportPolicy.Mode.GRPC);

        assertEquals(Collections.singleton(MediaTransportKind.GRPC), pinned);
        assertEquals(MediaTransportKind.GRPC, MediaTransportPolicy.first(
                offered("tcp", "udp"), pinned, true));
    }

    @Test
    public void first_matchesTheDeclaredNameIgnoringCaseAndSpaces() {
        assertEquals(MediaTransportKind.TCP, MediaTransportPolicy.first(
                offered(" TCP "), ALL, false));
    }

    @Test
    public void next_walksTheFallbackChainWithoutRepeatingAChannel() {
        Set<String> offered = offered("tcp", "udp");

        assertEquals(MediaTransportKind.TCP, MediaTransportPolicy.next(MediaTransportKind.UDP,
                offered, ALL, true, EnumSet.of(MediaTransportKind.UDP)));
        assertEquals(MediaTransportKind.GRPC, MediaTransportPolicy.next(MediaTransportKind.TCP,
                offered, ALL, true,
                EnumSet.of(MediaTransportKind.UDP, MediaTransportKind.TCP)));
    }

    @Test
    public void next_neverReturnsAFailedChannelEvenWithoutAnAlreadyFailedSet() {
        assertEquals(MediaTransportKind.TCP, MediaTransportPolicy.next(MediaTransportKind.UDP,
                offered("tcp", "udp"), ALL, true, null));
    }

    @Test
    public void next_afterTheStreamFailedAndTheClientDeclaresOnlyDatagrams_staysOnTheCarrier() {
        assertEquals(MediaTransportKind.GRPC, MediaTransportPolicy.next(MediaTransportKind.TCP,
                offered("tcp"), ALL, true, EnumSet.of(MediaTransportKind.TCP)));
    }

    @Test
    public void mode_parsesTheStoredSettingAndFallsBackToAuto() {
        assertEquals(MediaTransportPolicy.Mode.AUTO, MediaTransportPolicy.Mode.parse(null));
        assertEquals(MediaTransportPolicy.Mode.AUTO, MediaTransportPolicy.Mode.parse(""));
        assertEquals(MediaTransportPolicy.Mode.AUTO, MediaTransportPolicy.Mode.parse("unknown"));
        assertEquals(MediaTransportPolicy.Mode.GRPC, MediaTransportPolicy.Mode.parse("grpc"));
        assertEquals(MediaTransportPolicy.Mode.TCP, MediaTransportPolicy.Mode.parse("TCP"));
        assertEquals(MediaTransportPolicy.Mode.UDP, MediaTransportPolicy.Mode.parse("udp"));
    }

    @Test
    public void available_describesEachMode() {
        assertTrue(MediaTransportPolicy.available(MediaTransportPolicy.Mode.TCP)
                .containsAll(Arrays.asList(MediaTransportKind.TCP, MediaTransportKind.GRPC)));
        assertTrue(MediaTransportPolicy.available(MediaTransportPolicy.Mode.UDP)
                .containsAll(Arrays.asList(MediaTransportKind.UDP, MediaTransportKind.GRPC)));
        assertEquals(Collections.singleton(MediaTransportKind.GRPC),
                MediaTransportPolicy.available(MediaTransportPolicy.Mode.AUTO));
    }

    @Test
    public void allowsUdp_isOnlyTrueForTheExplicitDatagramMode() {
        assertFalse(MediaTransportPolicy.allowsUdp(null));
        assertFalse(MediaTransportPolicy.allowsUdp(MediaTransportPolicy.Mode.AUTO));
        assertFalse(MediaTransportPolicy.allowsUdp(MediaTransportPolicy.Mode.TCP));
        assertTrue(MediaTransportPolicy.allowsUdp(MediaTransportPolicy.Mode.UDP));
    }

    private static Set<String> offered(String... names) {
        return new LinkedHashSet<>(Arrays.asList(names));
    }
}
