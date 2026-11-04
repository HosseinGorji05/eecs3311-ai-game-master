package presentation;

import engine.core.ExplorationMode;
import engine.core.FixedDiceRoller;
import engine.core.GameState;
import engine.core.StoryMemory;
import engine.persistence.SaveManager;
import engine.events.GameEvent;
import engine.world.Direction;
import llm.ScriptedLLMClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliGameViewTest {

    @TempDir
    Path saveDirectory;

    private GameController controller;

    /** Plays the input lines through a CLI with the given model answers and dice; returns the output. */
    private String play(List<String> llmResponses, FixedDiceRoller dice, String... lines) {
        controller = new GameController(new ScriptedLLMClient(llmResponses), dice, new SaveManager(saveDirectory));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        String input = String.join("\n", lines) + "\n";
        CliGameView view = new CliGameView(controller,
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), out);
        controller.addView(view);
        view.readLoop();
        return bytes.toString(StandardCharsets.UTF_8);
    }

    /** The quest log of a new game: only the main quest. */
    private static final String MAIN_QUEST_ONLY = "Quests:\n- Recover the village relic (main quest, active): "
            + "defeat bandit leader 0/1, fetch village relic 0/1; reward: 50 gold, 50 XP\nVisited: ";

    private static void assertContains(String output, String expected) {
        assertTrue(output.contains(expected), "missing \"" + expected + "\" in:\n" + output);
    }

    @Test
    void aFullSessionCreatesACharacterMovesTakesTheKeyAttacksAndQuits() {
        String output = play(
                List.of("{\"actions\":[{\"type\":\"move\",\"target\":\"north\"}]}"),   // the model answers once
                new FixedDiceRoller(10, 5),   // Ari hits the wolf (10 + 5 = 15 vs 11); the wolf misses (5 + 3 vs 13)
                "Ari", "knight", "warrior",
                "go north",                    // understood by the model
                "take the shed key",           // offline from here on
                "go south and go south",
                "w",
                "attack wolf",
                "talk to the wolf",
                "save mygame",
                "history combat",
                "quit",
                "go east");                    // never read

        assertContains(output, "! Please choose warrior, rogue or mage.");
        assertContains(output, "Ari the warrior sets out.");
        assertContains(output, "Ari: 30/30 HP | Gold 0 | XP 0 | Carrying 3.5 of 10.0 kg");
        assertContains(output, "> Tavern\n");
        assertContains(output, "You take the shed key.");
        assertContains(output, "(Basic-command mode: simple commands only. Reason: LLM unavailable.)");
        assertContains(output, "Wolf Den\n");
        assertContains(output, "Enemies: wolf (8/8 HP)");
        assertContains(output, "Ari attacks wolf: rolls 10 + 5 = 15 against defense 11. Hit for 2 damage (wolf: 6/8 HP).");
        assertContains(output, "wolf attacks Ari: rolls 5 + 3 = 8 against defense 13. Miss.");
        assertContains(output, "That is not possible right now.");   // no talking in combat
        assertContains(output, "! You can't save during combat.");
        assertContains(output, "[turn 5] combat: Ari attacks wolf");
        assertTrue(output.endsWith("> Goodbye.\n"), output);
        assertTrue(controller.getRecentHistory(1).get(0).getDescription().startsWith("Rejected TALK"),
                "the line after quit is never played");

        GameState state = controller.getState();
        assertEquals("wolf-den", state.currentLocation().getId());
        assertNotNull(state.getPlayer().getInventory().find("shed key"));
        assertEquals(6, state.currentLocation().findEnemy("wolf").getHp());
        assertEquals(7, state.getTurn(), "six played turns, the rejected talk included");
    }

    @Test
    void aSessionEquipsTheSwordOpensTheShedAndKillsTheWolfWithIt() {
        String output = play(List.of(),
                // hit (10 + 5 = 15 vs 11) for 6; the wolf misses (5 + 3 vs 13); hit (12 + 5 = 17) for 6 more
                new FixedDiceRoller(10, 5, 12),
                "Ari", "warrior",
                "equip sword",
                "go north", "take the shed key", "go south",
                "go east",                     // the shed key opens the locked door
                "go west", "go south", "go west",
                "attack wolf",
                "inventory",                   // allowed in combat; the wolf gets no turn
                "attack wolf",
                "look",
                "quit");

        assertContains(output, "You equip the sword (damage 6).");
        assertContains(output, "You take the shed key.");
        assertContains(output, "You unlock the way with the shed key.\nTool Shed\n");
        assertContains(output, "Ari attacks wolf: rolls 10 + 5 = 15 against defense 11. Hit for 6 damage (wolf: 2/8 HP).");
        assertContains(output, "wolf attacks Ari: rolls 5 + 3 = 8 against defense 13. Miss.");
        assertContains(output, "Items: sword (3 kg), healing potion (0.5 kg), shed key (0.1 kg)\n"
                + "Weapon: sword (damage 6)\nYou carry 3.6 of 10 kg.");
        assertContains(output, "Ari attacks wolf: rolls 12 + 5 = 17 against defense 11. Hit for 6 damage (wolf: 0/8 HP).\n"
                + "The wolf is defeated.");
        assertContains(output, "\nWolf Den\nA rocky hollow littered with bones. Something growls in the dark.\n"
                + "Items: none\nExits: east (Forest Edge)\n");
        assertFalse(output.contains("! "), "no command was refused: " + output);
        assertTrue(output.endsWith("> Goodbye.\n"), output);

        GameState state = controller.getState();
        assertEquals("sword", state.getPlayer().getEquippedWeapon().getName());
        assertEquals(30, state.getPlayer().getHp());
        assertTrue(state.currentLocation().getEnemies().isEmpty());
        assertFalse(state.getWorld().findLocation("village-square").getExit(Direction.EAST).isLocked());
        assertEquals(13, state.getTurn(), "twelve played turns, look and inventory included");
    }

    @Test
    void anOfflineConversationWithTheTavernKeeperThenPlayGoesOn() {
        String output = play(List.of(), new FixedDiceRoller(),
                "Ari", "warrior",
                "go north",
                "talk to the tavern keeper",
                "Hello! Any news?",
                "go south",                    // said to Marta, not a move
                "map",
                "history dialogue",
                "leave",
                "take the shed key and go south",
                "quit");

        assertContains(output, "You start talking to Marta, the tavern keeper.\n"
                + "(You are talking to Marta. Say something, or type leave to end the conversation.)\n(to Marta) > ");
        assertContains(output, "(Marta answers with a pre-written line. Reason: LLM unavailable.)\n"
                + "Marta: \"Busy night. Ask what you need, I'm listening.\"");
        assertContains(output, "Marta: \"Folk say healing herbs still grow by the stream at the Old Mill.\"");
        assertContains(output, "Talking to Marta (tavern keeper).");
        assertContains(output, "[turn 2] dialogue: started talking to Marta\n"
                + "[turn 3] dialogue: Ari said \"Hello! Any news?\" to Marta; Marta replied \"Busy night.");
        assertContains(output, "You leave the conversation with Marta.\n> ");
        assertContains(output, "You take the shed key.\nVillage Square");
        assertTrue(output.endsWith("> Goodbye.\n"), output);

        GameState state = controller.getState();
        assertInstanceOf(ExplorationMode.class, state.getMode());
        assertEquals("village-square", state.currentLocation().getId());
        assertEquals(5, state.getTurn(), "move, talk, leave and the last line's turn; the dialogue lines are not turns");
    }

    @Test
    void aConversationWithAScriptedModelHandsOverTheIronKeyAndByeEndsIt() {
        String output = play(List.of(
                        "{\"actions\":[{\"type\":\"move\",\"target\":\"west\"}]}",
                        "{\"actions\":[{\"type\":\"talk\",\"target\":\"the blacksmith\"}]}",
                        "{\"text\":\"For the relic? Take it, and bring it back.\","
                                + "\"sideEffect\":{\"type\":\"take\",\"target\":\"iron key\"}}"),
                new FixedDiceRoller(),
                "Ari", "rogue",
                "head over to the forge",
                "I'd like a word with the blacksmith",
                "Could you lend me the iron key?",
                "bye",
                "inventory",
                "quit");

        assertContains(output, "You start talking to Brann, the blacksmith.");
        assertContains(output, "Brann: \"For the relic? Take it, and bring it back.\"\nBrann hands you the iron key.\n");
        assertFalse(output.contains("pre-written line"), output);
        assertContains(output, "You leave the conversation with Brann.");
        assertContains(output, "Items: dagger (1 kg), pouch (0.7 kg: healing potion (0.5 kg)), iron key (0.2 kg)");

        GameState state = controller.getState();
        assertNull(state.getPlayer().getInventory().find("iron key").getOwnerId());
        assertEquals(0, state.getEventLog().recent(100).stream().mapToInt(GameEvent::getAlignmentDelta).sum());
    }

    @Test
    void mapAndHistoryShowTheSituationAndUnknownHistoryTypesAreExplained() {
        String output = play(List.of(), new FixedDiceRoller(), "Vex", "rogue", "go north", "map", "history",
                "history dragons", "help", "quit");

        assertContains(output, "Visited: Village Square, Tavern");
        assertContains(output, MAIN_QUEST_ONLY);
        assertContains(output, "[turn 1] movement: moved to Tavern");
        assertContains(output, "! Unknown event type. Valid types: movement, item, combat, dialogue, quest, rejected.");
        assertContains(output, "Also: map, quests, journal, history, history <type>, help, quit.");
    }

    @Test
    void aDeathBeforeAnyQuestShowsTheShortDeathEndingAndTheSessionWaitsForALoadOrQuit() {
        String[] lines = new String[2 + 10 + 2];
        lines[0] = "Ari";
        lines[1] = "mage";   // 18 HP, defense 13: each wolf hit takes 3
        lines[2] = "go south";
        lines[3] = "go west";
        int[] rolls = new int[12];
        for (int round = 0; round < 6; round++) {
            lines[4 + round] = "attack wolf";
            rolls[2 * round] = 1;        // Ari misses
            rolls[2 * round + 1] = 20;   // the wolf hits
        }
        for (int i = 10; i < lines.length; i++) {
            lines[i] = "go east";
        }

        String output = play(List.of(), new FixedDiceRoller(rolls), lines);

        assertContains(output, "You die.\n(The epilogue is pre-written. Reason: the player died before completing any quest.)\n"
                + "=== The End: Death ending ===\n"
                + "Ari the mage died after 8 turns, before completing a single quest. The village relic stayed in the "
                + "bandits' hands.\n"
                + "Statistics: turns played 8, quests completed 0, enemies defeated 0.\n"
                + CliGameView.AFTER_ENDING + "\n> ! The game is over.\n");
        assertEquals(1, output.split("=== The End", -1).length - 1, "the ending is shown once");
        assertTrue(output.endsWith("> Goodbye.\n"), output);
        assertFalse(controller.getState().getPlayer().isAlive());
        assertEquals("wolf-den", controller.getState().currentLocation().getId());
    }

    @Test
    void saveSavesAndLoadWorkInAnOfflineSessionAndOverwritingAsksFirst() throws Exception {
        String output = play(List.of(), new FixedDiceRoller(),
                "Ari", "warrior",
                "saves",
                "save",
                "save ../x",
                "go north",
                "save Tavern",
                "saves",
                "go south",
                "save tavern", "no",
                "save tavern", "yes",
                "go west",
                "load tavern",
                "load nothing",
                "help",
                "quit");

        assertContains(output, "No saves yet.");
        assertContains(output, "! Type save <name>, e.g. save mygame.");
        assertContains(output, "! Could not save: A save name has 1 to 32 letters, digits, hyphens (-) or underscores (_).");
        assertContains(output, "> (Game saved.)\n");
        assertContains(output, "Saves (newest first):\n- tavern: Ari at Tavern, saved ");
        assertContains(output, "A save named \"tavern\" already exists. Overwrite it? (yes/no) "
                + "(Not saved. Type save with another name to keep both.)");
        assertContains(output, "Overwrite it? (yes/no) (Game saved.)");
        assertContains(output, "> Village Square\n");   // the loaded game is shown
        assertContains(output, "! Could not load: There is no save named \"nothing\".");
        assertContains(output, "save <name> saves the game (not during combat), load <name> continues a saved game, "
                + "saves lists them.");
        assertEquals("village-square", controller.getState().currentLocation().getId(), "the overwritten save was loaded");
        assertEquals(List.of("tavern.json"), Files.list(saveDirectory).map(path -> path.getFileName().toString()).toList());
    }

    @Test
    void aDeadPlayerCanLoadASaveAndPlayOn() {
        int[] rolls = new int[16];
        for (int i = 0; i < rolls.length; i += 2) {
            rolls[i] = 1;        // Ari misses
            rolls[i + 1] = 20;   // the wolf hits for 3 (Ari the mage has 18 HP)
        }
        String[] lines = {"Ari", "mage", "go south", "save edge", "go west",
                "attack wolf", "attack wolf", "attack wolf", "attack wolf", "attack wolf", "attack wolf",
                "go east", "load edge", "go north", "quit"};

        String output = play(List.of(), new FixedDiceRoller(rolls), lines);

        assertContains(output, "=== The End: Death ending ===");
        assertContains(output, CliGameView.AFTER_ENDING + "\n> ! The game is over.");
        assertTrue(controller.getState().getPlayer().isAlive());
        assertEquals("village-square", controller.getState().currentLocation().getId(), "played on after the load");
    }

    @Test
    void recoveringTheRelicShowsTheEndingAndAfterItOnlyReadingLoadingAndQuittingWork() {
        String output = play(List.of(), new FixedDiceRoller(20, 1, 20),   // Ari hits, the leader misses, Ari hits
                "Ari", "warrior",
                "equip sword",
                "go south",
                "save edge",
                "go south",
                "take village relic",
                "attack bandit leader",
                "attack bandit leader",
                "go north",
                "save late",
                "accept",
                "quests",
                "load edge",
                "new",
                "quit");

        assertContains(output, "Your quest: recover the village relic from the bandit leader. "
                + "After the ending, new starts a new game.");
        assertContains(output, "(The epilogue is pre-written. Reason: LLM unavailable.)\n"
                + "(Quest completed: Recover the village relic. Reward: 50 gold, 50 XP.)\n"
                + "=== The End: Neutral ending ===\n"
                + "Ari the warrior brought the village relic back from the Bandit Camp. In 6 turns, Ari completed 1 quest "
                + "and defeated 1 enemy.\n\n"
                + "The villagers were glad to have the relic home, though few could say what kind of person had returned "
                + "it. Ari moved on soon after, and the village went back to its quiet ways.\n"
                + "Statistics: turns played 6, quests completed 1, enemies defeated 1.\n"
                + CliGameView.AFTER_ENDING + "\n"
                + "> ! The game is over.\n"         // go north
                + "> ! The game is over.\n"         // save late
                + "> ! The game is over.\n");       // accept
        assertContains(output, "- Recover the village relic (main quest, completed): defeat bandit leader 1/1, "
                + "fetch village relic 1/1; reward: 50 gold, 50 XP\n");
        assertContains(output, "> Forest Edge\n");   // the save was loaded after the ending (UC11)
        assertContains(output, "> ! " + CliGameView.GAME_NOT_OVER + "\n");
        assertEquals(1, output.split("=== The End", -1).length - 1);

        GameState state = controller.getState();
        assertFalse(state.isGameOver());
        assertEquals("forest-edge", state.currentLocation().getId());
        assertEquals("Ari", state.getPlayer().getName(), "new was refused while the loaded game runs");
        assertNotNull(state.getWorld().findLocation("bandit-camp").findItem("village relic"));
    }

    @Test
    void afterTheEndingNewCreatesACharacterAndStartsANewGame() {
        int[] rolls = new int[12];
        for (int round = 0; round < 6; round++) {
            rolls[2 * round] = 1;        // Ari misses
            rolls[2 * round + 1] = 20;   // the wolf hits for 3 (Ari the mage has 18 HP)
        }
        String output = play(List.of(), new FixedDiceRoller(rolls),
                "Ari", "mage", "go south", "go west",
                "attack wolf", "attack wolf", "attack wolf", "attack wolf", "attack wolf", "attack wolf",
                "new", "", "Bo", "dragon", "rogue",
                "map", "go north", "quit");

        assertContains(output, "=== The End: Death ending ===");
        assertContains(output, CliGameView.AFTER_ENDING + "\n> Your name: Your name: Class (warrior, rogue, mage): "
                + "! Please choose warrior, rogue or mage.\nClass (warrior, rogue, mage): Bo the rogue sets out.\n"
                + "Village Square\n");
        assertContains(output, "Bo: 24/24 HP | Gold 0 | XP 0 | ");
        assertContains(output, MAIN_QUEST_ONLY + "Village Square\n");
        assertContains(output, "Reason: LLM unavailable.)\nTavern\n");

        GameState state = controller.getState();
        assertEquals("Bo", state.getPlayer().getName());
        assertEquals("tavern", state.currentLocation().getId());
        assertFalse(state.isGameOver());
        assertEquals(1, state.getEventLog().recent(100).size(), "only Bo's move is in the history");
    }

    @Test
    void theEndOfInputDuringCharacterCreationEndsQuietly() {
        String output = play(List.of(), new FixedDiceRoller(), "Ari");

        assertTrue(output.endsWith("Class (warrior, rogue, mage): Goodbye.\n"), output);
    }

    @Test
    void presetsGiveEveryClassAValidAllocation() {
        CliGameView.PRESET_STATS.values().forEach(stats -> assertTrue(stats.isValid()));
        assertEquals(3, CliGameView.PRESET_STATS.size());
    }

    @Test
    void aFullOfflineSessionAcceptsBrannsQuestPlaysItToTheEndAndShowsTheQuestLog() {
        String output = play(List.of(), new FixedDiceRoller(),
                "Ari", "warrior",
                "accept",                      // nothing on offer yet
                "go west",
                "talk to Brann",
                "Do you have any work for me?",
                "accept",
                "quests",
                "leave",
                "go east",
                "go north",
                "take shed key",
                "quests",
                "journal",
                "quit");

        assertContains(output, "! " + GameController.NO_OFFER);
        assertContains(output, "Brann offers you a quest: Find the shed key\n"
                + "  Brann needs the shed key from the Tavern. Find it and pick it up.\n"
                + "  Objectives: fetch shed key (1)\n"
                + "  Reward: 15 gold, 15 XP\n"
                + "Type accept or decline.\n");
        assertContains(output, "(Brann offers a pre-written quest. Reason: LLM unavailable.)");
        assertContains(output, "(Quest accepted: Find the shed key)");
        assertContains(output, "Quests:\n- Recover the village relic (main quest, active): defeat bandit leader 0/1, "
                + "fetch village relic 0/1; reward: 50 gold, 50 XP\n"
                + "- Find the shed key (active, from Brann): fetch shed key 0/1; reward: 15 gold, 15 XP\n");
        assertContains(output, "You take the shed key.\n(Quest completed: Find the shed key. Reward: 15 gold, 15 XP.)");
        assertContains(output, " HP | Gold 15 | XP 15 | ");
        assertContains(output, "- Find the shed key (completed, from Brann): fetch shed key 1/1; reward: 15 gold, 15 XP\n");
        assertContains(output, "Journal of Ari\nFacts:\n- Ari, a warrior, arrived at the Village Square in the Village "
                + "when the story began.\n");
        assertContains(output, "- Quest completed: Find the shed key. Reward: 15 gold, 15 XP.\nRecent events:\n");
        assertContains(output, "] took the shed key\n");
        assertEquals(1, output.split("Brann offers you a quest:", -1).length - 1, "one offer in the conversation");
    }

    @Test
    void aLongOfflineSessionSummarizesOlderEventsAndTheJournalShowsTheStorySoFar() {
        List<String> lines = new ArrayList<>(List.of("Ari", "warrior", "journal"));
        for (int i = 0; i < 23; i++) {
            lines.add(i % 2 == 0 ? "go north" : "go south");   // ends in the Tavern
        }
        lines.addAll(List.of("attack dragon", "take shed key", "talk to Marta", "hello", "leave", "journal", "quit"));

        String output = play(List.of(), new FixedDiceRoller(), lines.toArray(String[]::new));

        assertContains(output, "Journal of Ari\nFacts:\n- Ari, a warrior, arrived at the Village Square in the Village "
                + "when the story began.\n- Ari set out carrying: sword, healing potion.\n"
                + "- Marta the tavern keeper can be found at the Tavern.\n"
                + "- Brann the blacksmith can be found at the Blacksmith's Forge.\nThe story has just begun.\n");
        String journal = output.substring(output.lastIndexOf("Journal of Ari"));
        assertContains(journal, "Earlier:\n- Turns 1-10: moved to Tavern; moved to Village Square; moved to Tavern;");
        assertContains(journal, "Recent events:\n- [turn 11] moved to Tavern\n");
        assertContains(journal, "- [turn 25] took the shed key\n- [turn 26] started talking to Marta\n"
                + "- [turn 27] Ari said \"hello\" to Marta; Marta replied \"");
        assertContains(journal, "- [turn 27] left the conversation with Marta\n> Goodbye.");
        assertFalse(journal.contains("Rejected"), "a refused attempt is not part of the story");
        assertFalse(journal.contains("The story has just begun."));
        StoryMemory memory = controller.getState().getStoryMemory();
        assertEquals(1, memory.getSummaries().size());
        assertTrue(memory.recentCount() <= 20);
    }

    @Test
    void declineDiscardsTheOfferAndOnlyTheMainQuestIsInTheLog() {
        String output = play(List.of(), new FixedDiceRoller(),
                "Vex", "rogue", "go west", "talk to the blacksmith", "hello", "decline", "hello again", "accept",
                "quests", "quit");

        assertContains(output, "You decline \"Find the shed key\".");
        assertContains(output, "! " + GameController.NO_OFFER);
        assertContains(output, MAIN_QUEST_ONLY);
        assertEquals(1, output.split("Brann offers you a quest:", -1).length - 1, "no second offer in the conversation");
        assertTrue(controller.getState().getQuestLog().allQuests().stream().allMatch(quest -> quest.isMain()),
                "no side quest was added");
    }
}
