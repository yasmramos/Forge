package com.example.lib;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Entry point of the library example.
 *
 * <p>Demonstrates that a Forge project resolves a real Maven Central
 * dependency ({@code org.slf4j:slf4j-api}) and puts it on the compile
 * classpath: this class uses the logger API, so the build only succeeds
 * when resolution worked.</p>
 *
 * <p>It also reads {@code library.properties} from the classpath, proving
 * that resources under {@code src/main/resources} are packaged into the jar
 * (AGENT.md section 22).</p>
 */
public final class Main {

    private static final Logger LOG = LoggerFactory.getLogger(Main.class);

    private Main() {
        // Utility entry point; not meant to be instantiated.
    }

    public static void main(String[] args) {
        LOG.info("Library example running: 2 + 3 = {}", Calculator.add(2, 3));
        LOG.info("Bundled resource greeting: {}", bundledGreeting());
    }

    /** Reads a resource from the classpath; only works if resources are packaged. */
    static String bundledGreeting() {
        Properties props = new Properties();
        try (InputStream in = Main.class.getResourceAsStream("/library.properties")) {
            if (in == null) {
                throw new IllegalStateException("library.properties missing from the classpath");
            }
            props.load(in);
        } catch (IOException e) {
            throw new IllegalStateException("library.properties could not be read", e);
        }
        return props.getProperty("greeting");
    }
}
