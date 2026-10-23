package com.obby.android.localscreenshare.settings;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Static contract test for who may write the user's preferences.
 *
 * <p>A device round found random-looking values in the preference file after a session: the port
 * wheel persisted every intermediate value of a fling, and a hidden key was suspected of being
 * erased at runtime. The rule this test pins is the one the ruling states: <b>a preference stores
 * user intent</b>. Runtime facts - the port a server actually bound, a channel it negotiated -
 * belong to session state and must never be written back, because an injected or configured value
 * would silently be replaced by whatever the process happened to use.</p>
 *
 * <p>The scan reads the production sources: no test can execute the settings sheet or the server on
 * the JVM, so the shape of the source is the only artefact available, the same approach
 * {@code BuildEngineeringContractTest} and {@code ComponentConstructionTest} take.</p>
 */
public class PreferenceWriteContractTest {

    @Test
    public void hiddenMediaTransportKey_hasNoRuntimeWriter() throws IOException {
        for (final Source source : productionSources()) {
            if (source.path.endsWith("settings" + File.separator + "Preferences.java")) {
                continue;
            }
            assertEquals(source.path + " must not write the hidden media-transport key",
                0, count(source.text, "setMediaTransport("));
        }
    }

    @Test
    public void serverPort_isWrittenOnlyByTheSettingsWheel() throws IOException {
        int callSites = 0;
        final List<String> files = new ArrayList<>();
        for (final Source source : productionSources()) {
            if (source.path.endsWith("settings" + File.separator + "Preferences.java")) {
                continue;
            }
            final int count = count(source.text, "setServerPort(");
            if (count > 0) {
                callSites += count;
                files.add(source.path);
            }
        }

        assertEquals("the running server must never write the port it bound back: " + files,
            1, callSites);
        assertTrue("the only writer is the settings wheel: " + files,
            files.get(0).endsWith("MainActivity.java"));
    }

    @Test
    public void preferenceKeys_areNamedOnlyByPreferences() throws IOException {
        for (final Source source : productionSources()) {
            if (source.path.endsWith("settings" + File.separator + "Preferences.java")) {
                continue;
            }
            assertEquals(source.path + " must not name the server_port key",
                0, count(source.text, "\"server_port\""));
            assertEquals(source.path + " must not name the media_transport key",
                0, count(source.text, "\"media_transport\""));
        }
    }

    private static int count(final String text, final String needle) {
        int found = 0;
        int index = text.indexOf(needle);
        while (index >= 0) {
            found++;
            index = text.indexOf(needle, index + needle.length());
        }
        return found;
    }

    private static List<Source> productionSources() throws IOException {
        final File root = new File("app" + File.separator + "src" + File.separator + "main"
            + File.separator + "java");
        final File directory = root.isDirectory() ? root : new File(repositoryRoot(), root.getPath());
        final List<Source> sources = new ArrayList<>();
        collect(directory, sources);
        assertTrue("no production source found under " + directory.getAbsolutePath(),
            !sources.isEmpty());
        return sources;
    }

    private static void collect(final File directory, final List<Source> sources)
        throws IOException {
        final File[] children = directory.listFiles();
        if (children == null) {
            return;
        }
        for (final File child : children) {
            if (child.isDirectory()) {
                collect(child, sources);
            } else if (child.getName().endsWith(".java")) {
                sources.add(new Source(child.getPath(),
                    new String(Files.readAllBytes(child.toPath()), StandardCharsets.UTF_8)));
            }
        }
    }

    private static File repositoryRoot() throws IOException {
        for (final String name : new String[]{"settings.gradle", ".." + File.separator
            + "settings.gradle"}) {
            final File candidate = new File(name);
            if (candidate.isFile()) {
                return candidate.getAbsoluteFile().getParentFile();
            }
        }
        throw new IOException("cannot locate the repository root from "
            + new File(".").getAbsolutePath());
    }

    /** One production source file and its text. */
    private static final class Source {

        private final String path;

        private final String text;

        private Source(String path, String text) {
            this.path = path;
            this.text = text;
        }
    }
}
