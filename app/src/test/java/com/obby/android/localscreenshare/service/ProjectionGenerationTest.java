package com.obby.android.localscreenshare.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Unit tests for {@link ProjectionGeneration}.
 *
 * <p>The cases pin the three properties the capture path relies on: a token identifies exactly one
 * generation, starting a generation retires every earlier token, and reading the state never
 * advances it. The concurrency case checks that simultaneous starts still produce unique,
 * increasing tokens, because two readers must never share a generation.</p>
 */
public class ProjectionGenerationTest {

    @Test
    public void begin_issuesATokenThatIsCurrent() {
        final ProjectionGeneration generation = new ProjectionGeneration();

        final long token = generation.begin();

        assertTrue(token > 0L);
        assertTrue(generation.isCurrent(token));
    }

    @Test
    public void begin_invalidatesTheEarlierToken() {
        final ProjectionGeneration generation = new ProjectionGeneration();
        final long first = generation.begin();

        final long second = generation.begin();

        assertEquals(second, first + 1L);
        assertFalse(generation.isCurrent(first));
        assertTrue(generation.isCurrent(second));
    }

    @Test
    public void invalidate_invalidatesEveryIssuedToken() {
        final ProjectionGeneration generation = new ProjectionGeneration();
        final long first = generation.begin();
        final long second = generation.begin();

        generation.invalidate();

        assertFalse(generation.isCurrent(first));
        assertFalse(generation.isCurrent(second));

        final long restarted = generation.begin();
        assertTrue(generation.isCurrent(restarted));
        assertFalse(generation.isCurrent(second));
    }

    @Test
    public void begin_producesUniqueIncreasingTokensUnderConcurrency() throws InterruptedException {
        final int threadCount = 8;
        final int startsPerThread = 50;
        final ProjectionGeneration generation = new ProjectionGeneration();
        final List<Long> tokens = Collections.synchronizedList(new ArrayList<>());
        final CountDownLatch ready = new CountDownLatch(threadCount);
        final CountDownLatch go = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final Thread worker = new Thread(() -> {
                ready.countDown();
                try {
                    go.await();
                    for (int start = 0; start < startsPerThread; start++) {
                        tokens.add(generation.begin());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }, "projection-generation-" + i);
            worker.setDaemon(true);
            worker.start();
        }

        assertTrue(ready.await(5, TimeUnit.SECONDS));
        go.countDown();
        assertTrue(done.await(5, TimeUnit.SECONDS));

        final Set<Long> unique = new HashSet<>(tokens);
        assertEquals(threadCount * startsPerThread, tokens.size());
        assertEquals(tokens.size(), unique.size());

        final long last = Collections.max(tokens);
        assertTrue(generation.isCurrent(last));
        for (long token : unique) {
            if (token != last) {
                assertFalse(generation.isCurrent(token));
            }
        }
    }

    @Test
    public void isCurrent_doesNotChangeTheGeneration() {
        final ProjectionGeneration generation = new ProjectionGeneration();
        final long token = generation.begin();

        assertTrue(generation.isCurrent(token));
        assertTrue(generation.isCurrent(token));
        assertFalse(generation.isCurrent(0L));
        assertFalse(generation.isCurrent(-1L));
        assertFalse(generation.isCurrent(token + 10L));

        assertTrue(generation.isCurrent(token));
    }
}
