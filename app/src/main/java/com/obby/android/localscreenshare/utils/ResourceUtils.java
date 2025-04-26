package com.obby.android.localscreenshare.utils;

import android.content.Context;
import android.util.TypedValue;

import androidx.annotation.AnyRes;
import androidx.annotation.AttrRes;
import androidx.annotation.NonNull;
import androidx.core.content.res.ResourcesCompat;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ResourceUtils {
    @AnyRes
    public static int resolveAttribute(@NonNull final Context context, @AttrRes final int attrId) {
        final TypedValue typedValue = new TypedValue();
        if (context.getTheme().resolveAttribute(attrId, typedValue, true)) {
            return typedValue.resourceId;
        }
        return ResourcesCompat.ID_NULL;
    }
}
