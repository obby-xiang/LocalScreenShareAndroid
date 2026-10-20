package com.obby.android.localscreenshare.settings;

import com.obby.android.localscreenshare.discovery.model.JoinPolicy;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link VisibilityPolicy}.
 */
public class VisibilityPolicyTest {

    @Test
    public void hiddenAnnouncesNothingAndAcceptsNobody() {
        VisibilityPolicy policy = VisibilityPolicy.forVisibility(
            VisibilityPolicy.Visibility.HIDDEN, 1_000L);

        assertFalse(policy.isAnnouncing(1_000L));
        assertEquals(JoinPolicy.CLOSED, policy.joinPolicy(1_000L));
        assertFalse(policy.acceptsRegister(1_000L, false));
        assertFalse(policy.acceptsRegister(1_000L, true));
        assertEquals(0L, policy.getExpiresAtMs());
    }

    @Test
    public void favoritesAnnouncesClosedAndAcceptsOnlySavedDevices() {
        VisibilityPolicy policy = VisibilityPolicy.forVisibility(
            VisibilityPolicy.Visibility.FAVORITES, 1_000L);

        assertTrue(policy.isAnnouncing(1_000L));
        assertEquals(JoinPolicy.CLOSED, policy.joinPolicy(1_000L));
        assertTrue(policy.acceptsRegister(1_000L, true));
        assertFalse(policy.acceptsRegister(1_000L, false));
    }

    @Test
    public void visibleAnnouncesOpenAndAcceptsEveryCaller() {
        VisibilityPolicy policy = VisibilityPolicy.forVisibility(
            VisibilityPolicy.Visibility.VISIBLE, 1_000L);

        assertTrue(policy.isAnnouncing(1_000L));
        assertEquals(JoinPolicy.OPEN, policy.joinPolicy(1_000L));
        assertTrue(policy.acceptsRegister(1_000L, true));
        assertTrue(policy.acceptsRegister(1_000L, false));
        assertEquals(0L, policy.getExpiresAtMs());
    }

    @Test
    public void temporaryVisibilityExpiresIntoHidden() {
        VisibilityPolicy policy = VisibilityPolicy.forVisibility(
            VisibilityPolicy.Visibility.VISIBLE_10_MIN, 1_000L);

        assertEquals(1_000L + VisibilityPolicy.VISIBLE_DURATION_MS, policy.getExpiresAtMs());
        assertFalse(policy.isExpired(1_000L));
        assertTrue(policy.isAnnouncing(1_000L));
        assertEquals(JoinPolicy.OPEN, policy.joinPolicy(1_000L));

        long justBeforeExpiry = policy.getExpiresAtMs() - 1L;
        assertFalse(policy.isExpired(justBeforeExpiry));
        assertTrue(policy.isAnnouncing(justBeforeExpiry));

        long expiry = policy.getExpiresAtMs();
        assertTrue(policy.isExpired(expiry));
        assertFalse(policy.isAnnouncing(expiry));
        assertEquals(JoinPolicy.CLOSED, policy.joinPolicy(expiry));
        assertFalse(policy.acceptsRegister(expiry, true));
        assertEquals(VisibilityPolicy.Visibility.HIDDEN, policy.effective(expiry));
    }

    @Test
    public void expiryIsIgnoredForPresetsWithoutAWindow() {
        VisibilityPolicy policy = VisibilityPolicy.of(VisibilityPolicy.Visibility.VISIBLE, 99_999L);

        assertEquals(0L, policy.getExpiresAtMs());
        assertFalse(policy.isExpired(99_999L));
        assertEquals(VisibilityPolicy.Visibility.VISIBLE, policy.effective(99_999L));
    }

    @Test
    public void restoringAnExpiredWindowKeepsThePresetUntilEvaluated() {
        VisibilityPolicy policy = VisibilityPolicy.of(
            VisibilityPolicy.Visibility.VISIBLE_10_MIN, 5_000L);

        assertEquals(VisibilityPolicy.Visibility.VISIBLE_10_MIN, policy.getVisibility());
        assertEquals(VisibilityPolicy.Visibility.HIDDEN, policy.effective(5_000L));
    }

    @Test
    public void visibilityParsingAcceptsWireValuesNamesAndFallsBackToVisible() {
        assertEquals(VisibilityPolicy.Visibility.HIDDEN,
            VisibilityPolicy.Visibility.fromWire("hidden"));
        assertEquals(VisibilityPolicy.Visibility.VISIBLE_10_MIN,
            VisibilityPolicy.Visibility.fromWire("visible_10_min"));
        assertEquals(VisibilityPolicy.Visibility.VISIBLE_10_MIN,
            VisibilityPolicy.Visibility.fromWire("VISIBLE_10_MIN"));
        assertEquals(VisibilityPolicy.Visibility.FAVORITES,
            VisibilityPolicy.Visibility.fromWire(" favorites "));
        assertEquals(VisibilityPolicy.Visibility.VISIBLE,
            VisibilityPolicy.Visibility.fromWire("unexpected"));
    }
}
