package com.example;

/**
 * Math utilities class
 */
public class MathUtils {
    
    public static final double PI = 3.14159265359;
    public static final double E = 2.71828182846;
    
    public static double square(double x) {
        return x * x;
    }
    
    public static double cube(double x) {
        return x * x * x;
    }
    
    public static boolean isPrime(int n) {
        if (n <= 1) return false;
        if (n <= 3) return true;
        if (n % 2 == 0 || n % 3 == 0) return false;
        
        for (int i = 5; i * i <= n; i += 6) {
            if (n % i == 0 || n % (i + 2) == 0) return false;
        }
        return true;
    }
    
    public static long factorial(int n) {
        if (n < 0) {
            throw new IllegalArgumentException("Factorial is not defined for negative numbers");
        }
        if (n == 0 || n == 1) return 1;
        
        long result = 1;
        for (int i = 2; i <= n; i++) {
            result *= i;
        }
        return result;
    }
    
    public static double power(double base, int exponent) {
        if (exponent == 0) return 1.0;
        if (exponent < 0) {
            return 1.0 / power(base, -exponent);
        }
        
        double result = 1.0;
        for (int i = 0; i < exponent; i++) {
            result *= base;
        }
        return result;
    }
    
    public static int gcd(int a, int b) {
        a = Math.abs(a);
        b = Math.abs(b);
        while (b != 0) {
            int temp = b;
            b = a % b;
            a = temp;
        }
        return a;
    }
    
    public static int lcm(int a, int b) {
        return Math.abs(a * b) / gcd(a, b);
    }
}