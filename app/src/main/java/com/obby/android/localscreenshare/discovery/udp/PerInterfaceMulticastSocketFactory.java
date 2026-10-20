package com.obby.android.localscreenshare.discovery.udp;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * Production {@link MulticastSocketFactory}: one {@link MulticastSocket} per usable interface.
 *
 * <p>Plain {@code java.net} only: interfaces come from
 * {@link NetworkInterface#getNetworkInterfaces()} and every socket is a plain
 * {@link MulticastSocket}, so no Android API is involved. The class was named
 * {@code AndroidMulticastSocketFactory} before TB-ENG-011 R5; that name implied a platform
 * binding the code never had.</p>
 *
 * <p>Each socket joins the same group on exactly one interface. This avoids the outbound-interface
 * race of a single socket on a multi-homed device: the kernel picks the interface for a shared
 * socket per send, while a per-interface socket always leaves through the interface it joined.
 * Interfaces are filtered to up, non-loopback, multicast-capable, non-point-to-point interfaces
 * with an IPv4 address, and at most {@link MulticastConfig#getMaxInterfaces()} sockets are opened.
 * A socket that cannot be prepared is closed and skipped; only resolving the group address can
 * fail the whole open.</p>
 *
 * <p>Loopback delivery is left at the socket default because the sandboxed Android API does not
 * expose the loopback switch; the receiver filters this device's own announcements by device id
 * and never relies on loopback being off.</p>
 */
public final class PerInterfaceMulticastSocketFactory implements MulticastSocketFactory {

    @NonNull
    @Override
    public List<MulticastEndpoint> open(@NonNull final MulticastConfig config) throws IOException {
        final InetAddress group = InetAddress.getByName(config.getGroup());
        final List<NetworkInterface> interfaces = usableInterfaces(config.getMaxInterfaces());
        final List<MulticastEndpoint> endpoints = new ArrayList<>(interfaces.size());
        for (final NetworkInterface networkInterface : interfaces) {
            final MulticastEndpoint endpoint = openEndpoint(config, group, networkInterface);
            if (endpoint != null) {
                endpoints.add(endpoint);
            }
        }
        return Collections.unmodifiableList(endpoints);
    }

    @Nullable
    private MulticastEndpoint openEndpoint(@NonNull final MulticastConfig config,
        @NonNull final InetAddress group, @NonNull final NetworkInterface networkInterface) {
        MulticastSocket socket = null;
        try {
            socket = new MulticastSocket(null);
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(config.getPort()));
            socket.setTimeToLive(config.getTtl());
            // Loopback delivery is not disabled: the sandboxed Android API no longer exposes the
            // loopback switch, and self announcements are filtered by device id anyway.
            socket.joinGroup(new InetSocketAddress(group, config.getPort()), networkInterface);
            return new SocketEndpoint(networkInterface.getName(), socket, group, config.getPort());
        } catch (IOException | RuntimeException e) {
            if (socket != null) {
                try {
                    socket.close();
                } catch (RuntimeException ignored) {
                    // Nothing left to release.
                }
            }
            return null;
        }
    }

    @NonNull
    private static List<NetworkInterface> usableInterfaces(final int maxInterfaces) {
        final List<NetworkInterface> interfaces = new ArrayList<>(maxInterfaces);
        try {
            final Enumeration<NetworkInterface> enumeration = NetworkInterface.getNetworkInterfaces();
            while (enumeration != null && enumeration.hasMoreElements() && interfaces.size() < maxInterfaces) {
                final NetworkInterface networkInterface = enumeration.nextElement();
                if (isUsable(networkInterface)) {
                    interfaces.add(networkInterface);
                }
            }
        } catch (SocketException e) {
            // No interface can be enumerated; the channel runs without a socket.
        }
        return interfaces;
    }

    private static boolean isUsable(@NonNull final NetworkInterface networkInterface) {
        try {
            if (!networkInterface.isUp() || networkInterface.isLoopback()
                || networkInterface.isPointToPoint() || !networkInterface.supportsMulticast()) {
                return false;
            }
            final Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
            while (addresses.hasMoreElements()) {
                final InetAddress address = addresses.nextElement();
                if (address instanceof Inet4Address && !address.isLoopbackAddress()
                    && !address.isLinkLocalAddress()) {
                    return true;
                }
            }
        } catch (SocketException e) {
            return false;
        }
        return false;
    }

    /** {@link MulticastEndpoint} over one bound and joined socket. */
    private static final class SocketEndpoint implements MulticastEndpoint {
        @NonNull
        private final String mInterfaceName;

        @NonNull
        private final MulticastSocket mSocket;

        @NonNull
        private final InetAddress mGroup;

        private final int mPort;

        @Nullable
        private volatile InetAddress mLastSender;

        private SocketEndpoint(@NonNull final String interfaceName, @NonNull final MulticastSocket socket,
            @NonNull final InetAddress group, final int port) {
            mInterfaceName = interfaceName;
            mSocket = socket;
            mGroup = group;
            mPort = port;
        }

        @NonNull
        @Override
        public String interfaceName() {
            return mInterfaceName;
        }

        @Override
        public int receive(@NonNull final byte[] buffer) throws IOException {
            final java.net.DatagramPacket packet = new java.net.DatagramPacket(buffer, buffer.length);
            mSocket.receive(packet);
            mLastSender = packet.getAddress();
            return packet.getLength();
        }

        @Nullable
        @Override
        public InetAddress lastSender() {
            return mLastSender;
        }

        @Override
        public void send(@NonNull final byte[] payload) throws IOException {
            mSocket.send(new java.net.DatagramPacket(payload, payload.length, mGroup, mPort));
        }

        @Override
        public void close() {
            mSocket.close();
        }
    }
}
