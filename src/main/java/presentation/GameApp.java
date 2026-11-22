package presentation;

import engine.core.DiceRoller;
import llm.DeepSeekClient;
import llm.GroqClient;
import llm.LLMClient;
import llm.OpenAiCompatibleClient;
import llm.ScriptedLLMClient;

import java.awt.AWTError;
import java.awt.GraphicsEnvironment;
import java.awt.HeadlessException;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Starts the game: in the terminal with {@code ./mvnw -q compile exec:java}, or in a window (the Swing
 * GUI) with {@code ./mvnw -q compile exec:java -Dexec.args="gui"}; a provider can follow, e.g.
 * {@code -Dexec.args="gui groq"} (for the CLI {@code -Dexec.args="groq"}). It creates the chosen view,
 * the controller and the LLM client from the configuration. The same options work for the standalone jar
 * ({@code java -jar target/ai-game-master.jar --llm offline --seed 42 --input examples/task.txt}); see
 * {@link CliOptions} for all of them.
 *
 * <p>The provider is the {@code --llm} option or the provider argument if there is one, else {@code LLM_PROVIDER} from the
 * configuration ({@code .env}, overridden by environment variables), else DeepSeek. {@code deepseek}
 * and {@code groq} use their HTTP client when a key is set ({@code DEEPSEEK_API_KEY} or
 * {@code GROQ_API_KEY}, with {@code LLM_API_KEY} as a fallback for either) and their default model
 * unless {@code DEEPSEEK_MODEL} or {@code GROQ_MODEL} names another. Without a key, and for
 * {@code offline}, a {@link ScriptedLLMClient} without answers makes the action interpreter use its
 * keyword parser (basic-command mode).
 */
public class GameApp {

    static final String DEFAULT_PROVIDER = "deepseek";
    /** The argument that starts the Swing GUI instead of the CLI (like {@code --gui}). */
    static final String GUI_ARGUMENT = "gui";
    static final String NO_DISPLAY = "No display is available, so the window cannot open."
            + " Start the game in the terminal instead: ./mvnw -q compile exec:java";
    private static final String GROQ_KEY_PREFIX = "gsk_";
    private static final String DEEPSEEK_KEY_PREFIX = "sk-";

    /** Exit code of a normal end (and of {@code --help}). */
    static final int EXIT_OK = 0;
    /** Exit code of a wrong command line or an input file that cannot be read. */
    static final int EXIT_USAGE = 2;

    public static void main(String[] args) {
        int code = run(args, EnvConfig.load(Path.of(".env"), System.getenv()), System.in, System.out, System.err,
                GraphicsEnvironment.isHeadless(), SwingGameView::launch);
        if (code != EXIT_OK) {
            System.exit(code);
        }
    }

    /**
     * Plays one game in the terminal with the given configuration and streams, so tests use neither the
     * real .env nor the console. With {@code LLM_DEBUG=true}, failed model attempts are described on
     * {@code err}. It never opens a window: {@code gui} gets the no-display message. Returns the exit code.
     */
    static int run(String[] args, EnvConfig config, InputStream in, PrintStream out, PrintStream err) {
        return run(args, config, in, out, err, true, controller -> {
            throw new IllegalStateException("No window in this run");
        });
    }

    /**
     * Starts the chosen view (see {@link CliOptions}): with {@code gui} or {@code --gui} the Swing window
     * ({@code guiLauncher}, which returns at once; the window keeps the program running), else the CLI,
     * reading from the {@code --input} file if one is given (echoing each line) and else from {@code in}.
     * Without a display ({@code headless}) the GUI is not started and a message on {@code out} says how to
     * start the CLI instead; no LLM client is created then.
     *
     * @return {@link #EXIT_OK}, or {@link #EXIT_USAGE} after a message on {@code err} for a wrong command
     *         line or an input file that cannot be read (checked before anything else is printed)
     */
    static int run(String[] args, EnvConfig config, InputStream in, PrintStream out, PrintStream err,
                   boolean headless, Consumer<GameController> guiLauncher) {
        CliOptions options;
        try {
            options = CliOptions.parse(args);
        } catch (CliOptions.UsageException wrong) {
            return usageError(wrong.getMessage(), err);
        }
        if (options.help()) {
            out.println(CliOptions.USAGE);
            return EXIT_OK;
        }
        if (options.gui() && headless) {
            out.println(NO_DISPLAY);
            return EXIT_OK;
        }
        InputStream script = null;
        if (options.input() != null) {
            Path file = options.input();
            if (!Files.exists(file)) {
                return usageError("Input file not found: " + file, err);
            }
            if (!Files.isRegularFile(file) || !Files.isReadable(file)) {
                return usageError("Input file cannot be read: " + file, err);
            }
            try {
                script = Files.newInputStream(file);
            } catch (IOException unreadable) {
                return usageError("Input file cannot be read: " + file + " (" + unreadable.getMessage() + ")", err);
            }
        }
        String provider = options.provider() != null ? options.provider()
                : config.get(EnvConfig.LLM_PROVIDER).orElse(DEFAULT_PROVIDER);
        DiceRoller dice = new DiceRoller(options.seed() != null ? options.seed() : System.nanoTime());
        GameController controller = new GameController(createLlmClient(provider, config, out), dice);
        if (config.get(EnvConfig.LLM_DEBUG).filter("true"::equalsIgnoreCase).isPresent()) {
            controller.setLlmDebugOutput(err);
            out.println("LLM debug output is on (standard error).");
        }
        if (options.gui()) {
            try {
                guiLauncher.accept(controller);
            } catch (HeadlessException | AWTError noDisplay) {
                out.println(NO_DISPLAY);
            }
            return EXIT_OK;
        }
        if (script == null) {
            play(controller, in, false, out);
        } else {
            try (InputStream lines = script) {
                play(controller, lines, true, out);
            } catch (IOException closing) {
                // the game has ended; a failure to close the file changes nothing
            }
        }
        return EXIT_OK;
    }

    private static void play(GameController controller, InputStream in, boolean fromFile, PrintStream out) {
        CliGameView view = new CliGameView(controller, in, out, fromFile);
        controller.addView(view);
        view.readLoop();
    }

    private static int usageError(String message, PrintStream err) {
        err.println("Error: " + message);
        err.println("Run with --help to see the options.");
        return EXIT_USAGE;
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
