package io.github.yasmramos.forge.core.publish;

import io.github.yasmramos.forge.cli.error.ErrorCode;
import io.github.yasmramos.forge.cli.error.ForgeException;

import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.toml.TomlParser;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Repository credentials for publishing, read from
 * {@code ~/.forge/credentials.toml} (AGENT.md section 6).
 *
 * <p>Credentials are deliberately kept out of {@code forge.toml}, which is
 * committed and shared. The file maps a repository key (the same key used in
 * {@code [repositories]}) to a username/password pair:</p>
 *
 * <pre>
 * [central]
 * username = "deployer"
 * password = "s3cr3t"
 * </pre>
 *
 * <p>The file lives outside the project, so it is never accidentally committed.</p>
 */
public final class Credentials {

    /** The default credentials location: {@code ~/.forge/credentials.toml}. */
    public static final String FILE_NAME = "credentials.toml";

    /** One repository's credentials. */
    public static final class Entry {
        private final String username;
        private final String password;

        Entry(String username, String password) {
            this.username = username;
            this.password = password;
        }

        public String getUsername() { return username; }
        public String getPassword() { return password; }
    }

    private final Map<String, Entry> entries;

    private Credentials(Map<String, Entry> entries) {
        this.entries = Collections.unmodifiableMap(entries);
    }

    /** Credentials for {@code repositoryKey}, or {@code null} when absent. */
    public Entry forRepository(String repositoryKey) {
        return repositoryKey == null ? null : entries.get(repositoryKey);
    }

    /** True when no repository has credentials defined. */
    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /** Loads the default credentials file, or an empty set when it is absent. */
    public static Credentials loadDefault() {
        return load(defaultFile());
    }

    /** The default credentials file path, honoring {@code FORGE_HOME} if set. */
    public static File defaultFile() {
        String forgeHome = System.getenv("FORGE_HOME");
        File home = (forgeHome != null && !forgeHome.trim().isEmpty())
            ? new File(forgeHome.trim())
            : new File(System.getProperty("user.home"), ".forge");
        return new File(home, FILE_NAME);
    }

    /**
     * Loads credentials from {@code file}. A missing file yields an empty set:
     * a build that never publishes should not require the file to exist.
     */
    public static Credentials load(File file) {
        if (file == null || !file.isFile()) {
            return new Credentials(new LinkedHashMap<String, Entry>());
        }
        try (InputStream in = Files.newInputStream(file.toPath())) {
            Config root = new TomlParser().parse(in);
            Map<String, Entry> entries = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : root.valueMap().entrySet()) {
                if (e.getValue() instanceof UnmodifiableConfig) {
                    UnmodifiableConfig table = (UnmodifiableConfig) e.getValue();
                    Object user = table.get("username");
                    Object pass = table.get("password");
                    if (user != null && pass != null) {
                        entries.put(e.getKey(), new Entry(String.valueOf(user), String.valueOf(pass)));
                    }
                }
            }
            return new Credentials(entries);
        } catch (Exception e) {
            throw new ForgeException(ErrorCode.PUB_0007,
                "could not read credentials from " + file.getAbsolutePath() + ": " + e.getMessage(), e);
        }
    }
}
