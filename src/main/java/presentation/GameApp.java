package presentation;

import llm.DeepSeekClient;
import llm.GroqClient;
import llm.LLMClient;
import llm.OpenAiCompatibleClient;
import llm.ScriptedLLMClient;

import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Starts the game in the terminal: {@code ./mvnw -q compile exec:java}.
 *
 * <p>The provider is the first argument if there is one, else {@code LLM_PROVIDER} from the
 * configuration ({@code .env}, overridden by environment variables), else DeepSeek. {@code deepseek}
 * and {@code groq} use their HTTP client when a key is set ({@code DEEPSEEK_API_KEY} or
 * {@code GROQ_API_KEY}, with {@code LLM_API_KEY} as a fallback for either) and their default model
 * unless {@code DEEPSEEK_MODEL} or {@code GROQ_MODEL} names another. Without a key, and for
 * {@code offline}, a {@link ScriptedLLMClient} without answers makes the action interpreter use its
 * keyword parser (basic-command mode).
 */
public class GameApp {

    static final String DEFAULT_PROVIDER = "deepseek";
    private static final String GROQ_KEY_PREFIX = "gsk_";
    private static final String DEEPSEEK_KEY_PREFIX = "sk-";

    public static void main(String[] args) {
        run(args, EnvConfig.load(Path.of(".env"), System.getenv()), System.in, System.out, System.err);
    }

    /**
     * Plays one game with the given configuration and streams, so tests use neither the real .env
     * nor the console. With {@code LLM_DEBUG=true}, failed model attempts are described on {@code err}.
     */
    static void run(String[] args, EnvConfig config, InputStream in, PrintStream out, PrintStream err) {
        String provider = args.length > 0 ? args[0] : config.get(EnvConfig.LLM_PROVIDER).orElse(DEFAULT_PROVIDER);
        GameController controller = new GameController(createLlmClient(provider, config, out));
        if (config.get(EnvConfig.LLM_DEBUG).filter("true"::equalsIgnoreCase).isPresent()) {
            controller.setLlmDebugOutput(err);
            out.println("LLM debug output is on (standard error).");
        }
        CliGameView view = new CliGameView(controller, in, out);
        controller.addView(view);
        view.readLoop();
    }

    /**
     * The client for the provider, or the offline client with a one-line notice on {@code out} if
     * its key is missing or the provider is unknown. The notice also says when the other provider's
     * key is set, and a warning is printed when a key looks like the other provider's (by its public
     * prefix). Keys are never printed.
     */
    static LLMClient createLlmClient(String provider, EnvConfig config, PrintStream out) {
        String name = provider.trim().toLowerCase(Locale.ROOT);
        switch (name) {
            case "offline":
                return offline();
            case "deepseek": {
                Optional<String> key = apiKey(config, EnvConfig.DEEPSEEK_API_KEY);
                if (key.isEmpty()) {
                    return missingKey(name, EnvConfig.DEEPSEEK_API_KEY, config, "groq", EnvConfig.GROQ_API_KEY, out);
                }
                warnIfOtherProvidersKey(name, key.get(), GROQ_KEY_PREFIX, "groq", out);
                String model = config.get(EnvConfig.DEEPSEEK_MODEL).orElse(DeepSeekClient.DEFAULT_MODEL);
                return announce(new DeepSeekClient(key.get(), model), out);
            }
            case "groq": {
                Optional<String> key = apiKey(config, EnvConfig.GROQ_API_KEY);
                if (key.isEmpty()) {
                    return missingKey(name, EnvConfig.GROQ_API_KEY, config, "deepseek", EnvConfig.DEEPSEEK_API_KEY, out);
                }
                warnIfOtherProvidersKey(name, key.get(), DEEPSEEK_KEY_PREFIX, "deepseek", out);
                String model = config.get(EnvConfig.GROQ_MODEL).orElse(GroqClient.DEFAULT_MODEL);
                return announce(new GroqClient(key.get(), model), out);
            }
            default:
                out.println("Unknown LLM provider \"" + provider + "\" (use deepseek, groq or offline); playing offline.");
                return offline();
        }
    }

    private static Optional<String> apiKey(EnvConfig config, String providerKey) {
        Optional<String> key = config.get(providerKey);
        return key.isPresent() ? key : config.get(EnvConfig.LLM_API_KEY);
    }

    private static LLMClient announce(OpenAiCompatibleClient client, PrintStream out) {
        out.println("Using the " + client.providerName() + " LLM provider (" + client.getModel() + ").");
        return client;
    }

    private static LLMClient missingKey(String provider, String keyName, EnvConfig config,
                                        String otherProvider, String otherKeyName, PrintStream out) {
        String hint = config.get(otherKeyName).isPresent()
                ? " " + otherKeyName + " is set: use LLM_PROVIDER=" + otherProvider + " to play with " + otherProvider + "."
                : "";
        out.println("No API key for " + provider + ": set " + keyName + " (or " + EnvConfig.LLM_API_KEY
                + ") in .env (copy .env.example) or in the environment; playing offline." + hint);
        return offline();
    }

    /** Groq keys start with "gsk_" and DeepSeek keys with "sk-"; a mismatch makes every request fail with HTTP 401. */
    private static void warnIfOtherProvidersKey(String provider, String key, String otherPrefix,
                                                String otherProvider, PrintStream out) {
        if (key.startsWith(otherPrefix)) {
            out.println("Warning: the key for " + provider + " looks like a " + otherProvider + " key (it starts with \""
                    + otherPrefix + "\"); set LLM_PROVIDER=" + otherProvider + " if it is one.");
        }
    }

    private static LLMClient offline() {
        return new ScriptedLLMClient(List.of());
    }
}
