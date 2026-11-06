package com.example.lib;

/**
 * Trivial arithmetic used to exercise the example's test suite.
 */
public final class Calculator {

    private Calculator() {
        // Static helpers only.
    }

    /** Returns the sum of two integers. */
    public static int add(int a, int b) {
        return a + b;
    }

    /** Returns the product of two integers. */
    public static int multiply(int a, int b) {
        return a * b;
    }
}
