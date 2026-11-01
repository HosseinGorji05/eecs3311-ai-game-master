package engine.commands;

import engine.character.CharacterClass;
import engine.character.Enemy;
import engine.core.DialogueMode;
import engine.core.ExplorationMode;
import engine.events.EventType;
import engine.events.GameEvent;
import engine.world.Direction;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** TalkCommand and LeaveCommand, and what the player may do while talking. */
class DialogueCommandsTest {

    private static CommandTestGame talkingToMarta() {
        CommandTestGame game = new CommandTestGame(CharacterClass.WARRIOR, "tavern");
        assertTrue(game.run(new TalkCommand("Marta")).isSuccess());
        game.published.clear();
        return game;
    }

    @Test
    void typesAreTalkAndLeave() {
        assertEquals(ActionType.TALK, new TalkCommand("Marta").getType());
        assertEquals(ActionType.LEAVE, new LeaveCommand().getType());
    }

    @Test
    void talkingToAnNpcHereStartsAConversation() {
        CommandTestGame game = new CommandTestGame(CharacterClass.WARRIOR, "tavern");

        ActionResult result = game.run(new TalkCommand("tavern keeper"));

        assertTrue(result.isSuccess());
        assertEquals("You start talking to Marta, the tavern keeper.", result.getMessage());
        DialogueMode mode = assertInstanceOf(DialogueMode.class, game.state.getMode());
        assertEquals("Marta", mode.getNpc().getName());
        assertEquals(2, game.state.getTurn());
        GameEvent started = game.published.get(0);
        assertEquals(EventType.DIALOGUE, started.getType());
        assertEquals("started talking to Marta", started.getDescription());
        assertEquals("tavern-keeper", started.getSubjectId());
        assertEquals(0, started.getAlignmentDelta());
        assertEquals(game.published, result.getEvents());
    }

    @Test
    void anNpcThatIsNotHereCannotBeTalkedTo() {
        CommandTestGame game = new CommandTestGame(CharacterClass.WARRIOR, "tavern");
        game.assertRejectedWithoutChange(new TalkCommand("Brann"), "There is no Brann here.");
        game.assertRejectedWithoutChange(new TalkCommand("wolf"), "There is no wolf here.");
    }

    @Test
    void talkingAgainWhileTalkingIsRejected() {
        CommandTestGame game = talkingToMarta();

        game.assertRejectedWithoutChange(new TalkCommand("Marta"), "That is not possible right now.");
        assertEquals("You are already talking to Marta.", new TalkCommand("Marta").validate(game.state).getReason());
    }

    @Test
    void talkingIsNotPossibleInCombat() {
        CommandTestGame game = new CommandTestGame(CharacterClass.WARRIOR, "tavern");
        game.state.currentLocation().addEnemy(new Enemy("rat", 2, 1, 5));
        game.fight("rat");

        game.assertRejectedWithoutChange(new TalkCommand("Marta"), "That is not possible right now.");
    }

    @Test
    void leavingEndsTheConversation() {
        CommandTestGame game = talkingToMarta();

        ActionResult result = game.run(new LeaveCommand());

        assertTrue(result.isSuccess());
        assertEquals("You leave the conversation with Marta.", result.getMessage());
        assertInstanceOf(ExplorationMode.class, game.state.getMode());
        assertEquals(List.of(EventType.DIALOGUE), game.publishedTypes());
        assertEquals("left the conversation with Marta", game.published.get(0).getDescription());
        assertEquals("tavern-keeper", game.published.get(0).getSubjectId());
        assertTrue(game.run(new MoveCommand(Direction.SOUTH)).isSuccess(), "exploring again");
    }

    @Test
    void leavingIsOnlyPossibleInAConversation() {
        CommandTestGame game = new CommandTestGame(CharacterClass.WARRIOR, "tavern");

        game.assertRejectedWithoutChange(new LeaveCommand(), "That is not possible right now.");
        assertEquals("You are not talking to anyone.", new LeaveCommand().validate(game.state).getReason());
    }

    @Test
    void lookAndInventoryWorkWhileTalking() {
        CommandTestGame game = talkingToMarta();

        assertTrue(game.run(new LookCommand()).getMessage().contains("People: Marta (tavern keeper)"));
        assertTrue(game.run(new InventoryCommand()).isSuccess());
        assertInstanceOf(DialogueMode.class, game.state.getMode());
    }

    @Test
    void movingFightingAndUsingItemsMustWaitUntilTheConversationEnds() {
        CommandTestGame game = talkingToMarta();
        game.ari.takeDamage(5);

        for (GameCommand command : List.of(new MoveCommand(Direction.SOUTH), new EquipCommand("sword"),
                new UseItemCommand("healing potion"), new DropCommand("sword"), new PutCommand("sword", "pouch"),
                new AttackCommand("Marta"), new FleeCommand())) {
            game.assertRejectedWithoutChange(command, "That is not possible right now.");
        }
    }

    @Test
    void takingWhileTalkingOnlyAcceptsWhatTheNpcOwns() {
        CommandTestGame game = talkingToMarta();

        game.assertRejectedWithoutChange(new TakeCommand("bread"), "The bread is not Marta's to give.");
        game.assertRejectedWithoutChange(new TakeCommand("dragon"), "There is no dragon here.");
    }
}
