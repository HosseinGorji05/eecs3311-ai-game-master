package presentation;

import engine.core.Ending;
import engine.core.GameState;
import engine.quests.Quest;

/**
 * A user interface of the game; the controller shows everything through it.
 *
 * <p>Pattern role: <b>View</b> of MVC.
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

    /**
     * Shows the end screen (SD09): the ending type, the epilogue and the statistics, and how to load a
     * save or start a new game (UC13).
     */
    void showEnding(Ending ending);
}
