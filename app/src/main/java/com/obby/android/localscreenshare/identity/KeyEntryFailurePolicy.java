package com.obby.android.localscreenshare.identity;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.security.UnrecoverableKeyException;

/**
 * Decides what a failed keystore access says about the stored identity key.
 *
 * <p>Two very different situations used to look the same to the keystore adapter: an entry that can
 * never be used again, and an operation that failed for a reason that has nothing to do with the
 * entry. Answering both by deleting the key is what the 2026-10-21 device test caught: a cancelled
 * subnet scan interrupted a keystore read 14 times in 1.4 s, each interruption was read as "the key
 * is unusable", and the identity was regenerated under the running session. Peers kept the old
 * fingerprint, so the announcement and the handshake certificate disagreed and every connection
 * was refused (N3, root cause A).</p>
 *
 * <p>The classification is deliberately conservative: only a failure that names the entry itself as
 * permanently gone justifies replacement, and everything else keeps the key. A key that is kept by
 * mistake costs one failed handshake; a key that is deleted by mistake costs the identity, every pin
 * a peer stored for it, and the user's trust in the device list.</p>
 *
 * <p>Pure JVM: no Android type, no I/O, no logging. The adapter logs the verdict it acts on.</p>
 */
public final class KeyEntryFailurePolicy {

    /**
     * Answer of {@link #classify(Throwable)}.
     */
    public enum Verdict {
        /**
         * Nothing is known about the entry: keep it and report the failure to the caller.
         */
        TRANSIENT,

        /**
         * The entry itself cannot be used any more and may be replaced.
         */
        UNUSABLE
    }

    /**
     * Simple name of the Android exception a permanently invalidated key raises.
     *
     * <p>Matched by name because the class lives in {@code android.security.keystore}, which a JVM
     * test cannot load.</p>
     */
    public static final String PERMANENTLY_INVALIDATED_EXCEPTION =
        "KeyPermanentlyInvalidatedException";

    // Bound of the cause walk; a failure chain longer than this says nothing new about the entry.
    private static final int MAX_CAUSE_DEPTH = 8;

    private KeyEntryFailurePolicy() {
    }

    /**
     * Classifies one failed keystore operation or one unusable read.
     *
     * <p>{@code failure} is null when the entry was read without an exception but is not complete —
     * a certificate without a private key, a public key without an encoding. That is the "certificate
     * missing" case of the diagnosis and the only silent state that may be replaced.</p>
     *
     * @param failure exception the keystore raised, or null for an incomplete entry
     */
    @NonNull
    public static Verdict classify(@Nullable final Throwable failure) {
        if (failure == null) {
            return Verdict.UNUSABLE;
        }
        if (hasCause(failure, InterruptedException.class)) {
            return Verdict.TRANSIENT;
        }
        if (isPermanentlyInvalidated(failure) || hasCause(failure, UnrecoverableKeyException.class)) {
            return Verdict.UNUSABLE;
        }
        return Verdict.TRANSIENT;
    }

    /**
     * Returns a short English description of a failure, for the replacement log line.
     *
     * @param failure exception the keystore raised, or null for an incomplete entry
     */
    @NonNull
    public static String describe(@Nullable final Throwable failure) {
        if (failure == null) {
            return "entry is incomplete";
        }
        if (hasCause(failure, InterruptedException.class)) {
            return "operation interrupted";
        }
        if (isPermanentlyInvalidated(failure)) {
            return "key permanently invalidated";
        }
        if (hasCause(failure, UnrecoverableKeyException.class)) {
            return "key unrecoverable";
        }
        if (hasCause(failure, IOException.class)) {
            return "keystore unavailable";
        }
        return failure.getClass().getSimpleName();
    }

    private static boolean hasCause(@NonNull final Throwable failure,
        @NonNull final Class<? extends Throwable> type) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (type.isInstance(current)) {
                return true;
            }
            final Throwable cause = current.getCause();
            current = cause == current ? null : cause;
        }
        return false;
    }

    private static boolean isPermanentlyInvalidated(@NonNull final Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            for (Class<?> type = current.getClass(); type != null; type = type.getSuperclass()) {
                if (PERMANENTLY_INVALIDATED_EXCEPTION.equals(type.getSimpleName())) {
                    return true;
                }
            }
            final Throwable cause = current.getCause();
            current = cause == current ? null : cause;
        }
        return false;
    }
}
