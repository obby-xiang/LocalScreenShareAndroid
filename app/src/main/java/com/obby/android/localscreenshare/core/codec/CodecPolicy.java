package com.obby.android.localscreenshare.core.codec;

import com.obby.android.localscreenshare.core.model.EncodedAccessUnit;
import com.obby.android.localscreenshare.core.model.StreamDescriptor;

/**
 * Per-codec policy that guides a stream's overflow, join-point and reconfiguration handling.
 *
 * <p>Implementations must be stateless and safe to call concurrently: one policy instance is
 * shared by every queue and client session that uses the codec.</p>
 */
public interface CodecPolicy {

    /**
     * Returns the queue policy used when a receiver falls behind.
     *
     * <p>Independently decodable formats keep the latest frame; reference-dependent formats must
     * drop until a join point so that a receiver never resumes on a frame it cannot decode.</p>
     */
    OverflowAction getOverflowAction();

    /**
     * Decides whether an access unit is a valid random-access entry point for a late or
     * resynchronizing receiver.
     *
     * <p>Implementations must be side-effect free, must re-validate the payload when the flags
     * alone are not sufficient for the codec, and must return {@code false} for null or malformed
     * input instead of throwing. The unit's flags are hints; for reference codecs the validated
     * payload is authoritative.</p>
     */
    boolean isJoinPoint(EncodedAccessUnit accessUnit);

    /**
     * Returns whether moving from {@code previous} to {@code next} requires a new decoder
     * configuration.
     *
     * <p>The comparison is about the decode configuration, not the config epoch: an epoch bump
     * with an identical configuration must not force reconfiguration. A null previous or next
     * descriptor requires reconfiguration.</p>
     */
    boolean requiresReconfigure(StreamDescriptor previous, StreamDescriptor next);
}
