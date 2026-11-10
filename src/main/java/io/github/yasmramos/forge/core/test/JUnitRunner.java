package io.github.yasmramos.forge.core.test;

import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * In-process JUnit Platform runner used by {@code forge build} to execute
 * the compiled test classes of a Forge project (AGENT.md section 21).
 *
 * <p>This class is launched as a separate JVM process by
 * {@link io.github.yasmramos.forge.core.ForgeEngine}: the child JVM gets
 * the project's test classpath (test-classes + resolved dependencies,
 * including {@code junit-platform-launcher}) plus Forge's own jar. Keeping
 * the runner in its own process isolates the test JVM from Forge's runtime
 * and lets each project pick its own JUnit version.</p>
 *
 * <p>Usage: {@code java -cp <test-classpath>:<forge.jar> \
 * io.github.yasmramos.forge.core.test.JUnitRunner <test-classes-dir>
 * [--retry=N] [fully.qualified.ClassName ...]}. Without class names, every test
 * under the directory is discovered; with class names, only those classes run
 * (selective execution). {@code --retry=N} re-runs a failing class up to N extra
 * times and reports it as flaky if it then passes. Exit code is 0 when every
 * test passes (including on retry), 1 otherwise.</p>
 */
public final class JUnitRunner {

    private JUnitRunner() {
    }

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("usage: JUnitRunner <test-classes-directory>"
                + " [--retry=N] [fully.qualified.ClassName ...]");
            System.exit(2);
        }

        Path testClassesDir = Paths.get(args[0]);
        int retries = 0;
        List<String> classNames = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            if (args[i].startsWith("--retry=")) {
                try {
                    retries = Math.max(0, Integer.parseInt(args[i].substring("--retry=".length())));
                } catch (NumberFormatException ignored) {
                    retries = 0;
                }
            } else if (!args[i].isEmpty()) {
                classNames.add(args[i]);
            }
        }

        TestExecutionSummary summary = run(testClassesDir, classNames);
        Set<String> flaky = new LinkedHashSet<>();

        if (summary.getTotalFailureCount() > 0 && retries > 0) {
            List<String> remaining = new ArrayList<>(failingClasses(summary));
            for (int attempt = 0; attempt < retries && !remaining.isEmpty(); attempt++) {
                TestExecutionSummary retry = run(testClassesDir, remaining);
                if (retry.getTotalFailureCount() == 0) {
                    flaky.addAll(remaining);
                    remaining.clear();
                    summary = null;
                } else {
                    remaining = new ArrayList<>(failingClasses(retry));
                    summary = retry;
                }
            }
        }

        PrintWriter out = new PrintWriter(System.out);
        if (summary == null) {
            out.println("All failing tests passed on retry (flaky): " + String.join(", ", flaky));
            out.println("FORGE-TESTS-FLAKY " + String.join(",", flaky));
            out.flush();
            System.out.println("FORGE-TESTS found=0 passed=0 failed=0 skipped=0 flaky=" + flaky.size());
            System.exit(0);
        }

        summary.printTo(out);
        if (summary.getTotalFailureCount() > 0) {
            summary.printFailuresTo(out);
        }
        if (!flaky.isEmpty()) {
            out.println("FORGE-TESTS-FLAKY " + String.join(",", flaky));
        }
        out.flush();

        System.out.println("FORGE-TESTS found=" + summary.getTestsFoundCount()
            + " passed=" + summary.getTestsSucceededCount()
            + " failed=" + summary.getTestsFailedCount()
            + " skipped=" + summary.getTestsSkippedCount());

        boolean success = summary.getTotalFailureCount() == 0
            && summary.getTestsSucceededCount() > 0;
        System.exit(success ? 0 : 1);
    }

    /** Discovers and runs tests, restricted to {@code classNames} when non-empty. */
    private static TestExecutionSummary run(Path testClassesDir, List<String> classNames) {
        LauncherDiscoveryRequestBuilder builder = LauncherDiscoveryRequestBuilder.request();
        if (classNames.isEmpty()) {
            builder.selectors(DiscoverySelectors.selectClasspathRoots(
                java.util.Collections.singleton(testClassesDir)));
        } else {
            for (String className : classNames) {
                builder.selectors(DiscoverySelectors.selectClass(className));
            }
        }

        Launcher launcher = LauncherFactory.create();
        SummaryGeneratingListener listener = new SummaryGeneratingListener();
        launcher.registerTestExecutionListeners(listener);

        try {
            launcher.execute(builder.build());
        } catch (Exception e) {
            System.err.println("JUnit Platform execution failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
        return listener.getSummary();
    }

    /** Unique fully-qualified names of the classes that had failures. */
    private static Set<String> failingClasses(TestExecutionSummary summary) {
        Set<String> names = new LinkedHashSet<>();
        for (TestExecutionSummary.Failure failure : summary.getFailures()) {
            java.util.Optional<org.junit.platform.engine.TestSource> source =
                failure.getTestIdentifier().getSource();
            if (!source.isPresent()) {
                continue;
            }
            // A failed @Test reports a MethodSource; a failed class-level
            // condition reports a ClassSource. Both name a class to retry.
            if (source.get() instanceof ClassSource) {
                names.add(((ClassSource) source.get()).getClassName());
            } else if (source.get() instanceof MethodSource) {
                names.add(((MethodSource) source.get()).getClassName());
            }
        }
        return names;
    }
}
