package com.example;

import java.util.ArrayList;
import java.util.List;

/**
 * Simple calculator class for demonstration
 */
public class Calculator {
    
    public int add(int a, int b) {
        return a + b;
    }
    
    public int subtract(int a, int b) {
        return a - b;
    }
    
    public int multiply(int a, int b) {
        return a * b;
    }
    
    public double divide(int a, int b) {
        if (b == 0) {
            throw new IllegalArgumentException("Division by zero is not allowed");
        }
        return (double) a / b;
    }
    
    public List<Integer> getEvenNumbers(int limit) {
        List<Integer> evenNumbers = new ArrayList<>();
        for (int i = 1; i <= limit; i++) {
            if (i % 2 == 0) {
                evenNumbers.add(i);
            }
        }
        return evenNumbers;
    }
    
    public static void main(String[] args) {
        Calculator calc = new Calculator();
        System.out.println("5 + 3 = " + calc.add(5, 3));
        System.out.println("10 - 4 = " + calc.subtract(10, 4));
        System.out.println("6 * 7 = " + calc.multiply(6, 7));
        System.out.println("15 / 3 = " + calc.divide(15, 3));
        
        List<Integer> evens = calc.getEvenNumbers(10);
        System.out.println("Even numbers up to 10: " + evens);
    }
}