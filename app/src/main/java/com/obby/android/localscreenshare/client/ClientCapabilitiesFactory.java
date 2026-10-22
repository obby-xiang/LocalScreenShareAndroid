package com.obby.android.localscreenshare.client;

import com.obby.android.localscreenshare.core.codec.CodecIds;
import com.obby.android.localscreenshare.core.model.AudioCodecCapability;
import com.obby.android.localscreenshare.core.stream.ClientCapabilities;
import com.obby.android.localscreenshare.core.stream.CodecCapability;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds the single capability set a client advertises and its media session enforces.
 *
 * <p>The HELLO and the media session used to derive their capabilities separately, which is how a
 * server could be told about a codec the session then refused. They now share one instance produced
 * here, so a codec the server selects is always a codec the session accepts.</p>
 *
 * <p><b>JPEG.</b> The probe only reports codecs backed by a {@code MediaCodec} decoder, and JPEG is
 * not one of them: the viewer draws every complete JPEG frame with {@link android.graphics.BitmapFactory}
 * and never creates a decoder for it. JPEG is therefore always added here, as an unconstrained
 * capability. It is a compatibility fallback, not a performance path, and the set stays unconstrained
 * so any frame size the server produces can be shown.</p>
 *
 * <p>This class is pure Java: it only merges maps, so it can be exercised without an Android
 * runtime.</p>
 */
public final class ClientCapabilitiesFactory {

    private ClientCapabilitiesFactory() {
    }

    /**
     * Returns the capabilities to advertise, with the JPEG fallback added.
     *
     * <p>The video capabilities are copied, so the caller's map is never aliased or modified. A
     * null entry value is read as an unconstrained capability, matching
     * {@link ClientCapabilities}. An entry for JPEG keeps its probed details; only a missing one is
     * filled in. Audio capabilities are passed through unchanged, and a null audio map means "no
     * audio".</p>
     *
     * @param probedVideo video capabilities found by probing; null means none were found
     * @param probedAudio audio capabilities found by probing; null means no audio
     * @return the immutable advertised set, never null
     */
    public static ClientCapabilities advertised(Map<String, CodecCapability> probedVideo,
                                                Map<String, AudioCodecCapability> probedAudio) {
        Map<String, CodecCapability> video = new LinkedHashMap<>();
        if (probedVideo != null) {
            for (Map.Entry<String, CodecCapability> entry : probedVideo.entrySet()) {
                if (entry.getKey() == null) {
                    continue;
                }
                video.put(entry.getKey(), entry.getValue() != null
                        ? entry.getValue() : CodecCapability.any(entry.getKey()));
            }
        }
        if (!video.containsKey(CodecIds.JPEG)) {
            video.put(CodecIds.JPEG, CodecCapability.any(CodecIds.JPEG));
        }
        return new ClientCapabilities(video, probedAudio);
    }
}
