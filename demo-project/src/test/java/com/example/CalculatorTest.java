package com.example;

/**
 * Test class for Calculator
 */
public class CalculatorTest {
    
    public static void main(String[] args) {
        Calculator calc = new Calculator();
        boolean allTestsPassed = true;
        
        // Test addition
        if (calc.add(2, 3) != 5) {
            System.out.println("❌ Addition test failed");
            allTestsPassed = false;
        } else {
            System.out.println("✅ Addition test passed");
        }
        
        // Test subtraction
        if (calc.subtract(10, 4) != 6) {
            System.out.println("❌ Subtraction test failed");
            allTestsPassed = false;
        } else {
            System.out.println("✅ Subtraction test passed");
        }
        
        // Test multiplication
        if (calc.multiply(3, 4) != 12) {
            System.out.println("❌ Multiplication test failed");
            allTestsPassed = false;
        } else {
            System.out.println("✅ Multiplication test passed");
        }
        
        // Test division
        if (calc.divide(15, 3) != 5.0) {
            System.out.println("❌ Division test failed");
            allTestsPassed = false;
        } else {
            System.out.println("✅ Division test passed");
        }
        
        // Test division by zero
        try {
            calc.divide(10, 0);
            System.out.println("❌ Division by zero test failed - should have thrown exception");
            allTestsPassed = false;
        } catch (IllegalArgumentException e) {
            System.out.println("✅ Division by zero test passed - exception thrown correctly");
        }
        
        if (allTestsPassed) {
            System.out.println("🎉 All tests passed!");
        } else {
            System.out.println("💥 Some tests failed!");
        }
    }
}