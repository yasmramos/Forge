package io.github.yasmramos.forge.core.publish;

import io.github.yasmramos.forge.cli.error.ErrorCode;
import io.github.yasmramos.forge.cli.error.ForgeException;
import io.github.yasmramos.forge.core.sbom.SbomGenerator;
import io.github.yasmramos.forge.model.ProjectConfig;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Assembles everything {@code forge publish} sends to a repository (AGENT.md
 * section 6): the POM, Gradle Module Metadata, the main/sources/javadoc JARs and,
 * when enabled, the CycloneDX SBOM (section 18).
 *
 * <p>This class only produces a staging directory; the upload itself is a
 * separate concern. Keeping the two apart means the artifact set can be
 * validated and tested without a network, and the same staging output feeds
 * both a real upload and a dry run.</p>
 *
 * <p>Validation happens before anything is written, so a project missing the
 * fields Maven Central requires fails at configure time with an actionable
 * {@code FORGE-PUB-0001} instead of a rejection from the repository.</p>
 */
public final class PublishStaging {

    /** Outcome of staging: the directory plus the files it contains, in order. */
    public static final class Result {
        private final File directory;
        private final List<File> files;
        private final List<String> warnings;

        Result(File directory, List<File> files, List<String> warnings) {
            this.directory = directory;
            this.files = files;
            this.warnings = warnings;
        }

        public File getDirectory() { return directory; }
        public List<File> getFiles() { return files; }
        public List<String> getWarnings() { return warnings; }
    }

    private PublishStaging() {
    }

    /**
     * Stages the publishable artifacts.
     *
     * @param config     project configuration (coordinates + [publish] metadata)
     * @param buildDir   the build base directory (holds {@code forge-output/})
     * @param stagingDir directory to write the staged artifacts into
     * @param components resolved dependencies for the SBOM; may be empty
     * @param timestamp  fixed timestamp for reproducible documents (section 17)
     */
    public static Result stage(ProjectConfig config, File buildDir, File stagingDir,
                               List<SbomGenerator.Component> components, Instant timestamp) {
        validate(config);

        String artifactId = config.getName() + "-" + config.getVersion();
        List<File> files = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        try {
            Files.createDirectories(stagingDir.toPath());

            File pom = new File(stagingDir, artifactId + ".pom");
            write(pom, PomGenerator.generate(config));
            files.add(pom);

            File module = new File(stagingDir, artifactId + ".module");
            write(module, GradleModuleMetadataGenerator.generate(config));
            files.add(module);

            File outputDir = new File(buildDir, "forge-output");
            addRequired(files, new File(outputDir, "forge-main.jar"),
                new File(stagingDir, artifactId + ".jar"));
            addOptional(files, warnings, new File(outputDir, "forge-sources.jar"),
                new File(stagingDir, artifactId + "-sources.jar"));
            addOptional(files, warnings, new File(outputDir, "forge-javadoc.jar"),
                new File(stagingDir, artifactId + "-javadoc.jar"));

            if (config.getPublishSettings().isSbom()) {
                File sbom = new File(stagingDir, artifactId + "-cyclonedx.json");
                write(sbom, SbomGenerator.generate(config.getName(), config.getVersion(),
                    components, timestamp));
                files.add(sbom);
            }
        } catch (ForgeException fe) {
            throw fe;
        } catch (Exception e) {
            throw new ForgeException(ErrorCode.PUB_0003,
                "could not stage publish artifacts: " + e.getMessage(), e);
        }

        return new Result(stagingDir, files, warnings);
    }

    /**
     * Checks the metadata Maven Central requires but cannot infer. Fails with
     * the exact missing keys so the fix is obvious (AGENT.md section 10).
     */
    static void validate(ProjectConfig config) {
        Map<String, String> missing = new LinkedHashMap<>();
        if (isBlank(config.getGroup())) {
            missing.put("project.group", "[project] group");
        }
        if (isBlank(config.getName())) {
            missing.put("project.name", "[project] name");
        }
        if (isBlank(config.getVersion())) {
            missing.put("project.version", "[project] version");
        }
        if (!missing.isEmpty()) {
            throw new ForgeException(ErrorCode.PUB_0002,
                "missing project coordinates: " + String.join(", ", missing.values()));
        }

        ProjectConfig.PublishSettings pub = config.getPublishSettings();
        List<String> required = new ArrayList<>();
        if (isBlank(pub.getLicenseName())) {
            required.add("publish.license");
        }
        if (isBlank(pub.getDeveloperNames()) && isBlank(pub.getDeveloperIds())) {
            required.add("publish.developers");
        }
        if (isBlank(pub.getScmUrl())) {
            required.add("publish.scm");
        }
        if (isBlank(pub.getUrl())) {
            required.add("publish.url");
        }
        if (!required.isEmpty()) {
            throw new ForgeException(ErrorCode.PUB_0001,
                "Maven Central requires metadata that is missing from [publish]: "
                    + String.join(", ", required));
        }
    }

    private static void addRequired(List<File> files, File source, File target) {
        if (!source.isFile()) {
            throw new ForgeException(ErrorCode.PUB_0004,
                "main artifact not found at " + source.getAbsolutePath()
                    + "; run 'forge build' before publishing");
        }
        copy(source, target);
        files.add(target);
    }

    private static void addOptional(List<File> files, List<String> warnings,
                                    File source, File target) {
        if (!source.isFile()) {
            warnings.add("skipped " + target.getName() + ": " + source.getName()
                + " not built (Maven Central recommends sources and javadoc jars)");
            return;
        }
        copy(source, target);
        files.add(target);
    }

    private static void write(File file, String content) {
        try {
            Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new ForgeException(ErrorCode.PUB_0003,
                "could not write " + file.getAbsolutePath() + ": " + e.getMessage(), e);
        }
    }

    private static void copy(File source, File target) {
        try {
            Files.copy(source.toPath(), target.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            throw new ForgeException(ErrorCode.PUB_0003,
                "could not copy " + source.getName() + ": " + e.getMessage(), e);
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
