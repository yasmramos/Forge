package io.github.yasmramos.forge.core.ide;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders a {@link ProjectModel} into the files IntelliJ, Eclipse and VS Code
 * read (AGENT.md section 13, phase 1: "export the configuration to the IDE").
 *
 * <p>Every method is pure - it returns file contents and never touches disk -
 * so the writers are unit-testable without a filesystem, and the command that
 * persists them stays a thin shell.</p>
 */
public final class IdeGenerators {

    private IdeGenerators() {
    }

    /** A generated file: project-relative path plus its text content. */
    public static final class GeneratedFile {
        private final String path;
        private final String content;

        public GeneratedFile(String path, String content) {
            this.path = path;
            this.content = content;
        }

        public String getPath() {
            return path;
        }

        public String getContent() {
            return content;
        }
    }

    // --- IntelliJ IDEA -----------------------------------------------------

    /** IntelliJ project files: {@code .idea/*} plus the module {@code .iml}. */
    public static List<GeneratedFile> intellij(ProjectModel model) {
        List<GeneratedFile> files = new ArrayList<>();
        files.add(new GeneratedFile(".idea/modules.xml", modulesXml(model)));
        files.add(new GeneratedFile(".idea/misc.xml", miscXml(model)));
        files.add(new GeneratedFile(".idea/compiler.xml", compilerXml()));
        files.add(new GeneratedFile(model.getName() + ".iml", moduleIml(model)));
        return files;
    }

    private static String modulesXml(ProjectModel model) {
        String name = escapeXml(model.getName());
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<project version=\"4\">\n"
            + "  <component name=\"ProjectModuleManager\">\n"
            + "    <modules>\n"
            + "      <module fileurl=\"file://$PROJECT_DIR$/" + name + ".iml\""
            + " filepath=\"$PROJECT_DIR$/" + name + ".iml\" />\n"
            + "    </modules>\n"
            + "  </component>\n"
            + "</project>\n";
    }

    private static String miscXml(ProjectModel model) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<project version=\"4\">\n"
            + "  <component name=\"ProjectRootManager\" version=\"2\""
            + " languageLevel=\"JDK_" + escapeXml(model.getLanguageLevel()) + "\""
            + " default=\"true\" project-jdk-name=\""
            + escapeXml(model.getLanguageLevel()) + "\""
            + " project-jdk-type=\"JavaSDK\">\n"
            + "    <output url=\"file://$PROJECT_DIR$/" + escapeXml(model.getClassesDir()) + "\" />\n"
            + "  </component>\n"
            + "</project>\n";
    }

    private static String compilerXml() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<project version=\"4\">\n"
            + "  <component name=\"CompilerConfiguration\">\n"
            + "    <bytecodeTargetLevel target=\"1.8\" />\n"
            + "  </component>\n"
            + "</project>\n";
    }

    private static String moduleIml(ProjectModel model) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<module type=\"JAVA_MODULE\" version=\"4\">\n");
        sb.append("  <component name=\"NewModuleRootManager\" inherit-compiler-output=\"false\">\n");
        sb.append("    <output url=\"file://$MODULE_DIR$/")
            .append(escapeXml(model.getClassesDir())).append("\" />\n");
        sb.append("    <output-test url=\"file://$MODULE_DIR$/")
            .append(escapeXml(model.getTestClassesDir())).append("\" />\n");
        sb.append("    <exclude-output />\n");
        for (String root : model.getMainSourceRoots()) {
            sb.append("    <content url=\"file://$MODULE_DIR$\">\n");
            sb.append("      <sourceFolder url=\"file://$MODULE_DIR$/")
                .append(escapeXml(root)).append("\" isTestSource=\"false\" />\n");
            sb.append("    </content>\n");
        }
        for (String root : model.getTestSourceRoots()) {
            sb.append("    <content url=\"file://$MODULE_DIR$\">\n");
            sb.append("      <sourceFolder url=\"file://$MODULE_DIR$/")
                .append(escapeXml(root)).append("\" isTestSource=\"true\" />\n");
            sb.append("    </content>\n");
        }
        for (String root : model.getMainResourceRoots()) {
            sb.append("    <content url=\"file://$MODULE_DIR$\">\n");
            sb.append("      <sourceFolder url=\"file://$MODULE_DIR$/")
                .append(escapeXml(root)).append("\" type=\"java-resource\" />\n");
            sb.append("    </content>\n");
        }
        for (String root : model.getTestResourceRoots()) {
            sb.append("    <content url=\"file://$MODULE_DIR$\">\n");
            sb.append("      <sourceFolder url=\"file://$MODULE_DIR$/")
                .append(escapeXml(root)).append("\" type=\"java-test-resource\" />\n");
            sb.append("    </content>\n");
        }
        sb.append("    <orderEntry type=\"inheritedJdk\" />\n");
        sb.append("    <orderEntry type=\"sourceFolder\" forTests=\"false\" />\n");
        for (String lib : model.getCompileLibraries()) {
            sb.append("    <orderEntry type=\"module-library\">\n");
            sb.append("      <library>\n");
            sb.append("        <CLASSES>\n");
            sb.append("          <root url=\"jar://").append(escapeXml(lib)).append("!/\" />\n");
            sb.append("        </CLASSES>\n");
            sb.append("        <JAVADOC />\n");
            sb.append("        <SOURCES />\n");
            sb.append("      </library>\n");
            sb.append("    </orderEntry>\n");
        }
        for (String lib : model.getTestLibraries()) {
            sb.append("    <orderEntry type=\"module-library\" scope=\"TEST\">\n");
            sb.append("      <library>\n");
            sb.append("        <CLASSES>\n");
            sb.append("          <root url=\"jar://").append(escapeXml(lib)).append("!/\" />\n");
            sb.append("        </CLASSES>\n");
            sb.append("        <JAVADOC />\n");
            sb.append("        <SOURCES />\n");
            sb.append("      </library>\n");
            sb.append("    </orderEntry>\n");
        }
        sb.append("  </component>\n");
        sb.append("</module>\n");
        return sb.toString();
    }

    // --- Eclipse -----------------------------------------------------------

    /** Eclipse project files: {@code .project}, {@code .classpath} and JDT prefs. */
    public static List<GeneratedFile> eclipse(ProjectModel model) {
        List<GeneratedFile> files = new ArrayList<>();
        files.add(new GeneratedFile(".project", eclipseProject(model)));
        files.add(new GeneratedFile(".classpath", eclipseClasspath(model)));
        files.add(new GeneratedFile(".settings/org.eclipse.jdt.core.prefs",
            eclipseJdtPrefs(model)));
        return files;
    }

    private static String eclipseProject(ProjectModel model) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<projectDescription>\n"
            + "  <name>" + escapeXml(model.getName()) + "</name>\n"
            + "  <comment>Generated by Forge (AGENT.md section 13). Do not edit by hand.</comment>\n"
            + "  <buildSpec>\n"
            + "    <buildCommand>\n"
            + "      <name>org.eclipse.jdt.core.javabuilder</name>\n"
            + "      <arguments />\n"
            + "    </buildCommand>\n"
            + "  </buildSpec>\n"
            + "  <natures>\n"
            + "    <nature>org.eclipse.jdt.core.javanature</nature>\n"
            + "  </natures>\n"
            + "</projectDescription>\n";
    }

    private static String eclipseClasspath(ProjectModel model) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<classpath>\n");
        for (String root : model.getMainSourceRoots()) {
            sb.append("  <classpathentry kind=\"src\" path=\"")
                .append(escapeXml(root)).append("\" />\n");
        }
        for (String root : model.getTestSourceRoots()) {
            sb.append("  <classpathentry kind=\"src\" path=\"")
                .append(escapeXml(root)).append("\" />\n");
        }
        sb.append("  <classpathentry kind=\"con\" path=\"org.eclipse.jdt.launching.JRE_CONTAINER\" />\n");
        for (String lib : model.getCompileLibraries()) {
            sb.append("  <classpathentry kind=\"lib\" path=\"")
                .append(escapeXml(lib)).append("\" />\n");
        }
        for (String lib : model.getTestLibraries()) {
            sb.append("  <classpathentry kind=\"lib\" path=\"")
                .append(escapeXml(lib)).append("\" />\n");
        }
        sb.append("  <classpathentry kind=\"output\" path=\"")
            .append(escapeXml(model.getClassesDir())).append("\" />\n");
        sb.append("</classpath>\n");
        return sb.toString();
    }

    private static String eclipseJdtPrefs(ProjectModel model) {
        String level = model.getLanguageLevel() == null ? "1.8" : model.getLanguageLevel();
        return "eclipse.preferences.version=1\n"
            + "org.eclipse.jdt.core.compiler.codegen.targetPlatform=" + level + "\n"
            + "org.eclipse.jdt.core.compiler.compliance=" + level + "\n"
            + "org.eclipse.jdt.core.compiler.source=" + level + "\n";
    }

    // --- VS Code -----------------------------------------------------------

    /** VS Code Java settings; the Java extension reads {@code .vscode/settings.json}. */
    public static List<GeneratedFile> vscode(ProjectModel model) {
        List<GeneratedFile> files = new ArrayList<>();
        files.add(new GeneratedFile(".vscode/settings.json", vscodeSettings(model)));
        return files;
    }

    private static String vscodeSettings(ProjectModel model) {
        List<String> sourcePaths = new ArrayList<>();
        sourcePaths.addAll(model.getMainSourceRoots());
        sourcePaths.addAll(model.getTestSourceRoots());
        List<String> libraries = new ArrayList<>();
        libraries.addAll(model.getCompileLibraries());
        libraries.addAll(model.getTestLibraries());

        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"java.project.sourcePaths\": ").append(jsonArray(sourcePaths)).append(",\n");
        sb.append("  \"java.project.outputPath\": ").append(jsonString(model.getClassesDir()))
            .append(",\n");
        sb.append("  \"java.project.referencedLibraries\": {\n");
        sb.append("    \"include\": ").append(jsonArray(libraries)).append("\n");
        sb.append("  }\n");
        sb.append("}\n");
        return sb.toString();
    }

    // --- helpers -----------------------------------------------------------

    private static String jsonArray(List<String> values) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(jsonString(values.get(i)));
        }
        return sb.append("]").toString();
    }

    static String jsonString(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    sb.append(c);
            }
        }
        return sb.append("\"").toString();
    }

    static String escapeXml(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&':
                    sb.append("&amp;");
                    break;
                case '<':
                    sb.append("&lt;");
                    break;
                case '>':
                    sb.append("&gt;");
                    break;
                case '"':
                    sb.append("&quot;");
                    break;
                case '\'':
                    sb.append("&apos;");
                    break;
                default:
                    sb.append(c);
            }
        }
        return sb.toString();
    }
}
