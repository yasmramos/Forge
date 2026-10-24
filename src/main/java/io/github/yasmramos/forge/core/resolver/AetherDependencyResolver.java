package io.github.yasmramos.forge.core.resolver;

import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.connector.basic.BasicRepositoryConnectorFactory;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.impl.DefaultServiceLocator;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResolutionException;
import org.eclipse.aether.resolution.DependencyResult;
import org.eclipse.aether.spi.connector.RepositoryConnectorFactory;
import org.eclipse.aether.spi.connector.transport.TransporterFactory;
import org.eclipse.aether.transport.file.FileTransporterFactory;
import org.eclipse.aether.transport.http.HttpTransporterFactory;
import org.eclipse.aether.util.graph.visitor.PreorderNodeListGenerator;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Thin wrapper around the Maven Resolver (Aether) engine.
 *
 * <p>Per AGENT.md section 2, Forge does not reimplement dependency resolution:
 * it reuses the mature Maven Resolver engine and layers its own policy on top
 * (forge.toml parsing, conflict policy, lockfile). This class owns only the
 * Aether plumbing: bootstrapping the {@link RepositorySystem}, building
 * sessions and repositories, and translating coordinates into resolved files.</p>
 *
 * <p>The strict "fail by default" conflict policy of AGENT.md section 2bis is
 * layered above this class (see {@link ConflictPolicy}); here we expose the
 * raw resolution results plus the version-conflict metadata needed to make
 * that decision.</p>
 */
public final class AetherDependencyResolver {

    /** Result of resolving a coordinate set: selected artifacts plus graph metadata. */
    public static final class ResolvedGraph {
        private final List<ResolvedArtifact> artifacts;
        private final Map<String, List<String>> requestedVersionsByGa;

        ResolvedGraph(List<ResolvedArtifact> artifacts,
                      Map<String, List<String>> requestedVersionsByGa) {
            this.artifacts = Collections.unmodifiableList(artifacts);
            this.requestedVersionsByGa = Collections.unmodifiableMap(requestedVersionsByGa);
        }

        /** All artifacts actually selected in the resolved graph, with their files. */
        public List<ResolvedArtifact> getArtifacts() {
            return artifacts;
        }

        /**
         * Every version seen for each groupId:artifactId across the traversal.
         * More than one distinct value means a version conflict exists
         * (AGENT.md section 2bis definition of a conflict).
         */
        public Map<String, List<String>> getRequestedVersionsByGa() {
            return requestedVersionsByGa;
        }

        /** Classpath entries (jar files) in deterministic preorder. */
        public List<File> getClasspath() {
            List<File> cp = new ArrayList<>(artifacts.size());
            for (ResolvedArtifact a : artifacts) {
                cp.add(a.getFile());
            }
            return cp;
        }
    }

    /** A resolved artifact with its downloaded file and scope. */
    public static final class ResolvedArtifact {
        private final String coordinates;
        private final String scope;
        private final File file;

        ResolvedArtifact(String coordinates, String scope, File file) {
            this.coordinates = coordinates;
            this.scope = scope;
            this.file = file;
        }

        public String getCoordinates() {
            return coordinates;
        }

        public String getScope() {
            return scope;
        }

        public File getFile() {
            return file;
        }

        @Override
        public String toString() {
            return coordinates + " (" + scope + ")";
        }
    }

    private final RepositorySystem repositorySystem;
    private final File localRepositoryDir;
    private final List<RemoteRepository> remoteRepositories;

    /**
     * Creates a resolver backed by the given local cache directory and remote repos.
     *
     * @param localRepositoryDir shared dependency cache (AGENT.md section 3:
     *                           ~/.forge/cache/deps, never inside the project)
     * @param repositories       name -> base URL, from [repositories] in forge.toml
     */
    public AetherDependencyResolver(File localRepositoryDir, Map<String, String> repositories) {
        this.localRepositoryDir = localRepositoryDir;
        this.repositorySystem = newRepositorySystem();
        this.remoteRepositories = buildRemotes(repositories);
    }

    private static RepositorySystem newRepositorySystem() {
        DefaultServiceLocator locator = MavenRepositorySystemUtils.newServiceLocator();
        locator.addService(RepositoryConnectorFactory.class, BasicRepositoryConnectorFactory.class);
        locator.addService(TransporterFactory.class, FileTransporterFactory.class);
        locator.addService(TransporterFactory.class, HttpTransporterFactory.class);
        locator.setErrorHandler(new DefaultServiceLocator.ErrorHandler() {
            @Override
            public void serviceCreationFailed(Class<?> type, Class<?> impl, Throwable exception) {
                throw new IllegalStateException(
                        "Failed to initialize Maven Resolver service " + type.getName(), exception);
            }
        });
        return locator.getService(RepositorySystem.class);
    }

    private static List<RemoteRepository> buildRemotes(Map<String, String> repositories) {
        List<RemoteRepository> remotes = new ArrayList<>();
        for (Map.Entry<String, String> e : repositories.entrySet()) {
            remotes.add(new RemoteRepository.Builder(e.getKey(), "default", e.getValue()).build());
        }
        if (remotes.isEmpty()) {
            // Sensible default so a bare forge.toml still works.
            remotes.add(new RemoteRepository.Builder("central", "default",
                    "https://repo1.maven.org/maven2").build());
        }
        return remotes;
    }

    private RepositorySystemSession newSession() {
        DefaultRepositorySystemSession session = MavenRepositorySystemUtils.newSession();
        session.setLocalRepositoryManager(repositorySystem.newLocalRepositoryManager(
                session, new LocalRepository(localRepositoryDir)));
        return session;
    }

    /**
     * Resolves the full transitive graph for the given direct dependencies.
     *
     * @param dependencies list of {@code group:artifact:version} coordinates
     *                     (the compile-scope root set)
     * @return resolved graph including per-GA versions seen for conflict detection
     * @throws ResolutionFailedException when any coordinate cannot be resolved or downloaded
     */
    public ResolvedGraph resolveTransitive(List<String> dependencies) {
        RepositorySystemSession session = newSession();

        CollectRequest collectRequest = new CollectRequest();
        for (String coord : dependencies) {
            collectRequest.addDependency(new Dependency(new DefaultArtifact(coord), "compile"));
        }
        collectRequest.setRepositories(remoteRepositories);

        DependencyResult result;
        try {
            result = repositorySystem.resolveDependencies(session,
                    new DependencyRequest(collectRequest, null));
        } catch (DependencyResolutionException e) {
            throw new ResolutionFailedException(
                    "Could not resolve dependencies: " + e.getMessage(), e);
        }

        PreorderNodeListGenerator nlg = new PreorderNodeListGenerator();
        result.getRoot().accept(nlg);

        List<ResolvedArtifact> artifacts = new ArrayList<>();
        Map<String, List<String>> versionsSeenByGa = new LinkedHashMap<>();
        for (DependencyNode node : nlg.getNodes()) {
            Artifact a = node.getArtifact();
            if (a == null || a.getFile() == null) {
                continue; // synthetic root node has no artifact
            }
            String ga = a.getGroupId() + ":" + a.getArtifactId();
            versionsSeenByGa.computeIfAbsent(ga, k -> new ArrayList<>()).add(a.getVersion());
            String scope = node.getDependency() != null && node.getDependency().getScope() != null
                    ? node.getDependency().getScope() : "compile";
            artifacts.add(new ResolvedArtifact(
                    a.getGroupId() + ":" + a.getArtifactId() + ":" + a.getVersion(),
                    scope, a.getFile()));
        }
        return new ResolvedGraph(artifacts, versionsSeenByGa);
    }

    /**
     * Resolves a single coordinate to a local file (no transitivity).
     * Used by plugin loading and BOM import helpers.
     */
    public File resolveArtifactFile(String coordinates) {
        RepositorySystemSession session = newSession();
        try {
            ArtifactResult r = repositorySystem.resolveArtifact(session,
                    new ArtifactRequest(new DefaultArtifact(coordinates), remoteRepositories, null));
            return r.getArtifact().getFile();
        } catch (ArtifactResolutionException e) {
            throw new ResolutionFailedException(
                    "Could not resolve artifact " + coordinates, e);
        }
    }

    /** Thrown when resolution fails; the message always names the offending coordinate(s). */
    public static final class ResolutionFailedException extends RuntimeException {
        public ResolutionFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
