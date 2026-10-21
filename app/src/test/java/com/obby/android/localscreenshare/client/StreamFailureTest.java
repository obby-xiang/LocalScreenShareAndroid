package com.obby.android.localscreenshare.client;

import com.obby.android.localscreenshare.authorization.AuthorizationPolicy;
import com.obby.android.localscreenshare.trust.PinningTrustManager;

import org.junit.Test;

import java.security.cert.CertificateException;

import javax.net.ssl.SSLHandshakeException;

import io.grpc.Status;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Parsing of client stream terminations into a loggable, classifiable reason.
 *
 * <p>The fixtures mirror what the transport actually delivers: a server rejection is a status with
 * a description, and a rejected handshake is a transport status whose cause chain carries the
 * exception the pinning trust manager threw.</p>
 */
public class StreamFailureTest {

    @Test
    public void aCompletedStreamIsNotAFailure() {
        final StreamFailure failure = StreamFailure.completed();

        assertEquals(StreamFailure.REASON_COMPLETED, failure.getReason());
        assertFalse(failure.isIdentityRejected());
        assertNull(failure.getStatusCode());
        assertNull(failure.getDescription());
        assertTrue(failure.toLogText().contains("reason = completed"));
    }

    @Test
    public void aServerSideIdentityMismatchIsReadFromTheStatusDescription() {
        final StreamFailure failure = StreamFailure.from(Status.PERMISSION_DENIED
            .withDescription(PinningTrustManager.REASON_IDENTITY_MISMATCH)
            .asRuntimeException());

        assertTrue(failure.isIdentityRejected());
        assertEquals(PinningTrustManager.REASON_IDENTITY_MISMATCH, failure.getReason());
        assertEquals(Status.Code.PERMISSION_DENIED, failure.getStatusCode());
        assertEquals(PinningTrustManager.REASON_IDENTITY_MISMATCH, failure.getDescription());
    }

    @Test
    public void aRejectedHandshakeIsReadFromTheCauseChain() {
        final SSLHandshakeException handshake = new SSLHandshakeException("handshake failed");
        handshake.initCause(new CertificateException(PinningTrustManager.REASON_IDENTITY_CHANGED));

        final StreamFailure failure = StreamFailure.from(Status.UNAVAILABLE
            .withDescription("Connection closed")
            .withCause(handshake)
            .asRuntimeException());

        assertTrue(failure.isIdentityRejected());
        assertEquals(PinningTrustManager.REASON_IDENTITY_CHANGED, failure.getReason());
        assertEquals(Status.Code.UNAVAILABLE, failure.getStatusCode());
        assertTrue(failure.toLogText(), failure.toLogText().contains("identity_changed"));
    }

    @Test
    public void aMissingPinIsAnIdentityRejectionToo() {
        final StreamFailure failure = StreamFailure.from(new CertificateException(
            PinningTrustManager.REASON_PIN_UNKNOWN));

        assertTrue(failure.isIdentityRejected());
        assertEquals(PinningTrustManager.REASON_PIN_UNKNOWN, failure.getReason());
        assertEquals(Status.Code.UNKNOWN, failure.getStatusCode());
    }

    @Test
    public void anAuthorizationRefusalIsClassifiedFromTheStatusDescription() {
        final StreamFailure denied = StreamFailure.from(Status.PERMISSION_DENIED
            .withDescription(AuthorizationPolicy.REASON_DENIED).asRuntimeException());
        final StreamFailure timeout = StreamFailure.from(Status.PERMISSION_DENIED
            .withDescription(AuthorizationPolicy.REASON_TIMEOUT).asRuntimeException());
        final StreamFailure revoked = StreamFailure.from(Status.PERMISSION_DENIED
            .withDescription(AuthorizationPolicy.REASON_REVOKED).asRuntimeException());

        assertTrue(denied.isAuthorizationRejected());
        assertTrue(timeout.isAuthorizationRejected());
        assertTrue(revoked.isAuthorizationRejected());
        assertEquals(AuthorizationPolicy.REASON_DENIED, denied.getReason());
        assertEquals(AuthorizationPolicy.REASON_TIMEOUT, timeout.getReason());
        assertEquals(AuthorizationPolicy.REASON_REVOKED, revoked.getReason());
    }

    @Test
    public void anAuthorizationRefusalIsNotAnIdentityRejection() {
        final StreamFailure failure = StreamFailure.from(Status.PERMISSION_DENIED
            .withDescription(AuthorizationPolicy.REASON_DENIED).asRuntimeException());

        assertFalse(failure.isIdentityRejected());
        assertTrue(failure.toLogText().contains(AuthorizationPolicy.REASON_DENIED));
    }

    @Test
    public void aServerReasonThatIsNotAboutIdentityKeepsItsDescription() {
        final StreamFailure failure = StreamFailure.from(Status.ABORTED
            .withDescription("session taken over by a newer connection")
            .asRuntimeException());

        assertFalse(failure.isIdentityRejected());
        assertEquals("session taken over by a newer connection", failure.getReason());
        assertEquals(Status.Code.ABORTED, failure.getStatusCode());
    }

    @Test
    public void aStatusWithoutDescriptionFallsBackToTheCode() {
        final StreamFailure failure = StreamFailure.from(Status.UNAVAILABLE.asRuntimeException());

        assertFalse(failure.isIdentityRejected());
        assertEquals("unavailable", failure.getReason());
        assertNull(failure.getDescription());
    }

    @Test
    public void anOrdinaryExceptionStillProducesALoggableFailure() {
        final StreamFailure failure = StreamFailure.from(new IllegalStateException("stream broke"));

        assertFalse(failure.isIdentityRejected());
        assertEquals("unknown", failure.getReason());
        assertTrue(failure.toLogText(),
            failure.toLogText().contains("IllegalStateException: stream broke"));
    }

    @Test
    public void theLogTextNamesStatusDescriptionAndCause() {
        final Throwable error = Status.PERMISSION_DENIED
            .withDescription(PinningTrustManager.REASON_IDENTITY_MISMATCH)
            .asRuntimeException();

        final String text = StreamFailure.from(error).toLogText();

        assertTrue(text, text.startsWith("reason = identity_mismatch"));
        assertTrue(text, text.contains("status = PERMISSION_DENIED"));
        assertTrue(text, text.contains("description = identity_mismatch"));
        assertTrue(text, text.contains("cause = StatusRuntimeException"));
    }

    @Test
    public void aDeepCauseChainKeepsTheLogTextBounded() {
        // The chain is finite but deeper than the render limit, which is what a wrapped transport
        // error can look like; the fixture must stay acyclic because the gRPC status factory walks
        // the causes without a bound of its own.
        Throwable error = new IllegalStateException("innermost");
        for (int depth = 0; depth < 10; depth++) {
            error = new IllegalStateException("layer " + depth, error);
        }

        final StreamFailure failure = StreamFailure.from(error);

        assertFalse(failure.isIdentityRejected());
        assertTrue(failure.toLogText(), failure.toLogText().contains("layer 9"));
        assertFalse(failure.toLogText(), failure.toLogText().contains("innermost"));
    }
}
