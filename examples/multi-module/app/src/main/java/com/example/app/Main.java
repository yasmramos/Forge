package com.example.app;

import com.example.core.Calculator;

/**
 * Entry point of the app module. Consumes {@code example.core} through its
 * Maven coordinate, proving inter-module dependencies are explicit.
 */
public final class Main {

    private Main() {
        // Entry point only.
    }

    public static void main(String[] args) {
        int result = Calculator.add(2, 3);
        System.out.println("2 + 3 = " + result);
    }
}
