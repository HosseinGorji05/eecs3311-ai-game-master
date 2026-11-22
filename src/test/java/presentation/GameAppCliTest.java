package presentation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * The standalone CLI: options, exit codes and playing from an input file. Every game is offline with an
 * in-memory configuration, so neither the network, a key nor the real .env is used.
 */
class GameAppCliTest {

    private static final EnvConfig NO_KEYS = new EnvConfig(Map.of(), Map.of());
    private static final Path TASK = Path.of("examples", "task.txt");

    private ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    private ByteArrayOutputStream errBytes = new ByteArrayOutputStream();

    @TempDir
    Path temp;

    private int run(String stdin, String... args) {
        outBytes = new ByteArrayOutputStream();
        errBytes = new ByteArrayOutputStream();
        return GameApp.run(args, NO_KEYS, new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(outBytes, true, StandardCharsets.UTF_8),
                new PrintStream(errBytes, true, StandardCharsets.UTF_8));
    }

    private String out() {
        return outBytes.toString(StandardCharsets.UTF_8);
    }

    private String err() {
        return errBytes.toString(StandardCharsets.UTF_8);
    }

    private void assertUsageError(String expectedMessage, String... args) {
        assertEquals(GameApp.EXIT_USAGE, run("", args));
        assertEquals("Error: " + expectedMessage + "\nRun with --help to see the options.\n", err());
        assertEquals("", out(), "nothing starts after a usage error");
    }

    @Test
    void helpPrintsTheOptionsAndAnExampleAndExitsZero() {
        assertEquals(GameApp.EXIT_OK, run("", "--help"));

        String help = out();
        for (String option : new String[] {"--llm deepseek|groq|offline", "--input <file>", "--seed <number>", "--gui", "--help"}) {
            assertTrue(help.contains(option), option + " in " + help);
        }
        assertTrue(help.contains("java -jar target/ai-game-master.jar --llm offline --seed 42 --input examples/task.txt"), help);
        assertEquals("", err());
    }

    @Test
    void anUnknownOptionExitsWithTwo() {
        assertUsageError("Unknown option: --fast.", "--fast");
    }

    @Test
    void aMissingValueExitsWithTwo() {
        assertUsageError("Option --llm needs a value (deepseek, groq or offline).", "--llm");
        assertUsageError("Option --input needs a value (a file name).", "--input");
        assertUsageError("Option --seed needs a value (a whole number).", "--seed");
    }

    @Test
    void aBadValueExitsWithTwo() {
        assertUsageError("Unknown LLM provider for --llm: \"gpt\" (use deepseek, groq or offline).", "--llm", "gpt");
        assertUsageError("Option --seed needs a whole number, not \"forty\".", "--seed", "forty");
    }

    @Test
    void aMissingInputFileExitsWithTwo() {
        Path missing = temp.resolve("no-such-file.txt");

        assertUsageError("Input file not found: " + missing, "--llm", "offline", "--input", missing.toString());
    }

    @Test
    void anInputFileThatIsADirectoryExitsWithTwo() {
        assertUsageError("Input file cannot be read: " + temp, "--llm", "offline", "--input", temp.toString());
    }

    @Test
    void anUnreadableInputFileExitsWithTwo() throws IOException {
        Path locked = Files.writeString(temp.resolve("locked.txt"), "Ari\nwarrior\n");
        assumeFalse(!locked.toFile().setReadable(false) || Files.isReadable(locked),
                "the file system (or a root user) keeps the file readable");

        assertUsageError("Input file cannot be read: " + locked, "--llm", "offline", "--input", locked.toString());
    }

    @Test
    void theExampleTaskRecoversTheRelicAndReachesTheEnding() {
        assertEquals(GameApp.EXIT_OK, run("", "--llm", "offline", "--seed", "42", "--input", TASK.toString()));

        String output = out();
        assertTrue(output.contains("Your name: Ari\nClass (warrior, rogue, mage): warrior\n"), output);
        assertTrue(output.contains("> take shed key\n"), "each line is echoed after the prompt");
        assertTrue(output.contains("You equip the sword"), output);
        assertTrue(output.contains("The bandit leader is defeated."), output);
        assertTrue(output.contains("There is no bandit leader here."), "the spare attacks are refused harmlessly");
        assertTrue(output.contains("(Quest completed: Recover the village relic."), output);
        assertTrue(output.contains("=== The End: Neutral ending ==="), output);
        assertTrue(output.endsWith("> (end of input)\nGoodbye.\n"), "the end of the file quits");
        assertFalse(output.contains("> #"), "comments are not played");
        assertFalse(output.contains("I didn't understand"), output);
        assertEquals("", err());
    }

    @Test
    void theSameSeedGivesTheSameOutputAndAnotherSeedOtherRolls() {
        String[] args = {"--llm", "offline", "--seed", "42", "--input", TASK.toString()};
        run("", args);
        String first = out();
        run("", args);
        String second = out();
        run("", "--llm", "offline", "--seed", "7", "--input", TASK.toString());
        String otherSeed = out();

        assertEquals(first, second);
        assertTrue(first.contains("rolls"), first);
        assertNotEquals(first, otherSeed);
    }

    @Test
    void anInputFileSkipsCommentsAndEmptyLinesButTheBackstoryAcceptsAnEmptyLine() throws IOException {
        Path file = Files.writeString(temp.resolve("short.txt"),
                "# a comment\n\nBo\n\n# another\nrogue\n\n\n# before a command\ngo north\n");

        assertEquals(GameApp.EXIT_OK, run("", "--llm", "offline", "--seed", "1", "--input", file.toString()));

        String output = out();
        assertTrue(output.contains("Your name: Bo\nClass (warrior, rogue, mage): rogue\n"), output);
        assertTrue(output.contains("press Enter to start: \nBo the rogue sets out."), output);
        assertTrue(output.contains("> go north\n"), output);
        assertTrue(output.contains("Tavern"), output);
        assertTrue(output.endsWith("> (end of input)\nGoodbye.\n"), output);
    }

    @Test
    void standardInputStillWorksWithoutEchoAndSkipsComments() {
        assertEquals(GameApp.EXIT_OK, run("Ari\nwarrior\n\n# go to the tavern\ngo north\nquit\n", "offline"));

        String output = out();
        assertTrue(output.contains("Tavern"), output);
        assertFalse(output.contains("> go north"), "standard input is not echoed");
        assertFalse(output.contains("I didn't understand"), "the comment is not played as a command");
        assertTrue(output.endsWith("Goodbye.\n"), output);
    }

    @Test
    void theOlderFormsKeepWorkingAndExitZero() {
        assertEquals(GameApp.EXIT_OK, run("Ari\nmage\nquit\n", "offline"));
        assertTrue(out().endsWith("Goodbye.\n"), out());
        assertEquals(GameApp.EXIT_OK, run("", "gui", "offline"));
        assertEquals(GameApp.NO_DISPLAY + "\n", out());
        assertEquals(GameApp.EXIT_OK, run("", "--gui", "--llm", "offline"));
        assertEquals(GameApp.NO_DISPLAY + "\n", out());
    }
}
