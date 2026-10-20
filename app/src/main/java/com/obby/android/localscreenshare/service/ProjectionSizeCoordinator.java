package com.obby.android.localscreenshare.service;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Applies one capture-size change in the order that keeps a rejection free of side effects.
 *
 * <p><b>Why the order is fixed.</b> The virtual display and the encoder have to agree on the
 * capture size. The 2026-10-21 device test resized the display first and switched the encoder
 * second; when the hardware encoder rejected an odd size, the display had already been shrunk while
 * the encoder kept encoding the old geometry, so the viewer showed a squeezed picture with a black
 * band on the right. The encoder is therefore asked first, and the display is resized only after the
 * encoder accepted the size. A rejection then leaves the display, the encoder and the stream on the
 * previous geometry, which is what makes the failure harmless.</p>
 *
 * <p><b>Transient stretch.</b> The reverse cost is bounded: between the encoder switch and the
 * resize, the display still composes at the old size for at most a frame or two, and the encoder
 * surface scales that content into the new coded size. That is a momentary scaling during a resize
 * the user caused, not a lasting geometry mismatch, and the retry inside the switch makes it rarer
 * still.</p>
 *
 * <p><b>Purity.</b> Both sides of the transition are injected, so the ordering and the
 * "rejection changes nothing" rule are unit-tested on the JVM without an Android display or
 * codec.</p>
 */
final class ProjectionSizeCoordinator {

    /**
     * Switches the encoder to a new capture size.
     */
    interface EncoderSwitch {

        /**
         * Rebuilds the encoder at the new size and re-points the capture display at it.
         *
         * @return {@code true} when the runtime adopted the new size
         */
        boolean switchTo(int width, int height);
    }

    /**
     * Resizes the capture display that feeds the encoder.
     */
    interface CaptureDisplay {

        /**
         * Applies the new capture size to the display.
         */
        void resizeTo(int width, int height);
    }

    /**
     * Result of one capture-size change.
     */
    enum Outcome {

        /**
         * The display and the encoder both use the new size.
         */
        APPLIED,

        /**
         * The encoder refused the size; nothing was changed.
         */
        REJECTED
    }

    private ProjectionSizeCoordinator() {
    }

    /**
     * Applies a capture size, encoder first.
     *
     * @param width    capture width in pixels, already aligned to the encoder
     * @param height   capture height in pixels, already aligned to the encoder
     * @param encoder  encoder switch, or null for a capture path without an encoder
     * @param display  capture display resize; must not be null
     * @return {@link Outcome#APPLIED} when both sides use the new size, {@link Outcome#REJECTED}
     *     when the encoder refused it and the display was left untouched
     * @throws IllegalArgumentException if {@code display} is null
     */
    @NonNull
    static Outcome apply(int width, int height, @Nullable EncoderSwitch encoder,
                         @NonNull CaptureDisplay display) {
        if (display == null) {
            throw new IllegalArgumentException("display must not be null");
        }
        if (encoder != null && !encoder.switchTo(width, height)) {
            return Outcome.REJECTED;
        }
        display.resizeTo(width, height);
        return Outcome.APPLIED;
    }
}
