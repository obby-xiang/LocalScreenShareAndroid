package com.obby.android.localscreenshare.identity.support;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.obby.android.localscreenshare.identity.IdentityKeyStore;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;

/**
 * Keystore stand-in for JVM tests: holds one software EC P-256 key pair in memory.
 *
 * <p>It reproduces the three states the real keystore can be in — no entry, a usable entry, and an
 * entry whose key cannot be read anymore — without touching Android. Generated keys are real, so
 * the SPKI derivation under test runs on genuine key material.</p>
 */
public final class FakeIdentityKeyStore implements IdentityKeyStore {

    @Nullable
    private KeyPair mKeyPair;

    private boolean mFails;

    private int mLoadCount;

    private int mResetCount;

    /**
     * Drops the key while keeping nothing else, which is what a backup restore looks like.
     */
    public void forgetKey() {
        mKeyPair = null;
    }

    /**
     * Makes every call fail the way an unavailable keystore provider would.
     */
    public void failOnAccess() {
        mFails = true;
    }

    /**
     * Returns the stored key pair, or null when no key was generated yet.
     */
    @Nullable
    public KeyPair keyPair() {
        return mKeyPair;
    }

    public int loadCount() {
        return mLoadCount;
    }

    public int resetCount() {
        return mResetCount;
    }

    @NonNull
    @Override
    public KeyPair loadOrCreate() throws GeneralSecurityException, IOException {
        mLoadCount++;
        if (mFails) {
            throw new GeneralSecurityException("keystore unavailable");
        }
        if (mKeyPair == null) {
            mKeyPair = generateKeyPair();
        }
        return mKeyPair;
    }

    @NonNull
    @Override
    public KeyPair reset() throws GeneralSecurityException, IOException {
        mResetCount++;
        if (mFails) {
            throw new GeneralSecurityException("keystore unavailable");
        }
        mKeyPair = generateKeyPair();
        return mKeyPair;
    }

    @NonNull
    private static KeyPair generateKeyPair() throws GeneralSecurityException {
        final KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }
}
