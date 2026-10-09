package net.voiddustry.redvsblue.evolution;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Grid layout of the evolution trees, derived from {@link Evolution}.
 *
 * <ul>
 *     <li>A tree is identified by its tier-2 unit: any non-tier-1 unit that a tier-1 unit evolves into.</li>
 *     <li>Every tier-1 unit that evolves into that root is drawn on the row above it and converges into it.</li>
 *     <li>An evolution back into a tier-1 unit ends the branch (it is not drawn).</li>
 *     <li>Each leaf branch owns a fixed column, so ending branches never shift their neighbours.</li>
 *     <li>A unit with several parents (convergence) is drawn once, spanning all of its parents' columns.</li>
 * </ul>
 *
 * Rows follow tree depth: tier-1 units are row -1, the root is row 0. Columns are "leaf columns";
 * the menu splits each into two half-cells so connector lines can sit on exact centres.
 */
public final class EvolutionTree {

    public static final class Node {
        public final String unit;
        public final boolean tier1;
        public int row;
        /** first leaf column and number of leaf columns covered */
        public int col, span;
        public final List<Node> parents = new ArrayList<>();
        public final List<Node> children = new ArrayList<>();
        /** the parent that reserves columns for this node (first parent found) */
        Node owner;

        Node(String unit, boolean tier1) {
            this.unit = unit;
            this.tier1 = tier1;
        }

        /** Centre of this node in half-cell boundary coordinates. */
        public int center() {
            return col * 2 + span;
        }
    }

    public static final class Edge {
        public final Node from, to;

        Edge(Node from, Node to) {
            this.from = from;
            this.to = to;
        }
    }

    public static final class Tree {
        /** unit name of the tier-2 unit identifying this tree */
        public final String root;
        /** width in leaf columns */
        public int width;
        public int maxRow;
        public final List<Node> nodes = new ArrayList<>();
        public final List<Edge> edges = new ArrayList<>();

        Tree(String root) {
            this.root = root;
        }

        public Node nodeStartingAt(int row, int col) {
            for (Node n : nodes) {
                if (n.row == row && n.col == col) return n;
            }
            return null;
        }
    }

    private static List<Tree> trees;

    private EvolutionTree() {}

    public static synchronized List<Tree> trees() {
        if (trees == null) trees = build();
        return trees;
    }

    private static List<Tree> build() {
        List<Evolution> tier1 = new ArrayList<>();
        for (Evolution e : Evolution.values()) {
            if (e.tier == 1) tier1.add(e);
        }

        List<Tree> result = new ArrayList<>();
        for (Evolution e : Evolution.values()) {
            if (e.tier == 1) continue;
            List<Evolution> rootParents = new ArrayList<>();
            for (Evolution t : tier1) {
                if (contains(t.evolutions, e.unitName)) rootParents.add(t);
            }
            if (!rootParents.isEmpty()) result.add(buildTree(e.unitName, rootParents));
        }
        return result;
    }

    private static Tree buildTree(String rootName, List<Evolution> tier1Parents) {
        Tree tree = new Tree(rootName);
        Map<String, Node> nodes = new LinkedHashMap<>();

        Node root = new Node(rootName, false);
        nodes.put(rootName, root);
        expand(root, nodes, new HashSet<>());

        int rootWidth = width(root);
        tree.width = Math.max(rootWidth, tier1Parents.size());
        place(root, (tree.width - rootWidth) / 2);

        // longest-path depth (handles convergence nodes with parents on different depths)
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Node n : nodes.values()) {
                for (Node c : n.children) {
                    if (c.row < n.row + 1) {
                        c.row = n.row + 1;
                        changed = true;
                    }
                }
            }
        }

        // convergence: span the union of all parents' columns
        for (Node n : nodes.values()) {
            if (n.parents.size() > 1) {
                int start = Integer.MAX_VALUE, end = Integer.MIN_VALUE;
                for (Node p : n.parents) {
                    start = Math.min(start, p.col);
                    end = Math.max(end, p.col + p.span);
                }
                n.col = start;
                n.span = end - start;
            }
        }

        // tier-1 parents share the tree width as evenly as possible
        int k = tier1Parents.size();
        int x = 0;
        for (int i = 0; i < k; i++) {
            int span = tree.width / k + (i < tree.width % k ? 1 : 0);
            Node t = new Node(tier1Parents.get(i).unitName, true);
            t.row = -1;
            t.col = x;
            t.span = span;
            x += span;
            t.children.add(root);
            root.parents.add(t);
            tree.nodes.add(t);
            tree.edges.add(new Edge(t, root));
        }

        for (Node n : nodes.values()) {
            tree.nodes.add(n);
            tree.maxRow = Math.max(tree.maxRow, n.row);
            for (Node c : n.children) tree.edges.add(new Edge(n, c));
        }
        return tree;
    }

    private static void expand(Node node, Map<String, Node> nodes, Set<String> path) {
        path.add(node.unit);
        Evolution evolution = Evolutions.evolutions.get(node.unit);
        if (evolution != null) {
            for (String childName : evolution.evolutions) {
                Evolution child = Evolutions.evolutions.get(childName);
                // unknown units and loops back to tier 1 end the branch
                if (child == null || child.tier == 1 || path.contains(childName)) continue;

                Node existing = nodes.get(childName);
                if (existing == null) {
                    Node c = new Node(childName, false);
                    c.owner = node;
                    nodes.put(childName, c);
                    node.children.add(c);
                    c.parents.add(node);
                    expand(c, nodes, path);
                } else if (!existing.parents.contains(node)) {
                    node.children.add(existing);
                    existing.parents.add(node);
                }
            }
        }
        path.remove(node.unit);
    }

    private static int width(Node node) {
        int sum = 0;
        for (Node c : node.children) {
            if (c.owner == node) sum += width(c);
        }
        return Math.max(1, sum);
    }

    private static void place(Node node, int col) {
        node.col = col;
        node.span = width(node);
        int x = col;
        for (Node c : node.children) {
            if (c.owner == node) {
                place(c, x);
                x += width(c);
            }
        }
    }

    private static boolean contains(String[] array, String value) {
        for (String s : array) {
            if (s.equals(value)) return true;
        }
        return false;
    }
}
