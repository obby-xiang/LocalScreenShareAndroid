package com.obby.android.localscreenshare.service;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs an ordered list of teardown steps exactly once.
 *
 * <p><b>Why a coordinator.</b> Both foreground services are reachable from several stop paths - a
 * broadcast, a floating control, a system callback, a failed start - and the framework destroys a
 * service independently of those paths. Each service kept its release order in one method and had a
 * second, partial cleanup in {@code onDestroy}, so a destroyed service could leave a
 * self-rescheduling task or an encoder behind. The coordinator makes one full ordered list the only
 * cleanup: the first caller runs it and every later caller gets an empty result.</p>
 *
 * <p><b>Failure handling.</b> A step that throws is recorded and the remaining steps still run:
 * freeing the projection matters more than the step that failed, and one failing release must not
 * keep the capture thread alive. Failures are returned rather than thrown because teardown runs on
 * the main thread, where an escaping exception would abort the rest of the pipeline.</p>
 *
 * <p>Used from the main thread in practice; the one-shot guard also makes concurrent callers safe.</p>
 */
public final class TeardownCoordinator {

    /**
     * One named step of a teardown order.
     */
    public static final class Step {

        @NonNull
        private final String mName;

        @NonNull
        private final Runnable mAction;

        private Step(@NonNull final String name, @NonNull final Runnable action) {
            this.mName = name;
            this.mAction = action;
        }

        /**
         * Creates a step from its name and release action.
         *
         * @param name   short identifier used in failure reports; required
         * @param action release action; required
         * @return the step; never null
         * @throws IllegalArgumentException if {@code name} is blank or {@code action} is null
         */
        @NonNull
        public static Step of(@NonNull final String name, @NonNull final Runnable action) {
            if (name == null || name.trim().isEmpty()) {
                throw new IllegalArgumentException("name must not be blank");
            }
            return new Step(name, Objects.requireNonNull(action, "action must not be null"));
        }

        /**
         * Returns the short identifier of this step.
         */
        @NonNull
        public String getName() {
            return mName;
        }

        /**
         * Returns the release action of this step.
         */
        @NonNull
        public Runnable getAction() {
            return mAction;
        }
    }

    /**
     * One step that failed during {@link #run()}.
     *
     * <p>The step name identifies the release that failed; the cause is kept so the caller can log
     * the stack trace without the coordinator depending on Android logging.</p>
     */
    public static final class StepFailure {

        @NonNull
        private final String mStepName;

        @NonNull
        private final RuntimeException mCause;

        private StepFailure(@NonNull final String stepName,
                            @NonNull final RuntimeException cause) {
            this.mStepName = stepName;
            this.mCause = cause;
        }

        /**
         * Returns the identifier of the step that failed.
         */
        @NonNull
        public String getStepName() {
            return mStepName;
        }

        /**
         * Returns the exception the step threw.
         */
        @NonNull
        public RuntimeException getCause() {
            return mCause;
        }
    }

    @NonNull
    private final List<Step> mSteps;

    @NonNull
    private final AtomicBoolean mDone = new AtomicBoolean();

    /**
     * Creates a coordinator for the given order.
     *
     * <p>The list is copied, so later changes to the caller's list do not affect the order.</p>
     *
     * @param steps release steps in execution order; required, may be empty
     * @throws IllegalArgumentException if {@code steps} or one of its elements is null
     */
    public TeardownCoordinator(@NonNull final List<Step> steps) {
        Objects.requireNonNull(steps, "steps must not be null");
        final List<Step> copy = new ArrayList<>(steps.size());
        for (Step step : steps) {
            copy.add(Objects.requireNonNull(step, "step must not be null"));
        }
        mSteps = Collections.unmodifiableList(copy);
    }

    /**
     * Runs every step once, in registration order.
     *
     * <p>The first call owns the teardown; later calls do nothing and return an empty list, even
     * when the first call saw failures. A failing step does not stop the steps after it, so a
     * failing encoder release never keeps the projection or the notification alive.</p>
     *
     * @return the steps that failed, in execution order; empty when every step succeeded and on
     *     every call after the first
     */
    @NonNull
    public List<StepFailure> run() {
        if (!mDone.compareAndSet(false, true)) {
            return Collections.emptyList();
        }
        final List<StepFailure> failures = new ArrayList<>();
        for (Step step : mSteps) {
            try {
                step.getAction().run();
            } catch (RuntimeException e) {
                failures.add(new StepFailure(step.getName(), e));
            }
        }
        return failures;
    }

    /**
     * Returns whether the teardown already ran, whether or not single steps failed.
     */
    public boolean isDone() {
        return mDone.get();
    }
}
