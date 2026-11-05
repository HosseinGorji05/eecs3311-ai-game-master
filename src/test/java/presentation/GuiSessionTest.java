package presentation;

import engine.character.CharacterClass;
import engine.character.Stats;
import engine.commands.ActionProposal;
import engine.commands.ActionType;
import engine.core.Ending;
import engine.core.FixedDiceRoller;
import engine.core.GameState;
import engine.events.EventType;
import engine.events.GameEvent;
import engine.events.GameEventListener;
import engine.quests.Quest;
import engine.quests.QuestStatus;
import llm.ScriptedLLMClient;
import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A whole scripted session through the controller (offline client, fixed dice): talk, accept a quest, fight,
 * flee, then the history and the journal. A view without a screen keeps the panels' models up to date the
 * way {@link SwingGameView} does (a copy of the state on every render and event), so this runs headless.
 */
class GuiSessionTest {

    /** The panels' models, refreshed like the Swing view refreshes its panels. */
    private static final class ModelView implements GameView, GameEventListener {
        private final GameController controller;
        final ConversationLog conversation = new ConversationLog();
        final List<String> errors = new ArrayList<>();
        final List<String> modePanels = new ArrayList<>();
        GuiState shown = GuiState.empty();
        DialogueState.QuestCard card;
        Quest offered;

        ModelView(GameController controller) {
            this.controller = controller;
        }

        private void apply(GameState state, boolean loaded) {
            shown = GuiState.of(state);
            if (conversation.follow(shown.getDialogue(), loaded)) {
                card = null;
                offered = null;
            }
            String panel = shown.getCombat().isFighting() ? "combat" : shown.getDialogue().talking() ? "dialogue" : "none";
            if (modePanels.isEmpty() || !modePanels.get(modePanels.size() - 1).equals(panel)) {
                modePanels.add(panel);
            }
        }

        @Override
        public void render(GameState state) {
            apply(state, true);
        }

        @Override
        public void showNarration(String text) {
        }

        @Override
        public void showNotice(String text) {
        }

        @Override
        public void showError(String message) {
            errors.add(message);
        }

        @Override
        public void showQuestOffer(Quest quest) {
            offered = quest;
            card = DialogueState.QuestCard.of(quest, controller.getState().getWorld());
        }

        @Override
        public void showEnding(Ending ending) {
        }

        @Override
        public void onEvent(GameEvent event) {
            apply(controller.getState(), false);
        }
    }

    private static void move(GameController controller, String place) {
        controller.performAction(new ActionProposal(ActionType.MOVE, place, null));
    }

    @Test
    void aFullSessionKeepsEveryPanelsModelConsistent() {
        GameController controller = new GameController(new ScriptedLLMClient(List.of()), new FixedDiceRoller(20, 1, 20));
        ModelView view = new ModelView(controller);
        controller.addView(view);
        controller.newGame("Ari", CharacterClass.WARRIOR, new Stats(5, 3, 2));
        controller.startGame();

        move(controller, "Blacksmith's Forge");
        assertEquals(List.of(new DialogueState.Person("Brann", "Brann (blacksmith)")), view.shown.getPeopleHere());
        controller.performAction(new ActionProposal(ActionType.TALK, "Brann", null));
        assertTrue(view.shown.getDialogue().talking());
        assertEquals("Brann, the blacksmith", view.shown.getDialogue().heading());

        String reply = controller.sendDialogue("Do you have any work for me?").getText();
        view.conversation.addPlayerLine("Do you have any work for me?");
        view.conversation.addNpcLine("Brann", reply);
        assertNotNull(view.card, "Brann offers a (template) quest");
        assertEquals("Brann", view.card.giver());
        controller.respondToQuest(view.offered, true);
        assertEquals(2, view.shown.getQuests().getQuests().size());
        assertEquals(3, view.conversation.getLines().size());

        controller.performAction(new ActionProposal(ActionType.LEAVE, null, null));
        assertFalse(view.conversation.isActive());
        move(controller, "Village Square");
        move(controller, "Forest Edge");
        move(controller, "Wolf Den");

        controller.submitCommand("attack wolf");
        CombatSummary fight = view.shown.getCombat();
        assertTrue(fight.isFighting());
        assertEquals(new CombatSummary.Fighter("wolf", 6, 8), fight.getEnemy());
        assertEquals(2, fight.getLastRound().size());

        controller.performAction(new ActionProposal(ActionType.FLEE, null, null));
        assertFalse(view.shown.getCombat().isFighting());
        assertEquals(List.of("Flee: escaped from the wolf."), view.shown.getCombat().getLastRound());

        assertEquals(List.of("none", "dialogue", "none", "combat", "none"), view.modePanels);
        assertEquals(List.of(), view.errors);
        assertTrue(view.shown.getRules().enabled(ControlRules.PanelControl.TALK, false, false), "exploring again");
        assertTrue(view.shown.getPeopleHere().isEmpty(), "but nobody is in the Wolf Den, so there is no Talk button");

        EventTableModel all = EventTableModel.of(controller.getRecentHistory(EventTableModel.DEFAULT_CAP + 1),
                EventTableModel.DEFAULT_CAP, ZoneOffset.UTC);
        EventTableModel combat = EventTableModel.of(controller.getHistory(EventType.COMBAT), EventTableModel.DEFAULT_CAP,
                ZoneOffset.UTC);
        EventTableModel dialogue = EventTableModel.of(controller.getHistory(EventType.DIALOGUE),
                EventTableModel.DEFAULT_CAP, ZoneOffset.UTC);
        assertEquals("fled from the wolf", all.getValueAt(0, 3), "newest first");
        assertTrue(combat.getRows().stream().allMatch(row -> row.type().equals("Combat")));
        assertEquals(3, combat.getRowCount(), "the player's and the wolf's attack, then the flee: " + combat.getRows());
        assertEquals(3, dialogue.getRowCount(), "talk, the exchange and leave: " + dialogue.getRows());
        assertTrue(dialogue.getRows().get(1).description().contains("Do you have any work for me?"), dialogue.getRows().toString());

        String journal = controller.getJournal();
        assertTrue(journal.startsWith("Journal of Ari"), journal);
        assertTrue(journal.contains("Quest accepted"), journal);
        assertTrue(controller.getState().getQuestLog().allQuests().stream()
                .allMatch(quest -> quest.getStatus() == QuestStatus.ACTIVE));
    }
}
