package com.obby.android.localscreenshare.core.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable set of codec configuration parts a late client needs to decode the
 * stream after joining at a random-access point.
 */
public final class JoinInfo {

    private final int configEpoch;
    private final List<CodecConfigPart> requiredConfigParts;

    public JoinInfo(int configEpoch, List<CodecConfigPart> requiredConfigParts) {
        if (configEpoch < 1) {
            throw new IllegalArgumentException("configEpoch must be >= 1");
        }
        this.configEpoch = configEpoch;
        this.requiredConfigParts = requiredConfigParts == null
                ? Collections.<CodecConfigPart>emptyList()
                : Collections.unmodifiableList(new ArrayList<>(requiredConfigParts));
    }

    public int getConfigEpoch() {
        return configEpoch;
    }

    public List<CodecConfigPart> getRequiredConfigParts() {
        return requiredConfigParts;
    }
}
