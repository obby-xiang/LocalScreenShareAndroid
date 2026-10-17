package com.obby.android.localscreenshare.core.stream;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Immutable set of codec ids a client can decode.
 */
public final class ClientCapabilities {

    private final Set<String> codecIds;

    public ClientCapabilities(Set<String> codecIds) {
        if (codecIds == null) {
            throw new IllegalArgumentException("codecIds must not be null");
        }
        this.codecIds = Collections.unmodifiableSet(new HashSet<>(codecIds));
    }

    public boolean supports(String codecId) {
        return codecId != null && codecIds.contains(codecId);
    }

    public Set<String> getCodecIds() {
        return codecIds;
    }
}
