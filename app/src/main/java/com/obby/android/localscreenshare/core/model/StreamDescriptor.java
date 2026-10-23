package com.obby.android.localscreenshare.core.model;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable description of a stream's codec decode configuration.
 *
 * <p>A descriptor identifies the stream and config epoch, names the codec and bitstream framing,
 * and carries the geometry, color and codec configuration a decoder needs. It is built through
 * {@link #builder} and validated at construction; an instance never changes. A changed decode
 * configuration is published as a new descriptor with the next config epoch.</p>
 *
 * <p><b>Coded versus visible size.</b> The coded size is the encoder's output raster, including
 * alignment padding, and is what a decoder is configured with. The visible size is the region that
 * should actually be displayed; it may be smaller than the coded size. Rotation is applied by the
 * display side. Both visible size and rotation are part of the config hash, so changing either
 * requires a new descriptor and a new epoch.</p>
 *
 * <p><b>Config hash.</b> {@link #getConfigHash()} is a SHA-256 digest rendered as 64 lowercase
 * hexadecimal characters. It covers the codec id, MIME type, profile, level, coded size, visible
 * size, rotation, bitstream format, the ordered configuration parts (id plus payload bytes) and
 * the color description. It deliberately excludes {@code configEpoch} and the stream identity, so
 * descriptors of the same decode configuration share one hash across epoch bumps and receivers are
 * not forced to reconfigure for an epoch-only change. Null strings are encoded distinctly from
 * empty strings.</p>
 *
 * <p><b>Content frame rate.</b> {@link #getFrameRate()} is a display hint, not part of the decode
 * configuration: it changes how smoothly the display side paces the frames, never which decoder is
 * configured. It is therefore excluded from the config hash as well, and a changed hint alone must
 * not force a receiver to reconfigure. Zero means unknown or cleared; negative builder values are
 * normalised to zero.</p>
 */
public final class StreamDescriptor {

    private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();

    private final StreamKey streamKey;
    private final int configEpoch;
    private final String codecId;
    private final String mime;
    private final String profile;
    private final String level;
    private final int codedWidth;
    private final int codedHeight;
    private final int visibleWidth;
    private final int visibleHeight;
    private final int rotationDegrees;
    private final BitstreamFormat bitstreamFormat;
    private final List<CodecConfigPart> configParts;
    private final ColorInfo colorInfo;
    private final int frameRate;
    private final String configHash;

    private StreamDescriptor(StreamKey streamKey, int configEpoch, String codecId, String mime,
                             String profile, String level, int codedWidth, int codedHeight,
                             int visibleWidth, int visibleHeight, int rotationDegrees,
                             BitstreamFormat bitstreamFormat, List<CodecConfigPart> configParts,
                             ColorInfo colorInfo, int frameRate) {
        if (streamKey == null) {
            throw new IllegalArgumentException("streamKey must not be null");
        }
        if (configEpoch < 1) {
            throw new IllegalArgumentException("configEpoch must be >= 1");
        }
        if (codecId == null || codecId.trim().isEmpty()) {
            throw new IllegalArgumentException("codecId must not be null or blank");
        }
        if (mime == null || mime.trim().isEmpty()) {
            throw new IllegalArgumentException("mime must not be null or blank");
        }
        if (codedWidth <= 0 || codedHeight <= 0) {
            throw new IllegalArgumentException("coded size must be positive");
        }
        if (visibleWidth <= 0 || visibleHeight <= 0) {
            throw new IllegalArgumentException("visible size must be positive");
        }
        if (!isValidRotation(rotationDegrees)) {
            throw new IllegalArgumentException("rotationDegrees must be 0, 90, 180 or 270");
        }
        if (bitstreamFormat == null) {
            throw new IllegalArgumentException("bitstreamFormat must not be null");
        }
        if (colorInfo == null) {
            throw new IllegalArgumentException("colorInfo must not be null");
        }

        this.streamKey = streamKey;
        this.configEpoch = configEpoch;
        this.codecId = codecId;
        this.mime = mime;
        this.profile = profile;
        this.level = level;
        this.codedWidth = codedWidth;
        this.codedHeight = codedHeight;
        this.visibleWidth = visibleWidth;
        this.visibleHeight = visibleHeight;
        this.rotationDegrees = rotationDegrees;
        this.bitstreamFormat = bitstreamFormat;
        this.configParts = Collections.unmodifiableList(new ArrayList<>(configParts));
        this.colorInfo = colorInfo;
        this.frameRate = Math.max(0, frameRate);
        this.configHash = computeConfigHash();
    }

    private static boolean isValidRotation(int rotationDegrees) {
        return rotationDegrees == 0
                || rotationDegrees == 90
                || rotationDegrees == 180
                || rotationDegrees == 270;
    }

    /**
     * Starts building a descriptor.
     *
     * <p>The stream key, epoch, codec id, MIME type and bitstream format are required here; the
     * coded size must still be supplied through {@link Builder#codedSize(int, int)} before
     * {@link Builder#build()}.</p>
     *
     * @param configEpoch configuration epoch; must be at least one
     */
    public static Builder builder(StreamKey streamKey, int configEpoch, String codecId,
                                  String mime, BitstreamFormat bitstreamFormat) {
        return new Builder(streamKey, configEpoch, codecId, mime, bitstreamFormat);
    }

    /**
     * Returns the stream identity.
     */
    public StreamKey getStreamKey() {
        return streamKey;
    }

    /**
     * Returns the configuration epoch. It is at least one and advances by exactly one for each
     * published decode-configuration change.
     */
    public int getConfigEpoch() {
        return configEpoch;
    }

    /**
     * Returns the codec id used as the registry key.
     */
    public String getCodecId() {
        return codecId;
    }

    /**
     * Returns the codec MIME type.
     */
    public String getMime() {
        return mime;
    }

    /**
     * Returns the optional codec profile name, or null when unspecified.
     */
    public String getProfile() {
        return profile;
    }

    /**
     * Returns the optional codec level name, or null when unspecified.
     */
    public String getLevel() {
        return level;
    }

    /**
     * Returns the encoder's coded width, including alignment padding.
     */
    public int getCodedWidth() {
        return codedWidth;
    }

    /**
     * Returns the encoder's coded height, including alignment padding.
     */
    public int getCodedHeight() {
        return codedHeight;
    }

    /**
     * Returns the width of the region that should be displayed.
     */
    public int getVisibleWidth() {
        return visibleWidth;
    }

    /**
     * Returns the height of the region that should be displayed.
     */
    public int getVisibleHeight() {
        return visibleHeight;
    }

    /**
     * Returns the display rotation in degrees: 0, 90, 180 or 270.
     */
    public int getRotationDegrees() {
        return rotationDegrees;
    }

    public BitstreamFormat getBitstreamFormat() {
        return bitstreamFormat;
    }

    /**
     * Returns the ordered, unmodifiable codec configuration parts. The list may be empty for a
     * codec that carries no out-of-band configuration.
     */
    public List<CodecConfigPart> getConfigParts() {
        return configParts;
    }

    /**
     * Returns the color description; never null, but may be {@link ColorInfo#UNSPECIFIED}.
     */
    public ColorInfo getColorInfo() {
        return colorInfo;
    }

    /**
     * Returns the content frame-rate hint for the display side, or zero when unknown or cleared.
     *
     * <p>This is a presentation hint only: it never changes the decoder configuration and never
     * participates in {@link #getConfigHash()}. A receiver that renders into a surface may forward
     * it to the platform, and zero means it must stop replaying a previous hint.</p>
     */
    public int getFrameRate() {
        return frameRate;
    }

    /**
     * Returns the stable lowercase-hex SHA-256 digest of the decode configuration.
     */
    public String getConfigHash() {
        return configHash;
    }

    /**
     * Returns whether two descriptors describe the same decode configuration, by comparing config
     * hashes. Epoch and stream identity are ignored. A null argument returns false.
     */
    public boolean sameDecodeConfig(StreamDescriptor other) {
        if (other == null) {
            return false;
        }
        return configHash.equals(other.configHash);
    }

    /**
     * Returns a copy with a different config epoch. All other fields are shared and the config
     * hash is unchanged, so this does not by itself require receivers to reconfigure.
     *
     * @throws IllegalArgumentException if the new epoch is below one
     */
    public StreamDescriptor withConfigEpoch(int newConfigEpoch) {
        return new StreamDescriptor(streamKey, newConfigEpoch, codecId, mime, profile, level,
                codedWidth, codedHeight, visibleWidth, visibleHeight, rotationDegrees,
                bitstreamFormat, configParts, colorInfo, frameRate);
    }

    private String computeConfigHash() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeString(out, codecId);
        writeString(out, mime);
        writeString(out, profile);
        writeString(out, level);
        writeInt(out, codedWidth);
        writeInt(out, codedHeight);
        writeInt(out, visibleWidth);
        writeInt(out, visibleHeight);
        writeInt(out, rotationDegrees);
        writeString(out, bitstreamFormat.name());
        writeInt(out, configParts.size());
        for (CodecConfigPart part : configParts) {
            writeString(out, part.getId());
            writeBytes(out, part.getPayload());
        }
        writeString(out, colorInfo.getColorSpace());
        writeString(out, colorInfo.getTransferFunction());
        out.write(colorInfo.isFullRange() ? 1 : 0);
        return toLowerHex(sha256(out.toByteArray()));
    }

    private static void writeString(ByteArrayOutputStream out, String value) {
        if (value == null) {
            out.write(0);
        } else {
            out.write(1);
            writeBytes(out, value.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void writeBytes(ByteArrayOutputStream out, byte[] bytes) {
        writeInt(out, bytes.length);
        out.write(bytes, 0, bytes.length);
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.write((value >>> 24) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm is unavailable", e);
        }
    }

    private static String toLowerHex(byte[] digest) {
        char[] chars = new char[digest.length * 2];
        for (int i = 0; i < digest.length; i++) {
            int b = digest[i] & 0xFF;
            chars[i * 2] = HEX_DIGITS[b >>> 4];
            chars[i * 2 + 1] = HEX_DIGITS[b & 0x0F];
        }
        return new String(chars);
    }

    /**
     * Mutable builder for {@link StreamDescriptor}.
     *
     * <p>Defaults: profile and level are null, visible size equals the coded size, rotation is
     * zero, configuration parts are empty, color is {@link ColorInfo#UNSPECIFIED} and the content
     * frame rate is zero. The coded size has no default and must be positive. Validation happens
     * once in {@link #build()}.</p>
     */
    public static final class Builder {

        private final StreamKey streamKey;
        private final int configEpoch;
        private final String codecId;
        private final String mime;
        private final BitstreamFormat bitstreamFormat;

        private String profile;
        private String level;
        private int codedWidth;
        private int codedHeight;
        private Integer visibleWidth;
        private Integer visibleHeight;
        private int rotationDegrees;
        private List<CodecConfigPart> configParts;
        private ColorInfo colorInfo;
        private int frameRate;

        private Builder(StreamKey streamKey, int configEpoch, String codecId, String mime,
                        BitstreamFormat bitstreamFormat) {
            this.streamKey = streamKey;
            this.configEpoch = configEpoch;
            this.codecId = codecId;
            this.mime = mime;
            this.bitstreamFormat = bitstreamFormat;
            this.rotationDegrees = 0;
            this.configParts = Collections.emptyList();
            this.colorInfo = ColorInfo.UNSPECIFIED;
        }

        /**
         * Sets the optional codec profile name; null means unspecified.
         */
        public Builder profile(String profile) {
            this.profile = profile;
            return this;
        }

        /**
         * Sets the optional codec level name; null means unspecified.
         */
        public Builder level(String level) {
            this.level = level;
            return this;
        }

        /**
         * Sets the encoder's coded size. Required: the build fails unless both dimensions are
         * positive.
         */
        public Builder codedSize(int width, int height) {
            this.codedWidth = width;
            this.codedHeight = height;
            return this;
        }

        /**
         * Sets the displayed region. Optional: when not called, both dimensions default to the
         * coded size.
         */
        public Builder visibleSize(int width, int height) {
            this.visibleWidth = width;
            this.visibleHeight = height;
            return this;
        }

        /**
         * Sets the display rotation in degrees; must be 0, 90, 180 or 270. Defaults to zero.
         */
        public Builder rotationDegrees(int rotationDegrees) {
            this.rotationDegrees = rotationDegrees;
            return this;
        }

        /**
         * Sets the ordered codec configuration parts. Optional: null and an omitted call both
         * default to an empty list.
         */
        public Builder configParts(List<CodecConfigPart> configParts) {
            this.configParts = configParts;
            return this;
        }

        /**
         * Sets the color description; null defaults to {@link ColorInfo#UNSPECIFIED}.
         */
        public Builder colorInfo(ColorInfo colorInfo) {
            this.colorInfo = colorInfo;
            return this;
        }

        /**
         * Sets the content frame-rate hint for the display side. Optional: defaults to zero, which
         * means unknown or cleared. Negative values are normalised to zero. The hint never enters
         * the config hash, so changing it does not require a new config epoch by itself.
         */
        public Builder frameRate(int frameRate) {
            this.frameRate = frameRate;
            return this;
        }

        /**
         * Validates the accumulated values and creates the immutable descriptor.
         *
         * @throws IllegalArgumentException if a required value is missing, a size is
         *                                  non-positive, the rotation is unsupported, or a
         *                                  required reference is null
         */
        public StreamDescriptor build() {
            int effectiveVisibleWidth = visibleWidth != null ? visibleWidth : codedWidth;
            int effectiveVisibleHeight = visibleHeight != null ? visibleHeight : codedHeight;
            List<CodecConfigPart> effectiveParts = configParts != null
                    ? configParts
                    : Collections.<CodecConfigPart>emptyList();
            ColorInfo effectiveColor = colorInfo != null ? colorInfo : ColorInfo.UNSPECIFIED;
            return new StreamDescriptor(streamKey, configEpoch, codecId, mime, profile, level,
                    codedWidth, codedHeight, effectiveVisibleWidth, effectiveVisibleHeight,
                    rotationDegrees, bitstreamFormat, effectiveParts, effectiveColor, frameRate);
        }
    }
}
