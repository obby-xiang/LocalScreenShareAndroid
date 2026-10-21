package com.obby.android.localscreenshare.trust;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Turns a pin outcome into the action the viewer must take before dialing.
 *
 * <p>This is the only place that decides whether a connection may proceed silently, needs a
 * human confirmation, or must be refused outright. Keeping it separate from the activity makes
 * the trust-on-first-use rules testable without a UI, and makes it impossible for the rendering
 * code to soften a hard failure by accident.</p>
 */
public final class TofuPolicy {

    /**
     * What the caller should do next.
     */
    public enum Action {
        /**
         * The pin matched; connect without asking.
         */
        PROCEED,
        /**
         * The device was never pinned; show the first-connection confirmation.
         */
        CONFIRM_FIRST,
        /**
         * The pin exists but the fingerprint differs; show the change warning and require an
         * explicit acknowledgement before rebinding.
         */
        CONFIRM_CHANGE,
        /**
         * The certificate contradicts the selected id; refuse and do not offer a bypass.
         */
        BLOCK
    }

    private TofuPolicy() {
    }

    /**
     * Maps a pin outcome to the viewer action.
     */
    @NonNull
    public static Action decide(@NonNull final PinPolicy.Outcome outcome) {
        switch (outcome) {
            case MATCH:
                return Action.PROCEED;
            case UNKNOWN:
                return Action.CONFIRM_FIRST;
            case CHANGED:
                return Action.CONFIRM_CHANGE;
            case IDENTITY_MISMATCH:
            default:
                return Action.BLOCK;
        }
    }

    /**
     * Returns whether the address was last reached under a different device id.
     *
     * <p>A hint only: local networks reuse addresses, so a different id at the same address may
     * simply be a new DHCP lease. The caller shows it in the first-connection dialog and never
     * blocks on it.</p>
     */
    public static boolean isAddressChanged(@Nullable final String lastDeviceId,
                                           @Nullable final String currentDeviceId) {
        if (lastDeviceId == null || lastDeviceId.trim().isEmpty()
            || currentDeviceId == null || currentDeviceId.trim().isEmpty()) {
            return false;
        }
        return !lastDeviceId.trim().equals(currentDeviceId.trim());
    }
}
