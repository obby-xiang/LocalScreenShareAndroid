package com.obby.android.localscreenshare.discovery.udp;

import android.content.Context;
import android.net.wifi.WifiManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.obby.android.localscreenshare.App;

import java.util.Objects;

/**
 * Reference-counted owner of the Wi-Fi multicast lock.
 *
 * <p>Android only delivers multicast traffic to a wakeful Wi-Fi chip when a
 * {@link WifiManager.MulticastLock} is held, and it is easy to leak or to release while another
 * part of the process still needs it. The announcer and the discovery receiver therefore share one
 * process-wide manager: the underlying lock is acquired when the first user arrives and released
 * when the last one leaves, so neither side can cut the other off.</p>
 *
 * <p>The lock itself is injected as a {@link Lock}; tests exercise the counting on the JVM. The
 * production lock is created lazily from the application context and requires the
 * {@code CHANGE_WIFI_MULTICAST_STATE} permission.</p>
 */
public final class MulticastLockManager {

    @Nullable
    private static volatile MulticastLockManager sInstance;

    @NonNull
    private final Lock mLock;

    private int mRefCount;

    /**
     * Creates a manager over an explicit lock; used by tests and by {@link #get()}.
     */
    public MulticastLockManager(@NonNull final Lock lock) {
        mLock = Objects.requireNonNull(lock, "lock");
    }

    /**
     * Returns the process-wide manager, creating the Wi-Fi lock on first use.
     */
    @NonNull
    public static MulticastLockManager get() {
        MulticastLockManager instance = sInstance;
        if (instance == null) {
            synchronized (MulticastLockManager.class) {
                instance = sInstance;
                if (instance == null) {
                    instance = new MulticastLockManager(createWifiLock());
                    sInstance = instance;
                }
            }
        }
        return instance;
    }

    /**
     * Acquires the shared lock; the underlying lock is taken only for the first user.
     *
     * @return the reference count after the call
     */
    public synchronized int acquire() {
        if (mRefCount++ == 0) {
            mLock.acquire();
        }
        return mRefCount;
    }

    /**
     * Releases one reference; the underlying lock is released with the last one and an extra
     * release is ignored so a double stop cannot underflow the count.
     *
     * @return the reference count after the call
     */
    public synchronized int release() {
        if (mRefCount == 0) {
            return 0;
        }
        if (--mRefCount == 0) {
            mLock.release();
        }
        return mRefCount;
    }

    /**
     * Returns the current number of users.
     */
    public synchronized int refCount() {
        return mRefCount;
    }

    @NonNull
    private static Lock createWifiLock() {
        try {
            final WifiManager wifiManager = (WifiManager) App.get().getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
            if (wifiManager != null) {
                final WifiManager.MulticastLock multicastLock =
                    wifiManager.createMulticastLock("lss-multicast");
                multicastLock.setReferenceCounted(true);
                return new WifiLock(multicastLock);
            }
        } catch (RuntimeException e) {
            // Without a Wi-Fi service the channel still runs; announcements are best-effort.
        }
        return NOOP_LOCK;
    }

    private static final Lock NOOP_LOCK = new Lock() {
        @Override
        public void acquire() {
        }

        @Override
        public void release() {
        }
    };

    /**
     * The lock operation the manager counts.
     */
    public interface Lock {
        /**
         * Acquires the underlying resource.
         */
        void acquire();

        /**
         * Releases the underlying resource.
         */
        void release();
    }

    /**
     * {@link Lock} over the framework multicast lock.
     */
    private static final class WifiLock implements Lock {
        @NonNull
        private final WifiManager.MulticastLock mMulticastLock;

        private WifiLock(@NonNull final WifiManager.MulticastLock multicastLock) {
            mMulticastLock = multicastLock;
        }

        @Override
        public void acquire() {
            mMulticastLock.acquire();
        }

        @Override
        public void release() {
            if (mMulticastLock.isHeld()) {
                mMulticastLock.release();
            }
        }
    }
}
