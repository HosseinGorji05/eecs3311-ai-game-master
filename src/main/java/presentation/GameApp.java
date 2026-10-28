package presentation;

import llm.DeepSeekClient;
import llm.GroqClient;
import llm.LLMClient;
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
 * {@code GROQ_API_KEY}, with {@code LLM_API_KEY} as a fallback for either). Without a key, and for
 * {@code offline}, a {@link ScriptedLLMClient} without answers makes the action interpreter use its
 * keyword parser (basic-command mode).
 */
public class GameApp {

    static final String DEFAULT_PROVIDER = "deepseek";

    public static void main(String[] args) {
        run(args, EnvConfig.load(Path.of(".env"), System.getenv()), System.in, System.out);
    }

    /** Plays one game with the given configuration and streams, so tests use neither the real .env nor the console. */
    static void run(String[] args, EnvConfig config, InputStream in, PrintStream out) {
        String provider = args.length > 0 ? args[0] : config.get(EnvConfig.LLM_PROVIDER).orElse(DEFAULT_PROVIDER);
        GameController controller = new GameController(createLlmClient(provider, config, out));
        CliGameView view = new CliGameView(controller, in, out);
        controller.addView(view);
        view.readLoop();
    }

    /**
     * The client for the provider, or the offline client with a one-line notice on {@code out} if
     * its key is missing or the provider is unknown. Keys are never printed.
     */
    static LLMClient createLlmClient(String provider, EnvConfig config, PrintStream out) {
        String name = provider.trim().toLowerCase(Locale.ROOT);
        switch (name) {
            case "offline":
                return offline();
            case "deepseek": {
                Optional<String> key = apiKey(config, EnvConfig.DEEPSEEK_API_KEY);
                return key.isPresent() ? announce(new DeepSeekClient(key.get()), out)
                        : missingKey(name, EnvConfig.DEEPSEEK_API_KEY, out);
            }
            case "groq": {
                Optional<String> key = apiKey(config, EnvConfig.GROQ_API_KEY);
                return key.isPresent() ? announce(new GroqClient(key.get()), out)
                        : missingKey(name, EnvConfig.GROQ_API_KEY, out);
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

    private static LLMClient announce(LLMClient client, PrintStream out) {
        out.println("Using the " + client.providerName() + " LLM provider.");
        return client;
    }

    private static LLMClient missingKey(String provider, String keyName, PrintStream out) {
        out.println("No API key for " + provider + ": set " + keyName + " (or " + EnvConfig.LLM_API_KEY
                + ") in .env (copy .env.example) or in the environment; playing offline.");
        return offline();
    }

    private static LLMClient offline() {
        return new ScriptedLLMClient(List.of());
    }
}
