package io.github.yasmramos.forge.core.test;

import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.nio.file.Paths;

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
 * io.github.yasmramos.forge.core.test.JUnitRunner <test-classes-dir>}
 * Exit code is 0 when every discovered test passes, 1 otherwise.</p>
 */
public final class JUnitRunner {

    private JUnitRunner() {
    }

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("usage: JUnitRunner <test-classes-directory>");
            System.exit(2);
        }

        Path testClassesDir = Paths.get(args[0]);

        LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
            .selectors(DiscoverySelectors.selectClasspathRoots(
                java.util.Collections.singleton(testClassesDir)))
            .build();

        Launcher launcher = LauncherFactory.create();
        SummaryGeneratingListener listener = new SummaryGeneratingListener();
        launcher.registerTestExecutionListeners(listener);

        try {
            launcher.execute(request);
        } catch (Exception e) {
            System.err.println("JUnit Platform execution failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }

        TestExecutionSummary summary = listener.getSummary();
        PrintWriter out = new PrintWriter(System.out);
        summary.printTo(out);
        if (summary.getTotalFailureCount() > 0) {
            summary.printFailuresTo(out);
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
}
