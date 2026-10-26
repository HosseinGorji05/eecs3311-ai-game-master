package engine.commands;

import engine.character.CharacterClass;
import engine.character.CharacterFactory;
import engine.character.Stats;
import engine.core.DiceRoller;
import engine.core.GameState;
import engine.core.RulesEngine;
import engine.events.EventType;
import engine.events.EventVerb;
import engine.events.GameEvent;
import engine.events.GameEventBus;
import engine.items.KeyItem;
import engine.world.Direction;
import engine.world.World;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The events that Move and Take produce, and the REJECTED events of commands that fail. */
class CommandEventsTest {

    private GameState state;
    private RulesEngine rules;
    private final List<GameEvent> published = new ArrayList<>();

    @BeforeEach
    void startGame() {
        state = new GameState();
        state.start(CharacterFactory.forClass(CharacterClass.WARRIOR).createCharacter("Ari", new Stats(4, 3, 3)),
                World.createDefault());
        GameEventBus bus = new GameEventBus();
        bus.subscribe(published::add);
        rules = new RulesEngine(new DiceRoller(0), bus);
    }

    private ActionResult run(GameCommand command) {
        return rules.executeAll(List.of(command), state).get(0);
    }

    private void assertOnlyRejected(ActionResult result, ActionType type) {
        assertFalse(result.isSuccess());
        assertEquals(1, published.size(), published.toString());
        GameEvent rejected = published.get(0);
        assertEquals(EventType.REJECTED, rejected.getType());
        assertEquals(type.name(), rejected.getSubjectId());
        assertEquals("Rejected " + type + ": " + result.getMessage(), rejected.getDescription());
        assertEquals(0, rejected.getAlignmentDelta());
        assertNull(rejected.getVerb());
        assertEquals(List.of(rejected), result.getEvents());
    }

    @Test
    void aSuccessfulMovePublishesOneMovementEvent() {
        ActionResult result = run(new MoveCommand(Direction.NORTH));

        assertTrue(result.isSuccess());
        assertEquals(1, published.size());
        GameEvent moved = published.get(0);
        assertEquals(EventType.MOVEMENT, moved.getType());
        assertEquals(EventVerb.MOVED, moved.getVerb());
        assertEquals("tavern", moved.getSubjectId());
        assertEquals("moved to Tavern", moved.getDescription());
        assertEquals(1, moved.getTurn());
        assertEquals(0, moved.getAlignmentDelta());
        assertEquals(List.of(moved), result.getEvents());
    }

    @Test
    void eventsCarryTheTurnTheyHappenedIn() {
        run(new MoveCommand(Direction.NORTH));
        run(new MoveCommand(Direction.SOUTH));

        assertEquals(List.of(1, 2), published.stream().map(GameEvent::getTurn).toList());
        assertEquals(3, state.getTurn());
    }

    @Test
    void aBlockedMovePublishesOnlyRejected() {
        assertOnlyRejected(run(new MoveCommand(Direction.EAST)), ActionType.MOVE);   // locked, no key
    }

    @Test
    void aMoveWithoutAnExitPublishesOnlyRejected() {
        state.setCurrentLocation(state.getWorld().findLocation("tavern"));
        assertOnlyRejected(run(new MoveCommand("Bandit Camp")), ActionType.MOVE);
    }

    @Test
    void aSuccessfulTakeOfAnUnownedItemPublishesAnItemEventWithoutAlignment() {
        state.setCurrentLocation(state.getWorld().findLocation("tavern"));

        ActionResult result = run(new TakeCommand("shed key"));

        assertEquals("You take the shed key.", result.getMessage());
        assertEquals(1, published.size());
        GameEvent took = published.get(0);
        assertEquals(EventType.ITEM, took.getType());
        assertEquals(EventVerb.TOOK, took.getVerb());
        assertEquals("shed key", took.getSubjectId());
        assertEquals("took the shed key", took.getDescription());
        assertEquals(0, took.getAlignmentDelta());
    }

    @Test
    void takingAnItemThatBelongsToSomeoneIsATheft() {
        state.currentLocation().addItem(new KeyItem("iron key", 0.2, "blacksmith"));

        ActionResult result = run(new TakeCommand("iron key"));

        assertTrue(result.isSuccess());
        assertEquals("You take the iron key. It belongs to blacksmith.", result.getMessage());
        GameEvent stole = published.get(0);
        assertEquals(EventType.ITEM, stole.getType());
        assertEquals(EventVerb.TOOK, stole.getVerb(), "a theft is still a take, so fetch quests count it");
        assertEquals("stole the iron key from blacksmith", stole.getDescription());
        assertEquals(-2, stole.getAlignmentDelta());
    }

    @Test
    void aTakeThatIsTooHeavyPublishesOnlyRejected() {
        state.setCurrentLocation(state.getWorld().findLocation("forge"));
        assertOnlyRejected(run(new TakeCommand("warhammer")), ActionType.TAKE);
    }

    @Test
    void aTakeOfAMissingItemPublishesOnlyRejectedAndNoTheft() {
        state.currentLocation().addItem(new KeyItem("iron key", 0.2, "blacksmith"));
        state.setCurrentLocation(state.getWorld().findLocation("tavern"));

        assertOnlyRejected(run(new TakeCommand("iron key")), ActionType.TAKE);
    }

    @Test
    void anActionTheModeDoesNotAllowPublishesRejected() {
        GameCommand leave = new GameCommand() {
            @Override
            public ActionType getType() {
                return ActionType.LEAVE;
            }

            @Override
            public ValidationResult validate(GameState s) {
                return new ValidationResult(true, null);
            }

            @Override
            public ActionResult execute(GameState s, RulesEngine r) {
                return new ActionResult(true, "left");
            }
        };

        ActionResult result = run(leave);

        assertEquals("That is not possible right now.", result.getMessage());
        assertOnlyRejected(result, ActionType.LEAVE);
    }

    @Test
    void eachCommandOfATurnPublishesItsOwnEventsInOrder() {
        List<GameCommand> turn = List.of(new MoveCommand(Direction.EAST), new MoveCommand(Direction.NORTH),
                new TakeCommand("shed key"), new MoveCommand(Direction.SOUTH), new MoveCommand("Tool Shed"));

        rules.executeAll(turn, state);

        assertEquals(List.of(EventType.REJECTED, EventType.MOVEMENT, EventType.ITEM, EventType.MOVEMENT,
                EventType.MOVEMENT), published.stream().map(GameEvent::getType).toList());
        assertEquals("tool-shed", published.get(4).getSubjectId());
    }
}
