package com.obby.android.localscreenshare.core.stream;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Immutable set of codec ids a client can decode.
 *
 * <p>The set is copied at construction and exposed read-only, so an instance can be shared
 * between threads. Capabilities are used both to admit a join and to compute a fallback codec
 * common to all attached clients; they never change after construction.</p>
 */
public final class ClientCapabilities {

    private final Set<String> codecIds;

    /**
     * Creates capabilities from a set of codec ids.
     *
     * @param codecIds supported codec ids; copied, required
     * @throws IllegalArgumentException if {@code codecIds} is null
     */
    public ClientCapabilities(Set<String> codecIds) {
        if (codecIds == null) {
            throw new IllegalArgumentException("codecIds must not be null");
        }
        this.codecIds = Collections.unmodifiableSet(new HashSet<>(codecIds));
    }

    /** Returns whether a codec is supported; {@code null} is never supported. */
    public boolean supports(String codecId) {
        return codecId != null && codecIds.contains(codecId);
    }

    /** Returns the unmodifiable set of supported codec ids. */
    public Set<String> getCodecIds() {
        return codecIds;
    }
}
