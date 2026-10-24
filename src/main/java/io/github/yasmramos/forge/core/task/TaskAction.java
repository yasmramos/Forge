package io.github.yasmramos.forge.core.task;

/**
 * Executable body of a task, run during execution time only when the task's
 * hash is not present in the build cache.
 */
@FunctionalInterface
public interface TaskAction {

    TaskAction NOOP = ctx -> TaskResult.success();

    TaskResult execute(TaskContext context) throws Exception;
}
