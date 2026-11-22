package presentation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Parsing the command line of {@link GameApp}: the options and the older positional forms. */
class CliOptionsTest {

    private static CliOptions parse(String... args) throws CliOptions.UsageException {
        return CliOptions.parse(args);
    }

    private static String error(String... args) {
        return assertThrows(CliOptions.UsageException.class, () -> CliOptions.parse(args)).getMessage();
    }

    @Test
    void noArgumentsMeanTheCliWithTheConfiguredProvider() throws Exception {
        CliOptions options = parse();

        assertFalse(options.gui());
        assertFalse(options.help());
        assertNull(options.provider());
        assertNull(options.input());
        assertNull(options.seed());
    }

    @Test
    void allOptionsAreRead() throws Exception {
        CliOptions options = parse("--llm", "offline", "--input", "examples/task.txt", "--seed", "42");

        assertEquals("offline", options.provider());
        assertEquals(Path.of("examples/task.txt"), options.input());
        assertEquals(42L, options.seed());
        assertFalse(options.gui());
    }

    @Test
    void optionsCanBeWrittenWithAnEqualsSignAndInAnyOrder() throws Exception {
        CliOptions options = parse("--seed=-7", "--llm=Groq", "--gui");

        assertEquals(-7L, options.seed());
        assertEquals("groq", options.provider());
        assertTrue(options.gui());
    }

    @Test
    void aNegativeSeedIsAValueNotAnOption() throws Exception {
        assertEquals(-3L, parse("--seed", "-3").seed());
    }

    @Test
    void theOlderPositionalFormsStillWork() throws Exception {
        assertEquals("groq", parse("groq").provider());
        assertEquals("offline", parse("offline").provider());
        assertTrue(parse("gui").gui());
        assertTrue(parse("GUI").gui());
        CliOptions guiGroq = parse("gui", "groq");
        assertTrue(guiGroq.gui());
        assertEquals("groq", guiGroq.provider());
        assertEquals("deepseek", parse("gui", "deepseek").provider());
    }

    @Test
    void anUnknownPositionalProviderIsKeptSoTheGamePlaysOfflineWithANotice() throws Exception {
        assertEquals("gpt", parse("gpt").provider());
    }

    @Test
    void helpIsRecognised() throws Exception {
        assertTrue(parse("--help").help());
        assertTrue(parse("-h").help());
    }

    @Test
    void positionalFormsAndOptionsMix() throws Exception {
        CliOptions options = parse("offline", "--seed", "1");

        assertEquals("offline", options.provider());
        assertEquals(1L, options.seed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"--verbose", "-x", "--LLM"})
    void anUnknownOptionIsAnError(String option) {
        assertEquals("Unknown option: " + option + ".", error(option));
    }

    @ParameterizedTest
    @ValueSource(strings = {"--llm", "--input", "--seed"})
    void anOptionWithoutItsValueIsAnError(String option) {
        assertTrue(error(option).startsWith("Option " + option + " needs a value"), error(option));
        assertTrue(error(option, "--gui").startsWith("Option " + option + " needs a value"));
        assertTrue(error(option + "=").startsWith("Option " + option + " needs a value"));
    }

    @Test
    void anUnknownLlmValueIsAnError() {
        assertEquals("Unknown LLM provider for --llm: \"gpt\" (use deepseek, groq or offline).", error("--llm", "gpt"));
    }

    @Test
    void aSeedThatIsNoWholeNumberIsAnError() {
        assertEquals("Option --seed needs a whole number, not \"abc\".", error("--seed", "abc"));
        assertEquals("Option --seed needs a whole number, not \"4.2\".", error("--seed", "4.2"));
    }

    @Test
    void repeatsAndContradictionsAreErrors() {
        assertEquals("Option --seed is given twice.", error("--seed", "1", "--seed", "2"));
        assertEquals("Option --input is given twice.", error("--input", "a.txt", "--input", "b.txt"));
        assertEquals("The provider is given twice (groq and offline).", error("groq", "--llm", "offline"));
        assertEquals("Unexpected argument \"offline\": the provider is already groq.", error("groq", "offline"));
        assertEquals("Option --gui takes no value.", error("--gui=yes"));
    }

    @Test
    void anInputFileCannotBeUsedWithTheGui() {
        assertTrue(error("--gui", "--input", "examples/task.txt").startsWith("--input cannot be used with --gui"));
        assertTrue(error("gui", "--input", "examples/task.txt").startsWith("--input cannot be used with --gui"));
    }
}
