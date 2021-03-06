// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.actions;

import static org.openstreetmap.josm.gui.help.HelpUtil.ht;
import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.io.Serializable;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;

import javax.swing.JOptionPane;

import org.openstreetmap.josm.command.ChangeNodesCommand;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.command.MoveCommand;
import org.openstreetmap.josm.command.SequenceCommand;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.osm.WaySegment;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.MapView;
import org.openstreetmap.josm.gui.Notification;
import org.openstreetmap.josm.tools.Geometry;
import org.openstreetmap.josm.tools.MultiMap;
import org.openstreetmap.josm.tools.Shortcut;

/**
 * Action allowing to join a node to a nearby way, operating on two modes:<ul>
 * <li><b>Join Node to Way</b>: Include a node into the nearest way segments. The node does not move</li>
 * <li><b>Move Node onto Way</b>: Move the node onto the nearest way segments and include it</li>
 * </ul>
 * @since 466
 */
public class JoinNodeWayAction extends JosmAction {

    protected final boolean joinWayToNode;

    protected JoinNodeWayAction(boolean joinWayToNode, String name, String iconName, String tooltip,
            Shortcut shortcut, boolean registerInToolbar) {
        super(name, iconName, tooltip, shortcut, registerInToolbar);
        this.joinWayToNode = joinWayToNode;
    }

    /**
     * Constructs a Join Node to Way action.
     * @return the Join Node to Way action
     */
    public static JoinNodeWayAction createJoinNodeToWayAction() {
        JoinNodeWayAction action = new JoinNodeWayAction(false,
                tr("Join Node to Way"), /* ICON */ "joinnodeway",
                tr("Include a node into the nearest way segments"),
                Shortcut.registerShortcut("tools:joinnodeway", tr("Tools: {0}", tr("Join Node to Way")),
                        KeyEvent.VK_J, Shortcut.DIRECT), true);
        action.setHelpId(ht("/Action/JoinNodeWay"));
        return action;
    }

    /**
     * Constructs a Move Node onto Way action.
     * @return the Move Node onto Way action
     */
    public static JoinNodeWayAction createMoveNodeOntoWayAction() {
        JoinNodeWayAction action = new JoinNodeWayAction(true,
                tr("Move Node onto Way"), /* ICON*/ "movenodeontoway",
                tr("Move the node onto the nearest way segments and include it"),
                Shortcut.registerShortcut("tools:movenodeontoway", tr("Tools: {0}", tr("Move Node onto Way")),
                        KeyEvent.VK_N, Shortcut.DIRECT), true);
        action.setHelpId(ht("/Action/MoveNodeWay"));
        return action;
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        if (!isEnabled())
            return;
        DataSet ds = getLayerManager().getEditDataSet();
        Collection<Node> selectedNodes = ds.getSelectedNodes();
        Collection<Command> cmds = new LinkedList<>();
        Map<Way, MultiMap<Integer, Node>> data = new LinkedHashMap<>();

        // If the user has selected some ways, only join the node to these.
        boolean restrictToSelectedWays = !ds.getSelectedWays().isEmpty();

        // Planning phase: decide where we'll insert the nodes and put it all in "data"
        MapView mapView = MainApplication.getMap().mapView;
        for (Node node : selectedNodes) {
            List<WaySegment> wss = mapView.getNearestWaySegments(mapView.getPoint(node), OsmPrimitive::isSelectable);
            // we cannot trust the order of elements in wss because it was calculated based on rounded position value of node
            TreeMap<Double, List<WaySegment>> nearestMap = new TreeMap<>();
            EastNorth en = node.getEastNorth();
            for (WaySegment ws : wss) {
                // Maybe cleaner to pass a "isSelected" predicate to getNearestWaySegments, but this is less invasive.
                if (restrictToSelectedWays && !ws.way.isSelected()) {
                    continue;
                }
                /* perpendicular distance squared
                 * loose some precision to account for possible deviations in the calculation above
                 * e.g. if identical (A and B) come about reversed in another way, values may differ
                 * -- zero out least significant 32 dual digits of mantissa..
                 */
                double distSq = en.distanceSq(Geometry.closestPointToSegment(ws.getFirstNode().getEastNorth(),
                        ws.getSecondNode().getEastNorth(), en));
                // resolution in numbers with large exponent not needed here..
                distSq = Double.longBitsToDouble(Double.doubleToLongBits(distSq) >> 32 << 32);
                List<WaySegment> wslist = nearestMap.computeIfAbsent(distSq, k -> new LinkedList<>());
                wslist.add(ws);
            }
            Set<Way> seenWays = new HashSet<>();
            Double usedDist = null;
            while (!nearestMap.isEmpty()) {
                Entry<Double, List<WaySegment>> entry = nearestMap.pollFirstEntry();
                if (usedDist != null) {
                    double delta = entry.getKey() - usedDist;
                    if (delta > 1e-4)
                        break;
                }
                for (WaySegment ws : entry.getValue()) {
                    // only use the closest WaySegment of each way and ignore those that already contain the node
                    if (!ws.getFirstNode().equals(node) && !ws.getSecondNode().equals(node)
                            && !seenWays.contains(ws.way)) {
                        if (usedDist == null)
                            usedDist = entry.getKey();
                        MultiMap<Integer, Node> innerMap = data.get(ws.way);
                        if (innerMap == null) {
                            innerMap = new MultiMap<>();
                            data.put(ws.way, innerMap);
                        }
                        innerMap.put(ws.lowerIndex, node);
                        seenWays.add(ws.way);
                    }
                }
            }
        }

        // Execute phase: traverse the structure "data" and finally put the nodes into place
        Map<Node, EastNorth> movedNodes = new HashMap<>();
        for (Map.Entry<Way, MultiMap<Integer, Node>> entry : data.entrySet()) {
            final Way w = entry.getKey();
            final MultiMap<Integer, Node> innerEntry = entry.getValue();

            List<Integer> segmentIndexes = new LinkedList<>();
            segmentIndexes.addAll(innerEntry.keySet());
            segmentIndexes.sort(Collections.reverseOrder());

            List<Node> wayNodes = w.getNodes();
            for (Integer segmentIndex : segmentIndexes) {
                final Set<Node> nodesInSegment = innerEntry.get(segmentIndex);
                if (joinWayToNode) {
                    for (Node node : nodesInSegment) {
                        EastNorth newPosition = Geometry.closestPointToSegment(
                                w.getNode(segmentIndex).getEastNorth(),
                                w.getNode(segmentIndex+1).getEastNorth(),
                                node.getEastNorth());
                        EastNorth prevMove = movedNodes.get(node);
                        if (prevMove != null) {
                            if (!prevMove.equalsEpsilon(newPosition, 1e-4)) {
                                // very unlikely: node has same distance to multiple ways which are not nearly overlapping
                                new Notification(tr("Multiple target ways, no common point found. Nothing was changed."))
                                        .setIcon(JOptionPane.INFORMATION_MESSAGE)
                                        .show();
                                return;
                            }
                            continue;
                        }
                        MoveCommand c = new MoveCommand(node,
                                ProjectionRegistry.getProjection().eastNorth2latlon(newPosition));
                        cmds.add(c);
                        movedNodes.put(node, newPosition);
                    }
                }
                List<Node> nodesToAdd = new LinkedList<>();
                nodesToAdd.addAll(nodesInSegment);
                nodesToAdd.sort(new NodeDistanceToRefNodeComparator(
                        w.getNode(segmentIndex), w.getNode(segmentIndex+1), !joinWayToNode));
                wayNodes.addAll(segmentIndex + 1, nodesToAdd);
            }
            cmds.add(new ChangeNodesCommand(ds, w, wayNodes));
        }

        if (cmds.isEmpty()) return;
        UndoRedoHandler.getInstance().add(new SequenceCommand(getValue(NAME).toString(), cmds));
    }

    /**
     * Sorts collinear nodes by their distance to a common reference node.
     */
    private static class NodeDistanceToRefNodeComparator implements Comparator<Node>, Serializable {

        private static final long serialVersionUID = 1L;

        private final EastNorth refPoint;
        private final EastNorth refPoint2;
        private final boolean projectToSegment;

        NodeDistanceToRefNodeComparator(Node referenceNode, Node referenceNode2, boolean projectFirst) {
            refPoint = referenceNode.getEastNorth();
            refPoint2 = referenceNode2.getEastNorth();
            projectToSegment = projectFirst;
        }

        @Override
        public int compare(Node first, Node second) {
            EastNorth firstPosition = first.getEastNorth();
            EastNorth secondPosition = second.getEastNorth();

            if (projectToSegment) {
                firstPosition = Geometry.closestPointToSegment(refPoint, refPoint2, firstPosition);
                secondPosition = Geometry.closestPointToSegment(refPoint, refPoint2, secondPosition);
            }

            double distanceFirst = firstPosition.distance(refPoint);
            double distanceSecond = secondPosition.distance(refPoint);
            return Double.compare(distanceFirst, distanceSecond);
        }
    }

    @Override
    protected void updateEnabledState() {
        updateEnabledStateOnCurrentSelection();
    }

    @Override
    protected void updateEnabledState(Collection<? extends OsmPrimitive> selection) {
        updateEnabledStateOnModifiableSelection(selection);
    }
}
