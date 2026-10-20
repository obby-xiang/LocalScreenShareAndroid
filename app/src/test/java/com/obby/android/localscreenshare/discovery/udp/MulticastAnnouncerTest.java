package com.obby.android.localscreenshare.discovery.udp;

import com.obby.android.localscreenshare.discovery.model.DeviceRole;
import com.obby.android.localscreenshare.discovery.model.JoinPolicy;
import com.obby.android.localscreenshare.discovery.model.ShareState;
import com.obby.android.localscreenshare.discovery.support.FakeDelayedScheduler;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class MulticastAnnouncerTest {

    private static final String DEVICE_ID = "L7Q3K9M2F4X8B6N1C5V0Z3T7R2";

    private FakeDelayedScheduler mScheduler;

    private RecordingSender mSender;

    private MulticastAnnouncer mAnnouncer;

    @Before
    public void setUp() {
        mScheduler = new FakeDelayedScheduler();
        mSender = new RecordingSender();
        mAnnouncer = new MulticastAnnouncer(mSender, mScheduler, this::template,
            new Random(42L));
    }

    @Test
    public void startupBurstFollowsTheDocumentedDelays() {
        mAnnouncer.start();

        mScheduler.advance(99L);
        assertEquals(0, mSender.payloads().size());
        mScheduler.advance(1L);
        assertEquals(1, mSender.payloads().size());
        mScheduler.advance(399L);
        assertEquals(1, mSender.payloads().size());
        mScheduler.advance(1L);
        assertEquals(2, mSender.payloads().size());
        mScheduler.advance(1_500L);
        assertEquals(3, mSender.payloads().size());

        List<Announcement> announcements = mSender.decoded();
        assertEquals(3, announcements.size());
        assertEquals(1L, announcements.get(0).getSeq());
        assertEquals(2L, announcements.get(1).getSeq());
        assertEquals(3L, announcements.get(2).getSeq());
        String boot = announcements.get(0).getBoot();
        assertNotNull(boot);
        assertEquals(16, boot.length());
        for (Announcement announcement : announcements) {
            assertEquals(boot, announcement.getBoot());
            assertTrue(announcement.isAnnounces());
            assertEquals(ShareState.LIVE.toWire(), announcement.getState().toWire());
            assertEquals(5, announcement.getTtlSeconds());
        }
    }

    @Test
    public void heartbeatUsesLiveThenIdleInterval() {
        mAnnouncer.start();
        mScheduler.advance(2_000L);
        assertEquals(3, mSender.payloads().size());

        mScheduler.advance(4_999L);
        assertEquals(3, mSender.payloads().size());
        mScheduler.advance(1L);
        assertEquals(4, mSender.payloads().size());

        mAnnouncer.setLive(false);
        mScheduler.advance(14_999L);
        assertEquals(4, mSender.payloads().size());
        mScheduler.advance(1L);
        assertEquals(5, mSender.payloads().size());
        assertEquals(15, mSender.decoded().get(4).getTtlSeconds());

        mAnnouncer.setLive(true);
        mScheduler.advance(4_999L);
        assertEquals(5, mSender.payloads().size());
        mScheduler.advance(1L);
        assertEquals(6, mSender.payloads().size());
        assertEquals(5, mSender.decoded().get(5).getTtlSeconds());
    }

    @Test
    public void stopSendsThreeGoodbyesThenCloses() {
        mAnnouncer.start();
        mScheduler.advance(2_000L);
        assertEquals(3, mSender.payloads().size());

        mAnnouncer.stop();
        assertEquals(4, mSender.payloads().size());
        assertTrue(mSender.decoded().get(3).isGoodbye());

        mScheduler.advance(100L);
        assertEquals(5, mSender.payloads().size());
        assertFalse(mSender.isClosed());
        mScheduler.advance(200L);
        assertEquals(6, mSender.payloads().size());
        assertTrue(mSender.isClosed());

        mScheduler.advance(30_000L);
        assertEquals(6, mSender.payloads().size());

        List<Announcement> announcements = mSender.decoded();
        long previous = 0L;
        for (Announcement announcement : announcements) {
            assertTrue(announcement.getSeq() > previous);
            previous = announcement.getSeq();
        }
        assertTrue(announcements.get(3).isGoodbye());
        assertTrue(announcements.get(5).isGoodbye());
    }

    @Test
    public void stopWithoutStartDoesNothing() {
        mAnnouncer.stop();

        assertEquals(0, mSender.payloads().size());
        assertFalse(mSender.isClosed());
        assertFalse(mAnnouncer.isRunning());
    }

    @Test
    public void secondStartIsIgnored() {
        mAnnouncer.start();
        String boot = mAnnouncer.boot();
        mAnnouncer.start();

        mScheduler.advance(100L);

        assertEquals(1, mSender.payloads().size());
        assertEquals(boot, mAnnouncer.boot());
    }

    @Test
    public void nullTemplateSkipsSendsButKeepsTheSchedule() {
        MulticastAnnouncer announcer = new MulticastAnnouncer(mSender, mScheduler, () -> null,
            new Random(42L));
        announcer.start();

        mScheduler.advance(2_000L);

        assertEquals(0, mSender.payloads().size());
        assertTrue(announcer.isRunning());
    }

    private Announcement template() {
        return Announcement.builder()
            .id(DEVICE_ID)
            .role(DeviceRole.SERVER)
            .state(ShareState.LIVE)
            .join(JoinPolicy.OPEN)
            .port(8080)
            .platform("android")
            .build();
    }

    /** Records encoded announcements and decodes them for assertions. */
    private static final class RecordingSender implements MulticastAnnouncer.Sender {
        private final List<byte[]> mPayloads = new ArrayList<>();

        private boolean mClosed;

        @Override
        public void send(byte[] payload) {
            mPayloads.add(payload.clone());
        }

        @Override
        public void close() {
            mClosed = true;
        }

        List<byte[]> payloads() {
            return mPayloads;
        }

        boolean isClosed() {
            return mClosed;
        }

        List<Announcement> decoded() {
            List<Announcement> announcements = new ArrayList<>(mPayloads.size());
            for (byte[] payload : mPayloads) {
                Announcement announcement = AnnouncementCodec.decode(payload);
                assertNotNull(announcement);
                announcements.add(announcement);
            }
            return announcements;
        }
    }
}
