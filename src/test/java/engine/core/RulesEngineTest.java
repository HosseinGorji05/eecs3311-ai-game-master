package engine.core;

import engine.character.CharacterClass;
import engine.character.CharacterFactory;
import engine.character.Stats;
import engine.commands.ActionProposal;
import engine.commands.ActionResult;
import engine.commands.ActionType;
import engine.commands.CommandFactory;
import engine.commands.GameCommand;
import engine.commands.ValidationResult;
import engine.events.GameEventBus;
import engine.world.Direction;
import engine.world.World;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RulesEngineTest {

    /** Test double that records how the engine calls it. */
    private static final class RecordingCommand implements GameCommand {
        private final ActionType type;
        private final ValidationResult validation;
        int validateCalls;
        int executeCalls;
        RulesEngine rulesSeen;

        RecordingCommand(ActionType type, ValidationResult validation) {
            this.type = type;
            this.validation = validation;
        }

        @Override
        public ActionType getType() {
            return type;
        }

        @Override
        public ValidationResult validate(GameState state) {
            validateCalls++;
            return validation;
        }

        @Override
        public ActionResult execute(GameState state, RulesEngine rules) {
            executeCalls++;
            rulesSeen = rules;
            return new ActionResult(true, "done");
        }
    }

    private final RulesEngine rules = new RulesEngine(new DiceRoller(0), new GameEventBus());
    private GameState state;

    @BeforeEach
    void startGame() {
        state = new GameState();
        state.start(CharacterFactory.forClass(CharacterClass.WARRIOR).createCharacter("Ari", new Stats(4, 3, 3)),
                World.createDefault());
    }

    @Test
    void validCommandIsExecutedWithThisEngine() {
        RecordingCommand command = new RecordingCommand(ActionType.LOOK, new ValidationResult(true, null));

        List<ActionResult> results = rules.executeAll(List.of(command), state);

        assertEquals(1, command.validateCalls);
        assertEquals(1, command.executeCalls);
        assertSame(rules, command.rulesSeen);
        assertTrue(results.get(0).isSuccess());
        assertEquals("done", results.get(0).getMessage());
    }

    @Test
    void invalidCommandIsRejectedWithItsReasonAndNeverExecuted() {
        RecordingCommand command = new RecordingCommand(ActionType.TAKE,
                new ValidationResult(false, "There is no dragon egg here."));

        ActionResult result = rules.executeAll(List.of(command), state).get(0);

        assertEquals(1, command.validateCalls);
        assertEquals(0, command.executeCalls);
        assertFalse(result.isSuccess());
        assertEquals("There is no dragon egg here.", result.getMessage());
    }

    @Test
    void commandTheModeDoesNotAllowIsRejectedBeforeValidation() {
        RecordingCommand leave = new RecordingCommand(ActionType.LEAVE, new ValidationResult(true, null));

        ActionResult result = rules.executeAll(List.of(leave), state).get(0);

        assertEquals(0, leave.validateCalls);
        assertEquals(0, leave.executeCalls);
        assertFalse(result.isSuccess());
        assertEquals("That is not possible right now.", result.getMessage());
    }

    @Test
    void theTurnAdvancesOncePerCallWhateverTheOutcome() {
        RecordingCommand ok = new RecordingCommand(ActionType.LOOK, new ValidationResult(true, null));
        RecordingCommand bad = new RecordingCommand(ActionType.TAKE, new ValidationResult(false, "No."));

        rules.executeAll(List.of(ok, bad), state);
        assertEquals(2, state.getTurn());

        rules.executeAll(List.of(), state);
        assertEquals(3, state.getTurn());
    }

    @Test
    void commandsRunInOrderAndEachSeesTheEffectsOfTheOneBefore() {
        CommandFactory factory = new CommandFactory();
        List<GameCommand> turn = List.of(
                factory.create(new ActionProposal(ActionType.MOVE, "north", null)),
                factory.create(new ActionProposal(ActionType.TAKE, "shed key", null)),
                factory.create(new ActionProposal(ActionType.MOVE, "south", null)),
                factory.create(new ActionProposal(ActionType.MOVE, "Tool Shed", null)));

        List<ActionResult> results = rules.executeAll(turn, state);

        assertEquals(4, results.size());
        results.forEach(result -> assertTrue(result.isSuccess(), result.getMessage()));
        assertEquals("tool-shed", state.currentLocation().getId());
        assertFalse(state.getWorld().getStartLocation().getExit(Direction.EAST).isLocked());
        assertNotNull(state.getPlayer().getInventory().find("shed key"));
    }

    @Test
    void aRejectedCommandDoesNotStopTheRest() {
        CommandFactory factory = new CommandFactory();
        List<GameCommand> turn = List.of(
                factory.create(new ActionProposal(ActionType.MOVE, "east", null)),
                factory.create(new ActionProposal(ActionType.MOVE, "south", null)));

        List<ActionResult> results = rules.executeAll(turn, state);

        assertFalse(results.get(0).isSuccess());
        assertTrue(results.get(0).getMessage().contains("You need the shed key."));
        assertTrue(results.get(1).isSuccess());
        assertEquals("forest-edge", state.currentLocation().getId());
    }

    @Test
    void nullArgumentsAreRejected() {
        assertThrows(NullPointerException.class, () -> rules.executeAll(null, state));
        assertThrows(NullPointerException.class, () -> rules.executeAll(List.of(), null));
    }

    @Test
    void resultsAndValidationsKeepTheirValues() {
        ValidationResult invalid = new ValidationResult(false, "Too heavy.");
        assertFalse(invalid.isValid());
        assertEquals("Too heavy.", invalid.getReason());
        assertThrows(IllegalArgumentException.class, () -> new ValidationResult(false, null));
        assertThrows(IllegalArgumentException.class, () -> new ValidationResult(false, " "));
        assertThrows(NullPointerException.class, () -> new ActionResult(true, null));
    }
}
