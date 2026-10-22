package com.obby.android.localscreenshare.service;

import com.obby.android.localscreenshare.core.codec.CodecNotFoundException;
import com.obby.android.localscreenshare.core.codec.EncoderException;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link ProjectionCodecStartupCoordinator}.
 *
 * <p>The rule decides the codec of a whole share session, so the tests pin the three parts of it:
 * which failures fall back to JPEG, that exactly one fallback attempt runs, and that a codec choice
 * is never written anywhere durable. The capture pipeline itself is a lambda here, which is what
 * makes the retry order observable without an encoder.</p>
 */
public class ProjectionCodecStartupCoordinatorTest {

    private final List<Boolean> mAttempts = new ArrayList<>();

    private final List<RuntimeException> mFallbacks = new ArrayList<>();

    @Test
    public void h264StartSucceeds_usesH264AndReportsNoFallback() {
        final ProjectionCodecStartupCoordinator.Codec codec =
            ProjectionCodecStartupCoordinator.start(true, h264 -> mAttempts.add(h264),
                mFallbacks::add);

        assertEquals(ProjectionCodecStartupCoordinator.Codec.H264, codec);
        assertEquals(List.of(true), mAttempts);
        assertTrue(mFallbacks.isEmpty());
    }

    @Test
    public void codecNotFoundFallsBackToJpegOnce() {
        final CodecNotFoundException failure = new CodecNotFoundException("video/avc");

        final ProjectionCodecStartupCoordinator.Codec codec =
            ProjectionCodecStartupCoordinator.start(true, attempt -> {
                mAttempts.add(attempt);
                if (attempt) {
                    throw failure;
                }
            }, mFallbacks::add);

        assertEquals(ProjectionCodecStartupCoordinator.Codec.JPEG, codec);
        assertEquals(List.of(true, false), mAttempts);
        assertEquals(1, mFallbacks.size());
        assertSame(failure, mFallbacks.get(0));
    }

    @Test
    public void encoderStartFailureFallsBackToJpegOnce() {
        final EncoderException failure = new EncoderException("encoder failed to start");

        final ProjectionCodecStartupCoordinator.Codec codec =
            ProjectionCodecStartupCoordinator.start(true, attempt -> {
                mAttempts.add(attempt);
                if (attempt) {
                    throw failure;
                }
            }, mFallbacks::add);

        assertEquals(ProjectionCodecStartupCoordinator.Codec.JPEG, codec);
        assertEquals(List.of(true, false), mAttempts);
        assertSame(failure, mFallbacks.get(0));
    }

    @Test
    public void wrappedCodecFailureFallsBackLikeTheWrapper() {
        // The runtime wraps factory and surface failures in an EncoderException, so the type of the
        // topmost exception is what the rule sees; the cause is only for the log line.
        final EncoderException failure = new EncoderException("encoder factory failed for codec h264",
            new IllegalStateException("no usable encoder"));

        final ProjectionCodecStartupCoordinator.Codec codec =
            ProjectionCodecStartupCoordinator.start(true, attempt -> {
                mAttempts.add(attempt);
                if (attempt) {
                    throw failure;
                }
            }, mFallbacks::add);

        assertEquals(ProjectionCodecStartupCoordinator.Codec.JPEG, codec);
        assertSame(failure, mFallbacks.get(0));
    }

    @Test
    public void jpegRequested_startsJpegWithoutAFallback() {
        final ProjectionCodecStartupCoordinator.Codec codec =
            ProjectionCodecStartupCoordinator.start(false, h264 -> mAttempts.add(h264),
                mFallbacks::add);

        assertEquals(ProjectionCodecStartupCoordinator.Codec.JPEG, codec);
        assertEquals(List.of(false), mAttempts);
        assertTrue(mFallbacks.isEmpty());
    }

    @Test
    public void jpegRequestedFailure_propagatesWithoutRetry() {
        final EncoderException failure = new EncoderException("jpeg pipeline failed");

        final EncoderException thrown = assertThrows(EncoderException.class,
            () -> ProjectionCodecStartupCoordinator.start(false, h264 -> {
                mAttempts.add(h264);
                throw failure;
            }, mFallbacks::add));

        assertSame(failure, thrown);
        assertEquals(List.of(false), mAttempts);
        assertTrue(mFallbacks.isEmpty());
    }

    @Test
    public void plainIllegalStateFailure_propagatesWithoutFallback() {
        // A closed or already started runtime is a caller defect, not a missing codec.
        final IllegalStateException failure = new IllegalStateException("runtime is already started");

        final IllegalStateException thrown = assertThrows(IllegalStateException.class,
            () -> ProjectionCodecStartupCoordinator.start(true, h264 -> {
                mAttempts.add(h264);
                throw failure;
            }, mFallbacks::add));

        assertSame(failure, thrown);
        assertEquals(List.of(true), mAttempts);
        assertTrue(mFallbacks.isEmpty());
    }

    @Test
    public void unrelatedRuntimeFailure_propagatesWithoutFallback() {
        final RuntimeException failure = new RuntimeException("stream registration failed");

        final RuntimeException thrown = assertThrows(RuntimeException.class,
            () -> ProjectionCodecStartupCoordinator.start(true, h264 -> {
                mAttempts.add(h264);
                throw failure;
            }, mFallbacks::add));

        assertSame(failure, thrown);
        assertEquals(List.of(true), mAttempts);
        assertTrue(mFallbacks.isEmpty());
    }

    @Test
    public void jpegRetryFailure_propagatesAfterReportingTheFallback() {
        final EncoderException h264Failure = new EncoderException("h264 failed to start");
        final RuntimeException jpegFailure = new IllegalStateException("image reader unavailable");

        final RuntimeException thrown = assertThrows(RuntimeException.class,
            () -> ProjectionCodecStartupCoordinator.start(true, attempt -> {
                mAttempts.add(attempt);
                throw attempt ? h264Failure : jpegFailure;
            }, mFallbacks::add));

        assertSame(jpegFailure, thrown);
        assertEquals(List.of(true, false), mAttempts);
        assertEquals(1, mFallbacks.size());
        assertSame(h264Failure, mFallbacks.get(0));
    }

    @Test
    public void fallbackIsAttemptedOnlyOnce() {
        // A failing JPEG retry must not start a third attempt: the session either runs JPEG or fails.
        assertThrows(EncoderException.class,
            () -> ProjectionCodecStartupCoordinator.start(true, attempt -> {
                mAttempts.add(attempt);
                if (attempt) {
                    throw new EncoderException("h264 failed");
                }
                throw new EncoderException("jpeg failed");
            }, mFallbacks::add));

        assertEquals(2, mAttempts.size());
    }

    @Test
    public void nullCallbacksAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> ProjectionCodecStartupCoordinator.start(
            true, null, mFallbacks::add));
        assertThrows(IllegalArgumentException.class, () -> ProjectionCodecStartupCoordinator.start(
            true, h264 -> {
            }, null));
    }

    @Test
    public void fallbackNeverWritesTheCodecPreference() throws IOException {
        final String coordinator = readSource("service/ProjectionCodecStartupCoordinator.java");
        final String service = readSource("service/ScreenShareService.java");

        // The rule owns no durable state: it has no access to the preferences at all.
        assertTrue("the coordinator must not know about preferences",
            !coordinator.contains("Preferences"));
        // And the start path never persists the codec it ended up with, so the next share tries
        // H.264 again even after a fallback (the ruling behind TB-ENG-014).
        assertTrue("the capture service must not write the projection codec preference",
            !service.contains("setProjectionCodec("));
        // The start path goes through the rule; without this the fallback silently disappears.
        assertTrue("the capture service must start the pipeline through the rule",
            service.contains("ProjectionCodecStartupCoordinator.start("));
    }

    /**
     * Reads one production source by its path below {@code src/main/java}.
     */
    private static String readSource(final String relativePath) throws IOException {
        final File[] candidates = {
            new File("src/main/java/com/obby/android/localscreenshare/" + relativePath),
            new File("app/src/main/java/com/obby/android/localscreenshare/" + relativePath),
        };
        for (final File candidate : candidates) {
            if (candidate.isFile()) {
                return new String(Files.readAllBytes(candidate.toPath()), StandardCharsets.UTF_8);
            }
        }
        throw new IOException("cannot locate " + relativePath + " from "
            + new File(".").getAbsolutePath());
    }
}
