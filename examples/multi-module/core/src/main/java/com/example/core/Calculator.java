package com.example.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Minimal calculator used by the app module. Exists to prove inter-module
 * dependencies are resolved by coordinate, not by path. It also uses an
 * external dependency (slf4j) declared in this module's own forge.toml, so a
 * successful compile proves the module's external dependency reached its
 * compile classpath.
 */
public final class Calculator {

    private static final Logger LOG = LoggerFactory.getLogger(Calculator.class);

    private Calculator() {
        // Static helpers only.
    }

    /**
     * Returns the sum of two integers.
     */
    public static int add(int a, int b) {
        LOG.debug("add({}, {})", a, b);
        return a + b;
    }
}
