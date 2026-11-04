package presentation;

import engine.core.GameState;
import engine.world.Direction;
import engine.world.Exit;
import engine.world.Location;
import engine.world.Region;
import engine.world.World;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * What the GUI's map panel shows (F06), worked out from the real world without a display: every
 * location gets a grid cell from the exits ({@link #layout}), visited locations are shown, unvisited ones
 * next to a visited location are shown greyed out (their names are already listed among the exits), and
 * the rest stay hidden. The locations next to the current one are the ones a click can move to.
 *
 * <p>The model is a copy: it holds no reference to the world, so the Swing thread can paint it while the
 * game goes on.
 */
final class MapModel {

    /** How a shown location is drawn. */
    enum NodeState {
        /** The player is here (highlighted). */
        CURRENT,
        VISITED,
        /** Not visited yet, but next to a visited location (greyed out). */
        KNOWN
    }

    /** A grid cell: column grows to the east, row to the south. */
    record Cell(int column, int row) {
    }

    /**
     * A location on the map.
     *
     * @param adjacent whether an exit of the current location leads here, so a click moves here
     */
    record Node(String id, String name, Cell cell, NodeState state, boolean adjacent) {
    }

    /** A drawn connection between two shown locations; {@code locked} if a door between them is still locked. */
    record Edge(String fromId, String toId, boolean locked) {
    }

    static final MapModel EMPTY = new MapModel(List.of(), List.of(), 0, 0);

    private final List<Node> nodes;
    private final List<Edge> edges;
    private final int columns;
    private final int rows;

    private MapModel(List<Node> nodes, List<Edge> edges, int columns, int rows) {
        this.nodes = List.copyOf(nodes);
        this.edges = List.copyOf(edges);
        this.columns = columns;
        this.rows = rows;
    }

    /** The map of a running game, or {@link #EMPTY} before it starts. */
    static MapModel of(GameState state) {
        Objects.requireNonNull(state, "state");
        if (state.getWorld() == null || state.currentLocation() == null) {
            return EMPTY;
        }
        World world = state.getWorld();
        Location current = state.currentLocation();
        Map<String, Cell> cells = layout(world);
        Set<String> visible = new HashSet<>();
        Set<String> known = new HashSet<>();
        for (Location location : locations(world)) {
            if (location.isVisited()) {
                visible.add(location.getId());
                for (Exit exit : location.getExits()) {
                    known.add(exit.getDestination().getId());
                }
            }
        }
        visible.addAll(known);
        Set<String> adjacent = new HashSet<>();
        for (Exit exit : current.getExits()) {
            adjacent.add(exit.getDestination().getId());
        }
        List<Node> nodes = new ArrayList<>();
        for (Location location : locations(world)) {
            if (!visible.contains(location.getId())) {
                continue;
            }
            NodeState nodeState = location == current ? NodeState.CURRENT
                    : location.isVisited() ? NodeState.VISITED : NodeState.KNOWN;
            nodes.add(new Node(location.getId(), location.getName(), cells.get(location.getId()), nodeState,
                    adjacent.contains(location.getId())));
        }
        int columns = 0;
        int rows = 0;
        for (Cell cell : cells.values()) {
            columns = Math.max(columns, cell.column() + 1);
            rows = Math.max(rows, cell.row() + 1);
        }
        return new MapModel(nodes, edges(world, visible), columns, rows);
    }

    /**
     * Places every location on a grid by walking the exits from the start location: an exit north puts
     * its destination one row up, east one column right, and so on. If that cell is taken, the destination
     * goes further in the same direction. Locations the start cannot reach start a new group two rows
     * below. The result is shifted so the smallest column and row are 0.
     *
     * @return the cell of every location, by location id
     */
    static Map<String, Cell> layout(World world) {
        Objects.requireNonNull(world, "world");
        Map<String, Cell> cells = new LinkedHashMap<>();
        Set<Cell> taken = new HashSet<>();
        List<Location> order = new ArrayList<>();
        order.add(world.getStartLocation());
        order.addAll(locations(world));
        for (Location root : order) {
            if (cells.containsKey(root.getId())) {
                continue;
            }
            int lowest = cells.values().stream().mapToInt(Cell::row).max().orElse(-2);
            place(root, new Cell(0, lowest + 2), cells, taken);
            Deque<Location> queue = new ArrayDeque<>();
            queue.add(root);
            while (!queue.isEmpty()) {
                Location from = queue.removeFirst();
                Cell origin = cells.get(from.getId());
                for (Exit exit : from.getExits()) {
                    Location to = exit.getDestination();
                    if (cells.containsKey(to.getId())) {
                        continue;
                    }
                    Cell cell = step(origin, exit.getDirection());
                    while (taken.contains(cell)) {
                        cell = step(cell, exit.getDirection());
                    }
                    place(to, cell, cells, taken);
                    queue.addLast(to);
                }
            }
        }
        int minColumn = cells.values().stream().mapToInt(Cell::column).min().orElse(0);
        int minRow = cells.values().stream().mapToInt(Cell::row).min().orElse(0);
        Map<String, Cell> shifted = new LinkedHashMap<>();
        cells.forEach((id, cell) -> shifted.put(id, new Cell(cell.column() - minColumn, cell.row() - minRow)));
        return shifted;
    }

    /** The shown locations, in world order. */
    List<Node> getNodes() {
        return Collections.unmodifiableList(nodes);
    }

    /** The connections between shown locations where at least one end is visited, each pair once. */
    List<Edge> getEdges() {
        return Collections.unmodifiableList(edges);
    }

    /** The shown location with this id, or {@code null} if it is hidden or unknown. */
    Node find(String id) {
        for (Node node : nodes) {
            if (node.id().equals(id)) {
                return node;
            }
        }
        return null;
    }

    /** Width of the grid in cells (0 for the empty map). */
    int getColumns() {
        return columns;
    }

    /** Height of the grid in cells (0 for the empty map). */
    int getRows() {
        return rows;
    }

    private static List<Edge> edges(World world, Set<String> visible) {
        Map<String, Edge> edges = new LinkedHashMap<>();
        for (Location from : locations(world)) {
            if (!from.isVisited()) {
                continue;
            }
            for (Exit exit : from.getExits()) {
                Location to = exit.getDestination();
                if (!visible.contains(to.getId())) {
                    continue;
                }
                String key = from.getId().compareTo(to.getId()) < 0
                        ? from.getId() + "|" + to.getId() : to.getId() + "|" + from.getId();
                Edge known = edges.get(key);
                boolean locked = exit.isLocked() || (known != null && known.locked());
                edges.put(key, known == null ? new Edge(from.getId(), to.getId(), locked)
                        : new Edge(known.fromId(), known.toId(), locked));
            }
        }
        return new ArrayList<>(edges.values());
    }

    private static List<Location> locations(World world) {
        List<Location> all = new ArrayList<>();
        for (Region region : world.getRegions()) {
            all.addAll(region.getLocations());
        }
        return all;
    }

    private static void place(Location location, Cell cell, Map<String, Cell> cells, Set<Cell> taken) {
        cells.put(location.getId(), cell);
        taken.add(cell);
    }

    private static Cell step(Cell cell, Direction direction) {
        return switch (direction) {
            case NORTH -> new Cell(cell.column(), cell.row() - 1);
            case SOUTH -> new Cell(cell.column(), cell.row() + 1);
            case EAST -> new Cell(cell.column() + 1, cell.row());
            case WEST -> new Cell(cell.column() - 1, cell.row());
        };
    }
}
