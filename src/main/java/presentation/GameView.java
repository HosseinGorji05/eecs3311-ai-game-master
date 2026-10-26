package presentation;

import engine.core.GameState;

/**
 * A user interface of the game; the controller shows everything through it.
 *
 * <p>Pattern role: <b>View</b> of MVC. {@code showQuestOffer(Quest)} and {@code showEnding(Ending)}
 * of the design are added together with quest generation and the ending.
 */
public interface GameView {

    /** Shows the whole situation: location, player, mode and quests. */
    void render(GameState state);

    void showNarration(String text);

    void showNotice(String text);

    void showError(String message);
}
