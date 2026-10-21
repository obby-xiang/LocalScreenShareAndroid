package com.obby.android.localscreenshare.core.stream;

import com.obby.android.localscreenshare.core.model.AudioCodecCapability;

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
 *
 * <p><b>Audio.</b> Audio decoders are advertised in a map of their own, because an audio capability
 * describes sample rate, channels and bitrate rather than a video geometry. An empty audio map
 * means "no audio", which is exactly what an old client reports: the server then sends it no audio
 * and keeps the video stream unchanged.</p>
 */
public final class ClientCapabilities {

    private final Map<String, CodecCapability> capabilities;

    private final Map<String, AudioCodecCapability> audioCapabilities;

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
        this.audioCapabilities = Collections.emptyMap();
    }

    /**
     * Creates capabilities from a map of codec id to capability, with no audio support.
     *
     * @param capabilities map keyed by codec id; copied, required
     * @throws IllegalArgumentException if {@code capabilities} is null
     */
    public ClientCapabilities(Map<String, CodecCapability> capabilities) {
        this(capabilities, Collections.emptyMap());
    }

    /**
     * Creates capabilities that advertise both video and audio decoders.
     *
     * @param capabilities      video capabilities keyed by codec id; copied, required
     * @param audioCapabilities audio capabilities keyed by codec id; null means no audio
     * @throws IllegalArgumentException if {@code capabilities} is null
     */
    public ClientCapabilities(Map<String, CodecCapability> capabilities,
                              Map<String, AudioCodecCapability> audioCapabilities) {
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

        Map<String, AudioCodecCapability> audioCopy = new HashMap<>();
        if (audioCapabilities != null) {
            for (Map.Entry<String, AudioCodecCapability> entry : audioCapabilities.entrySet()) {
                if (entry.getKey() != null) {
                    audioCopy.put(entry.getKey(), entry.getValue() != null
                            ? entry.getValue()
                            : AudioCodecCapability.any(entry.getKey()));
                }
            }
        }
        this.audioCapabilities = Collections.unmodifiableMap(audioCopy);
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

    /**
     * Returns whether the client can decode an audio codec; {@code null} is never supported.
     */
    public boolean supportsAudio(String codecId) {
        return codecId != null && audioCapabilities.containsKey(codecId);
    }

    /**
     * Returns the audio capability for a codec, or {@code null} when unsupported.
     */
    public AudioCodecCapability getAudioCapability(String codecId) {
        return codecId == null ? null : audioCapabilities.get(codecId);
    }

    /**
     * Returns the unmodifiable set of supported audio codec ids; empty means no audio.
     */
    public Set<String> getAudioCodecIds() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(audioCapabilities.keySet()));
    }

    /**
     * Returns an unmodifiable snapshot of the audio codec-id-to-capability map.
     */
    public Map<String, AudioCodecCapability> getAudioCapabilities() {
        return audioCapabilities;
    }
}
