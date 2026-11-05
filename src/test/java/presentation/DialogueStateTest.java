package presentation;

import engine.character.CharacterClass;
import engine.character.CharacterFactory;
import engine.character.Stats;
import engine.core.CombatMode;
import engine.core.DialogueMode;
import engine.core.ExplorationMode;
import engine.core.GameState;
import engine.quests.Objective;
import engine.quests.ObjectiveType;
import engine.quests.Quest;
import engine.quests.Reward;
import engine.world.World;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The dialogue panel's state, its conversation lines and the quest offer card (F03, F07), without a display. */
class DialogueStateTest {

    private final GameState state = new GameState();
    private final World world = World.createDefault();

    DialogueStateTest() {
        state.start(CharacterFactory.forClass(CharacterClass.ROGUE).createCharacter("Ari", new Stats(2, 6, 2)), world);
        state.setCurrentLocation(world.findLocation("tavern"));
    }

    @Test
    void thePanelIsShownOnlyInAConversation() {
        assertFalse(DialogueState.of(state).talking());

        DialogueMode talk = new DialogueMode(world.findNpc("tavern-keeper"));
        state.setMode(talk);
        DialogueState dialogue = DialogueState.of(state);

        assertTrue(dialogue.talking());
        assertSame(talk, dialogue.conversation());
        assertEquals("Marta, the tavern keeper", dialogue.heading());
        assertEquals("M", dialogue.initial());

        state.setMode(new CombatMode(world.findLocation("wolf-den").findEnemy("wolf")));
        assertFalse(DialogueState.of(state).talking());
        assertEquals("", DialogueState.NONE.heading());
    }

    @Test
    void thePeopleHereCanBeTalkedToByName() {
        assertEquals(List.of(new DialogueState.Person("Marta", "Marta (tavern keeper)")), DialogueState.peopleHere(state));

        state.setCurrentLocation(world.findLocation("old-mill"));
        assertEquals(List.of(), DialogueState.peopleHere(state));
        assertEquals(List.of(), DialogueState.peopleHere(new GameState()));
    }

    @Test
    void aConversationStartsFreshForEachNewConversationAndClearsWhenItEnds() {
        ConversationLog log = new ConversationLog();
        state.setMode(new DialogueMode(world.findNpc("tavern-keeper")));
        DialogueState first = DialogueState.of(state);

        assertTrue(log.follow(first, false));
        log.addPlayerLine("Hello");
        log.addNpcLine("Marta", "Busy night.");
        assertFalse(log.follow(first, false), "same conversation, nothing changes");
        assertEquals(List.of("You are talking to Marta (tavern keeper).", "You: Hello", "Marta: Busy night."), log.getLines());

        state.setMode(new ExplorationMode());
        assertTrue(log.follow(DialogueState.of(state), false));
        assertFalse(log.isActive());
        assertEquals("", log.text());

        state.setMode(new DialogueMode(world.findNpc("tavern-keeper")));
        assertTrue(log.follow(DialogueState.of(state), false));
        assertEquals(List.of("You are talking to Marta (tavern keeper)."), log.getLines(), "a new conversation");
    }

    @Test
    void aConversationFromALoadedSaveOpensWithANote() {
        ConversationLog log = new ConversationLog();
        state.setMode(new DialogueMode(world.findNpc("tavern-keeper")));

        log.follow(DialogueState.of(state), true);

        assertEquals(List.of(ConversationLog.CONTINUED), log.getLines());
    }

    @Test
    void theQuestCardShowsTheOfferAndEscapesItsText() {
        Quest quest = new Quest("q1", "Wolves & <bandits>", "Clear the den.", "blacksmith", false,
                List.of(new Objective(ObjectiveType.DEFEAT, "wolf", 1), new Objective(ObjectiveType.FETCH, "bread", 2)),
                new Reward(25, 25, "iron key"));

        DialogueState.QuestCard card = DialogueState.QuestCard.of(quest, world);

        assertEquals(new DialogueState.QuestCard("Brann", "Wolves & <bandits>", "Clear the den.",
                List.of("Defeat wolf", "Fetch bread (2)"), "25 gold, 25 XP, the iron key"), card);
        assertTrue(card.toHtml().startsWith("<html><b>Wolves &amp; &lt;bandits&gt;</b>"), card.toHtml());
        assertTrue(card.toHtml().contains("&bull; Fetch bread (2)"), card.toHtml());
    }
}
