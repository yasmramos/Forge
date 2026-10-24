package com.example.core;

/**
 * Minimal calculator used by the app module. Exists to prove inter-module
 * dependencies are resolved by coordinate, not by path.
 */
public final class Calculator {

    private Calculator() {
        // Static helpers only.
    }

    /**
     * Returns the sum of two integers.
     */
    public static int add(int a, int b) {
        return a + b;
    }
}
