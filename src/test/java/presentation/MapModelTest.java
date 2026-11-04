package presentation;

import engine.character.CharacterClass;
import engine.character.CharacterFactory;
import engine.character.Stats;
import engine.core.GameState;
import engine.world.Direction;
import engine.world.Exit;
import engine.world.Location;
import engine.world.Region;
import engine.world.World;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The map panel's layout from the real exits and what it shows (F06), without a display. */
class MapModelTest {

    private static GameState startedGame() {
        GameState state = new GameState();
        state.start(CharacterFactory.forClass(CharacterClass.ROGUE).createCharacter("Ari", new Stats(2, 6, 2)),
                World.createDefault());
        return state;
    }

    @Test
    void theDefaultWorldIsLaidOutLikeItsDiagram() {
        Map<String, MapModel.Cell> cells = MapModel.layout(World.createDefault());

        assertEquals(new MapModel.Cell(1, 0), cells.get("tavern"));
        assertEquals(new MapModel.Cell(0, 1), cells.get("forge"));
        assertEquals(new MapModel.Cell(1, 1), cells.get("village-square"));
        assertEquals(new MapModel.Cell(2, 1), cells.get("tool-shed"));
        assertEquals(new MapModel.Cell(0, 2), cells.get("wolf-den"));
        assertEquals(new MapModel.Cell(1, 2), cells.get("forest-edge"));
        assertEquals(new MapModel.Cell(2, 2), cells.get("old-mill"));
        assertEquals(new MapModel.Cell(1, 3), cells.get("bandit-camp"));
        assertEquals(8, cells.size());
        assertEquals(8, cells.values().stream().distinct().count());
    }

    @Test
    void aTakenCellPushesTheDestinationFurtherInTheSameDirection() {
        Location a = new Location("a", "A", "a");
        Location b = new Location("b", "B", "b");
        Location c = new Location("c", "C", "c");
        Location d = new Location("d", "D", "d");
        // a -east-> b -south-> c, and a -south-> d -east-> e, whose cell c already holds
        a.addExit(new Exit("a-e", Direction.EAST, b));
        a.addExit(new Exit("a-s", Direction.SOUTH, d));
        b.addExit(new Exit("b-s", Direction.SOUTH, c));
        d.addExit(new Exit("d-e", Direction.EAST, new Location("e", "E", "e")));
        Location e = d.getExit(Direction.EAST).getDestination();
        World world = new World(List.of(new Region("R", List.of(a, b, c, d, e))), a);

        Map<String, MapModel.Cell> cells = MapModel.layout(world);

        assertEquals(new MapModel.Cell(1, 1), cells.get("c"));
        assertEquals(new MapModel.Cell(2, 1), cells.get("e"));
        assertEquals(5, cells.values().stream().distinct().count());
    }

    @Test
    void locationsTheStartCannotReachGetTheirOwnRowsBelow() {
        Location start = new Location("start", "Start", "s");
        Location island = new Location("island", "Island", "i");
        World world = new World(List.of(new Region("R", List.of(start, island))), start);

        Map<String, MapModel.Cell> cells = MapModel.layout(world);

        assertEquals(new MapModel.Cell(0, 0), cells.get("start"));
        assertEquals(new MapModel.Cell(0, 2), cells.get("island"));
    }

    @Test
    void atTheStartOnlyTheSquareIsVisitedAndItsNeighboursAreGreyedOut() {
        MapModel map = MapModel.of(startedGame());

        Map<String, MapModel.NodeState> states = map.getNodes().stream()
                .collect(Collectors.toMap(MapModel.Node::id, MapModel.Node::state));
        assertEquals(Map.of("village-square", MapModel.NodeState.CURRENT, "tavern", MapModel.NodeState.KNOWN,
                "forge", MapModel.NodeState.KNOWN, "tool-shed", MapModel.NodeState.KNOWN,
                "forest-edge", MapModel.NodeState.KNOWN), states);
        assertNull(map.find("bandit-camp"), "hidden until a neighbour is visited");
        assertTrue(map.find("tavern").adjacent());
        assertFalse(map.find("village-square").adjacent());
        assertEquals(3, map.getColumns());
        assertEquals(4, map.getRows());
    }

    @Test
    void theLockedShedDoorIsMarkedAndEachConnectionIsDrawnOnce() {
        MapModel map = MapModel.of(startedGame());

        assertEquals(4, map.getEdges().size());
        MapModel.Edge shed = map.getEdges().stream()
                .filter(edge -> edge.toId().equals("tool-shed") || edge.fromId().equals("tool-shed"))
                .findFirst().orElseThrow();
        assertTrue(shed.locked());
        assertTrue(map.getEdges().stream().filter(edge -> edge != shed).noneMatch(MapModel.Edge::locked));
    }

    @Test
    void movingOnRevealsTheNextNeighboursAndMovesTheHighlight() {
        GameState state = startedGame();
        Location forestEdge = state.getWorld().findLocation("forest-edge");
        state.setCurrentLocation(forestEdge);
        forestEdge.markVisited();

        MapModel map = MapModel.of(state);

        assertEquals(MapModel.NodeState.CURRENT, map.find("forest-edge").state());
        assertEquals(MapModel.NodeState.VISITED, map.find("village-square").state());
        assertEquals(MapModel.NodeState.KNOWN, map.find("bandit-camp").state());
        assertTrue(map.find("bandit-camp").adjacent());
        assertTrue(map.find("village-square").adjacent());
        assertFalse(map.find("tavern").adjacent(), "two steps away");
    }

    @Test
    void theModelIsACopyThatLaterMovesDoNotChange() {
        GameState state = startedGame();
        MapModel before = MapModel.of(state);
        Location tavern = state.getWorld().findLocation("tavern");

        state.setCurrentLocation(tavern);
        tavern.markVisited();

        assertEquals(MapModel.NodeState.CURRENT, before.find("village-square").state());
        assertNotEquals(before.find("tavern").state(), MapModel.of(state).find("tavern").state());
    }

    @Test
    void beforeAGameStartsTheMapIsEmpty() {
        MapModel map = MapModel.of(new GameState());

        assertNotNull(map);
        assertTrue(map.getNodes().isEmpty());
        assertEquals(0, map.getColumns());
    }
}
