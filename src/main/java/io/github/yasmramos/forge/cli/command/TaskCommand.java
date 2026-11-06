package io.github.yasmramos.forge.cli.command;

import io.github.yasmramos.forge.core.task.TaskType;
import io.github.yasmramos.forge.core.task.TaskTypeRegistry;

import java.io.PrintStream;
import java.util.Set;

/**
 * {@code forge tasks} - list the task types the core knows (AGENT.md section 10).
 *
 * <p>Built-in types are always present; plugins may register more through
 * {@link TaskTypeRegistry}, and those appear here too so the listing reflects
 * what a build can actually schedule.</p>
 */
public final class TaskCommand {

    private TaskCommand() {
    }

    public static int list(PrintStream out) {
        TaskTypeRegistry registry = new TaskTypeRegistry();
        Set<String> names = registry.names();

        out.println("Available task types:");
        for (String name : names) {
            out.println("  " + name + "  - " + describe(name));
        }
        return 0;
    }

    private static String describe(String name) {
        switch (name) {
            case TaskType.COMPILE:
                return "compile sources";
            case TaskType.TEST:
                return "run tests";
            case TaskType.PACKAGE:
                return "package artifacts";
            case TaskType.JAVA_EXEC:
                return "invoke a JVM process (graph task; distinct from 'forge run')";
            case TaskType.CLEAN:
                return "remove build outputs";
            case TaskType.RESOLVE:
                return "resolve dependencies";
            default:
                return "plugin-provided task type";
        }
    }
}
