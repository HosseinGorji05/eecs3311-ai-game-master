package engine.core;

import engine.character.CharacterClass;
import engine.character.Enemy;
import engine.character.Player;
import engine.character.Stats;
import engine.commands.ActionResult;
import engine.events.GameEventBus;
import engine.items.Weapon;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RulesEngineCombatTest {

    /** Strength 4 (attack bonus 4), agility 3 (defense 13), unarmed (damage 2). */
    private static Player ari() {
        return new Player("Ari", CharacterClass.WARRIOR, new Stats(4, 3, 3), 30, List.of());
    }

    private static Enemy wolf() {
        return new Enemy("wolf", 8, 3, 11);
    }

    private static RulesEngine rules(int... rolls) {
        return new RulesEngine(new FixedDiceRoller(rolls), new GameEventBus());
    }

    @Test
    void aRollThatReachesTheDefenseHitsAndDealsTheAttackersDamage() {
        Enemy wolf = wolf();

        ActionResult result = rules(10).resolveAttack(ari(), wolf);   // 10 + 4 = 14 >= 11

        assertTrue(result.isSuccess());
        assertEquals("Ari attacks wolf: rolls 10 + 4 = 14 against defense 11. Hit for 2 damage (wolf: 6/8 HP).",
                result.getMessage());
        assertEquals(6, wolf.getHp());
        assertTrue(result.getEvents().isEmpty(), "the calling command records the events");
    }

    @Test
    void exactlyTheDefenseIsAHit() {
        Enemy wolf = wolf();
        assertTrue(rules(7).resolveAttack(ari(), wolf).isSuccess());   // 7 + 4 = 11
        assertEquals(6, wolf.getHp());
    }

    @Test
    void oneBelowTheDefenseIsAMissAndChangesNothing() {
        Enemy wolf = wolf();

        ActionResult result = rules(6).resolveAttack(ari(), wolf);   // 6 + 4 = 10 < 11

        assertFalse(result.isSuccess());
        assertEquals("Ari attacks wolf: rolls 6 + 4 = 10 against defense 11. Miss.", result.getMessage());
        assertEquals(8, wolf.getHp());
    }

    @Test
    void thereAreNoSpecialRolls() {
        Enemy armoured = new Enemy("golem", 8, 0, 30);
        assertFalse(rules(20).resolveAttack(ari(), armoured).isSuccess(), "a natural 20 is not an automatic hit");

        Enemy defenceless = new Enemy("rat", 8, 0, 0);
        assertTrue(rules(1).resolveAttack(ari(), defenceless).isSuccess(), "a natural 1 is not an automatic miss");
        assertEquals(6, defenceless.getHp(), "and no critical damage");
    }

    @Test
    void anEnemyHitsThePlayerWithItsAttackAsDamage() {
        Player ari = ari();

        ActionResult result = rules(10).resolveAttack(wolf(), ari);   // 10 + 3 = 13 >= 13

        assertTrue(result.isSuccess());
        assertEquals(27, ari.getHp());
    }

    @Test
    void anEquippedWeaponDealsItsDamage() {
        Player ari = ari();
        ari.equip(new Weapon("sword", 3.0, 6));
        Enemy wolf = wolf();

        rules(15).resolveAttack(ari, wolf);

        assertEquals(2, wolf.getHp());
    }

    @Test
    void hpNeverDropsBelowZero() {
        Enemy wolf = new Enemy("wolf", 1, 3, 11);
        rules(20).resolveAttack(ari(), wolf);
        assertEquals(0, wolf.getHp());
        assertFalse(wolf.isAlive());
    }

    @Test
    void skillCheckSucceedsWhenTheRollPlusTheStatReachesTheDifficulty() {
        assertTrue(rules(9).skillCheck(3, 12));    // 9 + 3 = 12
        assertTrue(rules(20).skillCheck(3, 12));
        assertFalse(rules(8).skillCheck(3, 12));   // 8 + 3 = 11
        assertFalse(rules(1).skillCheck(3, 12));
    }

    @Test
    void eachAttackAndCheckRollsExactlyOneDie() {
        FixedDiceRoller dice = new FixedDiceRoller(10, 10, 10);
        RulesEngine rules = new RulesEngine(dice, new GameEventBus());

        rules.resolveAttack(ari(), wolf());
        rules.skillCheck(3, 12);

        assertEquals(1, dice.remaining());
    }

    @Test
    void nullArgumentsAreRejected() {
        RulesEngine rules = rules(10);
        assertThrows(NullPointerException.class, () -> rules.resolveAttack(null, wolf()));
        assertThrows(NullPointerException.class, () -> rules.resolveAttack(ari(), null));
        assertThrows(NullPointerException.class, () -> new RulesEngine(null, new GameEventBus()));
        assertThrows(NullPointerException.class, () -> new RulesEngine(new DiceRoller(1), null));
    }
}
