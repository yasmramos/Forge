package io.github.yasmramos.forge.cli.error;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for the central error renderer and exit-code mapping (AGENT.md 10bis). */
class ErrorRendererTest {

    private static String render(ForgeError error, boolean json, boolean debug, int[] exitOut) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        ErrorRenderer renderer = new ErrorRenderer(
            new PrintStream(buffer, true, StandardCharsets.UTF_8), json, debug);
        exitOut[0] = renderer.render(error);
        return buffer.toString(StandardCharsets.UTF_8);
    }

    private static ForgeError error(String code) {
        ErrorCode.Entry e = ErrorCode.lookup(code);
        return new ForgeError(e.getCode(), e.getWhat(), e.getWhy(), e.getHow(), e.getDocsUrl());
    }

    @Test
    void humanRenderingAnswersThreeQuestions() {
        int[] exit = new int[1];
        String out = render(error("FORGE-TSK-0001"), false, false, exit);
        assertTrue(out.contains("error[FORGE-TSK-0001]"), out);
        assertTrue(out.contains("why :"), out);
        assertTrue(out.contains("fix :"), out);
        assertTrue(out.contains("docs: https://forge.dev/e/TSK-0001"), out);
        assertEquals(1, exit[0], "graph errors are user/config errors");
    }

    @Test
    void jsonRenderingIsSingleLineKeyedOnCode() {
        int[] exit = new int[1];
        String out = render(error("FORGE-CFG-0002"), true, false, exit).strip();
        assertTrue(out.startsWith("{\"code\":\"FORGE-CFG-0002\""), out);
        assertFalse(out.contains("\n"), "JSON-per-line must not contain raw newlines");
        assertEquals(1, exit[0]);
    }

    @Test
    void exitCodesMapByErrorClass() {
        int[] exit = new int[1];
        render(error("FORGE-CFG-0001"), false, false, exit);
        assertEquals(1, exit[0], "config = user error");
        render(error("FORGE-CCH-0001"), false, false, exit);
        assertEquals(2, exit[0], "cache = environment error");
        render(error("FORGE-INT-0001"), false, false, exit);
        assertEquals(3, exit[0], "internal = bug report");
    }

    @Test
    void debugAppendsStackTrace() {
        int[] exit = new int[1];
        ForgeException ex = new ForgeException(ErrorCode.TSK_0001, "a -> a");
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        ErrorRenderer renderer = new ErrorRenderer(
            new PrintStream(buffer, true, StandardCharsets.UTF_8), false, true);
        exit[0] = renderer.render(ex);
        String out = buffer.toString(StandardCharsets.UTF_8);
        assertTrue(out.contains("error[FORGE-TSK-0001]"), out);
        assertTrue(out.contains("at io.github.yasmramos.forge.cli.error.ErrorRendererTest"), out);
        assertEquals(1, exit[0]);
    }

    @Test
    void debugIsSuppressedByDefault() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        ErrorRenderer renderer = new ErrorRenderer(
            new PrintStream(buffer, true, StandardCharsets.UTF_8), false, false);
        renderer.render(new ForgeException(ErrorCode.TSK_0001, "a -> a"));
        String out = buffer.toString(StandardCharsets.UTF_8);
        assertFalse(out.contains("ErrorRendererTest"), "no stack trace without --debug");
    }
}
