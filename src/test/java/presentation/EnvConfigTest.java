package presentation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The .env parser and the precedence of the environment; only temporary files, never the real .env. */
class EnvConfigTest {

    @Test
    void parsesKeyValueLinesAndSkipsBlankLinesCommentsAndJunk() {
        Map<String, String> values = EnvConfig.parse(List.of(
                "# a comment",
                "",
                "   ",
                "LLM_PROVIDER=groq",
                "  GROQ_API_KEY = gsk-abc  ",
                "no equals sign here",
                "=no key",
                "export DEEPSEEK_API_KEY=sk-123",
                "EMPTY="));

        assertEquals(Map.of("LLM_PROVIDER", "groq", "GROQ_API_KEY", "gsk-abc",
                "DEEPSEEK_API_KEY", "sk-123", "EMPTY", ""), values);
    }

    @Test
    void stripsMatchingQuotesAndTrailingComments() {
        Map<String, String> values = EnvConfig.parse(List.of(
                "DOUBLE=\"sk-double\"",
                "SINGLE='sk-single'",
                "SPACES=\"  keeps inner spaces  \"",
                "HASH_IN_QUOTES=\"a #b\" # comment",
                "COMMENTED=plain # the rest is a comment",
                "HASH_IN_VALUE=a#b",
                "UNMATCHED=\"open",
                "EQUALS=a=b"));

        assertEquals("sk-double", values.get("DOUBLE"));
        assertEquals("sk-single", values.get("SINGLE"));
        assertEquals("  keeps inner spaces  ", values.get("SPACES"));
        assertEquals("a #b", values.get("HASH_IN_QUOTES"));
        assertEquals("plain", values.get("COMMENTED"));
        assertEquals("a#b", values.get("HASH_IN_VALUE"));
        assertEquals("\"open", values.get("UNMATCHED"));
        assertEquals("a=b", values.get("EQUALS"));
    }

    @Test
    void aLaterLineWins() {
        assertEquals(Map.of("K", "second"), EnvConfig.parse(List.of("K=first", "K=second")));
    }

    @Test
    void theEnvironmentOverridesTheFileAndBlankValuesCountAsUnset() {
        EnvConfig config = new EnvConfig(
                Map.of("LLM_PROVIDER", "deepseek", "GROQ_API_KEY", "from-file", "LLM_API_KEY", "  "),
                Map.of("LLM_PROVIDER", "groq", "GROQ_API_KEY", " ", "DEEPSEEK_API_KEY", " sk-env "));

        assertEquals(Optional.of("groq"), config.get("LLM_PROVIDER"));
        assertEquals(Optional.of("from-file"), config.get("GROQ_API_KEY"));
        assertEquals(Optional.of("sk-env"), config.get("DEEPSEEK_API_KEY"));
        assertEquals(Optional.empty(), config.get("LLM_API_KEY"));
        assertEquals(Optional.empty(), config.get("NOT_THERE"));
    }

    @Test
    void thePlaceholderCountsAsUnsetInTheFileAndTheEnvironment() {
        EnvConfig config = new EnvConfig(
                Map.of("DEEPSEEK_API_KEY", "YOUR_LOCAL_API_KEY_HERE", "GROQ_API_KEY", "gsk-file",
                        "LLM_API_KEY", " YOUR_LOCAL_API_KEY_HERE "),
                Map.of("GROQ_API_KEY", "YOUR_LOCAL_API_KEY_HERE"));

        assertEquals(Optional.empty(), config.get("DEEPSEEK_API_KEY"));
        assertEquals(Optional.of("gsk-file"), config.get("GROQ_API_KEY"));
        assertEquals(Optional.empty(), config.get("LLM_API_KEY"));
    }

    @Test
    void anUnchangedCopyOfTheExampleSetsNoKey(@TempDir Path dir) throws IOException {
        Path dotEnv = dir.resolve(".env");
        Files.copy(Path.of(".env.example"), dotEnv);

        EnvConfig config = EnvConfig.load(dotEnv, Map.of());

        assertEquals(Optional.of("deepseek"), config.get("LLM_PROVIDER"));
        assertEquals(Optional.empty(), config.get("DEEPSEEK_API_KEY"));
        assertEquals(Optional.empty(), config.get("GROQ_API_KEY"));
        assertEquals(Optional.empty(), config.get("LLM_API_KEY"));
    }

    @Test
    void loadsAFileAndAddsTheEnvironment(@TempDir Path dir) throws IOException {
        Path dotEnv = dir.resolve(".env");
        Files.writeString(dotEnv, "LLM_PROVIDER=groq\nGROQ_API_KEY=\"gsk-file\"\n", StandardCharsets.UTF_8);

        EnvConfig config = EnvConfig.load(dotEnv, Map.of("LLM_PROVIDER", "deepseek"));

        assertEquals(Optional.of("deepseek"), config.get("LLM_PROVIDER"));
        assertEquals(Optional.of("gsk-file"), config.get("GROQ_API_KEY"));
    }

    @Test
    void aMissingFileLeavesOnlyTheEnvironment(@TempDir Path dir) {
        EnvConfig config = EnvConfig.load(dir.resolve(".env"), Map.of("GROQ_API_KEY", "gsk-env"));

        assertEquals(Optional.of("gsk-env"), config.get("GROQ_API_KEY"));
        assertEquals(Optional.empty(), config.get("LLM_PROVIDER"));
    }

    @Test
    void aDirectoryInsteadOfAFileCountsAsMissing(@TempDir Path dir) {
        assertEquals(Optional.empty(), EnvConfig.load(dir, Map.of()).get("LLM_PROVIDER"));
        assertThrows(NullPointerException.class, () -> EnvConfig.load(null, Map.of()));
        assertThrows(NullPointerException.class, () -> new EnvConfig(Map.of(), Map.of()).get(null));
    }
}
