package engine.commands;

import engine.character.CharacterClass;
import engine.character.CharacterFactory;
import engine.character.Enemy;
import engine.character.Player;
import engine.character.Stats;
import engine.core.CombatMode;
import engine.core.ExplorationMode;
import engine.core.FixedDiceRoller;
import engine.core.GameMode;
import engine.core.GameState;
import engine.core.RulesEngine;
import engine.events.EventType;
import engine.events.EventVerb;
import engine.events.GameEvent;
import engine.events.GameEventBus;
import engine.items.Weapon;
import engine.world.Direction;
import engine.world.Location;
import engine.world.World;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One round of combat in the Wolf Den. Ari is a warrior with strength 4 (attack bonus 4), agility 3
 * (defense 13), unarmed (damage 2) and 30 HP; the wolf has 8 HP, attack 3 and defense 11.
 */
class AttackCommandTest {

    private GameState state;
    private Player ari;
    private Location den;
    private Enemy wolf;
    private final List<GameEvent> published = new ArrayList<>();
    private FixedDiceRoller dice;

    @BeforeEach
    void startGame() {
        World world = World.createDefault();
        ari = CharacterFactory.forClass(CharacterClass.WARRIOR).createCharacter("Ari", new Stats(4, 3, 3));
        state = new GameState();
        state.start(ari, world);
        den = world.findLocation("wolf-den");
        state.setCurrentLocation(den);
        wolf = den.findEnemy("wolf");
    }

    private List<ActionResult> run(List<GameCommand> commands, int... rolls) {
        dice = new FixedDiceRoller(rolls);
        GameEventBus bus = new GameEventBus();
        bus.subscribe(published::add);
        return new RulesEngine(dice, bus).executeAll(commands, state);
    }

    private ActionResult run(GameCommand command, int... rolls) {
        ActionResult result = run(List.of(command), rolls).get(0);
        assertEquals(0, dice.remaining(), "every planned roll is used");
        return result;
    }

    private void assertRejectedWithoutChange(GameCommand command, String expectedMessage) {
        String before = StateFingerprint.of(state);
        int turn = state.getTurn();

        ActionResult result = run(command);   // no rolls planned: a rejected command must not roll

        assertFalse(result.isSuccess());
        assertEquals(expectedMessage, result.getMessage());
        assertEquals(before, StateFingerprint.of(state));
        assertEquals(turn + 1, state.getTurn());
        assertEquals(List.of(EventType.REJECTED), published.stream().map(GameEvent::getType).toList());
    }

    @Test
    void typeIsAttack() {
        assertEquals(ActionType.ATTACK, new AttackCommand("wolf").getType());
    }

    @Test
    void aHitStartsCombatDamagesTheEnemyAndTheEnemyAttacksBack() {
        ActionResult result = run(new AttackCommand("Wolf"), 10, 5);   // 14 >= 11 hit; 5 + 3 = 8 < 13 miss

        assertTrue(result.isSuccess());
        CombatMode combat = assertInstanceOf(CombatMode.class, state.getMode());
        assertSame(wolf, combat.getEnemy());
        assertEquals(6, wolf.getHp());
        assertEquals(30, ari.getHp());
        assertEquals(List.of(
                "Ari attacks wolf: rolls 10 + 4 = 14 against defense 11. Hit for 2 damage (wolf: 6/8 HP).",
                "wolf attacks Ari: rolls 5 + 3 = 8 against defense 13. Miss."), published.stream()
                .map(GameEvent::getDescription).toList());
        assertEquals(List.of("wolf", "Ari"), published.stream().map(GameEvent::getSubjectId).toList());
        published.forEach(event -> {
            assertEquals(EventType.COMBAT, event.getType());
            assertEquals(1, event.getTurn());
            assertNull(event.getVerb());
        });
        assertEquals(published, result.getEvents());
        assertEquals(String.join("\n", published.stream().map(GameEvent::getDescription).toList()),
                result.getMessage());
    }

    @Test
    void aMissLeavesTheEnemyUnhurtAndThePlayerTakesDamage() {
        ActionResult result = run(new AttackCommand("wolf"), 6, 10);   // 10 < 11 miss; 10 + 3 = 13 hit

        assertTrue(result.isSuccess(), "the attack happened, even though it missed");
        assertEquals(8, wolf.getHp());
        assertEquals(27, ari.getHp());
        assertInstanceOf(CombatMode.class, state.getMode());
    }

    @Test
    void theFightContinuesInTheSameCombatMode() {
        run(new AttackCommand("wolf"), 10, 5);
        GameMode combat = state.getMode();

        run(new AttackCommand("wolf"), 10, 5);

        assertSame(combat, state.getMode());
        assertEquals(4, wolf.getHp());
    }

    @Test
    void killingTheEnemyRemovesItRestoresExplorationAndPublishesItsDefeat() {
        ari.equip(new Weapon("sword", 3.0, 6));
        run(new AttackCommand("wolf"), 15, 1);   // hit for 6 (2/8 HP); the wolf misses
        published.clear();

        ActionResult result = run(new AttackCommand("wolf"), 15);   // hit for 6: dead, no counter-attack

        assertTrue(result.isSuccess());
        assertFalse(wolf.isAlive());
        assertNull(den.findEnemy("wolf"));
        assertInstanceOf(ExplorationMode.class, state.getMode());
        assertEquals(2, published.size());
        GameEvent defeated = published.get(1);
        assertEquals(EventType.COMBAT, defeated.getType());
        assertEquals(EventVerb.DEFEATED, defeated.getVerb());
        assertEquals("wolf", defeated.getSubjectId());
        assertEquals("wolf defeated", defeated.getDescription());
        assertTrue(result.getMessage().endsWith("The wolf is defeated."), result.getMessage());
    }

    @Test
    void anEnemyKilledByTheFirstBlowStillEndsInExploration() {
        Enemy rat = new Enemy("rat", 2, 1, 5);
        den.addEnemy(rat);

        run(new AttackCommand("rat"), 3);

        assertNull(den.findEnemy("rat"));
        assertInstanceOf(ExplorationMode.class, state.getMode());
        assertEquals(EventVerb.DEFEATED, published.get(1).getVerb());
    }

    @Test
    void thePlayerCanDieAndTheDeathIsPublished() {
        ari.takeDamage(28);   // 2 HP left

        ActionResult result = run(new AttackCommand("wolf"), 1, 20);   // miss; the wolf hits for 3

        assertFalse(ari.isAlive());
        assertEquals(0, ari.getHp());
        GameEvent died = published.get(2);
        assertEquals(EventType.COMBAT, died.getType());
        assertEquals(EventVerb.DIED, died.getVerb());
        assertEquals("Ari", died.getSubjectId());
        assertEquals("Ari died", died.getDescription());
        assertTrue(result.getMessage().endsWith("You die."), result.getMessage());
        assertInstanceOf(CombatMode.class, state.getMode(), "game over is decided by the controller");
    }

    @Test
    void attackingAnEnemyThatIsNotHereIsRejectedWithoutRollingOrChangingAnything() {
        state.setCurrentLocation(state.getWorld().findLocation("tavern"));
        assertRejectedWithoutChange(new AttackCommand("wolf"), "There is no wolf here.");
        assertInstanceOf(ExplorationMode.class, state.getMode());
    }

    @Test
    void duringAFightOnlyTheCurrentEnemyCanBeAttacked() {
        den.addEnemy(new Enemy("rat", 2, 1, 5));
        run(new AttackCommand("wolf"), 10, 5);
        published.clear();

        assertRejectedWithoutChange(new AttackCommand("rat"), "You are fighting the wolf.");
    }

    @Test
    void nonCombatActionsAreRejectedDuringAFight() {
        run(new AttackCommand("wolf"), 10, 5);
        published.clear();
        assertRejectedWithoutChange(new MoveCommand(Direction.EAST), "That is not possible right now.");
        published.clear();
        den.addItem(new Weapon("bone", 0.5, 1));
        assertRejectedWithoutChange(new TakeCommand("bone"), "That is not possible right now.");
    }

    @Test
    void executeWithoutTheEnemyIsRefused() {
        AttackCommand attack = new AttackCommand("bear");
        assertThrows(IllegalStateException.class,
                () -> attack.execute(state, new RulesEngine(new FixedDiceRoller(), new GameEventBus())));
    }

    @Test
    void theFactoryCreatesAttackAndFleeCommands() {
        CommandFactory factory = new CommandFactory();
        assertInstanceOf(AttackCommand.class, factory.create(new ActionProposal(ActionType.ATTACK, "wolf", null)));
        assertInstanceOf(FleeCommand.class, factory.create(new ActionProposal(ActionType.FLEE, null, null)));
        assertThrows(IllegalArgumentException.class, () -> new AttackCommand(" "));
        assertThrows(NullPointerException.class, () -> new AttackCommand(null));
    }
}
