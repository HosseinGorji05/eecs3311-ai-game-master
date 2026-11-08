package presentation;

import engine.character.CharacterClass;
import engine.character.Player;
import engine.character.StatType;
import engine.character.Stats;
import engine.core.Ending;
import engine.core.FixedDiceRoller;
import engine.core.GameState;
import engine.items.Item;
import engine.persistence.SaveManager;
import engine.quests.Quest;
import llm.LLMClient;
import llm.LlmRequest;
import llm.LlmResponse;
import llm.ScriptedLLMClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The backstory step of character creation through the controller and the CLI (F01, UC01, SD01). */
class GameControllerBackstoryTest {

    private static final String FIRST = "Ari grew up in a river town. Ari learned to pick locks early. Ari left after"
            + " a bad debt.";
    private static final String SECOND = "Ari was raised by smugglers on the coast. Ari learned to keep quiet. Ari"
            + " came inland to start again.";
    private static final String BOASTFUL = "Ari carries the legendary Sword of Dawn and a sack of 500 gold. Ari has the"
            + " strength of ten men and cannot die. Ari already knows every secret of the village.";

    @TempDir
    Path saves;

    private final List<String> shown = new ArrayList<>();

    private final GameView view = new GameView() {
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
        }

        @Override
        public void showEnding(Ending ending) {
        }
    };

    private static String reply(String backstory) {
        return "{\"backstory\":\"" + backstory + "\"}";
    }

    private GameController controller(LLMClient client) {
        GameController controller = new GameController(client, new FixedDiceRoller(), new SaveManager(saves));
        controller.addView(view);
        return controller;
    }

    private GameController controller(String... replies) {
        return controller(new ScriptedLLMClient(List.of(replies)));
    }

    @Test
    void createWriteRegenerateAndStartWithTheLastBackstory() {
        GameController controller = controller(reply(FIRST), reply(SECOND));
        Player ari = controller.newGame("Ari", CharacterClass.ROGUE, new Stats(2, 6, 2));

        String first = controller.regenerateBackstory();
        String second = controller.regenerateBackstory();
        controller.startGame();

        assertEquals(FIRST, first);
        assertEquals(SECOND, second);
        assertNotEquals(first, second);
        assertSame(ari, controller.getState().getPlayer());
        assertEquals(SECOND, controller.getState().getPlayer().getBackstory());
        assertEquals(List.of("render"), shown, "no notice: the model wrote both");
    }

    @Test
    void withoutAModelTheClassTemplateIsUsedWithANoticeAndTheGameStarts() {
        GameController controller = controller();
        controller.newGame("Bo", CharacterClass.WARRIOR, new Stats(5, 3, 2));

        String backstory = controller.regenerateBackstory();
        controller.startGame();

        assertTrue(backstory.startsWith("Bo grew up hauling iron"), backstory);
        assertEquals(List.of("notice: The backstory is pre-written. Reason: LLM unavailable.", "render"), shown);
        assertEquals(backstory, controller.getState().getPlayer().getBackstory());
    }

    @Test
    void aTimeoutOrInvalidOutputAlsoGivesTheTemplateWithTheReason() {
        LLMClient timingOut = new LLMClient() {
            @Override
            public LlmResponse complete(LlmRequest request) {
                return new LlmResponse(null, false, "timeout");
            }

            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String providerName() {
                return "slow";
            }
        };
        GameController slow = controller(timingOut);
        slow.newGame("Cy", CharacterClass.MAGE, new Stats(2, 3, 5));
        slow.regenerateBackstory();
        GameController confused = controller("{\"backstory\":\"Too short.\"}", "not a story {");
        confused.newGame("Cy", CharacterClass.MAGE, new Stats(2, 3, 5));
        String template = confused.regenerateBackstory();
        confused.startGame();

        assertTrue(shown.contains("notice: The backstory is pre-written. Reason: LLM unavailable: timeout."), shown.toString());
        assertTrue(shown.contains("notice: The backstory is pre-written. Reason: invalid output."), shown.toString());
        assertTrue(template.startsWith("Cy spent long years copying spells"), template);
        assertEquals(template, confused.getState().getPlayer().getBackstory());
    }

    @Test
    void theBackstoryStepCanBeSkipped() {
        GameController controller = controller();
        controller.newGame("Ari", CharacterClass.ROGUE, new Stats(2, 6, 2));

        controller.startGame();

        assertNull(controller.getState().getPlayer().getBackstory());
        assertEquals(List.of("render"), shown);
    }

    @Test
    void thereIsNothingToRegenerateBeforeACharacterIsCreatedOrAfterItStarted() {
        GameController controller = controller();
        assertThrows(IllegalStateException.class, controller::regenerateBackstory);

        controller.newGame(" ", CharacterClass.ROGUE, new Stats(2, 6, 2));
        assertThrows(IllegalStateException.class, controller::regenerateBackstory, "the name was refused");

        controller.newGame("Ari", CharacterClass.ROGUE, new Stats(2, 6, 2));
        controller.startGame();
        assertThrows(IllegalStateException.class, controller::regenerateBackstory, "the game has started");
    }

    @Test
    void theBackstoryIsFlavourOnlyWhateverItClaims() {
        GameController controller = controller(reply(BOASTFUL));
        Player ari = controller.newGame("Ari", CharacterClass.WARRIOR, new Stats(5, 3, 2));
        String before = fingerprint(ari);

        assertEquals(BOASTFUL, controller.regenerateBackstory());
        assertEquals(before, fingerprint(ari));
        controller.startGame();

        assertEquals(before, fingerprint(controller.getState().getPlayer()));
        assertEquals(30, ari.getHp());
        assertEquals(0, ari.getGold());
        assertEquals(0, ari.getXp());
        assertNull(ari.getInventory().find("Sword of Dawn"));
    }

    private static String fingerprint(Player player) {
        StringBuilder text = new StringBuilder();
        for (StatType stat : StatType.values()) {
            text.append(player.getStats().modifier(stat)).append('|');
        }
        text.append(player.getHp()).append('/').append(player.getMaxHp())
                .append('|').append(player.getGold()).append('|').append(player.getXp()).append('|')
                .append(player.attackBonus()).append('|').append(player.defense()).append('|').append(player.damage())
                .append('|').append(player.getEquippedWeapon()).append('|').append(player.getInventory().totalWeight());
        for (Item item : player.getInventory().getItems()) {
            text.append('|').append(item.describe());
        }
        return text.toString();
    }

    @Test
    void aSavedGameKeepsTheBackstory() {
        GameController controller = controller(reply(FIRST));
        controller.newGame("Ari", CharacterClass.ROGUE, new Stats(2, 6, 2));
        controller.regenerateBackstory();
        controller.startGame();
        controller.saveGame("story");

        GameController other = controller();
        other.loadGame("story");

        assertEquals(FIRST, other.getState().getPlayer().getBackstory());
    }

    @Test
    void theCliShowsTheBackstoryRegeneratesOnRequestAndStarts() {
        GameController controller = controller(reply(FIRST), reply(SECOND));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CliGameView cli = new CliGameView(controller,
                new ByteArrayInputStream("Ari\nrogue\nregenerate\n\nquit\n".getBytes(StandardCharsets.UTF_8)),
                new PrintStream(bytes, true, StandardCharsets.UTF_8));
        controller.addView(cli);

        cli.readLoop();

        String output = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("Your backstory:\n" + FIRST + "\n" + CliGameView.BACKSTORY_CHOICE
                + "Your backstory:\n" + SECOND + "\n" + CliGameView.BACKSTORY_CHOICE + "Ari the rogue sets out.\n"), output);
        assertEquals(SECOND, controller.getState().getPlayer().getBackstory());
        assertTrue(output.endsWith("Goodbye.\n"), output);
    }

    @Test
    void inTheCliACommandTypedAtTheBackstoryPromptStartsTheGameAndIsPlayed() {
        GameController controller = controller();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CliGameView cli = new CliGameView(controller,
                new ByteArrayInputStream("Ari\nrogue\ngo north\nquit\n".getBytes(StandardCharsets.UTF_8)),
                new PrintStream(bytes, true, StandardCharsets.UTF_8));
        controller.addView(cli);

        cli.readLoop();

        String output = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("(The backstory is pre-written. Reason: LLM unavailable.)\nYour backstory:\nAri learned early"),
                output);
        assertEquals("tavern", controller.getState().currentLocation().getId());
        assertTrue(output.contains("Ari the rogue sets out.\n"), output);
        assertTrue(output.contains("> (Basic-command mode: simple commands only. Reason: LLM unavailable.)\nTavern\n"), output);
    }
}
