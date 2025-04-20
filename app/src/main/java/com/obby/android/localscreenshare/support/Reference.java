package com.obby.android.localscreenshare.support;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import lombok.Getter;
import lombok.experimental.Accessors;

@Accessors(prefix = "m")
@Getter
public class Reference<T> {
    @Nullable
    private T mValue;

    public Reference(@NonNull final T value) {
        mValue = value;
    }

    public void clear() {
        mValue = null;
    }
}
