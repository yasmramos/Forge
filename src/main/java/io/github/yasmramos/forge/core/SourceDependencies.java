package io.github.yasmramos.forge.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Source-level dependency graph and Merkle hashes used to cascade cache
 * invalidation.
 *
 * <p>A file's compiled output can depend on another file even when the first is
 * unchanged: javac inlines {@code static final} constants, and API changes alter
 * the calling class's bytecode. Keying a cache entry only on the file's own
 * content therefore serves a stale output after a dependency changes. This class
 * computes, for every source file, a hash that folds in its transitive source
 * dependencies, so changing {@code A} also invalidates {@code B} that uses it
 * (AGENT.md sections 3 and 17).</p>
 *
 * <p>Dependencies are approximated from the source text: explicit imports,
 * wildcard imports expanded over the project's own packages, and simple type
 * names matching a type declared elsewhere in the module. The approximation
 * deliberately over-approximates (a mention in a comment or string counts), so a
 * stale entry is never served at the cost of an occasional extra recompile.</p>
 */
final class SourceDependencies {

    private static final Pattern PACKAGE =
        Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);
    private static final Pattern IMPORT =
        Pattern.compile("^\\s*import\\s+(static\\s+)?([\\w.]+?)(\\.\\*)?\\s*;", Pattern.MULTILINE);
    private static final Pattern IDENTIFIER =
        Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");

    private SourceDependencies() {
    }

    /**
     * Hash per source file that changes when the file or any file it transitively
     * depends on changes. Cycles (mutually referencing classes) are collapsed so
     * every member of a cycle shares one hash.
     *
     * @param sourceFiles all sources of the module
     * @return file to hash; files with no dependency still get their content hash
     */
    static Map<Path, String> effectiveHashes(List<Path> sourceFiles) {
        Map<Path, String> fqnByFile = new LinkedHashMap<>();
        Map<Path, String> packageByFile = new LinkedHashMap<>();
        Map<Path, String> contentHash = new LinkedHashMap<>();
        Map<String, Path> byFqn = new HashMap<>();
        Map<String, List<Path>> bySimple = new HashMap<>();
        Map<String, List<Path>> byPackage = new HashMap<>();

        for (Path file : sourceFiles) {
            String simple = baseName(file);
            String pkg = packageName(file);
            String fqn = pkg.isEmpty() ? simple : pkg + "." + simple;
            fqnByFile.put(file, fqn);
            packageByFile.put(file, pkg);
            contentHash.put(file, sha256(fqn + "\u0000", readBytes(file)));
            byFqn.putIfAbsent(fqn, file);
            bySimple.computeIfAbsent(simple, k -> new ArrayList<>()).add(file);
            byPackage.computeIfAbsent(pkg, k -> new ArrayList<>()).add(file);
        }

        Map<Path, Set<Path>> deps = new LinkedHashMap<>();
        for (Path file : sourceFiles) {
            deps.put(file, directDependencies(file, byFqn, bySimple, byPackage));
        }

        int[] counter = {0};
        Map<Path, Integer> index = new HashMap<>();
        Map<Path, Integer> low = new HashMap<>();
        Deque<Path> stack = new ArrayDeque<>();
        Set<Path> onStack = new HashSet<>();
        List<List<Path>> sccs = new ArrayList<>();
        Map<Path, Integer> sccOf = new HashMap<>();
        for (Path file : sourceFiles) {
            if (!index.containsKey(file)) {
                tarjan(file, deps, index, low, stack, onStack, sccs, sccOf, counter);
            }
        }

        List<Set<Integer>> sccDeps = new ArrayList<>();
        for (int i = 0; i < sccs.size(); i++) {
            sccDeps.add(new HashSet<>());
        }
        for (Path file : sourceFiles) {
            int from = sccOf.get(file);
            for (Path target : deps.get(file)) {
                int to = sccOf.get(target);
                if (to != from) {
                    sccDeps.get(from).add(to);
                }
            }
        }

        String[] sccHash = new String[sccs.size()];
        boolean[] resolved = new boolean[sccs.size()];
        for (int i = 0; i < sccs.size(); i++) {
            resolveSccHash(i, sccs, sccDeps, contentHash, sccHash, resolved);
        }

        Map<Path, String> result = new LinkedHashMap<>();
        for (Path file : sourceFiles) {
            result.put(file, sccHash[sccOf.get(file)]);
        }
        return result;
    }

    private static Set<Path> directDependencies(Path file, Map<String, Path> byFqn,
                                                Map<String, List<Path>> bySimple,
                                                Map<String, List<Path>> byPackage) {
        Set<Path> deps = new HashSet<>();
        String text = readText(file);

        for (Matcher m = IMPORT.matcher(text); m.find(); ) {
            boolean isStatic = m.group(1) != null;
            String base = m.group(2);
            boolean wildcard = m.group(3) != null;
            if (wildcard) {
                if (isStatic) {
                    addIfPresent(deps, byFqn.get(base));
                } else {
                    deps.addAll(byPackage.getOrDefault(base, Collections.emptyList()));
                }
            } else {
                addIfPresent(deps, byFqn.get(isStatic ? stripLastSegment(base) : base));
            }
        }

        // Simple names that match a type declared in this module: covers same
        // package references and imported types alike.
        for (Matcher m = IDENTIFIER.matcher(text); m.find(); ) {
            List<Path> candidates = bySimple.get(m.group());
            if (candidates != null) {
                deps.addAll(candidates);
            }
        }

        deps.remove(file);
        return deps;
    }

    private static void tarjan(Path v, Map<Path, Set<Path>> deps, Map<Path, Integer> index,
                               Map<Path, Integer> low, Deque<Path> stack, Set<Path> onStack,
                               List<List<Path>> sccs, Map<Path, Integer> sccOf, int[] counter) {
        index.put(v, counter[0]);
        low.put(v, counter[0]);
        counter[0]++;
        stack.push(v);
        onStack.add(v);

        for (Path w : deps.get(v)) {
            if (!index.containsKey(w)) {
                tarjan(w, deps, index, low, stack, onStack, sccs, sccOf, counter);
                low.put(v, Math.min(low.get(v), low.get(w)));
            } else if (onStack.contains(w)) {
                low.put(v, Math.min(low.get(v), index.get(w)));
            }
        }

        if (low.get(v).equals(index.get(v))) {
            List<Path> component = new ArrayList<>();
            Path w;
            do {
                w = stack.pop();
                onStack.remove(w);
                component.add(w);
            } while (!w.equals(v));
            int id = sccs.size();
            for (Path member : component) {
                sccOf.put(member, id);
            }
            sccs.add(component);
        }
    }

    private static String resolveSccHash(int i, List<List<Path>> sccs, List<Set<Integer>> sccDeps,
                                         Map<Path, String> contentHash, String[] sccHash,
                                         boolean[] resolved) {
        if (resolved[i]) {
            return sccHash[i];
        }
        TreeSet<String> own = new TreeSet<>();
        for (Path member : sccs.get(i)) {
            own.add(contentHash.get(member));
        }
        TreeSet<String> inherited = new TreeSet<>();
        for (int dep : sccDeps.get(i)) {
            inherited.add(resolveSccHash(dep, sccs, sccDeps, contentHash, sccHash, resolved));
        }
        StringBuilder sb = new StringBuilder();
        for (String h : own) {
            sb.append(h).append('\n');
        }
        sb.append("--\n");
        for (String h : inherited) {
            sb.append(h).append('\n');
        }
        sccHash[i] = sha256(sb.toString());
        resolved[i] = true;
        return sccHash[i];
    }

    private static void addIfPresent(Set<Path> deps, Path candidate) {
        if (candidate != null) {
            deps.add(candidate);
        }
    }

    private static String stripLastSegment(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? fqn : fqn.substring(0, dot);
    }

    private static String baseName(Path file) {
        String name = file.getFileName().toString();
        return name.endsWith(".java") ? name.substring(0, name.length() - ".java".length()) : name;
    }

    private static String packageName(Path file) {
        Matcher m = PACKAGE.matcher(readText(file));
        return m.find() ? m.group(1) : "";
    }

    private static byte[] readBytes(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            return new byte[0];
        }
    }

    private static String readText(Path file) {
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static String sha256(String prefix, byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(prefix.getBytes(StandardCharsets.UTF_8));
            md.update(data);
            return toHex(md.digest());
        } catch (Exception e) {
            return Integer.toHexString((prefix + data.length).hashCode());
        }
    }

    private static String sha256(String text) {
        return sha256(text, new byte[0]);
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
