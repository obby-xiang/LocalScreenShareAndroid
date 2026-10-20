package com.obby.android.localscreenshare.discovery.nsd;

import com.obby.android.localscreenshare.discovery.model.AuthMode;
import com.obby.android.localscreenshare.discovery.model.DeviceAddress;
import com.obby.android.localscreenshare.discovery.model.DeviceRole;
import com.obby.android.localscreenshare.discovery.model.DiscoveryChannel;
import com.obby.android.localscreenshare.discovery.model.JoinPolicy;
import com.obby.android.localscreenshare.discovery.model.DeviceInfo;
import com.obby.android.localscreenshare.discovery.model.ShareState;
import com.obby.android.localscreenshare.discovery.provider.DeviceObservation;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class NsdTxtRecordTest {

    private static final String DEVICE_ID = "L7Q3K9M2F4X8B6N1C5V0Z3T7R2";

    private static final String HOST = "10.0.0.5";

    private static final int PORT = 8080;

    @Test
    public void currentRecordCarriesTheMinimalSetAndNoNameByDefault() {
        Map<String, byte[]> attributes = NsdTxtRecord.encode(device(true), NsdTxtRecord.Namespace.CURRENT, false);

        assertEquals(NsdTxtRecord.SCHEMA_VERSION, value(attributes, NsdConstants.NSD_SERVICE_ATTR_VERSION));
        assertEquals(DEVICE_ID, value(attributes, NsdConstants.NSD_SERVICE_ATTR_ID));
        assertEquals("server", value(attributes, NsdConstants.NSD_SERVICE_ATTR_ROLE));
        assertEquals("live", value(attributes, NsdConstants.NSD_SERVICE_ATTR_STATE));
        assertEquals("approval", value(attributes, NsdConstants.NSD_SERVICE_ATTR_JOIN));
        assertEquals("1", value(attributes, NsdConstants.NSD_SERVICE_ATTR_SECURE));
        assertEquals("none", value(attributes, NsdConstants.NSD_SERVICE_ATTR_AUTH));
        assertEquals("1.0", value(attributes, NsdConstants.NSD_SERVICE_ATTR_PROTOCOL_VERSION));
        assertEquals("2", value(attributes, NsdConstants.NSD_SERVICE_ATTR_METADATA_VERSION));
        assertEquals("3", value(attributes, NsdConstants.NSD_SERVICE_ATTR_CAPABILITIES));
        assertFalse(attributes.containsKey(NsdConstants.NSD_SERVICE_ATTR_DISPLAY_NAME));
        assertFalse(attributes.containsKey(NsdConstants.NSD_SERVICE_ATTR_NAME));
    }

    @Test
    public void currentRecordRoundTripsIntoAnObservation() {
        Map<String, byte[]> attributes = NsdTxtRecord.encode(device(false), NsdTxtRecord.Namespace.CURRENT, false);

        DeviceObservation observation = NsdTxtRecord.observe(attributes, HOST, PORT, 1_234L);

        assertNotNull(observation);
        assertEquals(DiscoveryChannel.NSD, observation.getSource());
        assertEquals(DEVICE_ID, observation.getId());
        assertEquals(DeviceRole.SERVER, observation.getRole());
        assertEquals(ShareState.LIVE, observation.getState());
        assertEquals(JoinPolicy.APPROVAL, observation.getJoin());
        assertEquals(AuthMode.NONE, observation.getAuth());
        assertEquals("1.0", observation.getProtocolVersion());
        assertEquals(Integer.valueOf(2), observation.getMetadataVersion());
        assertEquals(Integer.valueOf(3), observation.getCapabilities());
        assertEquals(Boolean.FALSE, observation.getSecure());
        assertEquals(HOST, observation.getHost());
        assertEquals(Integer.valueOf(PORT), observation.getPort());
        assertEquals(Long.valueOf(1_234L), observation.getLastSeenMs());
        assertNull(observation.getName());
        assertFalse(observation.isGoodbye());
        assertFalse(observation.isLost());
    }

    @Test
    public void recordWithoutNameIsStillIngested() {
        Map<String, byte[]> attributes = attributes(NsdConstants.NSD_SERVICE_ATTR_ID, DEVICE_ID);

        DeviceObservation observation = NsdTxtRecord.observe(attributes, HOST, PORT, 0L);

        assertNotNull(observation);
        assertNull(observation.getName());
        assertNull(observation.getRole());
    }

    @Test
    public void sharedNameIsWrittenOnlyWhenEnabled() {
        Map<String, byte[]> attributes = NsdTxtRecord.encode(device(false), NsdTxtRecord.Namespace.CURRENT, true);

        assertEquals("Kitchen TV", value(attributes, NsdConstants.NSD_SERVICE_ATTR_DISPLAY_NAME));
        assertEquals("Kitchen TV", NsdTxtRecord.observe(attributes, HOST, PORT, 0L).getName());
    }

    @Test
    public void legacyRecordKeepsTheOldShapeWithShortIdFallbackName() {
        Map<String, byte[]> attributes = NsdTxtRecord.encode(device(false), NsdTxtRecord.Namespace.LEGACY, false);

        assertEquals(DEVICE_ID, value(attributes, NsdConstants.NSD_SERVICE_ATTR_ID));
        assertEquals("LSS-L7Q3K9M2", value(attributes, NsdConstants.NSD_SERVICE_ATTR_NAME));
        assertFalse(attributes.containsKey(NsdConstants.NSD_SERVICE_ATTR_VERSION));
        assertFalse(attributes.containsKey(NsdConstants.NSD_SERVICE_ATTR_ROLE));
    }

    @Test
    public void legacyRecordWithSharingWritesTheAliasAndNeverTheSystemName() {
        Map<String, byte[]> attributes = NsdTxtRecord.encode(device(false), NsdTxtRecord.Namespace.LEGACY, true);
        assertEquals("Kitchen TV", value(attributes, NsdConstants.NSD_SERVICE_ATTR_NAME));

        Map<String, byte[]> untitled = NsdTxtRecord.encode(
            DeviceInfo.builder().id(DEVICE_ID).build(), NsdTxtRecord.Namespace.LEGACY, true);
        assertEquals("LSS-L7Q3K9M2", value(untitled, NsdConstants.NSD_SERVICE_ATTR_NAME));
    }

    @Test
    public void legacyNameKeyIsNormalizedOnDecode() {
        DeviceObservation observation = NsdTxtRecord.observe(
            attributes(NsdConstants.NSD_SERVICE_ATTR_ID, DEVICE_ID, NsdConstants.NSD_SERVICE_ATTR_NAME, "Old server"),
            HOST, PORT, 0L);

        assertNotNull(observation);
        assertEquals("Old server", observation.getName());
    }

    @Test
    public void unknownKeysAreIgnored() {
        Map<String, byte[]> attributes = attributes(NsdConstants.NSD_SERVICE_ATTR_ID, DEVICE_ID, "future", "value");

        assertEquals(DEVICE_ID, NsdTxtRecord.decode(attributes).get(NsdConstants.NSD_SERVICE_ATTR_ID));
        assertEquals(1, NsdTxtRecord.decode(attributes).size());
    }

    @Test
    public void unsupportedSchemaVersionDropsTheRecord() {
        Map<String, byte[]> attributes = attributes(
            NsdConstants.NSD_SERVICE_ATTR_VERSION, "2", NsdConstants.NSD_SERVICE_ATTR_ID, DEVICE_ID);

        assertNull(NsdTxtRecord.observe(attributes, HOST, PORT, 0L));
    }

    @Test
    public void missingIdDropsTheRecord() {
        assertNull(NsdTxtRecord.observe(attributes(), HOST, PORT, 0L));
        assertNull(NsdTxtRecord.observe(
            attributes(NsdConstants.NSD_SERVICE_ATTR_ID, "   "), HOST, PORT, 0L));
    }

    @Test
    public void missingHostDropsTheRecord() {
        assertNull(NsdTxtRecord.observe(attributes(NsdConstants.NSD_SERVICE_ATTR_ID, DEVICE_ID), null, PORT, 0L));
        assertNull(NsdTxtRecord.observe(attributes(NsdConstants.NSD_SERVICE_ATTR_ID, DEVICE_ID), " ", PORT, 0L));
    }

    @Test
    public void nonPrintableIdDropsTheRecord() {
        assertNull(NsdTxtRecord.observe(
            attributes(NsdConstants.NSD_SERVICE_ATTR_ID, "bad\u0000id"), HOST, PORT, 0L));
    }

    @Test
    public void overlongNameIsTruncatedOnEncode() {
        StringBuilder name = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            name.append('x');
        }
        DeviceInfo device = DeviceInfo.builder().id(DEVICE_ID).name(name.toString()).build();

        Map<String, byte[]> attributes = NsdTxtRecord.encode(device, NsdTxtRecord.Namespace.CURRENT, true);

        assertEquals(NsdTxtRecord.MAX_NAME_LENGTH,
            value(attributes, NsdConstants.NSD_SERVICE_ATTR_DISPLAY_NAME).length());
    }

    @Test
    public void nonAsciiNameRoundTripsAsUtf8() {
        DeviceInfo device = DeviceInfo.builder().id(DEVICE_ID).name("Küche-TV").build();

        Map<String, byte[]> attributes = NsdTxtRecord.encode(device, NsdTxtRecord.Namespace.CURRENT, true);

        assertEquals("Küche-TV", NsdTxtRecord.observe(attributes, HOST, PORT, 0L).getName());
    }

    @Test
    public void invalidNumbersAndFlagsStayNull() {
        DeviceObservation observation = NsdTxtRecord.observe(attributes(
            NsdConstants.NSD_SERVICE_ATTR_ID, DEVICE_ID,
            NsdConstants.NSD_SERVICE_ATTR_CAPABILITIES, "many",
            NsdConstants.NSD_SERVICE_ATTR_METADATA_VERSION, "2.5",
            NsdConstants.NSD_SERVICE_ATTR_SECURE, "maybe"), HOST, PORT, 0L);

        assertNotNull(observation);
        assertNull(observation.getCapabilities());
        assertNull(observation.getMetadataVersion());
        assertNull(observation.getSecure());
    }

    @Test
    public void secureFlagAcceptsCommonSpellings() {
        assertEquals(Boolean.TRUE, NsdTxtRecord.observe(
            attributes(NsdConstants.NSD_SERVICE_ATTR_ID, DEVICE_ID, NsdConstants.NSD_SERVICE_ATTR_SECURE, "true"),
            HOST, PORT, 0L).getSecure());
        assertEquals(Boolean.FALSE, NsdTxtRecord.observe(
            attributes(NsdConstants.NSD_SERVICE_ATTR_ID, DEVICE_ID, NsdConstants.NSD_SERVICE_ATTR_SECURE, "0"),
            HOST, PORT, 0L).getSecure());
    }

    @Test
    public void emptyAttributesDecodeToEmptyMap() {
        assertTrue(NsdTxtRecord.decode(attributes()).isEmpty());
    }

    private static DeviceInfo device(boolean secure) {
        return DeviceInfo.builder()
            .id(DEVICE_ID)
            .name("Kitchen TV")
            .role(DeviceRole.SERVER)
            .state(ShareState.LIVE)
            .join(JoinPolicy.APPROVAL)
            .secure(secure)
            .auth(AuthMode.NONE)
            .protocolVersion("1.0")
            .metadataVersion(2)
            .capabilities(3)
            .addresses(Collections.singletonList(new DeviceAddress(HOST, PORT, DiscoveryChannel.NSD)))
            .build();
    }

    private static Map<String, byte[]> attributes(String... keyValues) {
        Map<String, byte[]> attributes = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            attributes.put(keyValues[i], keyValues[i + 1].getBytes(StandardCharsets.UTF_8));
        }
        return attributes;
    }

    private static String value(Map<String, byte[]> attributes, String key) {
        byte[] raw = attributes.get(key);
        return raw == null ? null : new String(raw, StandardCharsets.UTF_8);
    }
}
