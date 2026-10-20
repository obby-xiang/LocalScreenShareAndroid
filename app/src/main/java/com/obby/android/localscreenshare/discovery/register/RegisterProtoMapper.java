package com.obby.android.localscreenshare.discovery.register;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.obby.android.localscreenshare.discovery.model.AuthMode;
import com.obby.android.localscreenshare.discovery.model.DeviceAddress;
import com.obby.android.localscreenshare.discovery.model.DeviceRole;
import com.obby.android.localscreenshare.discovery.model.DiscoveryChannel;
import com.obby.android.localscreenshare.discovery.model.JoinPolicy;
import com.obby.android.localscreenshare.discovery.model.DeviceInfo;
import com.obby.android.localscreenshare.discovery.model.ShareState;
import com.obby.android.localscreenshare.discovery.provider.DeviceObservation;
import com.obby.android.localscreenshare.grpc.screenstream.DeviceSummary;
import com.obby.android.localscreenshare.grpc.screenstream.RegisterResponse;
import com.obby.android.localscreenshare.transport.ScreenStreamProtoMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Stateless mapping between the discovery model and the {@code Register} wire messages.
 *
 * <p>The mapper is pure JVM: it builds and reads proto messages without a channel, so a round trip
 * is covered by ordinary unit tests. The success criterion is applied on the receiving side of a
 * response — accepted, a compatible protocol version and a usable device id — because a remote
 * server can always answer with an empty or future-version summary.</p>
 */
public final class RegisterProtoMapper {

    private RegisterProtoMapper() {
    }

    /** Builds the discovery summary of a device, including every address the store knows. */
    @NonNull
    public static DeviceSummary toSummary(@NonNull final DeviceInfo device) {
        final DeviceSummary.Builder builder = DeviceSummary.newBuilder()
            .setDeviceId(device.getId())
            .setRole(device.getRole().toWire())
            .setState(device.getState().toWire())
            .setJoin(device.getJoin().toWire())
            .setSecure(device.isSecure())
            .setAuth(device.getAuth().toWire())
            .setMetadataVersion(device.getMetadataVersion())
            .setCapabilities(device.getCapabilities());
        if (device.getFingerprint() != null) {
            builder.setFingerprint(device.getFingerprint());
        }
        if (device.getName() != null) {
            builder.setName(device.getName());
        }
        for (final DeviceAddress address : device.getAddresses()) {
            builder.addAddresses(com.obby.android.localscreenshare.grpc.screenstream.DeviceAddress.newBuilder()
                .setHost(address.getHost())
                .setPort(address.getPort())
                .setFamily(address.getFamily())
                .build());
        }
        return builder.build();
    }

    /**
     * Converts a received summary into a store observation.
     *
     * @param summary discovery summary carried by the message
     * @param channel channel the call belongs to
     * @param host address the call was received from
     * @param port address the call was received at
     * @param nowMs observation time, or null to let the store stamp its own clock
     */
    @NonNull
    public static DeviceObservation toObservation(@NonNull final DeviceSummary summary,
        @NonNull final DiscoveryChannel channel, @NonNull final String host, final int port,
        @Nullable final Long nowMs) {
        return DeviceObservation.builder()
            .source(channel)
            .id(summary.getDeviceId())
            .fingerprint(emptyToNull(summary.getFingerprint()))
            .name(emptyToNull(summary.getName()))
            .role(DeviceRole.fromWire(summary.getRole()))
            .state(ShareState.fromWire(summary.getState()))
            .join(JoinPolicy.fromWire(summary.getJoin()))
            .secure(summary.getSecure())
            .auth(AuthMode.fromWire(summary.getAuth()))
            .metadataVersion(summary.getMetadataVersion())
            .capabilities(summary.getCapabilities() == 0 ? null : summary.getCapabilities())
            .host(host)
            .port(port > 0 ? port : null)
            .lastSeenMs(nowMs)
            .build();
    }

    /** Returns the addresses of a summary as discovery addresses, in wire order. */
    @NonNull
    public static List<DeviceAddress> toAddresses(@NonNull final DeviceSummary summary,
        @NonNull final DiscoveryChannel channel) {
        final List<DeviceAddress> addresses = new ArrayList<>(summary.getAddressesCount());
        for (final com.obby.android.localscreenshare.grpc.screenstream.DeviceAddress address
            : summary.getAddressesList()) {
            if (!address.getHost().trim().isEmpty() && address.getPort() > 0 && address.getPort() <= 0xFFFF) {
                addresses.add(new DeviceAddress(address.getHost(), address.getPort(), channel));
            }
        }
        return addresses;
    }

    /**
     * Returns why a response does not prove reachability, or null when it does.
     *
     * <p>A rejection, an incompatible protocol version or a missing device id all leave the
     * device in the "heard" state only.</p>
     */
    @Nullable
    public static String rejectionReason(@Nullable final RegisterResponse response) {
        if (response == null) {
            return "no_response";
        }
        if (!response.getAccepted()) {
            return emptyToNull(response.getRejectReason()) != null
                ? response.getRejectReason() : "rejected";
        }
        if (!isVersionCompatible(response.getProtocolVersion())) {
            return "unsupported_version";
        }
        if (response.getSelf().getDeviceId().trim().isEmpty()) {
            return "missing_identity";
        }
        return null;
    }

    /** Normalizes a wire protocol version: {@code 0} means the first version. */
    public static int normalizeVersion(final int version) {
        return version <= 0 ? ScreenStreamProtoMapper.PROTOCOL_VERSION : version;
    }

    /** True when a wire protocol version can be served by this implementation. */
    public static boolean isVersionCompatible(final int version) {
        final int normalized = normalizeVersion(version);
        return normalized > 0 && normalized <= ScreenStreamProtoMapper.PROTOCOL_VERSION;
    }

    @Nullable
    private static String emptyToNull(@Nullable final String value) {
        return value == null || value.trim().isEmpty() ? null : value;
    }
}
