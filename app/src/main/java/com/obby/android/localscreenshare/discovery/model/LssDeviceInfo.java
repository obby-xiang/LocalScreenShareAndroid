package com.obby.android.localscreenshare.discovery.model;

import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

/**
 * Immutable description of one discovered device.
 *
 * <p>This is the discovery model: identity ({@code id}, {@code fingerprint}), advertised
 * capabilities, every address the device was seen at, and the store-derived presence. It is
 * {@link Parcelable} so the viewer service can receive the complete description through
 * {@code Constants.EXTRA_SERVICE_INFO}, while {@link com.obby.android.localscreenshare.discovery.LssServiceInfo}
 * stays only as the legacy NSD adapter contract.</p>
 *
 * <p>The display name is optional because the device name is not broadcast by default. Callers
 * render {@link #displayName()}, which falls back to {@code LSS-<id first 8>}, rather than
 * {@link #getName()}.</p>
 */
@Getter
@ToString
public final class LssDeviceInfo implements Parcelable {

    /** Length of the short id used for the display-name fallback and the NSD instance name. */
    public static final int SHORT_ID_LENGTH = 8;

    /** Placeholder used for the display name when the device does not advertise one. */
    public static final String NAME_PREFIX = "LSS-";

    /** Default protocol version when a device does not advertise one. */
    public static final String DEFAULT_PROTOCOL_VERSION = "1.0";

    /** Restores a device description from a parcel written by {@link #writeToParcel(Parcel, int)}. */
    public static final Creator<LssDeviceInfo> CREATOR = new Creator<>() {
        @Override
        public LssDeviceInfo createFromParcel(final Parcel in) {
            return new LssDeviceInfo(in);
        }

        @Override
        public LssDeviceInfo[] newArray(final int size) {
            return new LssDeviceInfo[size];
        }
    };

    @NonNull
    private final String id;

    @Nullable
    private final String fingerprint;

    @Nullable
    private final String name;

    @NonNull
    private final DeviceRole role;

    @NonNull
    private final ShareState state;

    @NonNull
    private final JoinPolicy join;

    private final boolean secure;

    @NonNull
    private final AuthMode auth;

    @NonNull
    private final String protocolVersion;

    private final int metadataVersion;

    private final int capabilities;

    @NonNull
    private final List<DeviceAddress> addresses;

    @NonNull
    private final DevicePresence presence;

    private final long lastSeenMs;

    private final long lastConfirmedMs;

    private final boolean favorite;

    private final boolean identityChanged;

    /**
     * Creates a device description. Null enum values fall back to their {@code UNKNOWN} constant,
     * a null protocol version falls back to {@link #DEFAULT_PROTOCOL_VERSION}, and the address list
     * is defensively copied and sorted.
     *
     * @throws IllegalArgumentException if {@code id} is blank
     */
    @Builder(builderMethodName = "builder")
    private LssDeviceInfo(final String id, final String fingerprint, final String name,
        final DeviceRole role, final ShareState state, final JoinPolicy join,
        final boolean secure, final AuthMode auth, final String protocolVersion,
        final int metadataVersion, final int capabilities, final List<DeviceAddress> addresses,
        final DevicePresence presence, final long lastSeenMs, final long lastConfirmedMs,
        final boolean favorite, final boolean identityChanged) {
        if (id == null || id.trim().isEmpty()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        this.id = id;
        this.fingerprint = fingerprint;
        this.name = name == null || name.trim().isEmpty() ? null : name;
        this.role = role == null ? DeviceRole.UNKNOWN : role;
        this.state = state == null ? ShareState.UNKNOWN : state;
        this.join = join == null ? JoinPolicy.UNKNOWN : join;
        this.secure = secure;
        this.auth = auth == null ? AuthMode.UNKNOWN : auth;
        this.protocolVersion = protocolVersion == null ? DEFAULT_PROTOCOL_VERSION : protocolVersion;
        this.metadataVersion = metadataVersion;
        this.capabilities = capabilities;

        final List<DeviceAddress> sortedAddresses = addresses == null
            ? new ArrayList<>() : new ArrayList<>(addresses);
        Collections.sort(sortedAddresses);
        this.addresses = Collections.unmodifiableList(sortedAddresses);

        this.presence = presence == null ? DevicePresence.HEARD : presence;
        this.lastSeenMs = lastSeenMs;
        this.lastConfirmedMs = lastConfirmedMs;
        this.favorite = favorite;
        this.identityChanged = identityChanged;
    }

    /** Restores a device description written by {@link #writeToParcel(Parcel, int)}. */
    private LssDeviceInfo(@NonNull final Parcel in) {
        this(in.readString(), in.readString(), in.readString(),
            DeviceRole.valueOf(in.readString()), ShareState.valueOf(in.readString()),
            JoinPolicy.valueOf(in.readString()), in.readInt() != 0, AuthMode.valueOf(in.readString()),
            in.readString(), in.readInt(), in.readInt(),
            in.createTypedArrayList(DeviceAddress.CREATOR), DevicePresence.valueOf(in.readString()),
            in.readLong(), in.readLong(), in.readInt() != 0, in.readInt() != 0);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull final Parcel dest, final int flags) {
        dest.writeString(id);
        dest.writeString(fingerprint);
        dest.writeString(name);
        dest.writeString(role.name());
        dest.writeString(state.name());
        dest.writeString(join.name());
        dest.writeInt(secure ? 1 : 0);
        dest.writeString(auth.name());
        dest.writeString(protocolVersion);
        dest.writeInt(metadataVersion);
        dest.writeInt(capabilities);
        dest.writeTypedList(addresses);
        dest.writeString(presence.name());
        dest.writeLong(lastSeenMs);
        dest.writeLong(lastConfirmedMs);
        dest.writeInt(favorite ? 1 : 0);
        dest.writeInt(identityChanged ? 1 : 0);
    }

    /** Returns the first {@link #SHORT_ID_LENGTH} characters of {@link #getId()}, uppercased. */
    @NonNull
    public String shortId() {
        final String upper = id.toUpperCase(Locale.ROOT);
        return upper.length() <= SHORT_ID_LENGTH ? upper : upper.substring(0, SHORT_ID_LENGTH);
    }

    /** Returns the advertised name, or {@code LSS-<short id>} when no name is advertised. */
    @NonNull
    public String displayName() {
        return name != null ? name : NAME_PREFIX + shortId();
    }

    /** Returns the best address to connect to, or {@code null} when no address is known. */
    @Nullable
    public DeviceAddress primaryAddress() {
        return addresses.isEmpty() ? null : addresses.get(0);
    }

    /** True when this device has an application-level confirmation still inside its window. */
    public boolean isConfirmed() {
        return presence == DevicePresence.CONFIRMED;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof LssDeviceInfo)) {
            return false;
        }
        final LssDeviceInfo that = (LssDeviceInfo) o;
        return secure == that.secure && metadataVersion == that.metadataVersion
            && capabilities == that.capabilities && lastSeenMs == that.lastSeenMs
            && lastConfirmedMs == that.lastConfirmedMs && favorite == that.favorite
            && identityChanged == that.identityChanged && id.equals(that.id)
            && Objects.equals(fingerprint, that.fingerprint) && Objects.equals(name, that.name)
            && role == that.role && state == that.state && join == that.join && auth == that.auth
            && protocolVersion.equals(that.protocolVersion) && addresses.equals(that.addresses)
            && presence == that.presence;
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, fingerprint, name, role, state, join, secure, auth, protocolVersion,
            metadataVersion, capabilities, addresses, presence, lastSeenMs, lastConfirmedMs, favorite,
            identityChanged);
    }
}
