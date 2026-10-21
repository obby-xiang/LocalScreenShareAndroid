package com.obby.android.localscreenshare;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Static contract test for how Android components are constructed.
 *
 * <p>The framework instantiates a component with {@code Class.newInstance()} and attaches its base
 * context afterwards, so a field initialiser that reads the context through {@code this} throws a
 * {@code NullPointerException} inside the framework, which reports "Unable to instantiate" and
 * kills the process. Two device runs lost the app that way: the first to a device-profile sampler
 * in a service field initialiser, the second to a {@code GestureDetector} in
 * {@code ViewerActivity}. The JVM never walks that path, so the shape of the source is the only
 * thing a unit test can check.</p>
 *
 * <p>The scan is deliberately narrow: only class-level fields (four-space indentation, which is
 * where this codebase declares them) of classes that extend a framework component, and only
 * initialisers that do not open a block, because an anonymous class body runs its methods later.
 * The rule itself lives in {@code docs/naming-conventions.md}, chapter 8.</p>
 */
public class ComponentConstructionTest {

    // Framework superclasses that the platform instantiates itself.
    private static final Pattern COMPONENT = Pattern.compile(
        "\\bextends\\s+(?:AppCompatActivity|Activity|Service|IntentService|BroadcastReceiver"
            + "|ContentProvider|Application|AppWidgetProvider)\\b");

    // Start of a class-level field initialiser; the statement may continue on the following lines.
    private static final Pattern FIELD_INITIALISER = Pattern.compile("^ {4}\\S.*=[^=].*");

    // Expressions that read the context the framework has not attached yet.
    private static final Pattern CONTEXT_ACCESS = Pattern.compile(
        "\\(this[,)]|this\\.(?:getResources|getSystemService|getApplicationContext"
            + "|getApplication|getPackageManager)\\(");

    @Test
    public void componentFieldInitialisers_doNotTouchTheContext() throws IOException {
        final List<String> violations = new ArrayList<>();
        for (final File file : componentSources()) {
            final List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
            for (int index = 0; index < lines.size(); index++) {
                final String start = lines.get(index);
                if (!FIELD_INITIALISER.matcher(start).matches()) {
                    continue;
                }
                final String initialiser = initialiserText(lines, index);
                if (initialiser == null) {
                    // Anonymous class or lambda: the body runs on a callback, not at construction.
                    continue;
                }
                final Matcher access = CONTEXT_ACCESS.matcher(initialiser);
                if (access.find()) {
                    violations.add(file.getName() + ":" + (index + 1) + ": " + start.trim());
                }
            }
        }

        assertTrue("no component field initialiser may read the context before attachBaseContext: "
            + violations, violations.isEmpty());
    }

    /**
     * Returns the whole initialiser that starts at {@code start}, or null when it opens a block.
     *
     * <p>A block means an anonymous class or a lambda with a body; those run on a callback and are
     * therefore allowed to use the context. Plain constructors can wrap, so the statement is read
     * until its terminating semicolon.</p>
     */
    private static String initialiserText(final List<String> lines, final int start) {
        final StringBuilder text = new StringBuilder(lines.get(start));
        if (text.indexOf("{") >= 0) {
            return null;
        }
        int cursor = start;
        while (!text.toString().trim().endsWith(";") && cursor + 1 < lines.size()) {
            cursor++;
            final String next = lines.get(cursor);
            if (next.indexOf("{") >= 0) {
                return null;
            }
            text.append(' ').append(next.trim());
        }
        return text.toString();
    }

    @Test
    public void scan_coversEveryComponentOfTheApp() throws IOException {
        final Set<String> names = new LinkedHashSet<>();
        for (final File file : componentSources()) {
            names.add(file.getName());
        }

        assertTrue("the scan must see the activities: " + names,
            names.contains("MainActivity.java") && names.contains("ViewerActivity.java"));
        assertTrue("the scan must see the services: " + names,
            names.contains("ScreenShareService.java") && names.contains("ScreenViewerService.java"));
        assertTrue("the scan must see the application class: " + names,
            names.contains("App.java"));
    }

    /**
     * Returns the sources that declare a framework component.
     *
     * <p>The scan is vacuous when the directory is missing, so the lookup accepts both the module
     * directory Gradle uses as the working directory and the repository root, and skips with a
     * message rather than failing when neither exists.</p>
     */
    private static List<File> componentSources() throws IOException {
        final File root = sourceRoot();
        final List<File> sources = new ArrayList<>();
        if (root == null) {
            return sources;
        }
        final List<File> files = new ArrayList<>();
        collect(root, files);
        for (final File file : files) {
            final String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            if (COMPONENT.matcher(text).find()) {
                sources.add(file);
            }
        }
        return sources;
    }

    /**
     * Resolves the directory that holds the production sources, or null when it is not reachable.
     */
    private static File sourceRoot() {
        final File[] candidates = {
            new File("src/main/java"),
            new File("app/src/main/java"),
        };
        for (final File candidate : candidates) {
            if (candidate.isDirectory()) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Collects the Java sources below {@code directory}.
     */
    private static void collect(final File directory, final List<File> files) {
        final File[] children = directory.listFiles();
        if (children == null) {
            return;
        }
        for (final File child : children) {
            if (child.isDirectory()) {
                collect(child, files);
            } else if (child.getName().endsWith(".java")) {
                files.add(child);
            }
        }
    }

    @Test
    public void scan_isNotEmpty() throws IOException {
        assertFalse("the production sources must be reachable from the test working directory",
            componentSources().isEmpty());
    }
}
