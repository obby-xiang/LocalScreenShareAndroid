package com.obby.android.localscreenshare.core.codec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe, static registry of codec registrations.
 */
public final class CodecRegistry {

    private final ConcurrentHashMap<String, CodecRegistration> registrations =
            new ConcurrentHashMap<>();

    public void register(CodecRegistration registration) {
        if (registration == null) {
            throw new IllegalArgumentException("registration must not be null");
        }
        String codecId = registration.getDescriptor().getCodecId();
        CodecRegistration existing = registrations.putIfAbsent(codecId, registration);
        if (existing != null) {
            throw new IllegalStateException("codec already registered: " + codecId);
        }
    }

    public boolean contains(String codecId) {
        return codecId != null && registrations.containsKey(codecId);
    }

    public CodecRegistration require(String codecId) {
        CodecRegistration registration = codecId == null ? null : registrations.get(codecId);
        if (registration == null) {
            throw new CodecNotFoundException(codecId);
        }
        return registration;
    }

    public List<CodecRegistration> getRegistrations() {
        return Collections.unmodifiableList(new ArrayList<>(registrations.values()));
    }

    public Set<String> getCodecIds() {
        return Collections.unmodifiableSet(new HashSet<>(registrations.keySet()));
    }
}
