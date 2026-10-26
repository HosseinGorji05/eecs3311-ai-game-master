package engine.commands;

import engine.core.GameState;
import engine.items.Item;
import engine.world.Exit;
import engine.world.Location;
import engine.world.Region;

import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Test helper: everything a command could change, as one comparable string (current location,
 * every location's visited flag and items, every exit's lock, and the inventory's weight).
 * The turn number is left out because {@code RulesEngine.executeAll()} advances it on every turn.
 */
final class StateFingerprint {

    private StateFingerprint() {
    }

    static String of(GameState state) {
        StringBuilder text = new StringBuilder();
        text.append("at=").append(state.currentLocation().getId()).append('\n');
        text.append(String.format(Locale.ROOT, "carried=%.3f kg\n", state.getPlayer().getInventory().totalWeight()));
        for (Region region : state.getWorld().getRegions()) {
            for (Location location : region.getLocations()) {
                text.append(location.getId())
                        .append(location.isVisited() ? " visited" : " unvisited")
                        .append(" items=")
                        .append(location.getItems().stream().map(Item::getName).collect(Collectors.joining(",")));
                for (Exit exit : location.getExits()) {
                    text.append(' ').append(exit.getId()).append(exit.isLocked() ? ":locked" : ":open");
                }
                text.append('\n');
            }
        }
        return text.toString();
    }
}
