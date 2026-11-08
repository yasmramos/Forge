package com.example.lib;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Test suite for the library example.
 *
 * <p>Exercises the test-scope classpath: JUnit 5 is declared under
 * {@code [dependencies.test]} and must be resolved and made available only
 * to the test compilation/execution, not to the main sources.</p>
 */
class CalculatorTest {

    @Test
    void addsTwoNumbers() {
        assertEquals(5, Calculator.add(2, 3));
    }

    @Test
    void multipliesTwoNumbers() {
        assertEquals(6, Calculator.multiply(2, 3));
    }

    @Test
    void handlesNegativeOperands() {
        assertEquals(-1, Calculator.add(2, -3));
    }

    @Test
    void resourceIsOnTheClasspath() {
        assertEquals("hello", Main.bundledGreeting());
    }
}
