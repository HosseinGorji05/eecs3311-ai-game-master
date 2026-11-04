package presentation;

import engine.character.CharacterClass;
import engine.character.CharacterFactory;
import engine.character.Stats;
import engine.core.CombatMode;
import engine.core.DialogueMode;
import engine.core.Ending;
import engine.core.EndingType;
import engine.core.GameState;
import engine.persistence.SaveSlotInfo;
import engine.quests.Objective;
import engine.quests.ObjectiveType;
import engine.quests.Quest;
import engine.quests.Reward;
import engine.world.World;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The texts of the status area, the save dialog, the end screen and the story panel, without a display. */
class GuiTextTest {

    private final GameState state = new GameState();
    private final World world = World.createDefault();

    private void start() {
        state.start(CharacterFactory.forClass(CharacterClass.ROGUE).createCharacter("Ari", new Stats(2, 6, 2)), world);
    }

    @Test
    void theStatusLineNamesTheCharacterHpGoldXpLocationAndTurn() {
        start();
        state.getPlayer().takeDamage(4);
        state.nextTurn();

        assertEquals("Ari the Rogue | HP 20/24 | Gold 0 | XP 0 | Village Square | Turn 2", GuiText.statusLine(state));
    }

    @Test
    void beforeAGameTheStatusSaysSo() {
        assertEquals(GuiText.NO_GAME, GuiText.statusLine(state));
        assertEquals("", GuiText.modeLine(state));
        assertEquals(GuiText.COMMAND_PROMPT, GuiText.prompt(state));
    }

    @Test
    void theModeLineAndPromptFollowCombatAndConversation() {
        start();
        assertEquals("", GuiText.modeLine(state));

        state.setMode(new CombatMode(world.findLocation("wolf-den").findEnemy("wolf")));
        assertEquals("In combat with the wolf (8/8 HP).", GuiText.modeLine(state));
        assertEquals(GuiText.COMMAND_PROMPT, GuiText.prompt(state));

        state.setMode(new DialogueMode(world.findNpc("blacksmith")));
        assertEquals("Talking to Brann (blacksmith).", GuiText.modeLine(state));
        assertEquals("Say to Brann:", GuiText.prompt(state));

        state.getPlayer().takeDamage(100);
        assertEquals(GameController.GAME_OVER, GuiText.modeLine(state));
    }

    @Test
    void weightsUseOneDecimalWithoutATrailingZero() {
        assertEquals("9.5 of 10 kg", GuiText.weightText(9.5, 10));
        assertEquals("0 of 10 kg", GuiText.weightText(0, 10));
        assertEquals("1.7", GuiText.kg(1.7000000000000002));
    }

    @Test
    void aSaveSlotShowsItsNameCharacterLocationAndTime() {
        SaveSlotInfo slot = new SaveSlotInfo("mygame", "Ari", "Tavern", Instant.parse("2026-11-04T10:30:00Z"));

        assertEquals("mygame: Ari at Tavern, saved 2026-11-04 10:30", GuiText.slotLabel(slot, ZoneOffset.UTC));
        assertEquals("mygame: Ari at Tavern, saved 2026-11-04 05:30", GuiText.slotLabel(slot, ZoneOffset.ofHours(-5)));
    }

    @Test
    void theEndScreenShowsTheTypeAndTheStatisticsInOrder() {
        Map<String, Integer> statistics = new LinkedHashMap<>();
        statistics.put("turns played", 12);
        statistics.put("quests completed", 1);
        statistics.put("enemies defeated", 2);
        Ending ending = new Ending(EndingType.HEROIC, statistics);

        assertEquals("The End: Heroic ending", GuiText.endingTitle(EndingType.HEROIC));
        assertEquals("The End: Death ending", GuiText.endingTitle(EndingType.DEATH));
        assertEquals("Turns played: 12 | Quests completed: 1 | Enemies defeated: 2", GuiText.statistics(ending));
    }

    @Test
    void aQuestOfferIsShownWithTheClisLines() {
        Quest quest = new Quest("q1", "Bread for Brann", "Bring Brann some bread.", "blacksmith", false,
                List.of(new Objective(ObjectiveType.FETCH, "bread", 1)), new Reward(15, 15, null));

        assertEquals("Brann offers you a quest: Bread for Brann\n  Bring Brann some bread.\n  Objectives: fetch bread (1)"
                + "\n  Reward: 15 gold, 15 XP\nType accept or decline.", GuiText.questOffer(quest, world));
    }

    @Test
    void htmlSpecialCharactersAreEscaped() {
        assertEquals("a &lt;b&gt; &amp; &quot;c&quot;", GuiText.escapeHtml("a <b> & \"c\""));
    }
}
