package engine.world;

import engine.items.KeyItem;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldTest {

    private static List<Location> allLocations(World world) {
        List<Location> locations = new ArrayList<>();
        for (Region region : world.getRegions()) {
            locations.addAll(region.getLocations());
        }
        return locations;
    }

    /** Ids of the locations reachable from the start, optionally only through open exits. */
    private static Set<String> reachable(World world, boolean openExitsOnly) {
        Set<String> seen = new HashSet<>();
        Deque<Location> todo = new ArrayDeque<>(List.of(world.getStartLocation()));
        while (!todo.isEmpty()) {
            Location location = todo.pop();
            if (seen.add(location.getId())) {
                for (Exit exit : location.getExits()) {
                    if (!openExitsOnly || !exit.isLocked()) {
                        todo.push(exit.getDestination());
                    }
                }
            }
        }
        return seen;
    }

    @Test
    void defaultWorldHasTheVillageAndTheForestWithEightLocations() {
        World world = World.createDefault();

        assertEquals(List.of("Village", "Forest"), world.getRegions().stream().map(Region::getName).toList());
        assertEquals(8, allLocations(world).size());
        assertEquals("village-square", world.getStartLocation().getId());
    }

    @Test
    void everyLocationIsReachableFromTheStart() {
        World world = World.createDefault();
        assertEquals(8, reachable(world, false).size());
    }

    @Test
    void everyExitHasAWayBack() {
        for (Location location : allLocations(World.createDefault())) {
            for (Exit exit : location.getExits()) {
                assertNotNull(exit.getDestination().findExitTo(location.getName()),
                        "no way back from " + exit.getDestination().getName() + " to " + location.getName());
            }
        }
    }

    @Test
    void exactlyOneExitIsLockedAndItIsTheToolShedDoor() {
        World world = World.createDefault();
        List<Exit> locked = allLocations(world).stream()
                .flatMap(location -> location.getExits().stream())
                .filter(Exit::isLocked)
                .toList();

        assertEquals(1, locked.size());
        Exit door = locked.get(0);
        assertSame(door, world.getStartLocation().getExit(Direction.EAST));
        assertSame(world.findLocation("tool-shed"), door.getDestination());
        assertEquals("shed key", door.getRequiredKeyName());
    }

    @Test
    void theShedKeyLiesInALocationReachableWithoutPassingTheLockedDoor() {
        World world = World.createDefault();
        Set<String> open = reachable(world, true);
        assertFalse(open.contains("tool-shed"), "the shed is only reachable through its locked door");

        Location keyLocation = allLocations(world).stream()
                .filter(location -> location.findItem("shed key") != null)
                .findFirst()
                .orElseThrow();
        assertInstanceOf(KeyItem.class, keyLocation.findItem("shed key"));
        assertTrue(open.contains(keyLocation.getId()), "shed key is in " + keyLocation.getId());
    }

    @Test
    void theKeyInTheWorldOpensTheLockedDoor() {
        World world = World.createDefault();
        Exit door = world.getStartLocation().getExit(Direction.EAST);
        KeyItem key = (KeyItem) world.findLocation("tavern").findItem("shed key");

        assertTrue(door.unlock(key));
        assertFalse(door.isLocked());
    }

    @Test
    void eachCallCreatesAFreshWorld() {
        World first = World.createDefault();
        World second = World.createDefault();
        first.getStartLocation().getExit(Direction.EAST).unlock(new KeyItem("shed key", 0.1));

        assertTrue(second.getStartLocation().getExit(Direction.EAST).isLocked());
        assertNotSame(first.getStartLocation(), second.getStartLocation());
    }

    @Test
    void findLocationLooksUpByIdAcrossRegions() {
        World world = World.createDefault();
        assertEquals("Bandit Camp", world.findLocation("bandit-camp").getName());
        assertEquals("Tavern", world.findLocation("tavern").getName());
        assertNull(world.findLocation("moon"));
        assertNull(world.findLocation(null));
    }

    @Test
    void summarizeListsEveryLocationWithItsItemsAndExits() {
        World world = World.createDefault();
        String summary = world.summarize();

        for (Location location : allLocations(world)) {
            assertTrue(summary.contains(location.getName() + " [" + location.getId() + "]"), location.getId());
        }
        assertTrue(summary.startsWith("Village:\n"), summary);
        assertTrue(summary.contains("\nForest:\n"), summary);
        assertTrue(summary.contains("- Tavern [tavern]; items: shed key, bread; exits: south (Village Square)\n"),
                summary);
    }

    @Test
    void invalidWorldsAreRejected() {
        Location a = new Location("a", "A", "First.");
        Location b = new Location("b", "B", "Second.");
        Location duplicate = new Location("a", "A again", "Duplicate id.");
        Region region = new Region("Region", List.of(a, b));

        assertThrows(IllegalArgumentException.class, () -> new World(List.of(), a));
        assertThrows(IllegalArgumentException.class,
                () -> new World(List.of(region), new Location("c", "C", "Not in any region.")));
        assertThrows(IllegalArgumentException.class,
                () -> new World(List.of(region, new Region("Other", List.of(duplicate))), a));
        assertThrows(IllegalArgumentException.class, () -> new Region("Empty", List.of()));
        assertThrows(IllegalArgumentException.class, () -> new Region(" ", List.of(a)));
        assertThrows(NullPointerException.class, () -> new World(null, a));
        assertThrows(NullPointerException.class, () -> new World(List.of(region), null));
    }
}
