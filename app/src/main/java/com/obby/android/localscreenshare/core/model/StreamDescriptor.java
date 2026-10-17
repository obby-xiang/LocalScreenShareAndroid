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
 * <p>The {@link #getConfigHash()} digest covers every field that affects how an
 * encoded access unit must be decoded, but deliberately excludes {@code configEpoch}
 * and the stream identity, so descriptors of the same codec configuration share one
 * hash across epoch bumps.</p>
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
    private final String configHash;

    private StreamDescriptor(StreamKey streamKey, int configEpoch, String codecId, String mime,
                             String profile, String level, int codedWidth, int codedHeight,
                             int visibleWidth, int visibleHeight, int rotationDegrees,
                             BitstreamFormat bitstreamFormat, List<CodecConfigPart> configParts,
                             ColorInfo colorInfo) {
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
        this.configHash = computeConfigHash();
    }

    private static boolean isValidRotation(int rotationDegrees) {
        return rotationDegrees == 0
                || rotationDegrees == 90
                || rotationDegrees == 180
                || rotationDegrees == 270;
    }

    public static Builder builder(StreamKey streamKey, int configEpoch, String codecId,
                                  String mime, BitstreamFormat bitstreamFormat) {
        return new Builder(streamKey, configEpoch, codecId, mime, bitstreamFormat);
    }

    public StreamKey getStreamKey() {
        return streamKey;
    }

    public int getConfigEpoch() {
        return configEpoch;
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

    public int getCodedWidth() {
        return codedWidth;
    }

    public int getCodedHeight() {
        return codedHeight;
    }

    public int getVisibleWidth() {
        return visibleWidth;
    }

    public int getVisibleHeight() {
        return visibleHeight;
    }

    public int getRotationDegrees() {
        return rotationDegrees;
    }

    public BitstreamFormat getBitstreamFormat() {
        return bitstreamFormat;
    }

    public List<CodecConfigPart> getConfigParts() {
        return configParts;
    }

    public ColorInfo getColorInfo() {
        return colorInfo;
    }

    public String getConfigHash() {
        return configHash;
    }

    public boolean sameDecodeConfig(StreamDescriptor other) {
        if (other == null) {
            return false;
        }
        return configHash.equals(other.configHash);
    }

    public StreamDescriptor withConfigEpoch(int newConfigEpoch) {
        return new StreamDescriptor(streamKey, newConfigEpoch, codecId, mime, profile, level,
                codedWidth, codedHeight, visibleWidth, visibleHeight, rotationDegrees,
                bitstreamFormat, configParts, colorInfo);
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

        public Builder profile(String profile) {
            this.profile = profile;
            return this;
        }

        public Builder level(String level) {
            this.level = level;
            return this;
        }

        public Builder codedSize(int width, int height) {
            this.codedWidth = width;
            this.codedHeight = height;
            return this;
        }

        public Builder visibleSize(int width, int height) {
            this.visibleWidth = width;
            this.visibleHeight = height;
            return this;
        }

        public Builder rotationDegrees(int rotationDegrees) {
            this.rotationDegrees = rotationDegrees;
            return this;
        }

        public Builder configParts(List<CodecConfigPart> configParts) {
            this.configParts = configParts;
            return this;
        }

        public Builder colorInfo(ColorInfo colorInfo) {
            this.colorInfo = colorInfo;
            return this;
        }

        public StreamDescriptor build() {
            int effectiveVisibleWidth = visibleWidth != null ? visibleWidth : codedWidth;
            int effectiveVisibleHeight = visibleHeight != null ? visibleHeight : codedHeight;
            List<CodecConfigPart> effectiveParts = configParts != null
                    ? configParts
                    : Collections.<CodecConfigPart>emptyList();
            ColorInfo effectiveColor = colorInfo != null ? colorInfo : ColorInfo.UNSPECIFIED;
            return new StreamDescriptor(streamKey, configEpoch, codecId, mime, profile, level,
                    codedWidth, codedHeight, effectiveVisibleWidth, effectiveVisibleHeight,
                    rotationDegrees, bitstreamFormat, effectiveParts, effectiveColor);
        }
    }
}
