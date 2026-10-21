package com.obby.android.localscreenshare.core.model;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable description of an audio track's decode configuration.
 *
 * <p>Audio is a track of its own next to the video stream: it has its own config epoch, its own
 * descriptor and its own access units, so a failure or a format change on either side never forces
 * a reconfiguration of the other. The descriptor names the codec and carries everything a decoder
 * needs: sample rate, channel count, bitrate, the encoder's frame length and the ordered
 * configuration parts (for AAC-LC, the two-byte AudioSpecificConfig as {@code csd-0}).</p>
 *
 * <p><b>Validation.</b> The sample rate and bitrate must be positive, the channel count must be one
 * or two, the frame length must be {@link #FRAME_SAMPLES_AAC_LC} (this version encodes AAC-LC only)
 * and at least one configuration part must be present, because a decoder cannot be configured from
 * the descriptor's numbers alone. The config epoch must be at least one.</p>
 *
 * <p><b>Config hash.</b> {@link #getConfigHash()} is a SHA-256 digest rendered as lowercase
 * hexadecimal. It covers every field that a decoder consumes and deliberately excludes the config
 * epoch, so an epoch-only republication keeps the same hash and does not force a receiver to
 * reconfigure.</p>
 */
public final class AudioDescriptor {

    /**
     * Samples per frame of an AAC-LC access unit.
     */
    public static final int FRAME_SAMPLES_AAC_LC = 1024;

    private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();

    private final int configEpoch;
    private final String codecId;
    private final String mime;
    private final String profile;
    private final int sampleRate;
    private final int channels;
    private final int bitrateBps;
    private final int frameSamples;
    private final List<CodecConfigPart> configParts;
    private final String configHash;

    private AudioDescriptor(int configEpoch, String codecId, String mime, String profile,
                            int sampleRate, int channels, int bitrateBps, int frameSamples,
                            List<CodecConfigPart> configParts) {
        if (configEpoch < 1) {
            throw new IllegalArgumentException("configEpoch must be >= 1");
        }
        if (codecId == null || codecId.trim().isEmpty()) {
            throw new IllegalArgumentException("codecId must not be null or blank");
        }
        if (mime == null || mime.trim().isEmpty()) {
            throw new IllegalArgumentException("mime must not be null or blank");
        }
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive");
        }
        if (channels < 1 || channels > 2) {
            throw new IllegalArgumentException("channels must be 1 or 2");
        }
        if (bitrateBps <= 0) {
            throw new IllegalArgumentException("bitrateBps must be positive");
        }
        if (frameSamples != FRAME_SAMPLES_AAC_LC) {
            throw new IllegalArgumentException(
                    "frameSamples must be " + FRAME_SAMPLES_AAC_LC + " for AAC-LC");
        }
        if (configParts == null || configParts.isEmpty()) {
            throw new IllegalArgumentException("configParts must not be empty");
        }
        this.configEpoch = configEpoch;
        this.codecId = codecId;
        this.mime = mime;
        this.profile = profile;
        this.sampleRate = sampleRate;
        this.channels = channels;
        this.bitrateBps = bitrateBps;
        this.frameSamples = frameSamples;
        this.configParts = Collections.unmodifiableList(new ArrayList<>(configParts));
        this.configHash = computeConfigHash();
    }

    /**
     * Starts building an audio descriptor for an AAC-LC track.
     *
     * <p>The codec id, MIME type, sample rate, channel count and bitrate are required; the frame
     * length defaults to {@link #FRAME_SAMPLES_AAC_LC} and the configuration parts have no default,
     * because a descriptor without them cannot configure a decoder.</p>
     *
     * @param configEpoch configuration epoch; must be at least one
     * @param codecId     codec id used as the capability key; required
     * @param mime        codec MIME type; required
     * @param sampleRate  sample rate in hertz; must be positive
     * @param channels    channel count, one or two
     * @param bitrateBps  target bitrate in bits per second; must be positive
     */
    public static Builder builder(int configEpoch, String codecId, String mime, int sampleRate,
                                  int channels, int bitrateBps) {
        return new Builder(configEpoch, codecId, mime, sampleRate, channels, bitrateBps);
    }

    /**
     * Returns the configuration epoch of this decode configuration.
     */
    public int getConfigEpoch() {
        return configEpoch;
    }

    /**
     * Returns the codec id used as the capability key.
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
     * Returns the sample rate in hertz.
     */
    public int getSampleRate() {
        return sampleRate;
    }

    /**
     * Returns the channel count, one or two.
     */
    public int getChannels() {
        return channels;
    }

    /**
     * Returns the target bitrate in bits per second.
     */
    public int getBitrateBps() {
        return bitrateBps;
    }

    /**
     * Returns the number of samples carried by one encoded frame.
     */
    public int getFrameSamples() {
        return frameSamples;
    }

    /**
     * Returns the duration of one encoded frame in microseconds.
     *
     * <p>Derived from the sample rate and {@link #getFrameSamples()}; integer division rounds down,
     * which is the same rounding the encoder's PTS derivation uses.</p>
     */
    public long getFrameDurationUs() {
        return frameSamples * 1_000_000L / sampleRate;
    }

    /**
     * Returns the ordered, unmodifiable configuration parts; never empty.
     */
    public List<CodecConfigPart> getConfigParts() {
        return configParts;
    }

    /**
     * Returns the stable lowercase-hex SHA-256 digest of the decode configuration.
     */
    public String getConfigHash() {
        return configHash;
    }

    /**
     * Returns whether two descriptors describe the same decode configuration, by comparing config
     * hashes. Epoch is ignored. A null argument returns false.
     */
    public boolean sameDecodeConfig(AudioDescriptor other) {
        return other != null && configHash.equals(other.configHash);
    }

    /**
     * Returns a copy with a different config epoch and the same config hash.
     *
     * @throws IllegalArgumentException if the new epoch is below one
     */
    public AudioDescriptor withConfigEpoch(int newConfigEpoch) {
        return new AudioDescriptor(newConfigEpoch, codecId, mime, profile, sampleRate, channels,
                bitrateBps, frameSamples, configParts);
    }

    private String computeConfigHash() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeString(out, codecId);
        writeString(out, mime);
        writeString(out, profile);
        writeInt(out, sampleRate);
        writeInt(out, channels);
        writeInt(out, bitrateBps);
        writeInt(out, frameSamples);
        writeInt(out, configParts.size());
        for (CodecConfigPart part : configParts) {
            writeString(out, part.getId());
            writeBytes(out, part.getPayload());
        }
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
     * Mutable builder for {@link AudioDescriptor}.
     *
     * <p>Defaults: profile is null and the frame length is {@link #FRAME_SAMPLES_AAC_LC}. The
     * configuration parts have no default and must be set before {@link #build()}; validation
     * happens once, in the constructor.</p>
     */
    public static final class Builder {

        private final int configEpoch;
        private final String codecId;
        private final String mime;
        private final int sampleRate;
        private final int channels;
        private final int bitrateBps;

        private String profile;
        private int frameSamples = FRAME_SAMPLES_AAC_LC;
        private List<CodecConfigPart> configParts;

        private Builder(int configEpoch, String codecId, String mime, int sampleRate, int channels,
                        int bitrateBps) {
            this.configEpoch = configEpoch;
            this.codecId = codecId;
            this.mime = mime;
            this.sampleRate = sampleRate;
            this.channels = channels;
            this.bitrateBps = bitrateBps;
        }

        /**
         * Sets the optional codec profile name; null means unspecified.
         */
        public Builder profile(String profile) {
            this.profile = profile;
            return this;
        }

        /**
         * Sets the frame length in samples; {@link #build()} rejects anything but
         * {@link #FRAME_SAMPLES_AAC_LC}.
         */
        public Builder frameSamples(int frameSamples) {
            this.frameSamples = frameSamples;
            return this;
        }

        /**
         * Sets the ordered configuration parts; required and must not be empty.
         */
        public Builder configParts(List<CodecConfigPart> configParts) {
            this.configParts = configParts;
            return this;
        }

        /**
         * Validates the accumulated values and creates the immutable descriptor.
         *
         * @throws IllegalArgumentException if a required value is missing or out of range
         */
        public AudioDescriptor build() {
            return new AudioDescriptor(configEpoch, codecId, mime, profile, sampleRate, channels,
                    bitrateBps, frameSamples, configParts);
        }
    }
}
