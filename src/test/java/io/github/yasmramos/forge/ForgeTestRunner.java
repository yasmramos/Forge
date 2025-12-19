package io.github.yasmramos.forge;

import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;
import org.junit.platform.launcher.reporting.ConsoleReporter;
import org.junit.platform.launcher.reporting.EngineId;

import static org.junit.platform.engine.discovery.DiscoverySelectors.*;

/**
 * Test runner for Forge Build System
 * Provides comprehensive testing with detailed reporting
 */
public class ForgeTestRunner {
    
    public static void main(String[] args) {
        System.out.println("🧪 Forge Build System - Comprehensive Test Suite");
        System.out.println("================================================");
        
        // Create launcher
        Launcher launcher = LauncherFactory.create();
        
        // Create test discovery request
        var request = LauncherDiscoveryRequestBuilder.request()
            .selectors(
                // Unit tests
                selectPackage("io.github.yasmramos.forge.core"),
                selectPackage("io.github.yasmramos.forge.cache"),
                selectPackage("io.github.yasmramos.forge.model"),
                // Integration tests
                selectPackage("io.github.yasmramos.forge.integration"),
                // Performance tests
                selectPackage("io.github.yasmramos.forge.performance")
            )
            .filters(
                // Include only our test classes
                includeClassNamePatterns(".*Test.*")
            )
            .build();
        
        // Create listeners for reporting
        SummaryGeneratingListener summaryListener = new SummaryGeneratingListener();
        
        // Register listeners
        launcher.registerTestExecutionListeners(summaryListener);
        
        // Execute tests
        System.out.println("🔍 Discovering and executing tests...");
        launcher.execute(request);
        
        // Get summary
        TestExecutionSummary summary = summaryListener.getSummary();
        
        // Print detailed results
        printTestResults(summary);
        
        // Exit with appropriate code
        int exitCode = summary.getTestsFailedCount() > 0 ? 1 : 0;
        System.exit(exitCode);
    }
    
    private static void printTestResults(TestExecutionSummary summary) {
        System.out.println("\n📊 Test Execution Summary");
        System.out.println("==========================");
        
        // Basic statistics
        System.out.println("Tests found: " + summary.getTestsFoundCount());
        System.out.println("Tests started: " + summary.getTestsStartedCount());
        System.out.println("Tests succeeded: " + summary.getTestsSucceededCount());
        System.out.println("Tests failed: " + summary.getTestsFailedCount());
        
        // Duration
        long duration = summary.getTotalFailureDuration().toMillis();
        System.out.println("Total execution time: " + duration + "ms");
        
        if (summary.getTestsFoundCount() > 0) {
            double successRate = (double) summary.getTestsSucceededCount() / summary.getTestsFoundCount() * 100;
            System.out.println("Success rate: " + String.format("%.1f%%", successRate));
        }
        
        // Print failures if any
        if (summary.getTestsFailedCount() > 0) {
            System.out.println("\n❌ Test Failures:");
            System.out.println("=================");
            
            summary.getFailures().forEach(failure -> {
                System.out.println("Test: " + failure.getTestIdentifier().getDisplayName());
                System.out.println("Exception: " + failure.getException().getMessage());
                System.out.println("Stack trace:");
                failure.getException().printStackTrace();
                System.out.println();
            });
        }
        
        // Overall result
        System.out.println("\n" + (summary.getTestsFailedCount() == 0 ? "✅" : "❌") + " " + 
                         (summary.getTestsFailedCount() == 0 ? "ALL TESTS PASSED" : "SOME TESTS FAILED"));
        
        // Performance summary
        printPerformanceSummary(summary);
    }
    
    private static void printPerformanceSummary(TestExecutionSummary summary) {
        System.out.println("\n🚀 Performance Summary");
        System.out.println("======================");
        
        long avgTimePerTest = summary.getTestsStartedCount() > 0 ? 
            summary.getTotalFailureDuration().toMillis() / summary.getTestsStartedCount() : 0;
        
        System.out.println("Average time per test: " + avgTimePerTest + "ms");
        
        if (summary.getTestsFoundCount() > 0) {
            double testsPerSecond = (double) summary.getTestsStartedCount() / 
                (summary.getTotalFailureDuration().toMillis() / 1000.0);
            System.out.println("Tests per second: " + String.format("%.1f", testsPerSecond));
        }
    }
}