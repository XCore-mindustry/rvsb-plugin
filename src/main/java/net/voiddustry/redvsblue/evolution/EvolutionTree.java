package net.voiddustry.redvsblue.evolution;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Grid layout of the evolution trees, derived from {@link Evolutions}.
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

    /** Drops the cached layout; the next {@link #trees()} call rebuilds it from the reloaded tree. */
    static synchronized void reset() {
        trees = null;
    }

    private static List<Tree> build() {
        List<Evolution> all = Evolutions.all();
        List<Evolution> tier1 = new ArrayList<>();
        for (Evolution e : all) {
            if (e.tier == 1) tier1.add(e);
        }

        List<Tree> result = new ArrayList<>();
        for (Evolution e : all) {
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

        // A row can contain more nodes than the owner-based leaf width when
        // multiple branches converge into multiple shared children.
        Map<Integer, Integer> rowCounts = new LinkedHashMap<>();
        for (Node n : nodes.values()) rowCounts.merge(n.row, 1, Integer::sum);
        tree.width = Math.max(rootWidth, tier1Parents.size());
        for (int count : rowCounts.values()) tree.width = Math.max(tree.width, count);
        place(root, (tree.width - rootWidth) / 2);

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

        // Shared children can have identical parent spans. Keep every node in
        // a row visible instead of letting the first spanning cell hide the rest.
        for (int row : rowCounts.keySet()) {
            List<Node> rowNodes = new ArrayList<>();
            for (Node n : nodes.values()) if (n.row == row) rowNodes.add(n);
            // Nodes sharing exactly the same parent span should be distributed
            // around its centre, rather than packed into the leftmost columns.
            Set<Node> distributed = new HashSet<>();
            for (Node first : rowNodes) {
                if (distributed.contains(first)) continue;
                List<Node> shared = new ArrayList<>();
                for (Node other : rowNodes) {
                    if (other.col == first.col && other.span == first.span) shared.add(other);
                }
                if (shared.size() < 2 || shared.size() > first.span) continue;
                int start = first.col, span = first.span;
                double centre = start + (span - 1) / 2.0;
                double step = (double) span / shared.size();
                for (int i = 0; i < shared.size(); i++) {
                    double target = centre + (i - (shared.size() - 1) / 2.0) * step;
                    Node n = shared.get(i);
                    // Round half-cell ties inward to keep pairs centered without an extra gap.
                    n.col = (int) (i < shared.size() / 2 ? Math.floor(target + 0.5) : Math.ceil(target - 0.5));
                    n.span = 1;
                    distributed.add(n);
                }
            }
            rowNodes.sort((a, b) -> Integer.compare(a.center(), b.center()));
            boolean overlaps = false;
            int right = -1;
            for (Node n : rowNodes) {
                if (n.col < right) overlaps = true;
                right = Math.max(right, n.col + n.span);
            }
            if (!overlaps) continue;

            int[] cols = new int[rowNodes.size()];
            int next = tree.width;
            for (int i = rowNodes.size() - 1; i >= 0; i--) {
                cols[i] = Math.min(next - 1, Math.round((rowNodes.get(i).center() - 1) / 2f));
                next = cols[i];
            }
            int previous = -1;
            for (int i = 0; i < rowNodes.size(); i++) {
                Node n = rowNodes.get(i);
                n.col = Math.max(previous + 1, cols[i]);
                n.span = 1;
                previous = n.col;
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

    // Shared children with the same parents form one convergence group, not
    // separate branches in their first owner's row. Reserving a column for
    // each child there creates a gap between the other parent branches.
    private static boolean sameConvergence(Node a, Node b) {
        return a.parents.size() > 1 && a.parents.size() == b.parents.size()
                && a.parents.containsAll(b.parents);
    }

    private static int width(Node node) {
        int sum = 0;
        Set<Node> grouped = new HashSet<>();
        for (Node c : node.children) {
            if (c.owner != node || grouped.contains(c)) continue;
            int groupWidth = width(c);
            grouped.add(c);
            for (Node sibling : node.children) {
                if (sibling.owner == node && !grouped.contains(sibling) && sameConvergence(c, sibling)) {
                    groupWidth = Math.max(groupWidth, width(sibling));
                    grouped.add(sibling);
                }
            }
            sum += groupWidth;
        }
        return Math.max(1, sum);
    }

    private static void place(Node node, int col) {
        node.col = col;
        node.span = width(node);
        int x = col;
        Set<Node> grouped = new HashSet<>();
        for (Node c : node.children) {
            if (c.owner != node || grouped.contains(c)) continue;
            int groupWidth = width(c);
            place(c, x);
            grouped.add(c);
            for (Node sibling : node.children) {
                if (sibling.owner == node && !grouped.contains(sibling) && sameConvergence(c, sibling)) {
                    place(sibling, x);
                    groupWidth = Math.max(groupWidth, width(sibling));
                    grouped.add(sibling);
                }
            }
            x += groupWidth;
        }
    }

    private static boolean contains(String[] array, String value) {
        for (String s : array) {
            if (s.equals(value)) return true;
        }
        return false;
    }
}
