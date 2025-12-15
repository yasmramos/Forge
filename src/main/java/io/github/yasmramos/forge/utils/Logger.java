package io.github.yasmramos.forge.utils;

import org.slf4j.LoggerFactory;

/**
 * Forge Logger utility - a simple wrapper around SLF4J
 */
public class Logger {
    private final org.slf4j.Logger slf4jLogger;
    
    private Logger(Class<?> clazz) {
        this.slf4jLogger = LoggerFactory.getLogger(clazz);
    }
    
    public static Logger getLogger(Class<?> clazz) {
        return new Logger(clazz);
    }
    
    public void info(String message) {
        slf4jLogger.info(message);
    }
    
    public void debug(String message) {
        slf4jLogger.debug(message);
    }
    
    public void debug(String message, Throwable throwable) {
        slf4jLogger.debug(message, throwable);
    }
    
    public void warn(String message) {
        slf4jLogger.warn(message);
    }
    
    public void warn(String message, Throwable throwable) {
        slf4jLogger.warn(message, throwable);
    }
    
    public void error(String message) {
        slf4jLogger.error(message);
    }
    
    public void error(String message, Throwable throwable) {
        slf4jLogger.error(message, throwable);
    }
}