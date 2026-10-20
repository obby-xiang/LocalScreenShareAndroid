package com.obby.android.localscreenshare.discovery.udp;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;

public class MulticastLockManagerTest {

    @Test
    public void theUnderlyingLockIsHeldOnlyWhileReferencesExist() {
        AtomicInteger acquires = new AtomicInteger();
        AtomicInteger releases = new AtomicInteger();
        MulticastLockManager manager = new MulticastLockManager(new MulticastLockManager.Lock() {
            @Override
            public void acquire() {
                acquires.incrementAndGet();
            }

            @Override
            public void release() {
                releases.incrementAndGet();
            }
        });

        assertEquals(1, manager.acquire());
        assertEquals(2, manager.acquire());
        assertEquals(1, acquires.get());
        assertEquals(0, releases.get());

        assertEquals(1, manager.release());
        assertEquals(0, releases.get());
        assertEquals(0, manager.release());
        assertEquals(1, releases.get());
    }

    @Test
    public void anExtraReleaseDoesNotUnderflowOrReleaseAgain() {
        AtomicInteger releases = new AtomicInteger();
        MulticastLockManager manager = new MulticastLockManager(new MulticastLockManager.Lock() {
            @Override
            public void acquire() {
            }

            @Override
            public void release() {
                releases.incrementAndGet();
            }
        });

        assertEquals(0, manager.release());
        assertEquals(0, manager.release());
        assertEquals(0, manager.refCount());
        assertEquals(0, releases.get());
    }
}
