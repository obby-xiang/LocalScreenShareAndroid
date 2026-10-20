package com.obby.android.localscreenshare.transport;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * gRPC channel limits shared by the client and server endpoints.
 *
 * <p>The flow-control window and the inbound message limit have to match on both sides: a frame
 * larger than the limit is rejected by the receiver, not by the sender.</p>
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class GrpcConstants {
    /**
     * HTTP/2 flow-control window used by both the gRPC client and server (1 MiB).
     */
    public static final int GRPC_FLOW_CONTROL_WINDOW = 1024 * 1024;

    /**
     * Maximum inbound message size accepted by both the gRPC client and server (8 MiB).
     */
    public static final int GRPC_MAX_INBOUND_MESSAGE_SIZE = 8 * 1024 * 1024;
}
