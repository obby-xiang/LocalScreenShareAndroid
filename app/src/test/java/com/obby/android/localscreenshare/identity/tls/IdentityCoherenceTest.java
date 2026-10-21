package com.obby.android.localscreenshare.identity.tls;

import androidx.annotation.NonNull;

import com.obby.android.localscreenshare.discovery.model.AuthMode;
import com.obby.android.localscreenshare.discovery.model.DeviceInfo;
import com.obby.android.localscreenshare.discovery.model.DeviceRole;
import com.obby.android.localscreenshare.discovery.model.JoinPolicy;
import com.obby.android.localscreenshare.discovery.model.ShareState;
import com.obby.android.localscreenshare.discovery.register.RegisterProtoMapper;
import com.obby.android.localscreenshare.discovery.udp.Announcement;
import com.obby.android.localscreenshare.discovery.udp.AnnouncementCodec;
import com.obby.android.localscreenshare.grpc.screenstream.DeviceSummary;
import com.obby.android.localscreenshare.identity.DeviceIdentity;
import com.obby.android.localscreenshare.identity.SpkiId;
import com.obby.android.localscreenshare.server.ServerInfo;

import org.junit.Test;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;

/**
 * The three identity snapshots a running session publishes must be one value.
 *
 * <p>The 2026-10-21 device test found the announcement naming a device id whose key was no longer in
 * the keystore while the handshake presented another, so every {@code Register} ended in
 * {@code identity_mismatch} (N3, root cause B). The fix derives all three from {@link
 * TlsMaterialHolder#material()}, and this case pins the resulting contract on the payloads
 * themselves: the announced id, the certificate-derived id and the {@code Register} self id are
 * equal, and a replaced key moves all three together.</p>
 *
 * <p>The production call sites ({@code ScreenShareService#buildAnnouncement},
 * {@code ScreenShareServer#start}, {@code DiscoveryManager#selfSummary}) are Android-bound, so the
 * payloads are composed here with the same production builders and mappers they use.</p>
 */
public class IdentityCoherenceTest {

    @Test
    public void announcementCertificateAndRegisterAgree() throws Exception {
        final TlsMaterialHolder holder = new TlsMaterialHolder(new FixedKeySource(newKeyPair()));
        final TlsMaterial material = holder.material();
        final DeviceIdentity identity = material.toDeviceIdentity();

        assertEquals(material.getFingerprint(), identity.getFingerprint());
        assertEquals(material.getDeviceId(), identity.getId());
        assertEquals(identity.getId(), announcementId(identity));
        assertEquals(identity.getId(), registerSelfId(identity));
        assertEquals(identity.getFingerprint(), registerSelfFingerprint(identity));
        assertEquals(identity.getId(), serverInfoDeviceId(identity));
        assertEquals(identity.getFingerprint(), serverInfoFingerprint(identity));
    }

    @Test
    public void aReplacedKeyMovesAllThreeTogether() throws Exception {
        final FixedKeySource source = new FixedKeySource(newKeyPair());
        final TlsMaterialHolder holder = new TlsMaterialHolder(source);
        final DeviceIdentity before = holder.material().toDeviceIdentity();

        final KeyPair replaced = newKeyPair();
        source.useKeyPair(replaced);
        final DeviceIdentity after = holder.material().toDeviceIdentity();

        assertNotEquals(before.getId(), after.getId());
        assertEquals(announcementId(before), registerSelfId(before));
        assertEquals(announcementId(after), registerSelfId(after));
        assertEquals(SpkiId.deviceId(replaced.getPublic().getEncoded()), announcementId(after));
        assertEquals(fingerprintOf(replaced), serverInfoFingerprint(after));
    }

    @Test
    public void theAnnouncementNeverCarriesTheFingerprint() throws Exception {
        final TlsMaterialHolder holder = new TlsMaterialHolder(new FixedKeySource(newKeyPair()));
        final TlsMaterial material = holder.material();

        final byte[] payload = AnnouncementCodec.encode(announcement(material.toDeviceIdentity()));

        // The announcement is broadcast in the clear, so the fingerprint stays out of it; a viewer
        // learns it from the handshake, which is what N2's guided first connection uses.
        assertEquals(-1, new String(payload, java.nio.charset.StandardCharsets.UTF_8)
            .indexOf(material.getFingerprint()));
        assertNotNull(AnnouncementCodec.decode(payload));
    }

    @NonNull
    private static Announcement announcement(@NonNull final DeviceIdentity identity) {
        return Announcement.builder()
            .id(identity.getId())
            .role(DeviceRole.SERVER)
            .state(ShareState.LIVE)
            .join(JoinPolicy.OPEN)
            .secure(true)
            .auth(AuthMode.MTLS)
            .port(8080)
            .build();
    }

    @NonNull
    private static String announcementId(@NonNull final DeviceIdentity identity) {
        final Announcement decoded = AnnouncementCodec.decode(
            AnnouncementCodec.encode(announcement(identity)));
        assertNotNull(decoded);
        return decoded.getId();
    }

    @NonNull
    private static DeviceSummary registerSelf(@NonNull final DeviceIdentity identity) {
        return RegisterProtoMapper.toSummary(DeviceInfo.builder()
            .id(identity.getId())
            .fingerprint(identity.getFingerprint())
            .role(DeviceRole.BOTH)
            .state(ShareState.IDLE)
            .join(JoinPolicy.UNKNOWN)
            .secure(true)
            .auth(AuthMode.MTLS)
            .metadataVersion(1)
            .build());
    }

    @NonNull
    private static String registerSelfId(@NonNull final DeviceIdentity identity) {
        return registerSelf(identity).getDeviceId();
    }

    @NonNull
    private static String registerSelfFingerprint(@NonNull final DeviceIdentity identity) {
        return registerSelf(identity).getFingerprint();
    }

    @NonNull
    private static ServerInfo serverInfo(@NonNull final DeviceIdentity identity) {
        return ServerInfo.builder()
            .id(identity.getId())
            .deviceId(identity.getId())
            .fingerprint(identity.getFingerprint())
            .name("LSS-TEST")
            .hostAddress("192.0.2.1")
            .port(8080)
            .build();
    }

    @NonNull
    private static String serverInfoDeviceId(@NonNull final DeviceIdentity identity) {
        return serverInfo(identity).getDeviceId();
    }

    @NonNull
    private static String serverInfoFingerprint(@NonNull final DeviceIdentity identity) {
        return serverInfo(identity).getFingerprint();
    }

    @NonNull
    private static String fingerprintOf(@NonNull final KeyPair keyPair) {
        return SpkiId.fingerprint(keyPair.getPublic().getEncoded());
    }

    @NonNull
    private static KeyPair newKeyPair() {
        try {
            final KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("EC is unavailable", e);
        }
    }

    /**
     * Key source whose key pair can be replaced between two loads.
     */
    private static final class FixedKeySource implements TlsMaterialHolder.KeyPairSource {

        @NonNull
        private KeyPair mKeyPair;

        FixedKeySource(@NonNull final KeyPair keyPair) {
            mKeyPair = keyPair;
        }

        void useKeyPair(@NonNull final KeyPair keyPair) {
            mKeyPair = keyPair;
        }

        @NonNull
        @Override
        public KeyPair loadOrCreate() {
            return mKeyPair;
        }
    }
}
