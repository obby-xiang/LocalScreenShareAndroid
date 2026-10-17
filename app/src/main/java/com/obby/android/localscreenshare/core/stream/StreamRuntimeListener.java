package com.obby.android.localscreenshare.core.stream;

import com.obby.android.localscreenshare.core.model.EncodedAccessUnit;
import com.obby.android.localscreenshare.core.model.StreamDescriptor;

/**
 * Callback for a single active stream's runtime events.
 */
public interface StreamRuntimeListener {

    void onAccessUnit(EncodedAccessUnit accessUnit);

    void onDescriptorChanged(StreamDescriptor descriptor);
}
