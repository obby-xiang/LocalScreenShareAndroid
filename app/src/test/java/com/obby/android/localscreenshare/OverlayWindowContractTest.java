package com.obby.android.localscreenshare;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Static contract test for how the services touch overlay windows.
 *
 * <p>Every floating window and floating dialog of the two services goes through
 * {@code support/OverlayWindow}, which swallows the {@code BadTokenException} and
 * {@code SecurityException} the platform raises after the user revokes "display over other apps".
 * A direct {@code addView} or {@code removeViewImmediate} call left behind in a service bypasses
 * that guard and can kill the process on the main looper, which no JVM behaviour test can observe
 * because the failure only happens on a device.</p>
 *
 * <p>The scan is deliberately narrow: the two service sources, and the two window-manager calls
 * that create or destroy an overlay window. Dialogs are covered by the same rule in review terms,
 * but their {@code show()} call is not distinguishable from an in-activity dialog by source text,
 * so it is not part of the automated check.</p>
 */
public class OverlayWindowContractTest {

    private static final String[] FORBIDDEN = {
        "mWindowManager.addView(",
        "mWindowManager.removeViewImmediate(",
    };

    @Test
    public void services_doNotTouchTheWindowManagerDirectly() throws IOException {
        final List<String> violations = new ArrayList<>();
        for (final File file : serviceSources()) {
            final List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
            for (int index = 0; index < lines.size(); index++) {
                final String line = lines.get(index);
                for (final String forbidden : FORBIDDEN) {
                    if (line.contains(forbidden)) {
                        violations.add(file.getName() + ":" + (index + 1) + ": " + line.trim());
                    }
                }
            }
        }

        assertTrue("overlay windows and dialogs must go through support/OverlayWindow: "
            + violations, violations.isEmpty());
    }

    @Test
    public void scan_coversBothServices() throws IOException {
        final Set<String> names = new LinkedHashSet<>();
        for (final File file : serviceSources()) {
            names.add(file.getName());
        }

        assertTrue("the scan must see the services: " + names,
            names.contains("ScreenShareService.java") && names.contains("ScreenViewerService.java"));
    }

    /**
     * Returns the service sources, resolving the module directory Gradle uses as working
     * directory as well as the repository root.
     */
    private static List<File> serviceSources() throws IOException {
        final List<File> sources = new ArrayList<>();
        final File directory = new File(productionSources(), "com/obby/android/localscreenshare/service");
        final File[] children = directory.listFiles();
        if (children == null) {
            return sources;
        }
        for (final File child : children) {
            if (child.isFile() && child.getName().endsWith("Service.java")) {
                sources.add(child);
            }
        }
        return sources;
    }

    /**
     * Resolves the directory that holds the production sources.
     */
    private static File productionSources() {
        final File[] candidates = {
            new File("src/main/java"),
            new File("app/src/main/java"),
        };
        for (final File candidate : candidates) {
            if (candidate.isDirectory()) {
                return candidate;
            }
        }
        return new File("src/main/java");
    }
}
