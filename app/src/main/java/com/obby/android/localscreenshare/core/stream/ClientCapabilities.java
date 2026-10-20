package com.obby.android.localscreenshare.core.stream;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Immutable set of per-codec capabilities a client can decode.
 *
 * <p>The set constructor interprets each id as an unconstrained {@link CodecCapability}, preserving
 * the historical codec-id-only form. The map constructor carries full capability details, with a
 * null value meaning "unconstrained". All collections are copied at construction and exposed
 * read-only, so an instance can be shared between threads. Capabilities never change after
 * construction.</p>
 */
public final class ClientCapabilities {

    private final Map<String, CodecCapability> capabilities;

    /**
     * Creates capabilities from a set of codec ids, each treated as unconstrained.
     *
     * @param codecIds supported codec ids; copied, required
     * @throws IllegalArgumentException if {@code codecIds} is null
     */
    public ClientCapabilities(Set<String> codecIds) {
        if (codecIds == null) {
            throw new IllegalArgumentException("codecIds must not be null");
        }
        Map<String, CodecCapability> map = new HashMap<>();
        for (String codecId : codecIds) {
            if (codecId != null) {
                map.put(codecId, CodecCapability.any(codecId));
            }
        }
        this.capabilities = Collections.unmodifiableMap(map);
    }

    /**
     * Creates capabilities from a map of codec id to capability.
     *
     * @param capabilities map keyed by codec id; copied, required
     * @throws IllegalArgumentException if {@code capabilities} is null
     */
    public ClientCapabilities(Map<String, CodecCapability> capabilities) {
        if (capabilities == null) {
            throw new IllegalArgumentException("capabilities must not be null");
        }
        Map<String, CodecCapability> copy = new HashMap<>();
        for (Map.Entry<String, CodecCapability> entry : capabilities.entrySet()) {
            if (entry.getKey() != null) {
                copy.put(entry.getKey(), entry.getValue() != null
                        ? entry.getValue() : CodecCapability.any(entry.getKey()));
            }
        }
        this.capabilities = Collections.unmodifiableMap(copy);
    }

    /**
     * Returns whether a codec is supported; {@code null} is never supported.
     */
    public boolean supports(String codecId) {
        return codecId != null && capabilities.containsKey(codecId);
    }

    /**
     * Returns the capability for a codec, or {@code null} when unsupported.
     */
    public CodecCapability getCapability(String codecId) {
        return codecId == null ? null : capabilities.get(codecId);
    }

    /**
     * Returns the unmodifiable set of supported codec ids.
     */
    public Set<String> getCodecIds() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(capabilities.keySet()));
    }

    /**
     * Returns an unmodifiable snapshot of the codec-id-to-capability map.
     */
    public Map<String, CodecCapability> getCapabilities() {
        return capabilities;
    }
}
