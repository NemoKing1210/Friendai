package dev.friendai.execution.queue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Deterministic pure interpreter for bounded conditional plans. */
public final class PlanInterpreter {
    private static final int MAX_CONTROL_TRANSITIONS_PER_ADVANCE = 256;

    private PlanInterpreter() {}

    public sealed interface ActionResult permits ActionResult.None, ActionResult.Succeeded, ActionResult.Failed {
        record None() implements ActionResult {}

        record Succeeded() implements ActionResult {}

        record Failed(String summary) implements ActionResult {
            public Failed {
                summary = summary == null || summary.isBlank() ? "action failed" : summary;
            }
        }
    }

    public sealed interface Event permits Event.EmitAction, Event.Complete, Event.Failed {
        record EmitAction(QueuedStep step, String path) implements Event {}

        record Complete() implements Event {}

        record Failed(String path, String reason) implements Event {}
    }

    public record Transition(PlanCursor cursor, Event event) {}

    public static Transition advance(PlanDefinition plan, PlanCursor cursor, GuardFacts facts, ActionResult result) {
        if (plan == null || cursor == null || facts == null || result == null)
            throw new IllegalArgumentException("interpreter input");
        Mutable state = new Mutable(cursor);
        if (!cursor.pendingActionPath().isEmpty()) {
            if (result instanceof ActionResult.Failed failed) {
                return failed(state, cursor.pendingActionPath(), failed.summary());
            }
            if (result instanceof ActionResult.None) {
                return new Transition(cursor, new Event.EmitAction(cursor.pendingAction(), cursor.pendingActionPath()));
            }
            state.pendingPath = "";
            state.pendingAction = null;
            state.pendingJobId = 0;
        } else if (!(result instanceof ActionResult.None)) {
            return failed(state, "plan", "received an action result while no action was pending");
        }

        int transitions = 0;
        while (!state.stack.isEmpty()) {
            if (++transitions > MAX_CONTROL_TRANSITIONS_PER_ADVANCE) {
                return failed(state, "plan", "interpreter advancement limit exceeded");
            }
            PlanCursor.Frame frame = state.stack.getLast();
            List<PlanNode> sequence = resolveSequence(plan, frame.route());
            if (frame.nextIndex() >= sequence.size()) {
                state.stack.removeLast();
                if (frame.repeatBody()) {
                    PlanNode.Repeat repeat = (PlanNode.Repeat) nodeAtRoute(plan, frame.route());
                    int iteration = state.repeatIterations.getOrDefault(frame.ownerPath(), 1);
                    if (repeat.until().holds(facts)) continue;
                    if (iteration >= repeat.maxIterations()) {
                        return failed(
                                state,
                                frame.ownerPath() + " → repeat → until",
                                "repeat limit reached at " + iteration + "/" + repeat.maxIterations());
                    }
                    state.repeatIterations.put(frame.ownerPath(), iteration + 1);
                    state.stack.add(repeatFrame(frame.route(), frame.ownerPath(), repeat));
                }
                continue;
            }

            int index = frame.nextIndex();
            PlanNode node = sequence.get(index);
            String nodePath = nodePath(frame, index + 1);
            state.replaceLast(withNext(frame, index + 1));
            if (node instanceof PlanNode.Action action) {
                state.pendingPath = nodePath;
                state.pendingAction = action.step();
                state.pendingJobId = 0;
                return new Transition(state.freeze(), new Event.EmitAction(action.step(), nodePath));
            }
            if (node instanceof PlanNode.Conditional conditional) {
                boolean selectedThen = conditional.condition().holds(facts);
                state.selectedBranches.put(nodePath, selectedThen);
                List<PlanNode> selected = selectedThen ? conditional.thenNodes() : conditional.elseNodes();
                if (!selected.isEmpty()) {
                    int kind = selectedThen ? 1 : 2;
                    state.stack.add(new PlanCursor.Frame(
                            appendRoute(frame.route(), index, kind),
                            0,
                            nodePath + (selectedThen ? " → then" : " → else"),
                            nodePath,
                            false));
                }
                continue;
            }
            PlanNode.Repeat repeat = (PlanNode.Repeat) node;
            if (repeat.until().holds(facts)) {
                state.repeatIterations.put(nodePath, 0);
                continue;
            }
            state.repeatIterations.put(nodePath, 1);
            state.stack.add(repeatFrame(appendRoute(frame.route(), index, 3), nodePath, repeat));
        }
        return new Transition(state.freeze(), new Event.Complete());
    }

    private static PlanCursor.Frame repeatFrame(List<Integer> route, String path, PlanNode.Repeat repeat) {
        return new PlanCursor.Frame(route, 0, path + " → repeat", path, true);
    }

    private static PlanNode nodeAtRoute(PlanDefinition plan, List<Integer> route) {
        if (route.size() < 2 || route.getLast() != 3) throw new IllegalStateException("invalid repeat cursor route");
        List<PlanNode> sequence = resolveSequence(plan, route.subList(0, route.size() - 2));
        return sequence.get(route.get(route.size() - 2));
    }

    private static List<PlanNode> resolveSequence(PlanDefinition plan, List<Integer> route) {
        List<PlanNode> sequence = plan.nodes();
        for (int offset = 0; offset < route.size(); offset += 2) {
            PlanNode parent = sequence.get(route.get(offset));
            int kind = route.get(offset + 1);
            if (parent instanceof PlanNode.Conditional conditional && kind == 1) sequence = conditional.thenNodes();
            else if (parent instanceof PlanNode.Conditional conditional && kind == 2)
                sequence = conditional.elseNodes();
            else if (parent instanceof PlanNode.Repeat repeat && kind == 3) sequence = repeat.body();
            else throw new IllegalStateException("invalid cursor route");
        }
        return sequence;
    }

    private static String nodePath(PlanCursor.Frame frame, int index) {
        if (frame.pathPrefix().isEmpty()) return "step " + index;
        return frame.repeatBody() ? frame.pathPrefix() + " → step " + index : frame.pathPrefix() + " #" + index;
    }

    private static PlanCursor.Frame withNext(PlanCursor.Frame frame, int next) {
        return new PlanCursor.Frame(frame.route(), next, frame.pathPrefix(), frame.ownerPath(), frame.repeatBody());
    }

    private static List<Integer> appendRoute(List<Integer> route, int nodeIndex, int kind) {
        List<Integer> result = new ArrayList<>(route);
        result.add(nodeIndex);
        result.add(kind);
        return result;
    }

    private static Transition failed(Mutable state, String path, String reason) {
        return new Transition(state.freeze(), new Event.Failed(path, reason));
    }

    private static final class Mutable {
        private final List<PlanCursor.Frame> stack;
        private final Map<String, Boolean> selectedBranches;
        private final Map<String, Integer> repeatIterations;
        private final long startTick;
        private final long startDistance;
        private String pendingPath;
        private QueuedStep pendingAction;
        private long pendingJobId;

        private Mutable(PlanCursor cursor) {
            stack = new ArrayList<>(cursor.stack());
            selectedBranches = new HashMap<>(cursor.selectedBranches());
            repeatIterations = new HashMap<>(cursor.repeatIterations());
            startTick = cursor.startTick();
            startDistance = cursor.startDistance();
            pendingPath = cursor.pendingActionPath();
            pendingAction = cursor.pendingAction();
            pendingJobId = cursor.pendingJobId();
        }

        private void replaceLast(PlanCursor.Frame frame) {
            stack.set(stack.size() - 1, frame);
        }

        private PlanCursor freeze() {
            return new PlanCursor(
                    stack,
                    selectedBranches,
                    repeatIterations,
                    startTick,
                    startDistance,
                    pendingPath,
                    pendingAction,
                    pendingJobId);
        }
    }
}
