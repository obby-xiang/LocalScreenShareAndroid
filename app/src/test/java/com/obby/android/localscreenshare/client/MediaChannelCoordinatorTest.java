package com.obby.android.localscreenshare.client;

import com.obby.android.localscreenshare.grpc.screenstream.MediaChannelEvent;
import com.obby.android.localscreenshare.grpc.screenstream.MediaChannelEventType;
import com.obby.android.localscreenshare.grpc.screenstream.MediaClose;
import com.obby.android.localscreenshare.grpc.screenstream.MediaOffer;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link MediaChannelCoordinator}.
 *
 * <p>This build refuses every channel on purpose, so the tests assert the refusal itself and the
 * bookkeeping around it: one answer per channel, no answer for an id the call already dropped, and
 * a released channel after the server closes it.</p>
 */
public class MediaChannelCoordinatorTest {

    private static final byte[] CHANNEL_ID = bytes(16, 0x11);

    @Test
    public void offer_isRefusedAsNotImplemented() {
        MediaChannelCoordinator coordinator = new MediaChannelCoordinator();

        MediaChannelEvent answer = coordinator.onOffer(offer(CHANNEL_ID));

        assertTrue(answer != null);
        assertArrayEquals(CHANNEL_ID, answer.getChannelId().toByteArray());
        assertEquals(MediaChannelEventType.FAILED, answer.getType());
        assertEquals(MediaChannelCoordinator.REASON_NOT_IMPLEMENTED, answer.getReason());
        assertEquals(MediaChannelCoordinator.REASON_NOT_IMPLEMENTED,
                coordinator.getLastFailureReason());
        assertEquals(hexOf(CHANNEL_ID), coordinator.getOpenChannelIdHex());
    }

    @Test
    public void repeatedOffer_isAnsweredOnce() {
        MediaChannelCoordinator coordinator = new MediaChannelCoordinator();

        assertTrue(coordinator.onOffer(offer(CHANNEL_ID)) != null);
        assertNull(coordinator.onOffer(offer(CHANNEL_ID)));
    }

    @Test
    public void offerWithoutAChannelId_isIgnored() {
        MediaChannelCoordinator coordinator = new MediaChannelCoordinator();

        assertNull(coordinator.onOffer(null));
        assertNull(coordinator.onOffer(MediaOffer.getDefaultInstance()));
        assertNull(coordinator.getOpenChannelIdHex());
    }

    @Test
    public void lateOfferForAClosedChannel_isIgnored() {
        MediaChannelCoordinator coordinator = new MediaChannelCoordinator();
        assertTrue(coordinator.onOffer(offer(CHANNEL_ID)) != null);
        coordinator.onMediaClose(close(CHANNEL_ID));

        assertNull(coordinator.onOffer(offer(CHANNEL_ID)));
    }

    @Test
    public void close_releasesTheOpenChannelAndIsIdempotent() {
        MediaChannelCoordinator coordinator = new MediaChannelCoordinator();
        coordinator.onOffer(offer(CHANNEL_ID));

        coordinator.onMediaClose(close(CHANNEL_ID));
        coordinator.onMediaClose(close(CHANNEL_ID));
        coordinator.onMediaClose(null);

        assertNull(coordinator.getOpenChannelIdHex());
    }

    @Test
    public void closeOfAnotherChannel_keepsTheOpenOne() {
        MediaChannelCoordinator coordinator = new MediaChannelCoordinator();
        coordinator.onOffer(offer(CHANNEL_ID));

        coordinator.onMediaClose(close(bytes(16, 0x22)));

        assertEquals(hexOf(CHANNEL_ID), coordinator.getOpenChannelIdHex());
    }

    @Test
    public void reset_forgetsEveryChannel() {
        MediaChannelCoordinator coordinator = new MediaChannelCoordinator();
        coordinator.onOffer(offer(CHANNEL_ID));
        coordinator.onMediaClose(close(CHANNEL_ID));

        coordinator.reset();

        assertNull(coordinator.getOpenChannelIdHex());
        assertNull(coordinator.getLastFailureReason());
        assertTrue(coordinator.onOffer(offer(CHANNEL_ID)) != null);
    }

    @Test
    public void declaredTransports_namesBothChannels() {
        String[] declared = MediaChannelCoordinator.declaredTransports();

        assertEquals(2, declared.length);
        assertTrue(Arrays.asList(declared).contains("tcp"));
        assertTrue(Arrays.asList(declared).contains("udp"));
        assertFalse(declared[0].equals(declared[1]));
    }

    private static MediaOffer offer(byte[] channelId) {
        return MediaOffer.newBuilder()
                .setChannelId(com.google.protobuf.ByteString.copyFrom(channelId))
                .setTransport("tcp")
                .build();
    }

    private static MediaClose close(byte[] channelId) {
        return MediaClose.newBuilder()
                .setChannelId(com.google.protobuf.ByteString.copyFrom(channelId))
                .build();
    }

    private static byte[] bytes(int length, int value) {
        byte[] result = new byte[length];
        Arrays.fill(result, (byte) value);
        return result;
    }

    private static String hexOf(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            out.append(Character.forDigit((value >> 4) & 0x0f, 16));
            out.append(Character.forDigit(value & 0x0f, 16));
        }
        return out.toString();
    }
}
