package presentation;

import engine.commands.ActionResult;

import java.util.List;
import java.util.Objects;

/**
 * What one turn produced: the text shown to the player (the results' own messages, followed by the
 * narrator's narration on a narrated turn), the real results, and whether the game is over
 * ({@code GameState.isGameOver()}: the main quest is completed or the player died).
 */
public class TurnResult {

    private final String narration;
    private final List<ActionResult> results;
    private final boolean gameOver;

    public TurnResult(String narration, List<ActionResult> results, boolean gameOver) {
        this.narration = Objects.requireNonNull(narration, "narration");
        this.results = List.copyOf(Objects.requireNonNull(results, "results"));
        this.gameOver = gameOver;
    }

    public String getNarration() {
        return narration;
    }

    /** The results of the turn's commands; empty if nothing was executed. */
    public List<ActionResult> getResults() {
        return results;
    }

    public boolean isGameOver() {
        return gameOver;
    }
}
