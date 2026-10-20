package com.obby.android.localscreenshare.identity;

/**
 * Thrown when no device identity can be produced or replaced.
 *
 * <p>It is raised only when the backing keystore itself is unusable — an unavailable provider or a
 * failed key generation. A missing, invalidated or unreadable entry is not an error: the provider
 * regenerates the key, which surfaces as {@link DeviceIdentity#isChanged()} instead.</p>
 */
public class IdentityUnavailableException extends Exception {

    private static final long serialVersionUID = 1L;

    public IdentityUnavailableException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
