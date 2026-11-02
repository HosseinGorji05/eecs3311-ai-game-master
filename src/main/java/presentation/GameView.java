package presentation;

import engine.core.GameState;
import engine.quests.Quest;

/**
 * A user interface of the game; the controller shows everything through it.
 *
 * <p>Pattern role: <b>View</b> of MVC. {@code showEnding(Ending)} of the design is added together
 * with the ending.
 */
public interface GameView {

    /** Shows the whole situation: location, player, mode and quests. */
    void render(GameState state);

    void showNarration(String text);

    void showNotice(String text);

    void showError(String message);

    /**
     * Shows a quest the NPC the player is talking to offers (SD07); the player answers with
     * {@code GameController.respondToQuest(quest, accept)}.
     */
    void showQuestOffer(Quest quest);
}
