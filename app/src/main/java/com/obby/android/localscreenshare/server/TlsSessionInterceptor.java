package com.obby.android.localscreenshare.server;

import androidx.annotation.Nullable;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;

import javax.net.ssl.SSLSession;

/**
 * Publishes the TLS session of the current call to the application layer.
 *
 * <p>gRPC attaches {@link Grpc#TRANSPORT_ATTR_SSL_SESSION} to the call, but a handler would have to
 * reach into transport internals to read it. This interceptor copies the session into a
 * {@link Context} key, mirroring the existing remote-address interceptor, so a call handler can
 * derive the peer identity from the certificate that actually completed the handshake.</p>
 *
 * <p>The session is absent on a plaintext call; {@link #currentSession()} returns null there, and
 * the caller must treat the identity as unverified rather than invent one.</p>
 */
public final class TlsSessionInterceptor implements ServerInterceptor {

    private static final Context.Key<SSLSession> SSL_SESSION =
        Context.key("lss-ssl-session");

    /**
     * Returns the TLS session of the current call, or null on a plaintext call.
     */
    @Nullable
    public static SSLSession currentSession() {
        return SSL_SESSION.get();
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
        final ServerCall<ReqT, RespT> call, final Metadata headers,
        final ServerCallHandler<ReqT, RespT> next) {
        final SSLSession session =
            call.getAttributes().get(Grpc.TRANSPORT_ATTR_SSL_SESSION);
        final Context context = session == null
            ? Context.current() : Context.current().withValue(SSL_SESSION, session);
        return Contexts.interceptCall(context, call, headers, next);
    }
}
