package dev.friendai.features.knowledge.travel;

import com.google.gson.JsonObject;
import dev.friendai.features.navigation.pathing.Pos;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;

/** Coarse graph of routes physically executed and, optionally, reverse-validated. */
public final class TrailGraph {
    private static final int MAX_NODES = 1_024;
    private static final int MAX_EDGES = 2_048;
    private final Map<NodeKey, Pos> nodes = new HashMap<>();
    private final Map<Long, Edge> edges = new HashMap<>();
    /** Outgoing edges per node in ascending id order, so a search never sorts the whole edge set per node. */
    private final Map<NodeKey, List<Edge>> outgoing = new HashMap<>();

    private Runnable onChange = () -> {};
    private long nextEdgeId = 1;

    /** Registers the owner that persists the book; it runs after every real graph mutation. */
    public synchronized void onChange(Runnable listener) {
        onChange = Objects.requireNonNull(listener);
    }

    public synchronized List<Long> recordSegment(
            String dimension, List<Pos> executed, long traversalTicks, boolean reverseValidated) {
        if (dimension == null || dimension.isBlank() || executed == null || executed.size() < 2 || traversalTicks < 0)
            throw new IllegalArgumentException("invalid executed trail");
        List<Pos> samples = sample(executed);
        List<Long> created = new ArrayList<>();
        double total = length(executed);
        for (int i = 1; i < samples.size(); i++) {
            Pos a = samples.get(i - 1), b = samples.get(i);
            if (a.equals(b)) continue;
            if (nodes.size() + 2 > MAX_NODES) break;
            nodes.putIfAbsent(new NodeKey(dimension, a), a);
            nodes.putIfAbsent(new NodeKey(dimension, b), b);
            if (edges.size() >= MAX_EDGES) break;
            double segment = distance(a, b);
            long ticks = total <= 0 ? 0 : Math.max(1, Math.round(traversalTicks * segment / total));
            long forward = nextEdgeId++;
            Edge forwardEdge = new Edge(forward, dimension, a, b, ticks, false);
            edges.put(forward, forwardEdge);
            addOutgoing(forwardEdge);
            created.add(forward);
            if (reverseValidated && edges.size() < MAX_EDGES) {
                long reverse = nextEdgeId++;
                Edge reverseEdge = new Edge(reverse, dimension, b, a, ticks, false);
                edges.put(reverse, reverseEdge);
                addOutgoing(reverseEdge);
                created.add(reverse);
            }
        }
        if (!created.isEmpty()) onChange.run();
        return List.copyOf(created);
    }

    public synchronized void invalidate(long edgeId) {
        Edge edge = edges.get(edgeId);
        if (edge == null || edge.invalid()) return;
        Edge invalid = new Edge(edge.id(), edge.dimension(), edge.from(), edge.to(), edge.ticks(), true);
        edges.put(edgeId, invalid);
        List<Edge> list = outgoing.get(new NodeKey(edge.dimension(), edge.from()));
        if (list != null) {
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).id() == edgeId) {
                    list.set(i, invalid);
                    break;
                }
            }
        }
        onChange.run();
    }

    /** Returns known graph waypoints only; no direct connection is inferred across unseen space. */
    public synchronized List<Pos> route(String dimension, Pos from, Pos to) {
        NodeKey start = nearest(dimension, from), goal = nearest(dimension, to);
        if (start == null || goal == null) return List.of();
        Map<NodeKey, Long> distance = new HashMap<>();
        Map<NodeKey, NodeKey> previous = new HashMap<>();
        PriorityQueue<QueueNode> queue = new PriorityQueue<>(Comparator.comparingLong(QueueNode::cost)
                .thenComparingInt(node -> node.key().pos().x())
                .thenComparingInt(node -> node.key().pos().y())
                .thenComparingInt(node -> node.key().pos().z()));
        distance.put(start, 0L);
        queue.add(new QueueNode(start, 0));
        while (!queue.isEmpty()) {
            QueueNode current = queue.remove();
            if (current.cost() != distance.getOrDefault(current.key(), Long.MAX_VALUE)) continue;
            if (current.key().equals(goal)) break;
            for (Edge edge : outgoing.getOrDefault(current.key(), List.of())) {
                if (edge.invalid()
                        || !edge.dimension().equals(dimension)
                        || !edge.from().equals(current.key().pos())) continue;
                NodeKey next = new NodeKey(dimension, edge.to());
                long candidate = current.cost() + edge.ticks();
                if (candidate < distance.getOrDefault(next, Long.MAX_VALUE)) {
                    distance.put(next, candidate);
                    previous.put(next, current.key());
                    queue.add(new QueueNode(next, candidate));
                }
            }
        }
        if (!distance.containsKey(goal)) return List.of();
        List<Pos> path = new ArrayList<>();
        for (NodeKey at = goal; at != null; at = previous.get(at)) {
            path.add(at.pos());
            if (at.equals(start)) break;
        }
        java.util.Collections.reverse(path);
        return List.copyOf(path);
    }

    public synchronized int nodeCount() {
        return nodes.size();
    }

    public synchronized int edgeCount() {
        return edges.size();
    }

    /** True when a recorded trail node lies within {@code radius} of {@code pos} in this dimension. */
    public synchronized boolean hasNodeNear(String dimension, Pos pos, double radius) {
        if (dimension == null || pos == null || radius <= 0) return false;
        double limit = radius * radius;
        for (NodeKey key : nodes.keySet()) {
            if (!key.dimension().equals(dimension)) continue;
            Pos node = key.pos();
            double dx = (double) node.x() - pos.x(), dy = (double) node.y() - pos.y(), dz = (double) node.z() - pos.z();
            if (dx * dx + dy * dy + dz * dz <= limit) return true;
        }
        return false;
    }

    public synchronized List<JsonObject> toJson() {
        return edges.values().stream()
                .map(edge -> {
                    JsonObject row = new JsonObject();
                    row.addProperty("id", edge.id());
                    row.addProperty("dimension", edge.dimension());
                    row.addProperty("fx", edge.from().x());
                    row.addProperty("fy", edge.from().y());
                    row.addProperty("fz", edge.from().z());
                    row.addProperty("tx", edge.to().x());
                    row.addProperty("ty", edge.to().y());
                    row.addProperty("tz", edge.to().z());
                    row.addProperty("ticks", edge.ticks());
                    row.addProperty("invalid", edge.invalid());
                    return row;
                })
                .toList();
    }

    public static TrailGraph fromJson(List<JsonObject> rows) {
        TrailGraph graph = new TrailGraph();
        if (rows == null) return graph;
        if (rows.size() > MAX_EDGES) throw new IllegalArgumentException("too many trail edges");
        for (JsonObject row : rows) {
            String[] fields = {"id", "dimension", "fx", "fy", "fz", "tx", "ty", "tz", "ticks", "invalid"};
            if (row == null || java.util.Arrays.stream(fields).anyMatch(field -> !row.has(field)))
                throw new IllegalArgumentException("invalid trail edge");
            long id = row.get("id").getAsLong(), ticks = row.get("ticks").getAsLong();
            String dimension = row.get("dimension").getAsString();
            Pos from = new Pos(
                    row.get("fx").getAsInt(),
                    row.get("fy").getAsInt(),
                    row.get("fz").getAsInt());
            Pos to = new Pos(
                    row.get("tx").getAsInt(),
                    row.get("ty").getAsInt(),
                    row.get("tz").getAsInt());
            if (id < 1 || ticks < 0 || dimension.isBlank() || from.equals(to))
                throw new IllegalArgumentException("invalid trail edge values");
            if (graph.edges.size() >= MAX_EDGES || graph.edges.containsKey(id))
                throw new IllegalArgumentException("duplicate or excessive trail edge");
            graph.nodes.put(new NodeKey(dimension, from), from);
            graph.nodes.put(new NodeKey(dimension, to), to);
            Edge edge =
                    new Edge(id, dimension, from, to, ticks, row.get("invalid").getAsBoolean());
            graph.edges.put(id, edge);
            graph.addOutgoing(edge);
            graph.nextEdgeId = Math.max(graph.nextEdgeId, id + 1);
        }
        if (graph.nodes.size() > MAX_NODES) throw new IllegalArgumentException("too many trail nodes");
        graph.outgoing.values().forEach(list -> list.sort(Comparator.comparingLong(Edge::id)));
        return graph;
    }

    private NodeKey nearest(String dimension, Pos target) {
        return nodes.keySet().stream()
                .filter(key -> key.dimension().equals(dimension))
                .filter(key -> distance(key.pos(), target) <= 8.0)
                .min(Comparator.comparingDouble((NodeKey key) -> distance(key.pos(), target))
                        .thenComparingInt(key -> key.pos().x())
                        .thenComparingInt(key -> key.pos().y())
                        .thenComparingInt(key -> key.pos().z()))
                .orElse(null);
    }

    private static List<Pos> sample(List<Pos> route) {
        List<Pos> result = new ArrayList<>();
        result.add(route.get(0));
        double since = 0;
        for (int i = 1; i < route.size(); i++) {
            Pos prior = route.get(i - 1), current = route.get(i);
            since += distance(prior, current);
            boolean turn = i < route.size() - 1 && changedDirection(prior, current, route.get(i + 1));
            if (since >= 8 || turn || i == route.size() - 1) {
                result.add(current);
                since = 0;
            }
        }
        return result;
    }

    private void addOutgoing(Edge edge) {
        outgoing.computeIfAbsent(new NodeKey(edge.dimension(), edge.from()), ignored -> new ArrayList<>())
                .add(edge);
    }

    private static boolean changedDirection(Pos a, Pos b, Pos c) {
        int ax = Integer.compare(b.x(), a.x()), az = Integer.compare(b.z(), a.z());
        int bx = Integer.compare(c.x(), b.x()), bz = Integer.compare(c.z(), b.z());
        return ax != bx || az != bz;
    }

    private static double length(List<Pos> positions) {
        double result = 0;
        for (int i = 1; i < positions.size(); i++) result += distance(positions.get(i - 1), positions.get(i));
        return result;
    }

    private static double distance(Pos a, Pos b) {
        double x = (double) a.x() - b.x(), y = (double) a.y() - b.y(), z = (double) a.z() - b.z();
        return Math.sqrt(x * x + y * y + z * z);
    }

    private record NodeKey(String dimension, Pos pos) {}

    private record Edge(long id, String dimension, Pos from, Pos to, long ticks, boolean invalid) {}

    private record QueueNode(NodeKey key, long cost) {}
}
