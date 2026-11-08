package com.example.apt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies that Lombok-generated accessors are present at test time, i.e. that
 * annotation processing actually ran during compilation (AGENT.md section 23).
 */
class PersonTest {

    @Test
    void lombokGettersAreAvailable() {
        Person person = new Person("Grace", 45);
        assertEquals("Grace", person.getName());
        assertEquals(45, person.getAge());
    }
}
