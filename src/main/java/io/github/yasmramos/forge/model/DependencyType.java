package io.github.yasmramos.forge.model;

/**
 * Supported dependency types for different build systems
 */
public enum DependencyType {
    MAVEN,
    GRADLE,
    NPM,
    PIP,
    GO_MOD,
    CARGO,
    LOCAL,
    SYSTEM
}