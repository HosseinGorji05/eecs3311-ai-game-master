package presentation;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/**
 * The command-line options of {@link GameApp}, parsed from its arguments.
 *
 * <p>Options: {@code --llm deepseek|groq|offline}, {@code --input <file>}, {@code --seed <number>},
 * {@code --gui} and {@code --help} ({@code -h}); an option with a value can also be written
 * {@code --llm=offline}. The older positional forms still work, because the README and the demo videos
 * use them: {@code gui} starts the GUI and any other word names the provider, e.g. {@code gui groq} or
 * {@code offline}. An unknown positional provider is kept as given (the game then plays offline with a
 * notice, as before); an unknown {@code --llm} value is a usage error.
 */
final class CliOptions {

    static final Set<String> PROVIDERS = Set.of("deepseek", "groq", "offline");

    static final String USAGE = """
            Usage: java -jar target/ai-game-master.jar [options]
                   (or ./mvnw -q compile exec:java -Dexec.args="[options]")

            Options:
              --llm deepseek|groq|offline  the LLM provider (default: LLM_PROVIDER from .env, else deepseek)
              --input <file>               read every line the player would type from <file>, not the keyboard
              --seed <number>              fixed dice seed: the same input file gives the same rolls
              --gui                        open the game window (Swing GUI) instead of the terminal game
              --help, -h                   print this help and exit

            Input files: one line per thing the player types (name, class, an empty line to accept the
            backstory, then commands). Other empty lines and lines starting with # are skipped; the game
            quits at the end of the file.

            The older forms still work: gui, deepseek, groq, offline, gui groq, ...

            Example:
              java -jar target/ai-game-master.jar --llm offline --seed 42 --input examples/task.txt""";

    /** A wrong command line: the message says what is wrong. */
    static final class UsageException extends Exception {
        UsageException(String message) {
            super(message);
        }
    }

    private boolean help;
    private boolean gui;
    private String provider;
    private Path input;
    private Long seed;

    private CliOptions() {
    }

    /** Parses the arguments; throws {@link UsageException} for an unknown option, a missing or bad value, or a repeat. */
    static CliOptions parse(String[] args) throws UsageException {
        CliOptions options = new CliOptions();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i].trim();
            if (arg.isEmpty()) {
                continue;
            }
            if (!arg.startsWith("-")) {
                options.positional(arg);
                continue;
            }
            String name = arg;
            String value = null;
            int equals = arg.indexOf('=');
            if (arg.startsWith("--") && equals > 0) {
                name = arg.substring(0, equals);
                value = arg.substring(equals + 1);
            }
            switch (name) {
                case "--help", "-h" -> {
                    noValue(name, value);
                    options.help = true;
                }
                case "--gui" -> {
                    noValue(name, value);
                    options.gui = true;
                }
                case "--llm", "--input", "--seed" -> {
                    if (value == null) {
                        if (i + 1 >= args.length || args[i + 1].trim().startsWith("--") || args[i + 1].isBlank()) {
                            throw new UsageException("Option " + name + " needs a value" + valueHint(name) + ".");
                        }
                        value = args[++i].trim();
                    }
                    if (value.isEmpty()) {
                        throw new UsageException("Option " + name + " needs a value" + valueHint(name) + ".");
                    }
                    options.withValue(name, value);
                }
                default -> throw new UsageException("Unknown option: " + arg + ".");
            }
        }
        if (options.gui && options.input != null) {
            throw new UsageException("--input cannot be used with --gui: the window does not read an input file.");
        }
        return options;
    }

    private void positional(String arg) throws UsageException {
        if (arg.equalsIgnoreCase(GameApp.GUI_ARGUMENT)) {
            gui = true;
        } else if (provider == null) {
            provider = arg;
        } else {
            throw new UsageException("Unexpected argument \"" + arg + "\": the provider is already " + provider + ".");
        }
    }

    private void withValue(String name, String value) throws UsageException {
        switch (name) {
            case "--llm" -> {
                if (provider != null) {
                    throw new UsageException("The provider is given twice (" + provider + " and " + value + ").");
                }
                String lower = value.toLowerCase(Locale.ROOT);
                if (!PROVIDERS.contains(lower)) {
                    throw new UsageException("Unknown LLM provider for --llm: \"" + value + "\" (use deepseek, groq or offline).");
                }
                provider = lower;
            }
            case "--input" -> {
                if (input != null) {
                    throw new UsageException("Option --input is given twice.");
                }
                input = Path.of(value);
            }
            case "--seed" -> {
                if (seed != null) {
                    throw new UsageException("Option --seed is given twice.");
                }
                try {
                    seed = Long.parseLong(value);
                } catch (NumberFormatException notANumber) {
                    throw new UsageException("Option --seed needs a whole number, not \"" + value + "\".");
                }
            }
            default -> throw new IllegalStateException(name);
        }
    }

    private static void noValue(String name, String value) throws UsageException {
        if (value != null) {
            throw new UsageException("Option " + name + " takes no value.");
        }
    }

    private static String valueHint(String name) {
        return switch (name) {
            case "--llm" -> " (deepseek, groq or offline)";
            case "--input" -> " (a file name)";
            default -> " (a whole number)";
        };
    }

    boolean help() {
        return help;
    }

    boolean gui() {
        return gui;
    }

    /** The provider given on the command line, or {@code null} to use the configuration. */
    String provider() {
        return provider;
    }

    /** The input file, or {@code null} to read standard input. */
    Path input() {
        return input;
    }

    /** The dice seed, or {@code null} for a different one each run. */
    Long seed() {
        return seed;
    }
}
