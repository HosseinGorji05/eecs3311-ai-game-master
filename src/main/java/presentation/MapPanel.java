package presentation;

import javax.swing.JPanel;
import javax.swing.ToolTipManager;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * The map panel of {@link SwingGameView} (F06): paints a {@link MapModel} (the current location
 * highlighted, unvisited neighbours greyed out, locked doors dashed) and reports a click on a location
 * next to the current one, while moving is allowed. It only draws; the view sends the move.
 *
 * <p>Used on the Swing thread only.
 */
final class MapPanel extends JPanel {

    private static final Color CURRENT_FILL = new Color(255, 214, 102);
    private static final Color VISITED_FILL = Color.WHITE;
    private static final Color KNOWN_FILL = new Color(232, 232, 232);
    private static final Color KNOWN_TEXT = new Color(130, 130, 130);
    private static final Color REACHABLE_BORDER = new Color(30, 100, 200);
    private static final Color EDGE = new Color(110, 110, 110);
    private static final int GAP = 14;
    private static final Stroke DASHED = new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f,
            new float[] {5f, 4f}, 0f);

    private MapModel model = MapModel.EMPTY;
    private boolean moveEnabled;
    private Consumer<MapModel.Node> onMove = node -> { };

    MapPanel() {
        setBackground(new Color(246, 243, 236));
        setPreferredSize(new Dimension(420, 330));
        ToolTipManager.sharedInstance().registerComponent(this);
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                MapModel.Node node = clickableAt(event.getPoint());
                if (node != null) {
                    onMove.accept(node);
                }
            }

            @Override
            public void mouseMoved(MouseEvent event) {
                setCursor(clickableAt(event.getPoint()) != null
                        ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
    }

    void setModel(MapModel model) {
        this.model = Objects.requireNonNull(model, "model");
        repaint();
    }

    MapModel getModel() {
        return model;
    }

    /** Whether a click moves (the mode allows MOVE and no turn is pending). */
    void setMoveEnabled(boolean enabled) {
        this.moveEnabled = enabled;
        repaint();
    }

    boolean isMoveEnabled() {
        return moveEnabled;
    }

    /** What to do with a click on a location next to the current one. */
    void setOnMove(Consumer<MapModel.Node> onMove) {
        this.onMove = Objects.requireNonNull(onMove, "onMove");
    }

    /** The shown location at this point, or {@code null}. */
    MapModel.Node nodeAt(Point point) {
        for (MapModel.Node node : model.getNodes()) {
            if (nodeBounds(node).contains(point)) {
                return node;
            }
        }
        return null;
    }

    /** Where the location is drawn in the panel's current size. */
    Rectangle nodeBounds(MapModel.Node node) {
        int columns = Math.max(1, model.getColumns());
        int rows = Math.max(1, model.getRows());
        int cellWidth = Math.max(1, getWidth() / columns);
        int cellHeight = Math.max(1, getHeight() / rows);
        int width = Math.max(10, cellWidth - GAP);
        int height = Math.max(10, Math.min(cellHeight - GAP, 46));
        int x = node.cell().column() * cellWidth + (cellWidth - width) / 2;
        int y = node.cell().row() * cellHeight + (cellHeight - height) / 2;
        return new Rectangle(x, y, width, height);
    }

    @Override
    public String getToolTipText(MouseEvent event) {
        MapModel.Node node = nodeAt(event.getPoint());
        if (node == null) {
            return null;
        }
        return switch (node.state()) {
            case CURRENT -> "You are here: " + node.name();
            case VISITED, KNOWN -> node.adjacent() && moveEnabled ? "Click to go to " + node.name()
                    : node.name() + (node.state() == MapModel.NodeState.KNOWN ? " (not visited yet)" : "");
        };
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            for (MapModel.Edge edge : model.getEdges()) {
                MapModel.Node from = model.find(edge.fromId());
                MapModel.Node to = model.find(edge.toId());
                if (from == null || to == null) {
                    continue;
                }
                Rectangle a = nodeBounds(from);
                Rectangle b = nodeBounds(to);
                g.setColor(EDGE);
                g.setStroke(edge.locked() ? DASHED : new BasicStroke(2f));
                g.drawLine((int) a.getCenterX(), (int) a.getCenterY(), (int) b.getCenterX(), (int) b.getCenterY());
            }
            for (MapModel.Node node : model.getNodes()) {
                paintNode(g, node);
            }
        } finally {
            g.dispose();
        }
    }

    private void paintNode(Graphics2D g, MapModel.Node node) {
        Rectangle box = nodeBounds(node);
        g.setColor(switch (node.state()) {
            case CURRENT -> CURRENT_FILL;
            case VISITED -> VISITED_FILL;
            case KNOWN -> KNOWN_FILL;
        });
        g.fillRoundRect(box.x, box.y, box.width, box.height, 12, 12);
        boolean reachable = node.adjacent() && moveEnabled;
        g.setColor(reachable ? REACHABLE_BORDER : node.state() == MapModel.NodeState.KNOWN ? KNOWN_TEXT : Color.DARK_GRAY);
        g.setStroke(new BasicStroke(reachable || node.state() == MapModel.NodeState.CURRENT ? 2.5f : 1.2f));
        g.drawRoundRect(box.x, box.y, box.width, box.height, 12, 12);
        Font font = getFont().deriveFont(node.state() == MapModel.NodeState.CURRENT ? Font.BOLD : Font.PLAIN, 12f);
        g.setFont(font);
        g.setColor(node.state() == MapModel.NodeState.KNOWN ? KNOWN_TEXT : Color.BLACK);
        drawCentered(g, node.name(), box);
    }

    /** Draws the name centred in the box, on two lines if it does not fit on one. */
    private static void drawCentered(Graphics2D g, String text, Rectangle box) {
        FontMetrics metrics = g.getFontMetrics();
        String first = text;
        String second = null;
        int space = text.lastIndexOf(' ');
        if (metrics.stringWidth(text) > box.width - 6 && space > 0) {
            first = text.substring(0, space);
            second = text.substring(space + 1);
        }
        int lineHeight = metrics.getAscent();
        int lines = second == null ? 1 : 2;
        int y = box.y + (box.height - lines * lineHeight) / 2 + lineHeight - 1;
        g.drawString(first, box.x + (box.width - metrics.stringWidth(first)) / 2, y);
        if (second != null) {
            g.drawString(second, box.x + (box.width - metrics.stringWidth(second)) / 2, y + lineHeight);
        }
    }

    private MapModel.Node clickableAt(Point point) {
        MapModel.Node node = nodeAt(point);
        return node != null && node.adjacent() && moveEnabled ? node : null;
    }
}
