package presentation;

import engine.core.FixedDiceRoller;
import engine.core.GameState;
import llm.ScriptedLLMClient;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliGameViewTest {

    private GameController controller;

    /** Plays the input lines through a CLI with the given model answers and dice; returns the output. */
    private String play(List<String> llmResponses, FixedDiceRoller dice, String... lines) {
        controller = new GameController(new ScriptedLLMClient(llmResponses), dice);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        String input = String.join("\n", lines) + "\n";
        CliGameView view = new CliGameView(controller,
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), out);
        controller.addView(view);
        view.readLoop();
        return bytes.toString(StandardCharsets.UTF_8);
    }

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
                "drop shed key",
                "save mygame",
                "history combat",
                "quit",
                "go east");                    // never read

        assertContains(output, "! Please choose warrior, rogue or mage.");
        assertContains(output, "Ari the warrior sets out.");
        assertContains(output, "Ari: 30/30 HP | Gold 0 | XP 0 | Carrying 3.5 of 10.0 kg");
        assertContains(output, "> Tavern\n");
        assertContains(output, "You take the shed key.");
        assertContains(output, "(Basic-command mode: simple commands only.)");
        assertContains(output, "Wolf Den\n");
        assertContains(output, "Enemies: wolf (8/8 HP)");
        assertContains(output, "Ari attacks wolf: rolls 10 + 5 = 15 against defense 11. Hit for 2 damage (wolf: 6/8 HP).");
        assertContains(output, "wolf attacks Ari: rolls 5 + 3 = 8 against defense 13. Miss.");
        assertContains(output, "! You can't drop yet: that action is not available in this version.");
        assertContains(output, "! \"save\" is not available yet.");
        assertContains(output, "[turn 5] combat: Ari attacks wolf");
        assertTrue(output.endsWith("> Goodbye.\n"), output);
        assertTrue(controller.getRecentHistory(1).get(0).getDescription().startsWith("wolf attacks Ari"),
                "the line after quit is never played");

        GameState state = controller.getState();
        assertEquals("wolf-den", state.currentLocation().getId());
        assertNotNull(state.getPlayer().getInventory().find("shed key"));
        assertEquals(6, state.currentLocation().findEnemy("wolf").getHp());
        assertEquals(6, state.getTurn(), "five played turns; the rejected drop did not use a turn");
    }

    @Test
    void mapAndHistoryShowTheSituationAndUnknownHistoryTypesAreExplained() {
        String output = play(List.of(), new FixedDiceRoller(), "Vex", "rogue", "go north", "map", "history",
                "history dragons", "help", "quit");

        assertContains(output, "Visited: Village Square, Tavern");
        assertContains(output, "Quests: none");
        assertContains(output, "[turn 1] movement: moved to Tavern");
        assertContains(output, "! Unknown event type. Valid types: movement, item, combat, dialogue, quest, rejected.");
        assertContains(output, "Also: map, quests, history, history <type>, help, quit.");
    }

    @Test
    void theSessionEndsWhenThePlayerDies() {
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

        assertContains(output, "You die.");
        assertContains(output, "You have died. Game over.\nGoodbye.");
        assertFalse(controller.getState().getPlayer().isAlive());
        assertEquals("wolf-den", controller.getState().currentLocation().getId());
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
}
