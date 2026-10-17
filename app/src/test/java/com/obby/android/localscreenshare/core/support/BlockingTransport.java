package com.obby.android.localscreenshare.core.support;

import com.obby.android.localscreenshare.core.model.EncodedAccessUnit;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Fake transport that can hold the next access-unit send until released.
 */
public final class BlockingTransport extends FakeTransport {

    private final AtomicBoolean blockNextAccessUnitSend = new AtomicBoolean();
    private final CountDownLatch sendStarted = new CountDownLatch(1);
    private final CountDownLatch releaseSend = new CountDownLatch(1);

    public void blockNextAccessUnitSend() {
        blockNextAccessUnitSend.set(true);
    }

    public boolean awaitSendStarted(long timeout, TimeUnit unit) throws InterruptedException {
        return sendStarted.await(timeout, unit);
    }

    public void releaseSend() {
        releaseSend.countDown();
    }

    @Override
    public void sendAccessUnit(EncodedAccessUnit accessUnit) throws IOException {
        if (blockNextAccessUnitSend.compareAndSet(true, false)) {
            sendStarted.countDown();
            try {
                if (!releaseSend.await(5L, TimeUnit.SECONDS)) {
                    throw new IOException("blocked send was not released");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("blocked send was interrupted", e);
            }
        }
        super.sendAccessUnit(accessUnit);
    }
}
