package engine.world;

import engine.character.Enemy;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocationEnemyTest {

    private static Location den() {
        return new Location("wolf-den", "Wolf Den", "A rocky hollow.");
    }

    @Test
    void enemiesCanBeAddedFoundIgnoringCaseAndRemoved() {
        Location den = den();
        Enemy wolf = new Enemy("wolf", 8, 3, 11);
        den.addEnemy(wolf);

        assertSame(wolf, den.findEnemy(" WOLF "));
        assertNull(den.findEnemy("bear"));
        assertNull(den.findEnemy(null));
        assertEquals(List.of(wolf), den.getEnemies());

        den.removeEnemy(wolf);

        assertNull(den.findEnemy("wolf"));
        assertTrue(den.getEnemies().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> den.getEnemies().add(wolf));
        assertThrows(NullPointerException.class, () -> den.addEnemy(null));
    }

    @Test
    void describeListsEnemiesOnlyWhenThereAreAny() {
        Location den = den();
        assertEquals("Wolf Den\nA rocky hollow.\nItems: none\nExits: none", den.describe());

        Enemy wolf = new Enemy("wolf", 8, 3, 11);
        den.addEnemy(wolf);
        wolf.takeDamage(3);

        assertEquals("Wolf Den\nA rocky hollow.\nItems: none\nEnemies: wolf (5/8 HP)\nExits: none", den.describe());
    }

    @Test
    void theDefaultWorldHasAWolfAndTheBanditLeaderInTheForestOnly() {
        World world = World.createDefault();

        Enemy wolf = world.findLocation("wolf-den").findEnemy("wolf");
        assertEquals(List.of(8, 3, 11), List.of(wolf.getMaxHp(), wolf.attackBonus(), wolf.defense()));
        Enemy leader = world.findLocation("bandit-camp").findEnemy("bandit leader");
        assertEquals(List.of(12, 4, 12), List.of(leader.getMaxHp(), leader.attackBonus(), leader.defense()));

        List<Enemy> villageEnemies = new ArrayList<>();
        for (Location location : world.getRegions().get(0).getLocations()) {
            villageEnemies.addAll(location.getEnemies());
        }
        assertEquals("Village", world.getRegions().get(0).getName());
        assertTrue(villageEnemies.isEmpty());
        assertFalse(world.getStartLocation().describe().contains("Enemies"));
    }

    @Test
    void summarizeMentionsTheEnemies() {
        String summary = World.createDefault().summarize();
        assertTrue(summary.contains("- Wolf Den [wolf-den]; items: none; enemies: wolf (8/8 HP); exits: east (Forest Edge)\n"),
                summary);
        assertTrue(summary.contains("enemies: bandit leader (12/12 HP)"), summary);
    }
}
