package presentation;

import engine.core.GameState;
import engine.items.Container;
import engine.items.Inventory;
import engine.items.Item;
import engine.items.Weapon;
import engine.world.NPC;
import engine.world.World;

import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * The GUI's inventory panel content (F05) without a display: a tree model of the item Composite (the
 * inventory's top-level items, and inside each container its contents, at any depth), the items lying in
 * the current location (SD10's location panel), and the weight for the weight bar.
 *
 * <p>Every node holds an {@link ItemNode} copy, never the item itself, so the Swing thread can show the
 * tree while the game goes on. Buttons send the node's {@link ItemNode#name()} as the proposal's target,
 * and the engine looks the item up by that name (the first one, as for a typed command).
 */
final class InventoryTreeModel {

    /** Label of the tree's root. */
    static final String ROOT_LABEL = "Carried items";

    /**
     * One item as shown: its name (the target of a button's proposal), its label and its kind.
     *
     * @param equipped whether it is the player's equipped weapon
     */
    record ItemNode(String name, String label, boolean container, boolean equipped) {
        @Override
        public String toString() {
            return label;
        }
    }

    private InventoryTreeModel() {
    }

    /**
     * The tree of the carried items: the root, then the top-level items in order; a container is a
     * branch even when it is empty ({@code asksAllowsChildren}), other items are leaves.
     */
    static DefaultTreeModel build(Inventory inventory, Weapon equipped) {
        Objects.requireNonNull(inventory, "inventory");
        DefaultMutableTreeNode root = new DefaultMutableTreeNode(ROOT_LABEL, true);
        for (Item item : inventory.getItems()) {
            root.add(node(item, equipped));
        }
        return new DefaultTreeModel(root, true);
    }

    /** An empty tree, before a game starts. */
    static DefaultTreeModel empty() {
        return new DefaultTreeModel(new DefaultMutableTreeNode(ROOT_LABEL, true), true);
    }

    /**
     * The items lying in the current location, labelled with their weight and, for an item an NPC owns,
     * the owner ("iron key (0.2 kg, Brann's)": taking it outside a conversation is a theft).
     */
    static List<ItemNode> itemsHere(GameState state) {
        Objects.requireNonNull(state, "state");
        if (state.currentLocation() == null) {
            return List.of();
        }
        List<ItemNode> items = new ArrayList<>();
        for (Item item : state.currentLocation().getItems()) {
            String owner = ownerName(state.getWorld(), item.getOwnerId());
            String label = String.format(Locale.ROOT, "%s (%s kg%s)", item.getName(), GuiText.kg(item.getWeight()),
                    owner == null ? "" : ", " + owner + "'s");
            items.add(new ItemNode(item.getName(), label, item instanceof Container, false));
        }
        return items;
    }

    /** "sword (3 kg, equipped)", "pouch (0.7 kg, 1 of 4 places used)", "bread (0.3 kg)". */
    static String label(Item item, Weapon equipped) {
        StringBuilder label = new StringBuilder(item.getName()).append(" (").append(GuiText.kg(item.getWeight()))
                .append(" kg");
        if (item instanceof Container container) {
            label.append(", ").append(container.getContents().size()).append(" of ").append(container.getCapacity())
                    .append(" places used");
        }
        if (item == equipped) {
            label.append(", equipped");
        }
        return label.append(')').toString();
    }

    private static DefaultMutableTreeNode node(Item item, Weapon equipped) {
        boolean container = item instanceof Container;
        DefaultMutableTreeNode node = new DefaultMutableTreeNode(
                new ItemNode(item.getName(), label(item, equipped), container, item == equipped), container);
        if (item instanceof Container box) {
            for (Item inside : box.getContents()) {
                node.add(node(inside, equipped));
            }
        }
        return node;
    }

    private static String ownerName(World world, String ownerId) {
        if (ownerId == null) {
            return null;
        }
        NPC owner = world == null ? null : world.findNpc(ownerId);
        return owner != null ? owner.getName() : ownerId;
    }
}
