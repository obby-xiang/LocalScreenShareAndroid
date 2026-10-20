package com.obby.android.localscreenshare.support;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import lombok.Getter;
import lombok.experimental.Accessors;

/**
 * Mutable holder for a single value with an explicit release step.
 *
 * <p>Used to transfer ownership of a pooled resource such as a decoded frame: the receiver holds
 * the value until it calls {@link #clear()}, which drops it. Subclasses override
 * {@code clear()} to return or free the underlying resource and must call the super
 * implementation so the field is nulled. Not thread-safe; callers synchronize externally when a
 * holder is shared between threads.</p>
 */
@Accessors(prefix = "m")
@Getter
public class MutableReference<T> {
    @Nullable
    private T mValue;

    /**
     * Creates a holder around {@code value}; the argument is not null-checked.
     */
    public MutableReference(@NonNull final T value) {
        mValue = value;
    }

    /**
     * Drops the value, making the getter return null. The holder itself is idempotent, but an
     * override that releases a resource runs on every invocation.
     */
    public void clear() {
        mValue = null;
    }
}
