package com.obby.android.localscreenshare.core.stream;

/**
 * Immutable per-codec capability a client or server advertises or probes.
 *
 * <p>A zero dimension or rate means unbounded: the capability does not constrain that axis. A null
 * profile or level means the value is unspecified. {@link #any(String)} builds an unconstrained
 * capability used when only the codec id is known, which is how the legacy set-only capability form
 * is interpreted.</p>
 */
public final class CodecCapability {

    private final String codecId;

    private final String mime;

    private final String profile;

    private final String level;

    private final int maxWidth;

    private final int maxHeight;

    private final int maxFrameRate;

    private final int maxBitrateBps;

    private final boolean surfaceOutput;

    private final boolean adaptive;

    /**
     * Creates a capability.
     *
     * @throws IllegalArgumentException if {@code codecId} is null or blank, or a bound is negative
     */
    public CodecCapability(String codecId, String mime, String profile, String level,
                           int maxWidth, int maxHeight, int maxFrameRate, int maxBitrateBps,
                           boolean surfaceOutput, boolean adaptive) {
        if (codecId == null || codecId.trim().isEmpty()) {
            throw new IllegalArgumentException("codecId must not be null or blank");
        }
        if (maxWidth < 0 || maxHeight < 0 || maxFrameRate < 0 || maxBitrateBps < 0) {
            throw new IllegalArgumentException("capability bounds must be non-negative");
        }
        this.codecId = codecId;
        this.mime = mime;
        this.profile = profile;
        this.level = level;
        this.maxWidth = maxWidth;
        this.maxHeight = maxHeight;
        this.maxFrameRate = maxFrameRate;
        this.maxBitrateBps = maxBitrateBps;
        this.surfaceOutput = surfaceOutput;
        this.adaptive = adaptive;
    }

    /** Returns an unconstrained capability for {@code codecId}. */
    public static CodecCapability any(String codecId) {
        return new CodecCapability(codecId, null, null, null, 0, 0, 0, 0, false, false);
    }

    /**
     * Returns whether this capability and {@code other} can agree on a common configuration.
     *
     * <p>Size, frame-rate and bitrate bounds always admit a common point (their minimum), so only
     * a profile or level that both capabilities declare differently makes them incompatible. A
     * null or different-codec argument is treated as incompatible except for a null "no
     * constraint" capability.</p>
     */
    public boolean isCompatibleWith(CodecCapability other) {
        if (other == null) {
            return false;
        }
        if (!codecId.equals(other.codecId)) {
            return false;
        }
        if (profile != null && other.profile != null && !profile.equals(other.profile)) {
            return false;
        }
        if (level != null && other.level != null && !level.equals(other.level)) {
            return false;
        }
        return true;
    }

    public String getCodecId() {
        return codecId;
    }

    public String getMime() {
        return mime;
    }

    public String getProfile() {
        return profile;
    }

    public String getLevel() {
        return level;
    }

    public int getMaxWidth() {
        return maxWidth;
    }

    public int getMaxHeight() {
        return maxHeight;
    }

    public int getMaxFrameRate() {
        return maxFrameRate;
    }

    public int getMaxBitrateBps() {
        return maxBitrateBps;
    }

    public boolean isSurfaceOutput() {
        return surfaceOutput;
    }

    public boolean isAdaptive() {
        return adaptive;
    }
}
