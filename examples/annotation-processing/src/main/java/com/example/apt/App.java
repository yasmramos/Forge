package com.example.apt;

/**
 * Entry point of the annotation-processing example.
 */
public final class App {

    private App() {
        // Utility entry point; not meant to be instantiated.
    }

    public static void main(String[] args) {
        Person person = new Person("Ada", 36);
        System.out.println("Hello " + person.getName() + " (" + person.getAge() + ")");
    }
}
