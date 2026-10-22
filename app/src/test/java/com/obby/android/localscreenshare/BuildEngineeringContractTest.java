package com.obby.android.localscreenshare;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Static contract test for the release build configuration, its keep rules and its signing
 * material handling.
 *
 * <p>R8 only runs for the release variant, so the JVM test suite never exercises the
 * configuration a published APK is built with, and a missing keep rule does not fail the
 * build either: the app loses persisted data at runtime (the Gson field names are the JSON
 * keys) or loses a gRPC provider at startup. Signing material is the same kind of invisible
 * contract: a committed example key or a silent fallback to an unsigned APK is only noticed
 * when a release is shipped. The build files are therefore the only artefact a unit test can
 * inspect, and these assertions pin the parts that must not regress.</p>
 */
public class BuildEngineeringContractTest {

    // Persisted schemas whose field names are the JSON keys; see proguard-rules.pro.
    private static final String[] GSON_PERSISTED_DTO_RULES = {
        "-keep class com.obby.android.localscreenshare.authorization.store."
            + "AuthorizationStore$Document { <fields>; }",
        "-keep class com.obby.android.localscreenshare.authorization.store."
            + "AuthorizationStore$EntryRecord { <fields>; }",
        "-keep class com.obby.android.localscreenshare.trust."
            + "TlsPinStore$Document { <fields>; }",
        "-keep class com.obby.android.localscreenshare.trust."
            + "TlsPinStore$PinRecord { <fields>; }",
        "-keep class com.obby.android.localscreenshare.discovery.favorite."
            + "FavoriteStore$FavoriteRecord { <fields>; }",
    };

    // Providers registered through META-INF/services and looked up by ServiceLoader.
    private static final String[] GRPC_SERVICE_LOADER_PROVIDERS = {
        "io.grpc.okhttp.OkHttpChannelProvider",
        "io.grpc.okhttp.OkHttpServerProvider",
        "io.grpc.internal.DnsNameResolverProvider",
        "io.grpc.internal.PickFirstLoadBalancerProvider",
    };

    // Property keys whose values must never be committed; see app/build.gradle.
    private static final String[] SIGNING_PROPERTY_KEYS = {
        "signing.storeFile",
        "signing.storePassword",
        "signing.keyAlias",
        "signing.keyPassword",
    };

    // Patterns that keep local signing material out of the repository.
    private static final String[] SIGNING_IGNORE_PATTERNS = {
        "signing.properties",
        "*.jks",
        "*.keystore",
        "signing/",
    };

    @Test
    public void release_enablesMinification() throws IOException {
        final String gradle = collapseWhitespace(read("app/build.gradle"));

        assertTrue("the release build must run R8: " + gradle,
            gradle.contains("minifyEnabled true"));
        assertFalse("the release build must not switch R8 off again",
            gradle.contains("minifyEnabled false"));
        assertTrue("resource shrinking stays off until the unused-resource inventory is clean",
            gradle.contains("shrinkResources false"));
    }

    @Test
    public void proguardRules_keepGsonPersistedSchemas() throws IOException {
        final String rules = collapseWhitespace(read("app/proguard-rules.pro"));

        for (final String rule : GSON_PERSISTED_DTO_RULES) {
            assertTrue("missing Gson keep rule: " + rule, rules.contains(rule));
        }
    }

    @Test
    public void proguardRules_keepGrpcAndProtobufRuntime() throws IOException {
        final String rules = collapseWhitespace(read("app/proguard-rules.pro"));

        for (final String provider : GRPC_SERVICE_LOADER_PROVIDERS) {
            assertTrue("missing gRPC provider keep rule: " + provider,
                rules.contains("-keep class " + provider + " { *; }"));
        }
        assertTrue("protobuf-javalite messages need their fields kept",
            rules.contains("-keep class * extends com.google.protobuf.GeneratedMessageLite { <fields>; }"));
    }

    @Test
    public void gradleProperties_containNoSigningSecrets() throws IOException {
        final String properties = readRootFile("gradle.properties");

        for (final String key : SIGNING_PROPERTY_KEYS) {
            assertFalse("gradle.properties must not carry a value for " + key,
                properties.contains(key));
        }
        assertFalse("gradle.properties must not point at the example keystore",
            properties.contains("example.jks"));
    }

    @Test
    public void gitignore_coversKeystoreAndSigningDirectory() throws IOException {
        final String gitignore = readRootFile(".gitignore");

        for (final String pattern : SIGNING_IGNORE_PATTERNS) {
            assertTrue("missing .gitignore pattern: " + pattern,
                gitignore.contains(pattern));
        }
    }

    @Test
    public void releaseSigning_requiresExplicitMaterial() throws IOException {
        final String gradle = read("app/build.gradle");

        assertTrue("the release build must fail loudly when signing is required but absent",
            gradle.contains("requireSigning"));
        assertFalse("the release build must not reference the removed example keystore",
            gradle.contains("example.jks"));
        assertTrue("the release signing config must only exist when material is present",
            gradle.contains("hasSigningMaterial"));
    }

    /**
     * Reads a file of the app module from either the module or the repository root.
     */
    private static String read(final String modulePath) throws IOException {
        final File[] candidates = {
            new File(modulePath),
            new File("..", modulePath),
        };
        for (final File candidate : candidates) {
            if (candidate.isFile()) {
                return new String(Files.readAllBytes(candidate.toPath()), StandardCharsets.UTF_8);
            }
        }
        throw new IOException("cannot locate " + modulePath + " from "
            + new File(".").getAbsolutePath());
    }

    /**
     * Reads a repository root file, skipping a same-named file inside the app module.
     *
     * <p>Unit tests run with the app module as the working directory, and the module has its
     * own {@code .gitignore}. A root file is recognised by sitting next to
     * {@code settings.gradle}.</p>
     */
    private static String readRootFile(final String name) throws IOException {
        final File[] candidates = {
            new File(name),
            new File("..", name),
        };
        for (final File candidate : candidates) {
            final File parent = candidate.getAbsoluteFile().getParentFile();
            if (candidate.isFile() && parent != null
                && new File(parent, "settings.gradle").isFile()) {
                return new String(Files.readAllBytes(candidate.toPath()), StandardCharsets.UTF_8);
            }
        }
        throw new IOException("cannot locate the repository root file " + name + " from "
            + new File(".").getAbsolutePath());
    }

    /**
     * Collapses every whitespace run to a single space so rule text can be compared as one line.
     */
    private static String collapseWhitespace(final String text) {
        return text.replaceAll("\\s+", " ");
    }
}
