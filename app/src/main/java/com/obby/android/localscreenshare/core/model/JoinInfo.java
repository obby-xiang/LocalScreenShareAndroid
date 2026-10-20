package com.obby.android.localscreenshare.core.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable set of codec configuration parts a late client needs to decode the stream after
 * joining at a random-access point.
 *
 * <p>The config epoch must be at least one and must match the epoch of the access unit that
 * carries this information; the access unit constructor enforces that match. The required parts
 * may be empty for a codec whose frames are independently decodable (JPEG). The list is copied
 * into an unmodifiable view, so the instance is immutable, and the parts are the same
 * {@link CodecConfigPart} values that appear in the stream descriptor.</p>
 */
public final class JoinInfo {

    private final int configEpoch;
    private final List<CodecConfigPart> requiredConfigParts;

    /**
     * Creates join information.
     *
     * @param configEpoch         epoch of the descriptor the parts belong to; must be at least one
     * @param requiredConfigParts configuration parts a late joiner needs; null is treated as empty
     * @throws IllegalArgumentException if {@code configEpoch} is below one
     */
    public JoinInfo(int configEpoch, List<CodecConfigPart> requiredConfigParts) {
        if (configEpoch < 1) {
            throw new IllegalArgumentException("configEpoch must be >= 1");
        }
        this.configEpoch = configEpoch;
        this.requiredConfigParts = requiredConfigParts == null
                ? Collections.<CodecConfigPart>emptyList()
                : Collections.unmodifiableList(new ArrayList<>(requiredConfigParts));
    }

    /**
     * Returns the config epoch these parts belong to.
     */
    public int getConfigEpoch() {
        return configEpoch;
    }

    /**
     * Returns the unmodifiable configuration parts; may be empty.
     */
    public List<CodecConfigPart> getRequiredConfigParts() {
        return requiredConfigParts;
    }
}
