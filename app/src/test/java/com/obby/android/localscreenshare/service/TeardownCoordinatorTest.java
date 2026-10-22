package com.obby.android.localscreenshare.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Unit tests for {@link TeardownCoordinator}.
 *
 * <p>The cases pin the contract the services rely on: steps run in registration order, the second
 * caller runs nothing, a failing step is reported but does not stop the steps after it, and the
 * failure never escapes {@link TeardownCoordinator#run()}. The empty list is covered because a
 * service created and destroyed without ever starting still has to reach {@code isDone()}.</p>
 */
public class TeardownCoordinatorTest {

    @Test
    public void run_executesStepsInRegistrationOrder() {
        final List<String> executed = new ArrayList<>();
        final TeardownCoordinator coordinator = new TeardownCoordinator(Arrays.asList(
            step("first", executed),
            step("second", executed),
            step("third", executed)));

        assertFalse(coordinator.isDone());

        final List<TeardownCoordinator.StepFailure> failures = coordinator.run();

        assertTrue(failures.isEmpty());
        assertEquals(Arrays.asList("first", "second", "third"), executed);
    }

    @Test
    public void run_secondCallExecutesNoStep() {
        final List<String> executed = new ArrayList<>();
        final TeardownCoordinator coordinator = new TeardownCoordinator(
            Collections.singletonList(step("only", executed)));

        assertTrue(coordinator.run().isEmpty());
        assertTrue(coordinator.run().isEmpty());

        assertEquals(Collections.singletonList("only"), executed);
    }

    @Test
    public void run_failingStepIsRecordedAndLaterStepsStillRun() {
        final List<String> executed = new ArrayList<>();
        final RuntimeException failure = new IllegalStateException("injected step failure");
        final TeardownCoordinator coordinator = new TeardownCoordinator(Arrays.asList(
            step("first", executed),
            TeardownCoordinator.Step.of("failing", () -> {
                executed.add("failing");
                throw failure;
            }),
            step("last", executed)));

        final List<TeardownCoordinator.StepFailure> failures = coordinator.run();

        assertEquals(1, failures.size());
        assertEquals("failing", failures.get(0).getStepName());
        assertSame(failure, failures.get(0).getCause());
        assertEquals(Arrays.asList("first", "failing", "last"), executed);
    }

    @Test
    public void run_failureDoesNotEscapeAndEveryStepIsAttempted() {
        final List<String> executed = new ArrayList<>();
        final TeardownCoordinator coordinator = new TeardownCoordinator(Arrays.asList(
            TeardownCoordinator.Step.of("a", () -> {
                executed.add("a");
                throw new IllegalStateException("a failed");
            }),
            TeardownCoordinator.Step.of("b", () -> {
                executed.add("b");
                throw new IllegalArgumentException("b failed");
            })));

        final List<TeardownCoordinator.StepFailure> failures = coordinator.run();

        assertEquals(2, failures.size());
        assertEquals("a", failures.get(0).getStepName());
        assertEquals("b", failures.get(1).getStepName());
        assertEquals(Arrays.asList("a", "b"), executed);
    }

    @Test
    public void isDone_isTrueAfterRunEvenWithFailures() {
        final TeardownCoordinator coordinator = new TeardownCoordinator(
            Collections.singletonList(TeardownCoordinator.Step.of("failing", () -> {
                throw new IllegalStateException("injected step failure");
            })));

        assertFalse(coordinator.isDone());
        coordinator.run();

        assertTrue(coordinator.isDone());
        assertTrue(coordinator.run().isEmpty());
    }

    @Test
    public void emptyStepList_isLegal() {
        final TeardownCoordinator coordinator =
            new TeardownCoordinator(Collections.emptyList());

        assertFalse(coordinator.isDone());
        assertTrue(coordinator.run().isEmpty());
        assertTrue(coordinator.isDone());
    }

    private static TeardownCoordinator.Step step(final String name, final List<String> executed) {
        return TeardownCoordinator.Step.of(name, () -> executed.add(name));
    }
}
