package com.obby.android.localscreenshare.discovery.udp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.obby.android.localscreenshare.discovery.model.AuthMode;
import com.obby.android.localscreenshare.discovery.model.DeviceRole;
import com.obby.android.localscreenshare.discovery.model.JoinPolicy;
import com.obby.android.localscreenshare.discovery.model.ShareState;
import com.obby.android.localscreenshare.discovery.provider.DeviceObservation;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class AnnouncementCodecTest {

    private static final String DEVICE_ID = "L7Q3K9M2F4X8B6N1C5V0Z3T7R2";

    @Test
    public void roundTripPreservesEveryField() {
        Announcement announcement = Announcement.builder()
            .id(DEVICE_ID)
            .name("Living room")
            .role(DeviceRole.SERVER)
            .state(ShareState.LIVE)
            .join(JoinPolicy.APPROVAL)
            .secure(true)
            .auth(AuthMode.NONE)
            .protocolVersion("1.0")
            .metadataVersion(2)
            .capabilities(3)
            .port(8080)
            .platform("android")
            .boot("b7f3c2a1d4e59608")
            .seq(42L)
            .ttlSeconds(15)
            .announces(true)
            .build();

        Announcement decoded = AnnouncementCodec.decode(AnnouncementCodec.encode(announcement));

        assertNotNull(decoded);
        assertEquals(announcement, decoded);
        assertEquals("Living room", decoded.getName());
        assertEquals(8080, decoded.getPort());
        assertEquals(42L, decoded.getSeq());
        assertEquals(15, decoded.getTtlSeconds());
        assertFalse(decoded.isGoodbye());
    }

    @Test
    public void missingAnnouncesDefaultsToTrue() {
        byte[] payload = ("{\"v\":1,\"id\":\"" + DEVICE_ID + "\"}").getBytes(StandardCharsets.UTF_8);

        Announcement decoded = AnnouncementCodec.decode(payload);

        assertNotNull(decoded);
        assertTrue(decoded.isAnnounces());
        assertEquals(DEVICE_ID, decoded.getId());
        assertNull(decoded.getName());
    }

    @Test
    public void unknownFieldsAreIgnored() {
        Announcement announcement = Announcement.builder().id(DEVICE_ID).port(8080).build();
        JsonObject json = JsonParser.parseString(
            new String(AnnouncementCodec.encode(announcement), StandardCharsets.UTF_8)).getAsJsonObject();
        json.addProperty("future_field", "ignored");
        json.addProperty("x", 7);

        Announcement decoded = AnnouncementCodec.decode(json.toString().getBytes(StandardCharsets.UTF_8));

        assertNotNull(decoded);
        assertEquals(DEVICE_ID, decoded.getId());
        assertEquals(8080, decoded.getPort());
    }

    @Test
    public void overlongNameIsTruncatedInsteadOfDropped() {
        String name = new String(new char[100]).replace('\0', 'a');
        Announcement announcement = Announcement.builder().id(DEVICE_ID).name(name).build();

        Announcement decoded = AnnouncementCodec.decode(AnnouncementCodec.encode(announcement));

        assertNotNull(decoded);
        assertEquals(Announcement.MAX_STRING_LENGTH, decoded.getName().length());
    }

    @Test
    public void payloadOverTheWireLimitIsRejected() {
        byte[] payload = new byte[AnnouncementCodec.MAX_BYTES + 1];
        java.util.Arrays.fill(payload, (byte) 'a');

        assertNull(AnnouncementCodec.decode(payload));
    }

    @Test
    public void objectWithTooManyFieldsIsRejected() {
        JsonObject json = new JsonObject();
        json.addProperty("v", 1);
        json.addProperty("id", DEVICE_ID);
        for (int i = 0; i < AnnouncementCodec.MAX_FIELDS; i++) {
            json.addProperty("f" + i, i);
        }

        assertNull(AnnouncementCodec.decode(json.toString().getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void malformedJsonAndMissingIdAreRejected() {
        assertNull(AnnouncementCodec.decode("not json".getBytes(StandardCharsets.UTF_8)));
        assertNull(AnnouncementCodec.decode("{\"v\":1}".getBytes(StandardCharsets.UTF_8)));
        assertNull(AnnouncementCodec.decode(new byte[0]));
        assertNull(AnnouncementCodec.decode(null));
    }

    @Test
    public void unsupportedVersionAndControlCharactersAreRejected() {
        assertNull(AnnouncementCodec.decode(
            ("{\"v\":2,\"id\":\"" + DEVICE_ID + "\"}").getBytes(StandardCharsets.UTF_8)));
        assertNull(AnnouncementCodec.decode(
            ("{\"v\":1,\"id\":\"bad\\u0001id\"}").getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void goodbyeRoundTripsAndMapsToAGoodbyeObservation() {
        Announcement announcement = Announcement.builder()
            .id(DEVICE_ID)
            .role(DeviceRole.SERVER)
            .port(8080)
            .boot("b7f3c2a1d4e59608")
            .seq(7L)
            .announces(false)
            .build();

        Announcement decoded = AnnouncementCodec.decode(AnnouncementCodec.encode(announcement));

        assertNotNull(decoded);
        assertTrue(decoded.isGoodbye());

        DeviceObservation observation = decoded.toObservation("10.0.0.5", 1_234L);
        assertTrue(observation.isGoodbye());
        assertEquals(DEVICE_ID, observation.getId());
        assertEquals("10.0.0.5", observation.getHost());
        assertEquals(Integer.valueOf(8080), observation.getPort());
        assertEquals("b7f3c2a1d4e59608", observation.getBoot());
        assertEquals(Long.valueOf(7L), observation.getSeq());
    }

    @Test
    public void announcementWithoutServerPortOmitsTheObservationAddress() {
        Announcement announcement = Announcement.builder().id(DEVICE_ID).port(0).build();

        DeviceObservation observation = announcement.toObservation("10.0.0.5", 1_000L);

        assertFalse(observation.hasAddress());
        assertNull(observation.getPort());
    }
}
