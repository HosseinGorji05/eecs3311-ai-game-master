package presentation;

import engine.character.CharacterClass;
import engine.character.CharacterFactory;
import engine.character.Player;
import engine.character.Stats;
import engine.core.GameState;
import engine.items.Consumable;
import engine.items.Container;
import engine.items.Inventory;
import engine.items.Weapon;
import engine.world.World;
import org.junit.jupiter.api.Test;

import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The inventory panel's tree of the item Composite, the items here and the weight (F05, SD10), without a display. */
class InventoryTreeModelTest {

    private static InventoryTreeModel.ItemNode item(DefaultMutableTreeNode node) {
        return (InventoryTreeModel.ItemNode) node.getUserObject();
    }

    private static DefaultMutableTreeNode child(Object parent, int index) {
        return (DefaultMutableTreeNode) ((DefaultMutableTreeNode) parent).getChildAt(index);
    }

    @Test
    void theRoguesPouchIsABranchHoldingItsPotion() {
        Player rogue = CharacterFactory.forClass(CharacterClass.ROGUE).createCharacter("Ari", new Stats(2, 6, 2));

        DefaultTreeModel tree = InventoryTreeModel.build(rogue.getInventory(), rogue.getEquippedWeapon());

        Object root = tree.getRoot();
        assertEquals(InventoryTreeModel.ROOT_LABEL, ((DefaultMutableTreeNode) root).getUserObject());
        assertEquals(2, tree.getChildCount(root));
        DefaultMutableTreeNode dagger = child(root, 0);
        DefaultMutableTreeNode pouch = child(root, 1);
        assertEquals("dagger (1 kg)", item(dagger).label());
        assertTrue(tree.isLeaf(dagger));
        assertEquals("pouch (0.7 kg, 1 of 4 places used)", item(pouch).label());
        assertTrue(item(pouch).container());
        assertEquals("healing potion", item(child(pouch, 0)).name());
        assertEquals("healing potion (0.5 kg)", child(pouch, 0).getUserObject().toString());
    }

    @Test
    void nestedContainersExpandAtAnyDepthAndAnEmptyOneIsStillABranch() {
        Inventory inventory = new Inventory();
        Container backpack = new Container("backpack", 1.0, 8);
        Container pouch = new Container("pouch", 0.2, 4);
        Container empty = new Container("sack", 0.5, 2);
        pouch.add(new Consumable("bread", 0.3, 2));
        backpack.add(pouch);
        inventory.add(backpack);
        inventory.add(empty);

        DefaultTreeModel tree = InventoryTreeModel.build(inventory, null);

        DefaultMutableTreeNode backpackNode = child(tree.getRoot(), 0);
        DefaultMutableTreeNode pouchNode = child(backpackNode, 0);
        assertEquals("backpack (1.5 kg, 1 of 8 places used)", item(backpackNode).label());
        assertEquals("bread", item(child(pouchNode, 0)).name());
        DefaultMutableTreeNode sack = child(tree.getRoot(), 1);
        assertEquals(0, sack.getChildCount());
        assertFalse(tree.isLeaf(sack), "an empty container still shows as a container");
    }

    @Test
    void theEquippedWeaponIsMarked() {
        Player warrior = CharacterFactory.forClass(CharacterClass.WARRIOR).createCharacter("Ari", new Stats(5, 3, 2));
        Weapon sword = (Weapon) warrior.getInventory().find("sword");
        warrior.equip(sword);

        DefaultTreeModel tree = InventoryTreeModel.build(warrior.getInventory(), warrior.getEquippedWeapon());

        InventoryTreeModel.ItemNode node = item(child(tree.getRoot(), 0));
        assertEquals("sword (3 kg, equipped)", node.label());
        assertTrue(node.equipped());
    }

    @Test
    void theItemsHereNameTheirOwner() {
        GameState state = new GameState();
        World world = World.createDefault();
        state.start(CharacterFactory.forClass(CharacterClass.MAGE).createCharacter("Ari", new Stats(2, 3, 5)), world);
        state.setCurrentLocation(world.findLocation("forge"));

        List<InventoryTreeModel.ItemNode> here = InventoryTreeModel.itemsHere(state);

        assertEquals(List.of("iron key (0.2 kg, Brann's)", "warhammer (8 kg, Brann's)"),
                here.stream().map(InventoryTreeModel.ItemNode::label).toList());
        assertEquals("iron key", here.get(0).name());
    }

    @Test
    void theWeightBarCountsThroughNestedContainers() {
        Player rogue = CharacterFactory.forClass(CharacterClass.ROGUE).createCharacter("Ari", new Stats(2, 6, 2));
        GameState state = new GameState();
        state.start(rogue, World.createDefault());

        GuiState copy = GuiState.of(state);

        assertEquals(1.7, copy.getWeight(), 1e-9, "dagger 1 + pouch 0.2 + potion 0.5");
        assertEquals(Inventory.MAX_CARRY_WEIGHT, copy.getMaxWeight());
        assertEquals("1.7 of 10 kg", GuiText.weightText(copy.getWeight(), copy.getMaxWeight()));
    }

    @Test
    void theTreeIsACopyThatLaterChangesDoNotTouch() {
        Player rogue = CharacterFactory.forClass(CharacterClass.ROGUE).createCharacter("Ari", new Stats(2, 6, 2));
        DefaultTreeModel before = InventoryTreeModel.build(rogue.getInventory(), null);

        rogue.getInventory().remove("dagger");

        DefaultTreeModel after = InventoryTreeModel.build(rogue.getInventory(), null);
        assertEquals(2, before.getChildCount(before.getRoot()));
        assertEquals(1, after.getChildCount(after.getRoot()));
    }
}
