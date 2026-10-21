package com.obby.android.localscreenshare.service;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Holds a context-bound value that is created on first use instead of at construction.
 *
 * <p><b>Why defer.</b> The framework instantiates an Android component with
 * {@code Class.newInstance()} and calls {@code attachBaseContext} afterwards, so the field
 * initialisers of a service run while its base context is still null. A field initialiser that
 * hands {@code this} to a constructor which reads the context throws {@code NullPointerException}
 * inside {@code Class.newInstance}, the framework reports "Unable to instantiate service" and the
 * process dies on the next launch attempt; the 2026-10-21 device test lost every launch of the app
 * to exactly that. Keeping the context supplier and the factory unread until the first
 * {@link #get()} moves every context access past the attachment point.</p>
 *
 * <p><b>Lifetime.</b> The context supplier runs once, on the first {@link #get()}; the created
 * value is reused for the rest of the component's life. A factory that returns null leaves the
 * holder creating on every call, so implementations return a value.</p>
 *
 * <p><b>Threading.</b> Not synchronised: the holders in this package are read from the metric log
 * runnables, which run on the main thread.</p>
 *
 * @param <T> type of the deferred value
 */
final class LazyContextValue<T> {

    /**
     * Creates the deferred value from the context that is current when the value is first needed.
     *
     * @param <T> type of the created value
     */
    interface Factory<T> {

        /**
         * Creates the value.
         *
         * @param context attached context of the component, never null
         * @return the value, never null
         */
        @NonNull
        T create(@NonNull Context context);
    }

    /**
     * Supplies the component context at the moment the value is created.
     *
     * <p>A bound reference to {@code getApplicationContext()} is the intended implementation: it is
     * only a reference at construction time, and it resolves a usable context once the component is
     * attached.</p>
     */
    interface ContextSupplier {

        /**
         * Returns the context to create the value from.
         *
         * @return an attached context, never null
         */
        @NonNull
        Context getContext();
    }

    @NonNull
    private final ContextSupplier mContextSupplier;

    @NonNull
    private final Factory<T> mFactory;

    @Nullable
    private T mValue;

    /**
     * Creates a holder. Neither argument is invoked here.
     *
     * @param contextSupplier supplies the context on first use; required
     * @param factory        creates the value on first use; required
     */
    LazyContextValue(@NonNull final ContextSupplier contextSupplier,
                     @NonNull final Factory<T> factory) {
        mContextSupplier = contextSupplier;
        mFactory = factory;
    }

    /**
     * Returns the value, creating it on the first call.
     *
     * @return the value, never null
     */
    @NonNull
    T get() {
        T value = mValue;
        if (value == null) {
            value = mFactory.create(mContextSupplier.getContext());
            mValue = value;
        }
        return value;
    }
}
