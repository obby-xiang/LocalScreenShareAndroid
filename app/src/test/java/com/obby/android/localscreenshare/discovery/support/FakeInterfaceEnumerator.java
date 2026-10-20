package com.obby.android.localscreenshare.discovery.support;

import androidx.annotation.NonNull;

import com.obby.android.localscreenshare.discovery.scan.InterfaceEnumerator;
import com.obby.android.localscreenshare.discovery.scan.LocalInterface;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** {@link InterfaceEnumerator} that returns a fixed list a test controls. */
public final class FakeInterfaceEnumerator implements InterfaceEnumerator {

    @NonNull
    private final List<LocalInterface> mInterfaces = new ArrayList<>();

    /** Creates an empty enumerator. */
    public FakeInterfaceEnumerator() {
    }

    /** Creates an enumerator seeded with {@code interfaces}. */
    public FakeInterfaceEnumerator(@NonNull final LocalInterface... interfaces) {
        Collections.addAll(mInterfaces, interfaces);
    }

    /** Replaces the returned interfaces. */
    public void set(@NonNull final LocalInterface... interfaces) {
        mInterfaces.clear();
        Collections.addAll(mInterfaces, interfaces);
    }

    @NonNull
    @Override
    public List<LocalInterface> enumerate() {
        synchronized (mInterfaces) {
            return new ArrayList<>(mInterfaces);
        }
    }
}
