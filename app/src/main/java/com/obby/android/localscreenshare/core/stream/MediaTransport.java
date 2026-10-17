package com.obby.android.localscreenshare.core.stream;

import com.obby.android.localscreenshare.core.model.EncodedAccessUnit;
import com.obby.android.localscreenshare.core.model.StreamDescriptor;

import java.io.IOException;

/**
 * Transport used to push stream descriptors and access units to a client.
 */
public interface MediaTransport {

    void sendDescriptor(StreamDescriptor descriptor) throws IOException;

    void sendAccessUnit(EncodedAccessUnit accessUnit) throws IOException;

    void close();
}
