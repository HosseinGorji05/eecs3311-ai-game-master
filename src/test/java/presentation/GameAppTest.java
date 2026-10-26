package presentation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Starts the real entry point with System.in and System.out replaced (no combat, so no dice involved). */
class GameAppTest {

    private InputStream originalIn;
    private PrintStream originalOut;
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

    @BeforeEach
    void replaceStreams() {
        originalIn = System.in;
        originalOut = System.out;
        System.setOut(new PrintStream(bytes, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void restoreStreams() {
        System.setIn(originalIn);
        System.setOut(originalOut);
    }

    private String run(String input, String... args) {
        System.setIn(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)));
        GameApp.main(args);
        return bytes.toString(StandardCharsets.UTF_8);
    }

    @Test
    void theGameStartsOfflineAndPlaysFromStandardInput() {
        String output = run("Ari\nmage\ngo north\ntake bread\nquit\n");

        assertTrue(output.startsWith("=== AI Game Master ==="), output);
        assertTrue(output.contains("(Basic-command mode: simple commands only.)"), output);
        assertTrue(output.contains("You take the bread."), output);
        assertTrue(output.endsWith("Goodbye.\n"), output);
    }

    @Test
    void aProviderThatDoesNotExistYetFallsBackToOfflinePlay() {
        String output = run("Ari\nrogue\nquit\n", "deepseek");

        assertTrue(output.startsWith("The \"deepseek\" provider is not available yet; playing offline."), output);
        assertTrue(output.endsWith("Goodbye.\n"), output);
    }
}
