package engine.commands;

import engine.character.CharacterClass;
import engine.character.CharacterFactory;
import engine.character.Stats;
import engine.core.GameState;
import engine.core.RulesEngine;
import engine.world.World;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandFactoryTest {

    private final CommandFactory factory = new CommandFactory();
    private GameState state;

    @BeforeEach
    void startGame() {
        state = new GameState();
        state.start(CharacterFactory.forClass(CharacterClass.MAGE).createCharacter("Ari", new Stats(2, 3, 5)),
                World.createDefault());
    }

    private String runAndReportLocation(GameCommand command) {
        ActionResult result = new RulesEngine().executeAll(List.of(command), state).get(0);
        assertTrue(result.isSuccess(), result.getMessage());
        return state.currentLocation().getId();
    }

    @ParameterizedTest
    @ValueSource(strings = {"north", "NORTH", " North "})
    void moveWithADirectionTargetCreatesAMoveInThatDirection(String target) {
        GameCommand command = factory.create(new ActionProposal(ActionType.MOVE, target, null));

        assertInstanceOf(MoveCommand.class, command);
        assertEquals(ActionType.MOVE, command.getType());
        assertEquals("tavern", runAndReportLocation(command));
    }

    @Test
    void moveWithALocationNameCreatesAMoveToThatLocation() {
        GameCommand command = factory.create(new ActionProposal(ActionType.MOVE, "Blacksmith's Forge", null));

        assertInstanceOf(MoveCommand.class, command);
        assertEquals("forge", runAndReportLocation(command));
    }

    @Test
    void moveToALockedLocationIsStillCreatedAndThenRejectedByValidation() {
        GameCommand command = factory.create(new ActionProposal(ActionType.MOVE, "Tool Shed", null));

        assertInstanceOf(MoveCommand.class, command);
        ValidationResult validation = command.validate(state);
        assertFalse(validation.isValid());
        assertTrue(validation.getReason().contains("You need the shed key."), validation.getReason());
    }

    @Test
    void takeCreatesATakeCommandForTheTarget() {
        state.setCurrentLocation(state.getWorld().findLocation("tavern"));

        GameCommand command = factory.create(new ActionProposal(ActionType.TAKE, "shed key", null));

        assertInstanceOf(TakeCommand.class, command);
        assertEquals(ActionType.TAKE, command.getType());
        assertTrue(command.validate(state).isValid());
    }

    @Test
    void creatingACommandDoesNotChangeTheState() {
        String before = StateFingerprint.of(state);

        factory.create(new ActionProposal(ActionType.MOVE, "east", null));
        factory.create(new ActionProposal(ActionType.TAKE, "warhammer", null));

        assertEquals(before, StateFingerprint.of(state));
    }

    @ParameterizedTest
    @EnumSource(value = ActionType.class, names = {"MOVE", "TAKE"}, mode = EnumSource.Mode.EXCLUDE)
    void typesWithoutACommandYetAreRejected(ActionType type) {
        ActionProposal proposal = new ActionProposal(type, "something", null);
        assertThrows(IllegalArgumentException.class, () -> factory.create(proposal));
    }

    @ParameterizedTest
    @EnumSource(value = ActionType.class, names = {"MOVE", "TAKE"})
    void missingOrBlankTargetIsRejected(ActionType type) {
        assertThrows(IllegalArgumentException.class, () -> factory.create(new ActionProposal(type, null, null)));
        assertThrows(IllegalArgumentException.class, () -> factory.create(new ActionProposal(type, "  ", null)));
    }

    @Test
    void nullProposalOrTypeIsRejected() {
        assertThrows(NullPointerException.class, () -> factory.create(null));
        assertThrows(NullPointerException.class, () -> new ActionProposal(null, "north", null));
    }

    @Test
    void proposalKeepsItsTypeAndTargets() {
        ActionProposal proposal = new ActionProposal(ActionType.PUT, "potion", "pouch");
        assertEquals(ActionType.PUT, proposal.getType());
        assertEquals("potion", proposal.getTarget());
        assertEquals("pouch", proposal.getSecondTarget());
    }
}
