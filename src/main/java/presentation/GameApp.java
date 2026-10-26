package presentation;

import llm.LLMClient;
import llm.ScriptedLLMClient;

import java.util.List;
import java.util.Locale;

/**
 * Starts the game in the terminal: {@code ./mvnw -q compile exec:java}.
 *
 * <p>The first argument names the LLM provider. No network client exists yet, so every provider
 * plays offline: a {@link ScriptedLLMClient} without answers makes the action interpreter use its
 * keyword parser (basic-command mode).
 */
public class GameApp {

    public static void main(String[] args) {
        String provider = args.length > 0 ? args[0] : "offline";
        GameController controller = new GameController(createLlmClient(provider));
        CliGameView view = new CliGameView(controller, System.in, System.out);
        controller.addView(view);
        view.readLoop();
    }

    private static LLMClient createLlmClient(String provider) {
        if (!provider.toLowerCase(Locale.ROOT).equals("offline")) {
            System.out.println("The \"" + provider + "\" provider is not available yet; playing offline.");
        }
        return new ScriptedLLMClient(List.of());
    }
}
