package engine.commands;

import engine.character.CharacterClass;
import engine.character.CharacterFactory;
import engine.character.Enemy;
import engine.character.Player;
import engine.character.Stats;
import engine.core.CombatMode;
import engine.core.ExplorationMode;
import engine.core.FixedDiceRoller;
import engine.core.GameState;
import engine.core.RulesEngine;
import engine.events.EventType;
import engine.events.EventVerb;
import engine.events.GameEvent;
import engine.events.GameEventBus;
import engine.world.Location;
import engine.world.World;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ari (agility 3, defense 13, 30 HP) tries to flee from the wolf (attack 3): d20 + 3 must reach 12. */
class FleeCommandTest {

    private GameState state;
    private Player ari;
    private Location den;
    private Enemy wolf;
    private CombatMode combat;
    private final List<GameEvent> published = new ArrayList<>();
    private FixedDiceRoller dice;

    @BeforeEach
    void startFight() {
        World world = World.createDefault();
        ari = CharacterFactory.forClass(CharacterClass.WARRIOR).createCharacter("Ari", new Stats(4, 3, 3));
        state = new GameState();
        state.start(ari, world);
        den = world.findLocation("wolf-den");
        state.setCurrentLocation(den);
        wolf = den.findEnemy("wolf");
        combat = new CombatMode(wolf);
        state.setMode(combat);
    }

    private ActionResult run(GameCommand command, int... rolls) {
        dice = new FixedDiceRoller(rolls);
        GameEventBus bus = new GameEventBus();
        bus.subscribe(published::add);
        ActionResult result = new RulesEngine(dice, bus).executeAll(List.of(command), state).get(0);
        assertEquals(0, dice.remaining(), "every planned roll is used");
        return result;
    }

    @Test
    void typeIsFlee() {
        assertEquals(ActionType.FLEE, new FleeCommand().getType());
    }

    @Test
    void reachingDifficultyTwelveEscapesAndReturnsToExploration() {
        ActionResult result = run(new FleeCommand(), 9);   // 9 + 3 = 12

        assertTrue(result.isSuccess());
        assertEquals("You escape from the wolf.", result.getMessage());
        assertInstanceOf(ExplorationMode.class, state.getMode());
        assertSame(den, state.currentLocation(), "fleeing ends the fight, not the stay");
        assertSame(wolf, den.findEnemy("wolf"));
        assertEquals(30, ari.getHp());
        assertEquals(1, published.size());
        GameEvent fled = published.get(0);
        assertEquals(EventType.COMBAT, fled.getType());
        assertEquals(EventVerb.FLED, fled.getVerb());
        assertEquals("wolf", fled.getSubjectId());
        assertEquals("fled from the wolf", fled.getDescription());
    }

    @Test
    void missingDifficultyTwelveByOneGivesTheEnemyItsTurn() {
        ActionResult result = run(new FleeCommand(), 8, 10);   // 8 + 3 = 11 fails; the wolf hits 13 >= 13

        assertFalse(result.isSuccess());
        assertSame(combat, state.getMode());
        assertEquals(27, ari.getHp());
        assertEquals(List.of("failed to flee from the wolf",
                        "wolf attacks Ari: rolls 10 + 3 = 13 against defense 13. Hit for 3 damage (Ari: 27/30 HP)."),
                published.stream().map(GameEvent::getDescription).toList());
        assertTrue(published.stream().allMatch(event -> event.getType() == EventType.COMBAT),
                "a failed flee is a combat outcome, not a rejected command");
    }

    @Test
    void higherAgilityMakesFleeingEasier() {
        Player rogue = CharacterFactory.forClass(CharacterClass.ROGUE).createCharacter("Vex", new Stats(1, 8, 1));
        state.start(rogue, state.getWorld());
        state.setCurrentLocation(den);
        state.setMode(new CombatMode(wolf));

        assertTrue(run(new FleeCommand(), 4).isSuccess());   // 4 + 8 = 12
    }

    @Test
    void aFailedFleeCanKillThePlayer() {
        ari.takeDamage(29);

        run(new FleeCommand(), 1, 20);

        assertFalse(ari.isAlive());
        assertEquals(EventVerb.DIED, published.get(published.size() - 1).getVerb());
    }

    @Test
    void fleeingOutsideCombatIsRejectedWithoutRollingOrChangingAnything() {
        state.setMode(new ExplorationMode());
        String before = StateFingerprint.of(state);

        ActionResult result = run(new FleeCommand());

        assertFalse(result.isSuccess());
        assertEquals("That is not possible right now.", result.getMessage());
        assertEquals(before, StateFingerprint.of(state));
        assertEquals(List.of(EventType.REJECTED), published.stream().map(GameEvent::getType).toList());
    }

    @Test
    void validateAndExecuteRefuseOutsideCombat() {
        state.setMode(new ExplorationMode());
        FleeCommand flee = new FleeCommand();

        assertEquals("You are not in a fight.", flee.validate(state).getReason());
        assertThrows(IllegalStateException.class,
                () -> flee.execute(state, new RulesEngine(new FixedDiceRoller(), new GameEventBus())));
    }

    @Test
    void anAttackThenAFleeGoesIntoCombatAndBackOut() {
        state.setMode(new ExplorationMode());

        run(new AttackCommand("wolf"), 10, 5);
        assertInstanceOf(CombatMode.class, state.getMode());
        assertFalse(state.canSave());

        run(new FleeCommand(), 15);
        assertInstanceOf(ExplorationMode.class, state.getMode());
        assertTrue(state.canSave());
    }
}
