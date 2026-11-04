package presentation;

import com.google.gson.Gson;
import engine.character.CharacterClass;
import engine.character.Player;
import engine.character.Stats;
import engine.commands.ActionProposal;
import engine.commands.ActionType;
import engine.core.Ending;
import engine.core.EndingCalculator;
import engine.core.EndingType;
import engine.core.FixedDiceRoller;
import engine.core.GameState;
import engine.events.GameEvent;
import engine.events.GameEventListener;
import engine.persistence.SaveManager;
import engine.quests.Objective;
import engine.quests.ObjectiveType;
import engine.quests.Quest;
import engine.quests.QuestStatus;
import engine.quests.Reward;
import engine.world.World;
import llm.ScriptedLLMClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The main quest and the ending through the controller (F12, UC13, SD02, SD09), offline: Ari the warrior
 * recovers the village relic from the bandit leader, or dies trying.
 */
class GameControllerEndingTest {

    private static final String HEROIC_EPILOGUE = "Ari the warrior brought the village relic home from the Bandit "
            + "Camp, and Marta and Brann thanked the warrior for every errand.\n\nThe Village never forgot the "
            + "warrior who stood by them.";

    @TempDir
    Path saveDirectory;

    /** Records everything the controller shows and every published event. */
    private static final class RecordingView implements GameView, GameEventListener {
        final List<String> shown = new ArrayList<>();
        final List<Ending> endings = new ArrayList<>();
        final List<GameEvent> events = new ArrayList<>();

        @Override
        public void render(GameState state) {
            shown.add("render");
        }

        @Override
        public void showNarration(String text) {
            shown.add("narration: " + text);
        }

        @Override
        public void showNotice(String text) {
            shown.add("notice: " + text);
        }

        @Override
        public void showError(String message) {
            shown.add("error: " + message);
        }

        @Override
        public void showQuestOffer(Quest quest) {
            shown.add("offer: " + quest.getTitle());
        }

        @Override
        public void showEnding(Ending ending) {
            endings.add(ending);
            shown.add("ending: " + ending.getType());
        }

        @Override
        public void onEvent(GameEvent event) {
            events.add(event);
        }
    }

    private final RecordingView view = new RecordingView();
    private GameController controller;

    private void start(FixedDiceRoller dice, String... llmReplies) {
        controller = new GameController(new ScriptedLLMClient(List.of(llmReplies)), dice, new SaveManager(saveDirectory));
        controller.addView(view);
        controller.newGame("Ari", CharacterClass.WARRIOR, new Stats(5, 3, 2));
        controller.startGame();
    }

    private static String reply(String epilogue) {
        return "{\"epilogue\":" + new Gson().toJson(epilogue) + "}";
    }

    private TurnResult act(ActionType type, String target) {
        TurnResult turn = controller.performAction(new ActionProposal(type, target, null));
        assertTrue(turn.getResults().stream().allMatch(result -> result.isSuccess()), turn.getNarration());
        return turn;
    }

    private GameState state() {
        return controller.getState();
    }

    private Quest mainQuest() {
        return state().getQuestLog().allQuests().stream().filter(Quest::isMain).findFirst().orElseThrow();
    }

    private void acceptVisitQuest(String id, String giver, String target) {
        assertTrue(state().getQuestLog().accept(new Quest(id, "Scout " + target, "Go and look.", giver, false,
                List.of(new Objective(ObjectiveType.VISIT, target, 1)), new Reward(5, 5, null))));
    }

    /** Equips the sword and walks to the Bandit Camp (Forest Edge, then the camp): turns 1-3. */
    private void walkToTheCamp() {
        act(ActionType.EQUIP, "sword");
        act(ActionType.MOVE, "south");
        act(ActionType.MOVE, "south");
    }

    private String lastShown() {
        return view.shown.get(view.shown.size() - 1);
    }

    @Test
    void aNewGameStartsWithTheMainQuestActiveAndItLeavesRoomForThreeSideQuests() {
        start(new FixedDiceRoller());

        Quest main = mainQuest();
        assertEquals(GameController.MAIN_QUEST_TITLE, main.getTitle());
        assertEquals(QuestStatus.ACTIVE, main.getStatus());
        assertNull(main.getGiverId(), "nobody gave it, so Brann can still offer side quests");
        assertEquals(List.of(ObjectiveType.DEFEAT, ObjectiveType.FETCH),
                main.getObjectives().stream().map(Objective::getType).toList());
        assertEquals(List.of(World.BANDIT_LEADER, World.RELIC),
                main.getObjectives().stream().map(Objective::getTargetId).toList());
        assertEquals(0, state().getQuestLog().activeSideQuestCount());
        assertTrue(state().getQuestLog().canOfferFrom(state().getWorld().findNpc("blacksmith")));
        assertFalse(state().isGameOver());

        acceptVisitQuest("a", "someone", "tavern");
        acceptVisitQuest("b", "someone", "forge");
        acceptVisitQuest("c", "someone", "old-mill");

        assertEquals(3, state().getQuestLog().activeSideQuestCount());
        assertEquals(4, state().getQuestLog().activeQuests().size());
        assertFalse(state().getQuestLog().accept(new Quest("d", "One more", "No.", "someone", false,
                List.of(new Objective(ObjectiveType.VISIT, "wolf-den", 1)), new Reward(1, 1, null))));
    }

    @Test
    void helpingMartaAndBrannAndRecoveringTheRelicGivesTheHeroicEndingWithTheModelsEpilogue() {
        start(new FixedDiceRoller(20, 1, 20), reply(HEROIC_EPILOGUE));   // Ari hits, the leader misses, Ari hits
        acceptVisitQuest("tavern-keeper:scout", "tavern-keeper", "forest-edge");
        acceptVisitQuest("blacksmith:camp", "blacksmith", "bandit-camp");
        walkToTheCamp();                                             // completes both side quests: +2 +2
        act(ActionType.TAKE, World.RELIC);
        TurnResult hit = act(ActionType.ATTACK, World.BANDIT_LEADER);
        assertFalse(hit.isGameOver());
        assertTrue(view.endings.isEmpty());

        TurnResult last = act(ActionType.ATTACK, World.BANDIT_LEADER);

        assertTrue(last.isGameOver());
        assertTrue(state().isGameOver());
        assertEquals(QuestStatus.COMPLETED, mainQuest().getStatus());
        assertEquals(1, view.endings.size());
        Ending ending = view.endings.get(0);
        assertEquals(EndingType.HEROIC, ending.getType());
        assertEquals(HEROIC_EPILOGUE, ending.getEpilogue());
        assertEquals(6, ending.getStatistic(EndingCalculator.TURNS_PLAYED));
        assertEquals(3, ending.getStatistic(EndingCalculator.QUESTS_COMPLETED), "two side quests and the main quest");
        assertEquals(1, ending.getStatistic(EndingCalculator.ENEMIES_DEFEATED));
        List<String> lastTurn = view.shown.subList(view.shown.size() - 2, view.shown.size());
        assertTrue(lastTurn.get(0).startsWith("narration: Ari attacks bandit leader"), "SD02: narration first");
        assertEquals("ending: HEROIC", lastTurn.get(1));
        assertFalse(view.shown.stream().anyMatch(line -> line.startsWith("notice: The epilogue")), "the model's epilogue");
    }

    @Test
    void recoveringTheRelicWithoutHelpingAnyoneGivesTheNeutralEndingWithATemplateOffline() {
        start(new FixedDiceRoller(20, 1, 20));
        walkToTheCamp();
        act(ActionType.TAKE, World.RELIC);
        act(ActionType.ATTACK, World.BANDIT_LEADER);
        act(ActionType.ATTACK, World.BANDIT_LEADER);

        Ending ending = view.endings.get(0);
        assertEquals(EndingType.NEUTRAL, ending.getType());
        assertEquals("Ari the warrior brought the village relic back from the Bandit Camp. In 6 turns, Ari completed "
                + "1 quest and defeated 1 enemy.\n\nThe villagers were glad to have the relic home, though few could say "
                + "what kind of person had returned it. Ari moved on soon after, and the village went back to its quiet "
                + "ways.", ending.getEpilogue());
        assertTrue(view.shown.contains("notice: The epilogue is pre-written. Reason: LLM unavailable."));
    }

    @Test
    void afterTheEndingEveryCommandAnswersThatTheGameIsOver() {
        start(new FixedDiceRoller(20, 1, 20));
        walkToTheCamp();
        act(ActionType.TAKE, World.RELIC);
        act(ActionType.ATTACK, World.BANDIT_LEADER);
        act(ActionType.ATTACK, World.BANDIT_LEADER);
        int turn = state().getTurn();

        TurnResult move = controller.performAction(new ActionProposal(ActionType.MOVE, "north", null));
        assertTrue(move.isGameOver());
        assertEquals(GameController.GAME_OVER, move.getNarration());
        assertTrue(controller.submitCommand("go north").isGameOver());
        controller.saveGame("after");
        assertEquals("error: " + GameController.GAME_OVER, lastShown());
        assertNull(controller.sendDialogue("hello"));
        assertEquals("error: " + GameController.GAME_OVER, lastShown());
        controller.respondToQuest(null, true);
        assertEquals("error: " + GameController.GAME_OVER, lastShown());

        assertEquals(1, view.endings.size(), "the ending is shown once");
        assertEquals(turn, state().getTurn(), "no turn was played");
        assertTrue(controller.listSaves().isEmpty(), "nothing was saved");
        assertNotNull(controller.getJournal());
    }

    @Test
    void dyingAfterAQuestGivesTheDeathEndingWithTheDeathTemplate() {
        start(new FixedDiceRoller(1, 20));   // Ari misses, the wolf hits for 4
        acceptVisitQuest("tavern-keeper:scout", "tavern-keeper", "forest-edge");
        act(ActionType.MOVE, "south");        // completes the scouting quest: +2
        act(ActionType.MOVE, "west");
        Player ari = state().getPlayer();
        ari.takeDamage(ari.getHp() - 1);

        TurnResult turn = controller.performAction(new ActionProposal(ActionType.ATTACK, "wolf", null));

        assertTrue(turn.isGameOver());
        Ending ending = view.endings.get(0);
        assertEquals(EndingType.DEATH, ending.getType(), "death wins over a positive score");
        assertEquals("Ari the warrior died before the village relic could be recovered. In 3 turns, Ari completed 1 "
                + "quest and defeated 0 enemies.\n\nThe village mourned the warrior who had tried to help, and the relic "
                + "stayed in the bandits' hands.", ending.getEpilogue());
        assertEquals(QuestStatus.ACTIVE, mainQuest().getStatus());
    }

    @Test
    void dyingBeforeAnyQuestGivesTheShortDeathEndingWithoutAskingTheModel() {
        start(new FixedDiceRoller(1, 20), reply(HEROIC_EPILOGUE));
        act(ActionType.MOVE, "south");
        act(ActionType.MOVE, "west");
        Player ari = state().getPlayer();
        ari.takeDamage(ari.getHp() - 1);

        controller.performAction(new ActionProposal(ActionType.ATTACK, "wolf", null));

        Ending ending = view.endings.get(0);
        assertEquals(EndingType.DEATH, ending.getType());
        assertEquals("Ari the warrior died after 3 turns, before completing a single quest. The village relic stayed "
                + "in the bandits' hands.", ending.getEpilogue());
        assertTrue(view.shown.contains("notice: The epilogue is pre-written. Reason: "
                + "the player died before completing any quest."));
    }

    @Test
    void aSaveMadeBeforeTheEndingLoadsWithTheMainQuestAndCanBePlayedToTheEndAgain() {
        start(new FixedDiceRoller(20, 1, 20, 20, 1, 20));
        walkToTheCamp();
        act(ActionType.TAKE, World.RELIC);
        controller.saveGame("camp");
        assertEquals("notice: " + GameController.SAVED, lastShown());
        act(ActionType.ATTACK, World.BANDIT_LEADER);
        act(ActionType.ATTACK, World.BANDIT_LEADER);
        assertTrue(state().isGameOver());

        controller.loadGame("camp");

        assertFalse(state().isGameOver(), "UC11: a save can be loaded after the ending");
        Quest main = mainQuest();
        assertEquals(QuestStatus.ACTIVE, main.getStatus(), "the main quest survives save and load");
        assertEquals(List.of(0, 1), main.getObjectives().stream().map(Objective::getProgress).toList(),
                "the relic was already taken");
        assertNotNull(state().getPlayer().getInventory().find(World.RELIC));
        assertEquals(12, state().currentLocation().findEnemy(World.BANDIT_LEADER).getHp());
        assertEquals("render", lastShown());

        act(ActionType.ATTACK, World.BANDIT_LEADER);
        TurnResult last = act(ActionType.ATTACK, World.BANDIT_LEADER);

        assertTrue(last.isGameOver());
        assertEquals(2, view.endings.size(), "the loaded game reached its own ending");
        assertEquals(EndingType.NEUTRAL, view.endings.get(1).getType());
    }

    @Test
    void aNewGameAfterTheEndingStartsClean() {
        start(new FixedDiceRoller(1, 20));
        act(ActionType.MOVE, "south");
        act(ActionType.MOVE, "west");
        Player ari = state().getPlayer();
        ari.takeDamage(ari.getHp() - 1);
        controller.performAction(new ActionProposal(ActionType.ATTACK, "wolf", null));
        assertTrue(state().isGameOver());

        controller.newGame("Bo", CharacterClass.MAGE, new Stats(2, 3, 5));
        controller.startGame();

        assertFalse(state().isGameOver());
        assertEquals("Bo", state().getPlayer().getName());
        assertEquals(1, state().getTurn());
        assertEquals(List.of(GameController.MAIN_QUEST_TITLE),
                state().getQuestLog().allQuests().stream().map(Quest::getTitle).toList());
        assertEquals(QuestStatus.ACTIVE, mainQuest().getStatus());
        assertTrue(state().getEventLog().recent(10).isEmpty(), "the old history is gone");
        assertTrue(state().getStoryMemory().getFixedFacts().get(0).startsWith("Bo, a mage, arrived"));
        assertTrue(controller.performAction(new ActionProposal(ActionType.MOVE, "north", null)).getResults().get(0).isSuccess());
    }

    @Test
    void endGameNeedsAFinishedGame() {
        start(new FixedDiceRoller());

        assertThrows(IllegalStateException.class, controller::endGame);
        assertThrows(IllegalStateException.class,
                () -> new GameController(new ScriptedLLMClient(List.of()), new FixedDiceRoller()).endGame());
        assertTrue(view.endings.isEmpty());
    }
}
