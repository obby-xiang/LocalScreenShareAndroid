package com.obby.android.localscreenshare.core.model;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;

public class ClientSessionKeyTest {

    private static StreamKey streamKey(String streamId) {
        return new StreamKey("session-instance-1", streamId);
    }

    @Test
    public void equalsHashCode_includeStreamAndClient() {
        ClientSessionKey key = new ClientSessionKey(streamKey("stream-1"), "client-1");
        ClientSessionKey sameKey = new ClientSessionKey(streamKey("stream-1"), "client-1");
        ClientSessionKey otherStream = new ClientSessionKey(streamKey("stream-2"), "client-1");
        ClientSessionKey otherClient = new ClientSessionKey(streamKey("stream-1"), "client-2");
        ClientSessionKey otherSession = new ClientSessionKey(
                new StreamKey("session-instance-2", "stream-1"), "client-1");

        assertEquals(streamKey("stream-1"), key.getStreamKey());
        assertEquals("client-1", key.getClientId());

        assertEquals(key, sameKey);
        assertEquals(key.hashCode(), sameKey.hashCode());
        assertNotEquals(key, otherStream);
        assertNotEquals(key, otherClient);
        assertNotEquals(key, otherSession);
    }

    @Test
    public void constructor_rejectsNullAndBlankClientId() {
        assertThrows(IllegalArgumentException.class,
                () -> new ClientSessionKey(streamKey("stream-1"), null));
        assertThrows(IllegalArgumentException.class,
                () -> new ClientSessionKey(streamKey("stream-1"), ""));
        assertThrows(IllegalArgumentException.class,
                () -> new ClientSessionKey(streamKey("stream-1"), "   "));
    }
}
