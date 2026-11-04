package presentation;

import engine.character.CharacterClass;
import engine.character.Player;
import engine.character.Stats;
import engine.commands.ActionProposal;
import engine.commands.ActionType;
import engine.core.CombatMode;
import engine.core.DialogueMode;
import engine.core.Ending;
import engine.core.FixedDiceRoller;
import engine.core.GameState;
import engine.events.GameEvent;
import engine.events.GameEventListener;
import engine.items.Container;
import engine.persistence.GameSnapshot;
import engine.persistence.SaveManager;
import engine.persistence.SaveSlotInfo;
import engine.quests.Objective;
import engine.quests.ObjectiveType;
import engine.quests.Quest;
import engine.quests.QuestStatus;
import engine.quests.Reward;
import engine.world.Direction;
import llm.ScriptedLLMClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Saving and loading through the controller (F10, UC10, UC11, SD08) with the real engine, offline:
 * Vex the rogue packs a bag, robs Brann, takes his quest, finishes another, kills the wolf, saves, and
 * loads the game back.
 */
class GameControllerSaveTest {

    @TempDir
    Path saveDirectory;

    /** Records everything the controller shows, every quest offered and every published event. */
    private static final class RecordingView implements GameView, GameEventListener {
        final List<String> shown = new ArrayList<>();
        final List<Ending> endings = new ArrayList<>();
        final List<Quest> offers = new ArrayList<>();
        final List<GameEvent> events = new ArrayList<>();
        int renders;

        @Override
        public void render(GameState state) {
            renders++;
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
            offers.add(quest);
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

    private GameController start(FixedDiceRoller dice) {
        controller = new GameController(new ScriptedLLMClient(List.of()), dice, new SaveManager(saveDirectory));
        controller.addView(view);
        controller.newGame("Vex", CharacterClass.ROGUE, new Stats(2, 6, 2));
        controller.startGame();
        return controller;
    }

    private void act(ActionType type, String target) {
        act(type, target, null);
    }

    private void act(ActionType type, String target, String second) {
        TurnResult turn = controller.performAction(new ActionProposal(type, target, second));
        assertTrue(turn.getResults().stream().allMatch(result -> result.isSuccess()), turn.getNarration());
    }

    private GameState state() {
        return controller.getState();
    }

    private String stateJson() {
        return state().createSnapshot().getStateJson();
    }

    private String lastShown() {
        return view.shown.get(view.shown.size() - 1);
    }

    /**
     * Plays the whole story and ends in the Wolf Den: a dagger equipped, the pouch (with its potion)
     * and the bread packed in the backpack, the shed key taken and the shed unlocked, the iron key
     * stolen from Brann, his quest accepted, Marta's scouting quest completed and the wolf killed.
     */
    private void playTheStory() {
        start(new FixedDiceRoller(20, 1, 20));   // Vex hits, the wolf misses, Vex kills it
        act(ActionType.EQUIP, "dagger");
        act(ActionType.MOVE, "north");
        act(ActionType.TAKE, "shed key");
        act(ActionType.TAKE, "bread");
        act(ActionType.MOVE, "south");
        act(ActionType.MOVE, "east");            // unlocks the Tool Shed
        act(ActionType.TAKE, "backpack");
        act(ActionType.PUT, "pouch", "backpack");
        act(ActionType.PUT, "bread", "backpack");
        act(ActionType.MOVE, "west");
        act(ActionType.MOVE, "west");
        act(ActionType.TAKE, "iron key");        // a theft: Brann owns it
        act(ActionType.TALK, "Brann");
        controller.sendDialogue("Any work?");
        controller.respondToQuest(view.offers.get(0), true);
        act(ActionType.LEAVE, null);
        state().getQuestLog().accept(new Quest("tavern-keeper:scout", "Scout the Forest Edge", "Look around.",
                "tavern-keeper", false, List.of(new Objective(ObjectiveType.VISIT, "forest-edge", 1)),
                new Reward(10, 5, null)));
        act(ActionType.MOVE, "east");
        act(ActionType.MOVE, "south");           // completes the scouting quest
        act(ActionType.MOVE, "west");
        act(ActionType.ATTACK, "wolf");
        act(ActionType.ATTACK, "wolf");
        view.shown.clear();
    }

    @Test
    void aLoadedGameMatchesTheSavedOneExactly() {
        playTheStory();
        assertFalse(state().getStoryMemory().getSummaries().isEmpty(), "the memory was summarized");
        controller.saveGame("story");
        assertEquals(List.of("notice: " + GameController.SAVED), view.shown);
        String saved = stateJson();
        String journal = controller.getJournal();
        List<String> history = describe(state().getEventLog().recent(1000));
        Player before = state().getPlayer();
        int turn = state().getTurn();

        act(ActionType.MOVE, "east");
        act(ActionType.MOVE, "north");
        act(ActionType.DROP, "bread");
        assertNotEquals(saved, stateJson());
        int eventsBeforeLoad = view.events.size();

        controller.loadGame("story");

        assertEquals(saved, stateJson(), "a second snapshot equals the first");
        assertEquals(journal, controller.getJournal());
        assertEquals(history, describe(state().getEventLog().recent(1000)), "same events, turns and timestamps");
        assertEquals(eventsBeforeLoad, view.events.size(), "loading publishes no event");
        assertEquals(2, view.renders, "the views render the started and the loaded game");

        Player vex = state().getPlayer();
        assertNotEquals(before, vex);
        assertEquals("wolf-den", state().currentLocation().getId());
        assertEquals(turn, state().getTurn());
        assertEquals("dagger", vex.getEquippedWeapon().getName());
        assertSame(vex.getInventory().find("dagger"), vex.getEquippedWeapon());
        Container backpack = (Container) vex.getInventory().find("backpack");
        Container pouch = (Container) backpack.getContents().get(0);
        assertEquals("pouch", pouch.getName());
        assertEquals("healing potion", pouch.getContents().get(0).getName());
        assertEquals("blacksmith", vex.getInventory().find("iron key").getOwnerId(), "stolen, still Brann's");
        assertEquals(10, vex.getGold());
        assertFalse(state().getWorld().findLocation("village-square").getExit(Direction.EAST).isLocked());
        assertTrue(state().getWorld().findLocation("wolf-den").getEnemies().isEmpty(), "the wolf stays dead");
        assertEquals(List.of(QuestStatus.ACTIVE, QuestStatus.ACTIVE, QuestStatus.COMPLETED),
                state().getQuestLog().allQuests().stream().map(Quest::getStatus).toList());
        Quest main = state().getQuestLog().allQuests().get(0);
        assertTrue(main.isMain(), "the main quest survives the load");
        assertEquals(GameController.MAIN_QUEST_TITLE, main.getTitle());
        assertEquals(-2 + 2, state().getEventLog().recent(1000).stream().mapToInt(GameEvent::getAlignmentDelta).sum(),
                "the theft and the completed quest");
        assertTrue(state().getStoryMemory().getFixedFacts().contains("stole the iron key from Brann"));
    }

    @Test
    void afterALoadTheObserversStillLogTrackAndRememberNewEvents() {
        playTheStory();
        controller.saveGame("story");
        var eventLog = state().getEventLog();
        var questLog = state().getQuestLog();
        var memory = state().getStoryMemory();
        act(ActionType.MOVE, "east");
        controller.loadGame("story");
        assertSame(eventLog, state().getEventLog(), "the same objects were reloaded");
        assertSame(questLog, state().getQuestLog());
        assertSame(memory, state().getStoryMemory());
        Quest brannsQuest = questLog.activeQuests().stream().filter(quest -> !quest.isMain()).findFirst().orElseThrow();
        assertEquals("Find the hand axe", brannsQuest.getTitle());

        act(ActionType.MOVE, "east");
        act(ActionType.MOVE, "north");
        act(ActionType.MOVE, "east");
        act(ActionType.TAKE, "hand axe");

        assertEquals(QuestStatus.COMPLETED, brannsQuest.getStatus(), "the quest tracker still sees events");
        List<GameEvent> recent = eventLog.recent(2);
        assertEquals("took the hand axe", recent.get(0).getDescription(), "the event log is still subscribed");
        assertTrue(recent.get(1).getDescription().startsWith("Quest completed: Find the hand axe"));
        List<GameEvent> remembered = memory.getRecentEvents();
        assertEquals("took the hand axe", remembered.get(remembered.size() - 2).getDescription(),
                "the memory manager still records");
        assertTrue(memory.getFixedFacts().stream().anyMatch(fact -> fact.startsWith("Quest completed: Find the hand axe")));
    }

    @Test
    void aConversationIsSavedWithItsNpcAndWhetherAQuestWasOffered() {
        start(new FixedDiceRoller());
        act(ActionType.MOVE, "west");
        act(ActionType.TALK, "Brann");
        controller.sendDialogue("Hello");
        controller.saveGame("talking");
        controller.respondToQuest(view.offers.get(0), true);
        act(ActionType.LEAVE, null);

        controller.loadGame("talking");

        DialogueMode dialogue = assertInstanceOf(DialogueMode.class, state().getMode());
        assertEquals("Brann", dialogue.getNpc().getName());
        assertSame(state().currentLocation().findNpc("Brann"), dialogue.getNpc());
        assertTrue(dialogue.isQuestOffered(), "no second offer in the same conversation");
        assertEquals(List.of(GameController.MAIN_QUEST_TITLE),
                state().getQuestLog().allQuests().stream().map(Quest::getTitle).toList(), "only the main quest");
        controller.respondToQuest(view.offers.get(0), true);
        assertEquals("error: " + GameController.NO_OFFER, lastShown(), "the pending offer is not part of the save");
    }

    @Test
    void savingDuringCombatIsRefusedAndNothingIsWritten() throws IOException {
        start(new FixedDiceRoller(1, 1));   // Vex misses, the wolf misses
        act(ActionType.MOVE, "south");
        act(ActionType.MOVE, "west");
        act(ActionType.ATTACK, "wolf");
        assertInstanceOf(CombatMode.class, state().getMode());

        controller.saveGame("fight");

        assertEquals("error: You can't save during combat.", lastShown());
        try (var files = Files.list(saveDirectory)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void savingAfterDeathIsRefusedButLoadingBringsThePlayerBack() {
        int[] rolls = new int[16];   // 24 HP, eight wolf hits of 3
        for (int i = 0; i < rolls.length; i += 2) {
            rolls[i] = 1;        // Vex misses
            rolls[i + 1] = 20;   // the wolf hits for 3
        }
        start(new FixedDiceRoller(rolls));
        act(ActionType.MOVE, "south");
        controller.saveGame("before-the-den");
        act(ActionType.MOVE, "west");
        for (int round = 0; round < 8 && state().getPlayer().isAlive(); round++) {
            controller.performAction(new ActionProposal(ActionType.ATTACK, "wolf", null));
        }
        assertFalse(state().getPlayer().isAlive());

        controller.saveGame("dead");
        assertEquals("error: " + GameController.GAME_OVER, lastShown());

        controller.loadGame("before-the-den");
        assertTrue(state().getPlayer().isAlive());
        assertEquals("forest-edge", state().currentLocation().getId());
        assertTrue(controller.performAction(new ActionProposal(ActionType.MOVE, "north", null)).getResults().get(0).isSuccess());
    }

    @Test
    void invalidSlotNamesAreRefusedAndCannotLeaveTheSaveDirectory() throws IOException {
        start(new FixedDiceRoller());

        for (String slot : List.of("../x", "a/b", "a.b", "", " ", "x".repeat(SaveManager.MAX_SLOT_LENGTH + 1), "..")) {
            controller.saveGame(slot);
            assertTrue(lastShown().startsWith("error: Could not save: A save name has 1 to 32 letters"), slot + ": " + lastShown());
            controller.loadGame(slot);
            assertTrue(lastShown().startsWith("error: Could not load: A save name has"), lastShown());
        }
        assertFalse(Files.exists(saveDirectory.getParent().resolve("x.json")));
        try (var files = Files.list(saveDirectory)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void aMissingSaveIsReportedAndTheGameGoesOn() {
        start(new FixedDiceRoller());
        String before = stateJson();

        controller.loadGame("nothing");

        assertEquals("error: Could not load: There is no save named \"nothing\".", lastShown());
        assertEquals(before, stateJson());
    }

    @Test
    void aDamagedOrIncompatibleSaveLeavesTheGameUntouched() throws IOException {
        start(new FixedDiceRoller());
        act(ActionType.MOVE, "north");
        controller.saveGame("good");
        String good = Files.readString(saveDirectory.resolve("good.json"));
        act(ActionType.MOVE, "south");
        act(ActionType.MOVE, "west");
        String before = stateJson();
        String lastEventType = "\"type\": \"MOVEMENT\"";
        int last = good.lastIndexOf(lastEventType);

        List<List<String>> cases = List.of(
                List.of("garbage", "{not json at all", "damaged or not a saved game"),
                List.of("truncated", good.substring(0, good.length() / 2), "damaged or not a saved game"),
                List.of("empty", "", "damaged or not a saved game"),
                List.of("other-version", good.replace("\"version\": 2", "\"version\": 1"), "another version of the game (format 1;"),
                List.of("bad-location", good.replace("\"currentLocation\": \"tavern\"", "\"currentLocation\": \"moon\""),
                        "damaged (the current location is not in the world)"),
                List.of("bad-last-event", good.substring(0, last) + "\"type\": \"EXPLOSION\"" + good.substring(last + lastEventType.length()),
                        "The saved game is damaged"),
                List.of("cheated-stats", good.replace("\"agility\": 6", "\"agility\": 60"), "The saved game is damaged"));
        for (List<String> damaged : cases) {
            Files.writeString(saveDirectory.resolve(damaged.get(0) + ".json"), damaged.get(1), StandardCharsets.UTF_8);

            controller.loadGame(damaged.get(0));

            assertTrue(lastShown().startsWith("error: Could not load: "), damaged.get(0) + ": " + lastShown());
            assertTrue(lastShown().contains(damaged.get(2)), damaged.get(0) + ": " + lastShown());
            assertEquals(before, stateJson(), damaged.get(0) + " left the game untouched");
        }
        assertTrue(controller.performAction(new ActionProposal(ActionType.MOVE, "east", null)).getResults().get(0).isSuccess());
    }

    @Test
    void aFailedWriteIsShownAndTheGameContinues() throws IOException {
        Path notADirectory = saveDirectory.resolve("file");
        Files.writeString(notADirectory, "I am a file");
        controller = new GameController(new ScriptedLLMClient(List.of()), new FixedDiceRoller(), new SaveManager(notADirectory));
        controller.addView(view);
        controller.newGame("Vex", CharacterClass.ROGUE, new Stats(2, 6, 2));
        controller.startGame();

        controller.saveGame("game");

        assertTrue(lastShown().startsWith("error: Could not save: the file could not be written ("), lastShown());
        assertEquals("I am a file", Files.readString(notADirectory));
        assertTrue(controller.performAction(new ActionProposal(ActionType.MOVE, "north", null)).getResults().get(0).isSuccess());
        assertTrue(controller.listSaves().isEmpty());
    }

    @Test
    void savingAgainUnderTheSameNameReplacesTheSave() {
        start(new FixedDiceRoller());
        controller.saveGame("slot");
        act(ActionType.MOVE, "north");
        controller.saveGame("SLOT");   // the same slot: names ignore case

        List<SaveSlotInfo> saves = controller.listSaves();
        assertEquals(1, saves.size());
        assertEquals("slot", saves.get(0).getSlot());
        assertEquals("Vex", saves.get(0).getCharacterName());
        assertEquals("Tavern", saves.get(0).getLocationName());
    }

    @Test
    void aGameCanBeLoadedBeforeOneHasStarted() {
        start(new FixedDiceRoller());
        act(ActionType.MOVE, "north");
        controller.saveGame("menu");
        String saved = stateJson();
        GameController fresh = new GameController(new ScriptedLLMClient(List.of()), new FixedDiceRoller(),
                new SaveManager(saveDirectory));
        RecordingView freshView = new RecordingView();
        fresh.addView(freshView);

        fresh.loadGame("menu");

        assertEquals(1, freshView.renders);
        assertEquals(saved, fresh.getState().createSnapshot().getStateJson());
        assertTrue(fresh.performAction(new ActionProposal(ActionType.MOVE, "south", null)).getResults().get(0).isSuccess());
    }

    @Test
    void aSaveHoldsOnlyTheGameNeverPromptsKeysOrSettings() throws IOException {
        playTheStory();
        controller.saveGame("story");

        String file = Files.readString(saveDirectory.resolve("story.json"));

        GameSnapshot snapshot = GameSnapshot.fromJson(file);
        assertEquals(List.of("version", "createdAt", "characterName", "locationName", "state"),
                List.copyOf(com.google.gson.JsonParser.parseString(file).getAsJsonObject().keySet()));
        assertEquals(List.of("turn", "currentLocation", "mode", "player", "world", "quests", "events", "memory"),
                List.copyOf(com.google.gson.JsonParser.parseString(snapshot.getStateJson()).getAsJsonObject().keySet()));
        for (String forbidden : List.of("API_KEY", "apiKey", "Bearer", "sk-", "gsk_", "LLM_PROVIDER", "deepseek", "groq",
                "You turn a text-adventure", "Answer with one JSON object", "{{")) {
            assertFalse(file.contains(forbidden), forbidden);
        }
    }

    private static List<String> describe(List<GameEvent> events) {
        return events.stream().map(event -> event.getTurn() + " " + event.getType() + " " + event.getTimestamp() + " "
                + event.getDescription() + " " + event.getSubjectId() + " " + event.getAlignmentDelta() + " "
                + event.getVerb()).toList();
    }
}
