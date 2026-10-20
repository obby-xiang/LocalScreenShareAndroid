package com.obby.android.localscreenshare.core.codec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.obby.android.localscreenshare.core.codec.jpeg.JpegCodec;

/**
 * Thread-safe, static registry of codec registrations.
 *
 * <p>The registry is configuration, not discovery: registrations are added explicitly and looked
 * up by codec id. All operations are safe for concurrent use and lookups never mutate the
 * registry. A registry is normally built once during setup (see {@link #withDefaults()}) and then
 * shared by the stream runtime and all client sessions.</p>
 */
public final class CodecRegistry {

    private final ConcurrentHashMap<String, CodecRegistration> registrations =
            new ConcurrentHashMap<>();

    /**
     * Registers one codec under its descriptor's codec id.
     *
     * @throws IllegalArgumentException if {@code registration} is null
     * @throws IllegalStateException    if the codec id is already registered; duplicate
     *                                  registration is a setup error, never a silent replace
     */
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

    /**
     * Returns true when the id is registered; a null id returns false.
     */
    public boolean contains(String codecId) {
        return codecId != null && registrations.containsKey(codecId);
    }

    /**
     * Returns the registration for the id, or throws {@link CodecNotFoundException}.
     *
     * <p>This is the strict lookup used on media paths: an unknown or null codec id fails
     * explicitly instead of silently falling back to another codec.</p>
     */
    public CodecRegistration require(String codecId) {
        CodecRegistration registration = codecId == null ? null : registrations.get(codecId);
        if (registration == null) {
            throw new CodecNotFoundException(codecId);
        }
        return registration;
    }

    /**
     * Returns an unmodifiable snapshot of the current registrations.
     *
     * <p>The iteration order is unspecified; callers that need a preference order must use
     * {@link CodecRegistration#getFallbackPriority()} rather than registration order.</p>
     */
    public List<CodecRegistration> getRegistrations() {
        return Collections.unmodifiableList(new ArrayList<>(registrations.values()));
    }

    /**
     * Returns an unmodifiable snapshot of the registered codec ids.
     */
    public Set<String> getCodecIds() {
        return Collections.unmodifiableSet(new HashSet<>(registrations.keySet()));
    }

    /**
     * Creates a registry containing the built-in reference codecs; currently only JPEG.
     *
     * <p>Additional adapters are registered explicitly by the caller; this factory does not
     * discover codecs at runtime.</p>
     */
    public static CodecRegistry withDefaults() {
        CodecRegistry registry = new CodecRegistry();
        registry.register(JpegCodec.registration());
        return registry;
    }
}
