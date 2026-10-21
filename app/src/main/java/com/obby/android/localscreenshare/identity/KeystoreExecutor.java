package com.obby.android.localscreenshare.identity;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Runs every AndroidKeystore operation on one process-wide thread.
 *
 * <p>The AndroidKeyStore refuses two operations on one key at the same time, and an operation made
 * while another is in flight fails with a provider exception that says nothing about the key. The
 * identity path used to reach the keystore from the handshake threads, the identity holders and the
 * certificate signature at once, so such a refusal could be mistaken for an unusable key (N3). With
 * every call serialized here, a keystore failure is a real failure of that one operation.</p>
 *
 * <p><b>Interruption.</b> A caller that is already interrupted never reaches the keystore, and a
 * caller interrupted while waiting stops waiting without interrupting the operation it was waiting
 * for: aborting a keystore read in progress is exactly what produced the interrupted-key reads of
 * the device test. The operation finishes on the keystore thread and its result is discarded.</p>
 *
 * <p>A call made from the keystore thread itself runs inline, so operations composed of smaller
 * keystore steps do not deadlock on the single thread.</p>
 */
public final class KeystoreExecutor {

    // Marks the one thread that runs keystore work; set only while a task runs on it.
    @NonNull
    private static final ThreadLocal<Boolean> ON_KEYSTORE_THREAD = new ThreadLocal<>();

    @NonNull
    private final ExecutorService mExecutor;

    private KeystoreExecutor() {
        mExecutor = Executors.newSingleThreadExecutor(runnable -> {
            final Thread thread = new Thread(runnable, "lss-keystore");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Returns the process-wide keystore thread.
     */
    @NonNull
    public static KeystoreExecutor get() {
        return InstanceHolder.INSTANCE;
    }

    /**
     * Runs one keystore operation on the keystore thread and returns its result.
     *
     * @param operation keystore work; must not wait for another keystore call from another thread
     * @throws GeneralSecurityException if the operation failed with a security failure
     * @throws IOException if the keystore could not be opened
     * @throws InterruptedException if the caller was interrupted before or while waiting
     */
    @NonNull
    public <T> T call(@NonNull final Callable<T> operation)
        throws GeneralSecurityException, IOException, InterruptedException {
        Objects.requireNonNull(operation, "operation");
        if (Boolean.TRUE.equals(ON_KEYSTORE_THREAD.get())) {
            return runInline(operation);
        }
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("caller was already interrupted");
        }

        final Future<T> future = mExecutor.submit(() -> {
            ON_KEYSTORE_THREAD.set(Boolean.TRUE);
            try {
                return operation.call();
            } finally {
                ON_KEYSTORE_THREAD.remove();
            }
        });
        try {
            return future.get();
        } catch (InterruptedException e) {
            // The operation is not interrupted on purpose: it finishes and its result is dropped.
            future.cancel(false);
            throw e;
        } catch (CancellationException e) {
            throw new GeneralSecurityException("keystore operation was cancelled", e);
        } catch (ExecutionException e) {
            throw unwrap(e.getCause());
        }
    }

    // Runs a nested call on the keystore thread; the checked exceptions keep their types so a caller
    // can still tell a transient keystore failure from an unusable entry.
    @NonNull
    private static <T> T runInline(@NonNull final Callable<T> operation)
        throws GeneralSecurityException, IOException, InterruptedException {
        try {
            return operation.call();
        } catch (GeneralSecurityException | IOException | InterruptedException
            | RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new GeneralSecurityException("keystore operation failed", e);
        }
    }

    @NonNull
    private static RuntimeException unwrap(@NonNull final Throwable cause)
        throws GeneralSecurityException, IOException {
        if (cause instanceof GeneralSecurityException) {
            throw (GeneralSecurityException) cause;
        }
        if (cause instanceof IOException) {
            throw (IOException) cause;
        }
        if (cause instanceof RuntimeException) {
            return (RuntimeException) cause;
        }
        if (cause instanceof Error) {
            throw (Error) cause;
        }
        throw new GeneralSecurityException("keystore operation failed", cause);
    }

    // Holds the process-wide executor; the thread is created on the first call.
    private static final class InstanceHolder {
        private static final KeystoreExecutor INSTANCE = new KeystoreExecutor();
    }
}
