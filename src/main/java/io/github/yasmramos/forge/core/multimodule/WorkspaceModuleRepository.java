package io.github.yasmramos.forge.core.multimodule;

import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.repository.WorkspaceReader;
import org.eclipse.aether.repository.WorkspaceRepository;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Local workspace holding the artifacts of the modules built in a multi-module
 * project (AGENT.md section 5).
 *
 * <p>Modules consume each other by Maven coordinate, never by path. Aether's
 * {@link WorkspaceReader} is the correct hook for that: it is consulted for a
 * module's POM <em>and</em> jar <em>before</em> the shared local cache, so a
 * freshly rebuilt module always wins over a stale copy. Pointing resolution at a
 * plain {@code file://} remote would not have that property — the cached
 * artifact from a previous build would be served instead.</p>
 *
 * <p>The workspace is a flat directory of {@code <artifactId>-<version>.<ext>}
 * files under {@code <root>/.forge/workspace}. Because it persists on disk, any
 * later command in the project ({@code forge run}, {@code forge tree},
 * {@code forge why}) sees the same module artifacts without rebuilding.</p>
 */
public final class WorkspaceModuleRepository implements WorkspaceReader {

    private static final WorkspaceRepository REPOSITORY =
        new WorkspaceRepository("forge-workspace");

    private final File directory;
    /**
     * {@code groupId:artifactId} of every module in this build. The workspace
     * only answers for these coordinates: without this guard, an external
     * dependency that happens to share an artifactId and version with a module
     * would be silently resolved to the module's jar.
     */
    private final java.util.Set<String> moduleCoordinates;

    public WorkspaceModuleRepository(File directory) {
        this(directory, java.util.Collections.emptySet());
    }

    public WorkspaceModuleRepository(File directory, java.util.Set<String> moduleCoordinates) {
        this.directory = directory;
        this.moduleCoordinates = moduleCoordinates != null
            ? moduleCoordinates : java.util.Collections.<String>emptySet();
    }

    /** Directory holding the installed module artifacts. */
    public File getDirectory() {
        return directory;
    }

    /**
     * Installs a built module's jar and POM into the workspace so downstream
     * modules (and later commands) resolve it by coordinate.
     */
    public void install(String artifactId, String version, File jar, File pom)
            throws IOException {
        Files.createDirectories(directory.toPath());
        Files.copy(jar.toPath(),
            new File(directory, artifactId + "-" + version + ".jar").toPath(),
            StandardCopyOption.REPLACE_EXISTING);
        Files.copy(pom.toPath(),
            new File(directory, artifactId + "-" + version + ".pom").toPath(),
            StandardCopyOption.REPLACE_EXISTING);
    }

    /** True when the module directory holds any installed artifact. */
    public boolean isEmpty() {
        File[] files = directory.listFiles();
        return files == null || files.length == 0;
    }

    @Override
    public WorkspaceRepository getRepository() {
        return REPOSITORY;
    }

    @Override
    public File findArtifact(Artifact artifact) {
        if (!isModuleArtifact(artifact)) {
            return null;
        }
        if (artifact.getVersion() == null || artifact.getVersion().isEmpty()) {
            return null;
        }
        File candidate = new File(directory,
            artifact.getArtifactId() + "-" + artifact.getVersion() + "." + artifact.getExtension());
        return candidate.isFile() ? candidate : null;
    }

    @Override
    public List<String> findVersions(Artifact artifact) {
        if (!moduleCoordinates.isEmpty()
                && !moduleCoordinates.contains(artifact.getGroupId() + ":" + artifact.getArtifactId())) {
            return Collections.emptyList();
        }
        List<String> versions = new ArrayList<>();
        String prefix = artifact.getArtifactId() + "-";
        File[] files = directory.listFiles();
        if (files == null) {
            return Collections.emptyList();
        }
        for (File f : files) {
            String name = f.getName();
            if (name.startsWith(prefix) && name.endsWith(".jar")) {
                versions.add(name.substring(prefix.length(), name.length() - ".jar".length()));
            }
        }
        return versions;
    }

    /**
     * Only answers for artifacts that belong to a module of this build (or, when
     * no coordinate set was supplied, any artifact placed in the workspace).
     * Classifier-bearing artifacts are never served from here.
     */
    private boolean isModuleArtifact(Artifact artifact) {
        if (artifact.getClassifier() != null && !artifact.getClassifier().isEmpty()) {
            return false;
        }
        if (moduleCoordinates.isEmpty()) {
            return true;
        }
        return moduleCoordinates.contains(artifact.getGroupId() + ":" + artifact.getArtifactId());
    }
}
