package com.obby.android.localscreenshare.core.net;

/**
 * Media channel a send session currently uses.
 *
 * <p>Every channel still carries complete access units; the kind only names which socket family
 * carries them, and it is what the session row prints as its {@code chan} field. The label is a
 * constant string so that printing a channel never allocates.</p>
 *
 * <p>The gRPC bidirectional stream is the control plane and the only media carrier of the first
 * batches; a media channel that swaps in reports itself through the same session, so the label
 * follows the transport rather than the configuration.</p>
 */
public enum MediaTransportKind {

    /**
     * Media carried on the gRPC bidirectional stream that also carries control messages.
     */
    GRPC("grpc"),

    /**
     * Media carried on a dedicated TLS stream.
     */
    TCP("tcp"),

    /**
     * Media carried on encrypted datagrams.
     */
    UDP("udp");

    private final String label;

    MediaTransportKind(String label) {
        this.label = label;
    }

    /**
     * Returns the stable log label of this channel, for example {@code grpc}.
     */
    public String getLabel() {
        return label;
    }
}
