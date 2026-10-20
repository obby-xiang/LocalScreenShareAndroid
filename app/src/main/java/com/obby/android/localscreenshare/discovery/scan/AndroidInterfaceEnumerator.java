package com.obby.android.localscreenshare.discovery.scan;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.util.Log;

import androidx.annotation.NonNull;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the interfaces of this device from {@code ConnectivityManager}.
 *
 * <p>Every network the platform knows is inspected through {@code LinkProperties}: interface
 * name, IPv4 link addresses and prefix lengths. Only Wi-Fi and Ethernet survive
 * {@link InterfaceFilter}; IPv6-only interfaces simply produce no candidate because the scan is
 * IPv4 only. A network whose properties or capabilities are unavailable is skipped, never assumed
 * eligible.</p>
 *
 * <p>This class is the only Android-touching part of the interface path; the filter and the
 * candidate generation are pure JVM and are covered by unit tests.</p>
 */
public final class AndroidInterfaceEnumerator implements InterfaceEnumerator {

    private static final String TAG = "InterfaceEnumerator";

    @NonNull
    private final ConnectivityManager mConnectivityManager;

    /** Creates the enumerator from an application context. */
    public AndroidInterfaceEnumerator(@NonNull final Context context) {
        mConnectivityManager = context.getSystemService(ConnectivityManager.class);
    }

    @Override
    @NonNull
    public List<LocalInterface> enumerate() {
        final List<LocalInterface> candidates = new ArrayList<>();
        if (mConnectivityManager == null) {
            return candidates;
        }

        final Network[] networks;
        try {
            networks = mConnectivityManager.getAllNetworks();
        } catch (RuntimeException e) {
            Log.w(TAG, "enumerate: unable to list networks", e);
            return candidates;
        }

        for (final Network network : networks) {
            final LinkProperties linkProperties = mConnectivityManager.getLinkProperties(network);
            final NetworkCapabilities capabilities = mConnectivityManager.getNetworkCapabilities(network);
            if (linkProperties == null || capabilities == null) {
                continue;
            }
            final String name = linkProperties.getInterfaceName();
            final LocalInterface.Transport transport = transportOf(capabilities);
            for (final LinkAddress linkAddress : linkProperties.getLinkAddresses()) {
                final InetAddress address = linkAddress.getAddress();
                if (!(address instanceof Inet4Address)) {
                    continue;
                }
                candidates.add(new LocalInterface(name == null ? "" : name, (Inet4Address) address,
                    linkAddress.getPrefixLength(), transport, isPointToPoint(name),
                    address.isLoopbackAddress() || address.isAnyLocalAddress()));
            }
        }
        return InterfaceFilter.eligible(candidates);
    }

    /** Maps the transports of one network, preferring the transport that disqualifies a scan. */
    @NonNull
    private static LocalInterface.Transport transportOf(@NonNull final NetworkCapabilities capabilities) {
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            return LocalInterface.Transport.VPN;
        }
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return LocalInterface.Transport.WIFI;
        }
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            return LocalInterface.Transport.ETHERNET;
        }
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            return LocalInterface.Transport.CELLULAR;
        }
        return LocalInterface.Transport.OTHER;
    }

    /** Reads the point-to-point flag of the named OS interface; unknown names are not exclusive. */
    private static boolean isPointToPoint(@NonNull final String name) {
        if (name.isEmpty()) {
            return false;
        }
        try {
            final NetworkInterface networkInterface = NetworkInterface.getByName(name);
            return networkInterface != null && networkInterface.isPointToPoint();
        } catch (SocketException e) {
            return false;
        }
    }
}
