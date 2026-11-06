package com.example.lib;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point of the library example.
 *
 * <p>Demonstrates that a Forge project resolves a real Maven Central
 * dependency ({@code org.slf4j:slf4j-api}) and puts it on the compile
 * classpath: this class uses the logger API, so the build only succeeds
 * when resolution worked.</p>
 */
public final class Main {

    private static final Logger LOG = LoggerFactory.getLogger(Main.class);

    private Main() {
        // Utility entry point; not meant to be instantiated.
    }

    public static void main(String[] args) {
        LOG.info("Library example running: 2 + 3 = {}", Calculator.add(2, 3));
    }
}
