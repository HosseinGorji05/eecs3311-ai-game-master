package presentation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The start-up configuration: values from a local {@code .env} file, overridden by real
 * environment variables of the same name. A blank value, or the {@link #PLACEHOLDER} of
 * {@code .env.example}, counts as not set.
 *
 * <p>The {@code .env} format is one {@code KEY=VALUE} per line. Blank lines and lines starting with
 * {@code #} are ignored, as are lines without {@code =} and a byte order mark; an {@code export } prefix is allowed; a value
 * in matching single or double quotes is taken as is, otherwise it is trimmed and a {@code " #"} starts
 * a comment. Values are never printed, since they include API keys.
 */
public final class EnvConfig {

    public static final String LLM_PROVIDER = "LLM_PROVIDER";
    public static final String DEEPSEEK_API_KEY = "DEEPSEEK_API_KEY";
    public static final String GROQ_API_KEY = "GROQ_API_KEY";
    public static final String LLM_API_KEY = "LLM_API_KEY";
    /** Optional model overrides; when unset, each client uses its default model. */
    public static final String DEEPSEEK_MODEL = "DEEPSEEK_MODEL";
    public static final String GROQ_MODEL = "GROQ_MODEL";
    public static final String LLM_DEBUG = "LLM_DEBUG";
    /** The value {@code .env.example} ships with; a copy left unchanged plays offline. */
    public static final String PLACEHOLDER = "YOUR_LOCAL_API_KEY_HERE";

    private final Map<String, String> fileValues;
    private final Map<String, String> environment;

    /**
     * @param fileValues  the values read from {@code .env}
     * @param environment the process environment, which takes precedence
     */
    public EnvConfig(Map<String, String> fileValues, Map<String, String> environment) {
        this.fileValues = Map.copyOf(Objects.requireNonNull(fileValues, "fileValues"));
        this.environment = Map.copyOf(Objects.requireNonNull(environment, "environment"));
    }

    /** Reads {@code dotEnv} if it exists (an unreadable file counts as missing) and adds the environment. */
    public static EnvConfig load(Path dotEnv, Map<String, String> environment) {
        Objects.requireNonNull(dotEnv, "dotEnv");
        Map<String, String> fileValues = Map.of();
        if (Files.isRegularFile(dotEnv)) {
            try {
                fileValues = parse(Files.readAllLines(dotEnv, StandardCharsets.UTF_8));
            } catch (IOException | RuntimeException unreadable) {
                fileValues = Map.of();
            }
        }
        return new EnvConfig(fileValues, environment);
    }

    /** The {@code KEY=VALUE} pairs in the lines; a later line wins over an earlier one with the same key. */
    static Map<String, String> parse(List<String> lines) {
        Map<String, String> values = new HashMap<>();
        for (String raw : lines) {
            String line = raw.replace("\uFEFF", "").strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("export ")) {
                line = line.substring("export ".length()).strip();
            }
            int equals = line.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            String key = line.substring(0, equals).strip();
            if (!key.isEmpty()) {
                values.put(key, value(line.substring(equals + 1).strip()));
            }
        }
        return values;
    }

    /** The value of {@code key}: the environment's if set, else the file's; empty if neither is set. */
    public Optional<String> get(String key) {
        Objects.requireNonNull(key, "key");
        String fromEnvironment = environment.get(key);
        if (isSet(fromEnvironment)) {
            return Optional.of(fromEnvironment.strip());
        }
        String fromFile = fileValues.get(key);
        return isSet(fromFile) ? Optional.of(fromFile.strip()) : Optional.empty();
    }

    /** Neither missing, blank nor the placeholder. */
    private static boolean isSet(String value) {
        return value != null && !value.isBlank() && !value.strip().equals(PLACEHOLDER);
    }

    private static String value(String raw) {
        if (raw.length() >= 2) {
            char first = raw.charAt(0);
            if ((first == '"' || first == '\'') && raw.indexOf(first, 1) > 0) {
                return raw.substring(1, raw.indexOf(first, 1));
            }
        }
        int comment = raw.indexOf(" #");
        return (comment >= 0 ? raw.substring(0, comment) : raw).strip();
    }
}
