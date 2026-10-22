package com.obby.android.localscreenshare.core.net;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;

/**
 * Unit tests for the media channel labels of {@link MediaTransportKind}.
 *
 * <p>The label is printed in the per-session log line, so it is an external contract: renaming one
 * makes historical lines incomparable.</p>
 */
public class MediaTransportKindTest {

    @Test
    public void labels_areTheStableLogLabels() {
        assertEquals("grpc", MediaTransportKind.GRPC.getLabel());
        assertEquals("tcp", MediaTransportKind.TCP.getLabel());
        assertEquals("udp", MediaTransportKind.UDP.getLabel());
    }

    @Test
    public void values_coverEveryChannelExactlyOnce() {
        MediaTransportKind[] kinds = MediaTransportKind.values();

        assertEquals(3, kinds.length);
        Set<String> labels = new HashSet<>();
        for (MediaTransportKind kind : kinds) {
            labels.add(kind.getLabel());
        }
        assertEquals(kinds.length, labels.size());
    }

    @Test
    public void valueOf_roundTripsEveryConstant() {
        for (MediaTransportKind kind : MediaTransportKind.values()) {
            assertSame(kind, MediaTransportKind.valueOf(kind.name()));
            assertNotNull(kind.getLabel());
        }
    }
}
