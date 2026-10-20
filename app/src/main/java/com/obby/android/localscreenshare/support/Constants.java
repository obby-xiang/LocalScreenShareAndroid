package com.obby.android.localscreenshare.support;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * Process-wide runtime values shared by the Android components.
 *
 * <p>It holds the processor count that sizes the gRPC executor pools and the placeholder host
 * address reported before NSD resolution yields a real address. Domain constants live with their
 * domain: service contract in {@link ServiceProtocol}, NSD in
 * {@code discovery.nsd.NsdConstants}, multicast in {@code discovery.udp.MulticastConstants},
 * Register and scan in {@code discovery.register.RegisterConstants} and
 * {@code discovery.scan.ScanConstants}, gRPC limits in {@code transport.GrpcConstants}.</p>
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class Constants {
    /**
     * Processor count read when the class is initialized; sizes the gRPC executor pools.
     */
    public static final int CPU_COUNT = Runtime.getRuntime().availableProcessors();

    /**
     * Placeholder host address reported until NSD resolution yields a real address, so a server
     * info value is never built with an empty host.
     */
    public static final String UNKNOWN_HOST_ADDRESS = "N/A";
}
