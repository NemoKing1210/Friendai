package dev.friendai.task;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.core.FriendaiServerState;
import dev.friendai.core.CreativeModeService;
import dev.friendai.creative.CreativeAction;
import dev.friendai.creative.CreativeActionContext;
import dev.friendai.place.PlaceCandidate;
import dev.friendai.queue.FailurePolicy;
import dev.friendai.queue.GuardAction;
import dev.friendai.queue.GuardFacts;
import dev.friendai.queue.Job;
import dev.friendai.queue.JobQueue;
import dev.friendai.queue.JobState;
import dev.friendai.queue.PlanOptions;
import dev.friendai.queue.PlanRecord;
import dev.friendai.queue.PlanDefinition;
import dev.friendai.queue.PlanCursor;
import dev.friendai.queue.PlanInterpreter;
import dev.friendai.queue.PlanNode;
import dev.friendai.queue.PlanStatus;
import dev.friendai.queue.QueueLimits;
import dev.friendai.queue.QueuePolicy;
import dev.friendai.queue.QueueSave;
import dev.friendai.queue.QueueText;
import dev.friendai.queue.QueuedStep;
import dev.friendai.movement.MovementOrders;
import dev.friendai.task.builtin.FollowTask;
import dev.friendai.task.plan.StepFactory;
import dev.friendai.task.plan.PlanEntry;
import dev.friendai.task.plan.SequenceTask;
import dev.friendai.task.plan.PlanStepFactory;
import dev.friendai.task.queue.CompanionGuardFacts;
import dev.friendai.tool.Caller;
import dev.friendai.queue.QueueTradeScope;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Runs one task at a time and schedules durable jobs when no urgent or legacy task owns the runner. */
public final class TaskRunner {
    private static final int POLL_INTERVAL_TICKS = 10;
    private static final Logger LOGGER = LoggerFactory.getLogger("friendai/task");
    private record Active(int id, long jobId, long planId, String toolName, Task task, boolean emit,
                          boolean defaultActivity, boolean interrupt, int startActiveTick,
                          CreativeActionContext creativeActionContext, boolean creativeSensitive) {
        private Active(int id, long jobId, long planId, String toolName, Task task, boolean emit,
                       boolean defaultActivity, boolean interrupt, int startActiveTick) {
            this(id, jobId, planId, toolName, task, emit, defaultActivity, interrupt, startActiveTick, null, false);
        }
    }

    private final CompanionEntity companion;
    private final IntSupplier timeoutSec;
    private final BooleanSupplier defaultFollow;
    /** Whether the idle fallback may walk to the owner; free time narrows it (see DefaultActivityPolicy). */
    private final BooleanSupplier mayFollowOwner;
    private JobQueue queue;
    private final Map<Long, StepFactory> factories = new HashMap<>();
    private final Map<Long, Map<String, PlanStepFactory>> structuredFactories = new HashMap<>();
    private final Map<Long, String> blockedPlans = new HashMap<>();
    /**
     * Plans whose remaining steps wait for a decision (a failed step, a missing tool result). The companion stands
     * and reports them instead of walking to the owner: a follow would preempt the work the failure is about.
     */
    private final java.util.Set<Long> awaitingDecision = new java.util.HashSet<>();
    /** A movement order is a task without a job behind it, so waiting in place never pins the queue. */
    private static boolean movementOrder(Active active) {
        return active != null && active.jobId() == 0 && MovementOrders.isOrder(active.toolName());
    }

    /** Queue plans that free time started; their jobs are autonomous work. */
    private final java.util.Set<Long> autonomousPlans = new java.util.HashSet<>();
    private Active current;
    private Active frozen;
    private int frozenElapsed;
    private int nextId = 1;
    private int activeTicks;
    private long blockedSince = -1;
    private long nextPoll;
    private CompanionGuardFacts sharedFacts;
    private TaskListener listener;
    private final List<String> pendingNotices = new java.util.ArrayList<>();
    private boolean paused;
    private boolean suspended;
    private int holds;

    public TaskRunner(CompanionEntity companion, IntSupplier timeoutSec, BooleanSupplier defaultFollow) {
        this(companion, timeoutSec, defaultFollow, () -> true);
    }

    /** A runner that knows whether following is its business at all; the real entity passes the free-time gate. */
    public TaskRunner(CompanionEntity companion, IntSupplier timeoutSec, BooleanSupplier defaultFollow,
                      BooleanSupplier mayFollowOwner) {
        this.companion=companion; this.timeoutSec=timeoutSec; this.defaultFollow=defaultFollow;
        this.mayFollowOwner=mayFollowOwner;
        this.queue=new JobQueue(TaskSettings.queueLimits());
    }
    public void setListener(TaskListener listener) { this.listener=listener; if(listener!=null){for(String notice:pendingNotices)listener.onNotice(notice);pendingNotices.clear();} }
    public void setPaused(boolean paused) { this.paused=paused; }
    public boolean paused() { return paused || holds>0; }
    public void suspend() { suspended=true; }
    public void resume() { suspended=false; }
    public boolean suspended() { return suspended; }
    public void hold() { holds++; }
    public void release() { if(holds>0)holds--; }
    public boolean held() { return holds>0; }
    public void notice(String text) { if(listener!=null)listener.onNotice(text);else if(text!=null){if(pendingNotices.size()==16)pendingNotices.removeFirst();pendingNotices.add(text);} }
    public void debug(String text) { if(listener!=null)listener.onDebug(text); }
    public Optional<Task> current() { return Optional.ofNullable(current).map(Active::task); }
    /** Current task context for adapters such as movement controllers that tick below the Task boundary. */
    public TaskContext activeTaskContext() { return context(); }
    public boolean currentIsDefaultActivity() { return current != null && current.defaultActivity(); }
    public boolean currentCreativeActionAllowed(CreativeAction action) {
        return current != null && CreativeModeService.isActionAuthorized(
                companion, current.creativeActionContext(), action);
    }
    public int currentId() { return current==null?0:current.id(); }
    public JobQueue queue() { return queue; }
    /** Marks a queue plan as free time's own, so its jobs act under the autonomous rules. */
    public void markAutonomousPlan(long planId) { autonomousPlans.add(planId); }
    public void unmarkAutonomousPlan(long planId) { autonomousPlans.remove(planId); }
    /** True while the running task is a job of a plan free time started. */
    public boolean autonomousActive() { return current!=null && current.planId()>0 && autonomousPlans.contains(current.planId()); }
    /** True while a queued job belongs to the owner rather than to a plan free time started. */
    public boolean ownerWorkQueued() {
        for(Job job:queue.jobs())if(!JobQueue.terminal(job)&&!autonomousPlans.contains(job.planId()))return true;
        return false;
    }
    /** The tool that started the running task, empty when nothing runs. */
    public String currentToolName() { return current==null?"":current.toolName(); }
    /**
     * True while a task that is neither the default activity, free time's nor a movement order runs: owner work comes
     * first. A movement order ("wait here", "come here") says where to be, not what to do, so free time keeps its
     * shift instead of reading it as work in the way.
     */
    public boolean busyWithOther() {
        return current!=null && !current.defaultActivity() && !autonomousActive() && !movementOrder(current);
    }
    /** True while the runner is only holding a movement order, which queued work may end. */
    public boolean movementOrderActive() { return movementOrder(current); }
    public Optional<Job> activeJob() { return queue.active(); }
    public int pendingJobs() { return (int)queue.jobs().stream().filter(j->j.state()==JobState.PENDING).count(); }
    public boolean hasWork() { return queue.jobCount()>0 || (current!=null&&!current.defaultActivity()); }
    public java.util.List<dev.friendai.debug.QueueRow> queueRows() { return queue.jobCount()==0 && queue.plans().stream().noneMatch(p->p.cursor()!=null)?java.util.List.of():dev.friendai.debug.QueueRows.build(queue,facts(),current==null?null:current.task().describe()); }
    public String queueSummary() { return queue.jobCount()==0 && queue.plans().stream().noneMatch(p->p.cursor()!=null)?current().map(Task::describe).orElse("none"):QueueText.summary(queue,facts(),current==null?null:current.task().describe()); }

    public long enqueue(QueuedStep step, StepFactory factory) {
        requireCreativeAuthorization(step.principal(), step.creativeSensitive());
        Job job=queue.addDirect(step); factories.put(job.id(),factory); wakeQueue();
        if(current==null || current.defaultActivity() || movementOrder(current)) startNextQueued(); return job.id();
    }
    public long enqueuePlan(String goal,List<QueuedStep> steps,List<StepFactory> stepFactories,PlanOptions options) { return enqueuePlan(goal,steps,stepFactories,options,id->{}); }
    /** {@code prepare} runs with the new plan id after it is queued but before any of its jobs can start. */
    public long enqueuePlan(String goal,List<QueuedStep> steps,List<StepFactory> stepFactories,PlanOptions options,java.util.function.LongConsumer prepare) {
        if(steps.size()!=stepFactories.size())throw new IllegalArgumentException("steps and factories differ");
        for (QueuedStep step : steps) requireCreativeAuthorization(step.principal(), step.creativeSensitive());
        JobQueue.Added added=queue.addPlan(goal,steps,options); for(int i=0;i<stepFactories.size();i++)factories.put(added.jobIds().get(i),stepFactories.get(i));
        prepare.accept(added.planId());
        wakeQueue(); if(current==null || current.defaultActivity() || movementOrder(current))startNextQueued(); return added.planId();
    }
    public long enqueueStructuredPlan(String goal, PlanDefinition definition, PlanOptions options,
                                      Map<String, PlanStepFactory> stepFactories) {
        for (PlanNode node : definition.nodes()) validateStructuredAuthorization(node);
        facts();
        long startTick = companion.level().getGameTime();
        long startDistance = sharedFacts == null ? 0 : sharedFacts.totalTravelledDistance();
        long planId = queue.addStructuredPlan(goal, definition, options);
        queue.updatePlanCursor(planId, PlanCursor.start(startTick, startDistance));
        structuredFactories.put(planId, Map.copyOf(stepFactories));
        wakeQueue(); if(current==null || current.defaultActivity() || movementOrder(current))startNextQueued(); return planId;
    }
    public long enqueueStructuredPlan(PlanDefinition definition, PlanOptions options,
                                      Map<String, PlanStepFactory> stepFactories) {
        return enqueueStructuredPlan("", definition, options, stepFactories);
    }
    private void validateStructuredAuthorization(PlanNode node) {
        if (node instanceof PlanNode.Action action) requireCreativeAuthorization(action.step().principal(), action.step().creativeSensitive());
        else if (node instanceof PlanNode.Conditional conditional) {
            conditional.thenNodes().forEach(this::validateStructuredAuthorization);
            conditional.elseNodes().forEach(this::validateStructuredAuthorization);
        } else ((PlanNode.Repeat) node).body().forEach(this::validateStructuredAuthorization);
    }
    public void wakeQueue() { blockedSince=-1; nextPoll=0; factories.keySet().removeIf(id->queue.job(id).isEmpty()); }
    public void registerFactory(long jobId,StepFactory factory){factories.put(jobId,factory);}

    /** Legacy task entry point. A new task freezes a queue job so the queue can resume afterwards. */
    public int start(Task task,String toolName) {
        return start(task, toolName, null, false);
    }
    public int start(Task task, String toolName, Caller caller, boolean creativeSensitive) {
        CreativeActionContext actionContext = actionContext(caller, creativeSensitive);
        requireCreativeAuthorization(actionContext == null ? null : actionContext.principal(), creativeSensitive);
        if(current!=null && current.jobId()>0 && !current.interrupt()) freezeCurrent();
        else if(current!=null && current.defaultActivity()) clearCurrentSilently();
        else if(current!=null) cancelCurrent("replaced by a new task",true);
        return begin(task,toolName,task.emitsEvents(),false,actionContext,creativeSensitive);
    }
    public void restore(Task task) { begin(task,task.type(),false,false); }

    public void interrupt(Task task,String toolName) {
        interrupt(task, toolName, null, false);
    }
    public void interrupt(Task task, String toolName, Caller caller, boolean creativeSensitive) {
        CreativeActionContext actionContext = actionContext(caller, creativeSensitive);
        requireCreativeAuthorization(actionContext == null ? null : actionContext.principal(), creativeSensitive);
        if(current!=null && current.interrupt()) { cancelCurrent("replaced by urgent task",false); }
        else if(current!=null && current.jobId()>0) freezeCurrent();
        else if(current!=null && current.defaultActivity()) clearCurrentSilently();
        else if(current!=null) cancelCurrent("interrupted by urgent task",true);
        begin(task,toolName,task.emitsEvents(),true,actionContext,creativeSensitive);
    }
    private void freezeCurrent() { frozen=current; frozenElapsed=Math.max(0,activeTicks-current.startActiveTick()); current=null; }

    public void stop(String reason) {
        if(current!=null && current.interrupt()) { cancelCurrent(reason,false); resumeFrozen(); return; }
        Active active=current;
        if(active!=null && active.jobId()>0) {
            Task cleanup=active.task().cleanupAfterCancel(context()); cancelCurrent(reason,false);
            if(active.planId()>0) {
                queue.cancelPlan(active.planId()); finishPlan(active.planId(),"stopped by request",false);
            } else { queue.cancel(active.jobId()); queue.remove(active.jobId()); factories.remove(active.jobId()); }
            if(cleanup!=null)begin(cleanup,"scaffold_cleanup",false,false); else startNextOrDefault(); return;
        }
        Task cleanup=active==null?null:active.task().cleanupAfterCancel(context());
        if(active!=null)cancelCurrent(reason,false);
        if(cleanup!=null)begin(cleanup,"scaffold_cleanup",false,false);else startNextOrDefault();
    }
    public void stopWithoutCleanup(String reason) {
        if(current!=null)cancelCurrent(reason,false);
        for(PlanRecord plan:queue.plans()){queue.cancelPlan(plan.id());finishPlan(plan.id(),reason,false);}
        if(frozen!=null){safeStop(frozen.task(),context());frozen=null;}
        queue.clear();factories.clear();structuredFactories.clear();awaitingDecision.clear();startDefault();
    }
    /** Cancels stale work during a capability transition without reporting task failures to the brain. */
    public void cancelForCapabilityChange() {
        Active active = current;
        Task cleanup = active == null ? null : active.task().cleanupAfterCancel(context());
        if (frozen != null) {
            cleanup = combineCleanup(cleanup, frozen.task().cleanupAfterCancel(context()));
            safeStop(frozen.task(), context());
            frozen = null;
        }
        if (active != null) {
            safeStop(active.task(), context());
            current = null;
        }
        queue.clear();
        factories.clear();
        blockedPlans.clear();
        awaitingDecision.clear();
        frozenElapsed = 0;
        if (cleanup != null) begin(cleanup, "scaffold_cleanup", false, false);
        else startDefault();
    }

    public void stopAll(String reason) {
        Active active=current;
        Task cleanup=active==null?null:active.task().cleanupAfterCancel(context());
        if(frozen!=null){cleanup=combineCleanup(cleanup,frozen.task().cleanupAfterCancel(context()));safeStop(frozen.task(),context());frozen=null;}
        if(active!=null)cancelCurrent(reason,false);
        for(PlanRecord p:queue.plans()) finishPlan(p.id(),"stopped by request",false);
        queue.clear(); factories.clear(); awaitingDecision.clear();
        if(cleanup!=null)begin(cleanup,"scaffold_cleanup",false,false);else startDefault();
    }
    public String clearQueue(String reason) {
        Active active=current;
        Task cleanup=active!=null&&active.jobId()>0?active.task().cleanupAfterCancel(context()):null;
        if(frozen!=null){cleanup=combineCleanup(cleanup,frozen.task().cleanupAfterCancel(context()));safeStop(frozen.task(),context());frozen=null;}
        if(active!=null&&active.jobId()>0)cancelCurrent(reason,false);
        for(PlanRecord plan:queue.plans())finishPlan(plan.id(),reason,false);
        String result=queue.clear().text(); factories.clear(); awaitingDecision.clear();
        if(cleanup!=null)begin(cleanup,"scaffold_cleanup",false,false);else startNextOrDefault(); return result;
    }
    public void cancelJob(long id,String reason) {
        Job original=queue.job(id).orElse(null);Active active=current;
        Task cleanup=null;
        if(active!=null&&active.jobId()==id) { cleanup=active.task().cleanupAfterCancel(context()); cancelCurrent(reason,false); queue.cancel(id); factories.remove(id); }
        else { if(frozen!=null&&frozen.jobId()==id){cleanup=frozen.task().cleanupAfterCancel(context());safeStop(frozen.task(),context());frozen=null;} queue.cancel(id); factories.remove(id); }
        Job j=queue.job(id).orElse(null); if(j!=null&&j.planId()==0)queue.remove(id);else if(j!=null&&isStructuredPlan(j.planId())){queue.cancelPlan(j.planId());finishPlan(j.planId(),"action cancelled",false);}else if(j!=null&&j.planId()>0&&queue.planStatus(j.planId())!=PlanStatus.ACTIVE)finishPlan(j.planId(),"cancelled",false);
        if(original!=null&&original.planId()==0&&(active==null||active.jobId()!=id)&&listener!=null)listener.onCancelled((int)original.id(),original.tool(),reason);
        if(cleanup!=null)begin(cleanup,"scaffold_cleanup",false,false);else if(current==null)startNextOrDefault();
    }
    public JobQueue.Result skipJob(long id) {
        Job job=queue.job(id).orElse(null);if(job==null)return JobQueue.Result.error("unknown job #"+id);
        JobQueue.Result result=queue.skip(id);if(!result.ok())return result;
        if(job.planId()==0){queue.remove(id);factories.remove(id);}
        else if(isStructuredPlan(job.planId()))advanceStructuredPlan(job.planId(), new PlanInterpreter.ActionResult.Succeeded());
        else if(queue.planStatus(job.planId())==PlanStatus.DONE||queue.planStatus(job.planId())==PlanStatus.FAILED)finishPlan(job.planId(),QueueText.planSummary(queue,job.planId()),queue.planStatus(job.planId())==PlanStatus.DONE);
        wakeQueue();if(current==null)startNextOrDefault();return result;
    }
    public void cancelPlan(long id,String reason) {
        Active active=current; Task cleanup=null;
        if(active!=null&&active.planId()==id){cleanup=active.task().cleanupAfterCancel(context());cancelCurrent(reason,false);}
        if(frozen!=null&&frozen.planId()==id){cleanup=combineCleanup(cleanup,frozen.task().cleanupAfterCancel(context()));safeStop(frozen.task(),context());frozen=null;}
        queue.cancelPlan(id); finishPlan(id,"cancelled",false);
        if(cleanup!=null)begin(cleanup,"scaffold_cleanup",false,false);else if(current==null)startNextOrDefault();
    }
    public Optional<String> stopNamed(String name,String reason) {
        Active a=current; if(a==null||!(a.toolName().equals(name)||a.task().matches(name)))return Optional.empty();
        String description=a.task().describe(); stop(reason); return Optional.of(description);
    }

    public void tick() {
        revalidateCreativeHover();
        if(paused||suspended||held())return;
        if(current!=null&&current.defaultActivity()&&!mayFollowOwner.getAsBoolean())clearCurrentSilently();
        queue.advanceClock(); expireDeadlines();
        boolean hasStructuredPlan = false;
        for (PlanRecord plan : queue.plans()) if (plan.cursor() != null && !plan.cursor().stack().isEmpty()) { hasStructuredPlan = true; break; }
        if (hasStructuredPlan) {
            if (sharedFacts == null) sharedFacts = new CompanionGuardFacts(companion);
            sharedFacts.refresh();
        }
        if(current==null) { if(!pollQueue())startDefault(); return; }
        if((current.defaultActivity() || movementOrder(current)) && pollQueue())return;
        activeTicks++; Active active=current; TaskStatus status; String summary=null;
        if (active.planId() > 0) queue.plan(active.planId()).filter(p -> p.cursor() != null).ifPresent(this::planFacts);
        if (active.creativeSensitive() && companion.creativeMode()
                && !CreativeModeService.isActionAuthorized(companion, active.creativeActionContext(),
                CreativeAction.WORLD_CHANGING_TASK)) {
            cancelForCapabilityChange();
            return;
        }
        try { status=active.task().tick(context()); }
        catch(RuntimeException e){ LOGGER.warn("Task {} crashed",active.toolName(),e);status=TaskStatus.FAILED;summary="task crashed: "+e.getClass().getSimpleName(); }
        if(status==TaskStatus.RUNNING&&active.task().hasTimeout()&&activeTicks-active.startActiveTick()>timeoutSec.getAsInt()*20){safeStop(active.task(),context());status=TaskStatus.FAILED;summary="timed out";}
        if(status!=TaskStatus.RUNNING) completeActive(active,status,summary);
        else maybeNoticeBlocked();
    }
    private void revalidateCreativeHover() {
        if (!companion.creativeMode() || companion.creativeLanding() || !companion.isNoGravity()) return;
        if (currentCreativeActionAllowed(CreativeAction.FLIGHT)
                || CreativeModeService.ownerMayUsePassiveFlight(companion)) return;
        companion.endCreativeFlight(false);
        companion.getNavigation().stop();
    }
    private void completeActive(Active active,TaskStatus status,String summary) {
        String result=summary==null?active.task().resultSummary():summary; current=null;
        // One place per finished task: a thing the companion built, farmed or mined is worth remembering itself.
        if(status==TaskStatus.SUCCESS&&listener!=null) {
            PlaceCandidate candidate=active.task().placeCandidate();
            if(candidate!=null)listener.onPlace(candidate,active.toolName());
        }
        if(active.interrupt()) {
            if(active.emit()&&listener!=null)listener.onFinished(active.id(),active.toolName(),status,result);
            resumeFrozen(); return;
        }
        if(active.jobId()>0) {
            boolean retried=false;
            if(status==TaskStatus.SUCCESS)queue.finish(active.jobId(),result); else retried=queue.fail(active.jobId(),result).retried();
            if(!retried&&listener!=null)listener.onJobFinished(active.jobId(),active.planId(),active.toolName(),status,result);
            if(active.planId()==0) {
                if(!retried) {
                    if(active.task().emitsEvents()&&listener!=null)listener.onFinished(active.id(),active.toolName(),status,result);
                    queue.remove(active.jobId());factories.remove(active.jobId());
                }
            } else if (isStructuredPlan(active.planId())) {
                if (!retried) {
                    if (status == TaskStatus.SUCCESS) advanceStructuredPlan(active.planId(), new PlanInterpreter.ActionResult.Succeeded());
                    else handleStructuredActionFailure(active.planId(), active.jobId(), result);
                }
            }
            else if(queue.planStatus(active.planId())==PlanStatus.NEEDS_DECISION){awaitingDecision.add(active.planId());notifyBlocked(active.planId(),active.jobId(),result);}
            else if(queue.planStatus(active.planId())==PlanStatus.DONE||queue.planStatus(active.planId())==PlanStatus.FAILED) finishPlan(active.planId(),QueueText.planSummary(queue,active.planId()),queue.planStatus(active.planId())==PlanStatus.DONE);
        } else if(active.emit()&&listener!=null)listener.onFinished(active.id(),active.toolName(),status,result);
        if (frozen != null) { resumeFrozen(); return; }
        if(!startNextQueued())startDefault();
    }
    private int begin(Task task,String name,boolean emit,boolean interrupt) {
        return begin(task, name, emit, interrupt, null, false);
    }
    private int begin(Task task, String name, boolean emit, boolean interrupt,
                      CreativeActionContext actionContext, boolean creativeSensitive) {
        Active active=new Active(emit?nextId++:0,0,0,name,task,emit,false,interrupt,activeTicks,
                actionContext,creativeSensitive);
        current=active;
        try { task.start(context()); }
        catch(RuntimeException e){current=null;LOGGER.warn("Task {} failed to start",name,e);if(emit&&listener!=null)listener.onFinished(active.id(),name,TaskStatus.FAILED,"task crashed: "+e.getClass().getSimpleName());if(interrupt)resumeFrozen();else startNextOrDefault();}
        return active.id();
    }
    private void cancelCurrent(String reason,boolean cleanup) {
        Active a=current;if(a==null)return;current=null;safeStop(a.task(),context());
        if(a.jobId()>0) { queue.cancel(a.jobId()); if(listener!=null)listener.onJobFinished(a.jobId(),a.planId(),a.toolName(),TaskStatus.FAILED,reason);if(a.planId()==0&&a.emit()&&listener!=null)listener.onCancelled(a.id(),a.toolName(),reason); }
        else if(a.emit()&&listener!=null)listener.onCancelled(a.id(),a.toolName(),reason);
    }
    private void clearCurrentSilently(){ if(current!=null){safeStop(current.task(),context());current=null;} }
    /** Idle-time start attempt, throttled so a blocked queue is not re-scanned every tick. */
    private boolean pollQueue() {
        if((queue.jobCount()==0 && queue.plans().stream().noneMatch(plan -> plan.cursor() != null))||queue.clock()<nextPoll)return false;
        boolean started=startNextQueued(); if(!started)nextPoll=queue.clock()+POLL_INTERVAL_TICKS; return started;
    }
    private GuardFacts facts() { if(sharedFacts==null)sharedFacts=new CompanionGuardFacts(companion); sharedFacts.refresh(); return sharedFacts; }
    private GuardFacts planFacts(PlanRecord plan) {
        if (sharedFacts == null) sharedFacts = new CompanionGuardFacts(companion);
        long baseline = plan.cursor() == null ? 0 : plan.cursor().startDistance();
        sharedFacts.refresh(plan.cursor() == null ? plan.addedTick() : plan.cursor().startTick(), baseline);
        return sharedFacts;
    }
    private boolean isStructuredPlan(long planId) {
        return queue.plan(planId).map(plan -> plan.definition() != null && plan.cursor() != null).orElse(false);
    }
    private boolean advanceNextStructuredPlan() {
        PlanRecord selected = null;
        for (PlanRecord plan : queue.plans()) {
            if (plan.cursor() == null || !plan.cursor().pendingActionPath().isEmpty() || plan.cursor().stack().isEmpty()) continue;
            if (selected == null || plan.priority() > selected.priority()
                    || plan.priority() == selected.priority() && plan.addedTick() < selected.addedTick()) selected = plan;
        }
        if (selected == null) return false;
        advanceStructuredPlan(selected.id(), new PlanInterpreter.ActionResult.None());
        return true;
    }
    private void advanceStructuredPlan(long planId, PlanInterpreter.ActionResult result) {
        PlanRecord plan = queue.plan(planId).orElse(null);
        if (plan == null || plan.cursor() == null) return;
        var transition = PlanInterpreter.advance(plan.definition(), plan.cursor(), planFacts(plan), result);
        queue.updatePlanCursor(planId, transition.cursor());
        if (transition.event() instanceof PlanInterpreter.Event.EmitAction emit) {
            QueuedStep action = emit.step();
            PlanStepFactory factory = structuredFactories.getOrDefault(planId, Map.of()).get(action.tool());
            if (factory == null) {
                queue.cancelPlan(planId);
                finishPlan(planId, "task factory is unavailable for " + action.tool() + " at " + emit.path(), false);
                return;
            }
            TaskContext taskContext = new TaskContext(companion, (ServerLevel) companion.level(), this,
                    actionContext(action.principal(), action.creativeSensitive()));
            if (!factory.authorized(taskContext, action)) {
                queue.cancelPlan(planId);
                finishPlan(planId, "access to " + action.tool() + " was revoked at " + emit.path(), false);
                return;
            }
            try {
                requireCreativeAuthorization(action.principal(), action.creativeSensitive());
                QueuedStep emitted = new QueuedStep(action.tool(), action.args(), action.priority(), action.guards(),
                        action.onUnmet(), action.onFailure(), action.maxAttempts(), List.of(), action.principal(), action.creativeSensitive());
                Job job = queue.addJob(planId, emitted);
                queue.updatePlanCursor(planId, queue.plan(planId).orElseThrow().cursor().withPendingJob(job.id()));
                factories.put(job.id(), context -> factory.create(context, emitted));
            } catch (RuntimeException exception) {
                queue.cancelPlan(planId);
                finishPlan(planId, "could not emit action at " + emit.path() + ": " + exception.getMessage(), false);
            }
        } else if (transition.event() instanceof PlanInterpreter.Event.Complete) {
            finishPlan(planId, "conditional plan completed", true);
        } else if (transition.event() instanceof PlanInterpreter.Event.Failed failed) {
            queue.cancelPlan(planId);
            finishPlan(planId, failed.path() + ": " + failed.reason(), false);
        }
    }
    private boolean startNextQueued() {
        if((queue.jobCount()==0 && queue.plans().stream().noneMatch(plan -> plan.cursor() != null))||paused||suspended||held())return false;
        GuardFacts facts=facts(); skipUnmet(facts); expireDeadlines();
        Optional<Job> candidate=QueuePolicy.next(queue,facts);
        if (candidate.isEmpty() && advanceNextStructuredPlan()) {
            facts = facts();
            candidate = QueuePolicy.next(queue, facts);
        }
        if(candidate.isEmpty()){maybeNoticeBlocked();return false;}
        if(current!=null&&current.defaultActivity())clearCurrentSilently();
        else if(current!=null&&movementOrder(current))clearCurrentSilently();
        else if(current!=null)return false;
        Job job=candidate.get(); StepFactory factory=factories.get(job.id());
        if(factory==null){
            if (isStructuredPlan(job.planId())) { queue.cancelPlan(job.planId()); finishPlan(job.planId(), "task factory is unavailable for " + job.tool(), false); }
            else { queue.fail(job.id(),"task factory is unavailable");if(job.planId()>0)notifyBlocked(job.planId(),job.id(),"task factory is unavailable"); }
            return startNextQueued();
        }
        CreativeActionContext actionContext = actionContext(job.principal(), job.creativeSensitive());
        if (companion.creativeMode() && job.creativeSensitive()
                && !CreativeModeService.isActionAuthorized(companion, actionContext, CreativeAction.WORLD_CHANGING_TASK)) {
            cancelForCapabilityChange();
            return false;
        }
        if (isStructuredPlan(job.planId())) {
            PlanStepFactory planFactory = structuredFactories.getOrDefault(job.planId(), Map.of()).get(job.tool());
            QueuedStep step = step(job);
            TaskContext authorizationContext = new TaskContext(companion, (ServerLevel) companion.level(), this, actionContext);
            if (planFactory == null || !planFactory.authorized(authorizationContext, step)) {
                queue.cancelPlan(job.planId());
                finishPlan(job.planId(), "access to " + job.tool() + " was revoked before the action started", false);
                return startNextQueued();
            }
        }
        try { Task task=factory.create(new TaskContext(companion,(ServerLevel)companion.level(),this,actionContext));queue.markRunning(job.id());awaitingDecision.remove(job.planId());Active active=new Active((int)job.id(),job.id(),job.planId(),job.tool(),task,true,false,false,activeTicks,actionContext,job.creativeSensitive());current=active;task.start(context());blockedSince=-1;return true; }
        catch(RuntimeException e){String reason=e instanceof IllegalArgumentException?e.getMessage():"task crashed: "+e.getClass().getSimpleName();var failed=queue.fail(job.id(),reason);if(!failed.retried()&&isStructuredPlan(job.planId()))handleStructuredActionFailure(job.planId(),job.id(),reason);else if(!failed.retried()&&job.planId()>0){if(queue.planStatus(job.planId())==PlanStatus.NEEDS_DECISION){awaitingDecision.add(job.planId());notifyBlocked(job.planId(),job.id(),reason);}else if(queue.planStatus(job.planId())==PlanStatus.FAILED)finishPlan(job.planId(),reason,false);}return startNextQueued();}
    }
    private void skipUnmet(GuardFacts facts) { for(Job j:queue.jobs())if(j.state()==JobState.PENDING&&j.onUnmet()==GuardAction.SKIP&&j.guards().stream().anyMatch(g->!g.holds(facts))){queue.skip(j.id());if(isStructuredPlan(j.planId()))advanceStructuredPlan(j.planId(),new PlanInterpreter.ActionResult.Succeeded());else if(j.planId()>0&&queue.planStatus(j.planId())!=PlanStatus.ACTIVE)finishPlan(j.planId(),QueueText.planSummary(queue,j.planId()),queue.planStatus(j.planId())==PlanStatus.DONE);} }
    private void handleStructuredActionFailure(long planId, long jobId, String reason) {
        PlanRecord plan = queue.plan(planId).orElse(null);
        if (plan == null) return;
        FailurePolicy policy = queue.job(jobId).map(Job::onFailure).orElse(plan.onFailure());
        switch (policy) {
            case SKIP -> advanceStructuredPlan(planId, new PlanInterpreter.ActionResult.Succeeded());
            case STOP_PLAN -> { queue.cancelPlan(planId); finishPlan(planId, reason, false); }
            case ASK -> { awaitingDecision.add(planId); notifyBlocked(planId, jobId, reason); }
        }
    }
    private static QueuedStep step(Job job) {
        return new QueuedStep(job.tool(), job.args(), job.priorityExplicit() ? job.priority() : null, job.guards(), job.onUnmet(),
                job.onFailure(), job.maxAttempts(), List.of(), job.principal(), job.creativeSensitive());
    }
    private void maybeNoticeBlocked() {
        if(current!=null&&current.jobId()>0)return;
        if(blockedSince<0)blockedSince=queue.clock(); if(queue.clock()-blockedSince<TaskSettings.queueBlockedNoticeTicks())return;
        GuardFacts facts=facts();
        for(PlanRecord plan:queue.plans()) {
            if(queue.planStatus(plan.id())!=PlanStatus.ACTIVE)continue;
            // The first pending step that is gated by its guards, not by a predecessor or a retry delay.
            Job gating=queue.jobsOfPlan(plan.id()).stream().filter(j->j.state()==JobState.PENDING&&j.retryAtTick()<=queue.clock()&&QueuePolicy.dependenciesDone(queue,j)).findFirst().orElse(null);
            if(gating==null)continue;
            if(QueuePolicy.ready(queue,gating,facts)){blockedPlans.remove(plan.id());continue;}
            String reason=QueueText.truncate(QueuePolicy.notReadyReason(queue,gating,facts),QueueText.MAX_BLOCKED_REASON);
            if(!reason.equals(blockedPlans.get(plan.id())))notifyBlocked(plan.id(),gating.id(),reason);
        }
    }
    private void notifyBlocked(long plan,long jobId,String reason) { Job job=queue.job(jobId).orElse(null);if(job==null)return;String goal=queue.plan(plan).map(PlanRecord::goal).orElse("");int remaining=(int)queue.jobsOfPlan(plan).stream().filter(j->j.state()==JobState.PENDING).count();String bounded=QueueText.truncate(reason,QueueText.MAX_BLOCKED_REASON);blockedPlans.put(plan,bounded);if(listener!=null)listener.onPlanBlocked(plan,goal,jobId,job.tool(),bounded,remaining); }
    private void finishPlan(long id,String summary,boolean success) { PlanRecord p=queue.plan(id).orElse(null);if(p==null)return;String goal=p.goal();queue.archivePlan(id,success,summary);factories.entrySet().removeIf(e->queue.job(e.getKey()).isEmpty());structuredFactories.remove(id);blockedPlans.remove(id);awaitingDecision.remove(id);if(listener!=null)listener.onPlanFinished(id,goal,success,summary); }
    private void expireDeadlines() { for(long id:queue.expiredPlans()){Active a=current;if(a!=null&&a.planId()==id){Task cleanup=a.task().cleanupAfterCancel(context());cancelCurrent("ran out of time",false);if(cleanup!=null)begin(cleanup,"scaffold_cleanup",false,false);}queue.cancelPlan(id);finishPlan(id,"ran out of time",false);} }
    private void resumeFrozen(){Active old=frozen;frozen=null;if(old!=null&&queue.job(old.jobId()).filter(j->j.state()==JobState.RUNNING).isPresent()){current=new Active(old.id(),old.jobId(),old.planId(),old.toolName(),old.task(),old.emit(),false,false,activeTicks-frozenElapsed,old.creativeActionContext(),old.creativeSensitive());}else{if(old!=null)safeStop(old.task(),context());startNextOrDefault();}}
    private void startNextOrDefault(){if(current!=null)return;if(frozen!=null){resumeFrozen();return;}if(!startNextQueued())startDefault();}
    private Task combineCleanup(Task first,Task second){if(first==null)return second;if(second==null)return first;return new SequenceTask("scaffold cleanup",List.of(new PlanEntry("scaffold_cleanup",ctx->first),new PlanEntry("scaffold_cleanup",ctx->second)),()->timeoutSec.getAsInt()*20);}
    private void startDefault(){if(!paused&&!held()&&!suspended&&current==null&&!plansAwaitingDecision()&&defaultFollow.getAsBoolean()&&mayFollowOwner.getAsBoolean()){Active a=new Active(0,0,0,"follow",new FollowTask(true),false,true,false,activeTicks);current=a;try{a.task().start(context());}catch(RuntimeException e){current=null;LOGGER.warn("Default follow failed to start",e);}}}
    /**
     * A plan whose remaining steps wait for a decision is not idleness: the companion stands where it is and the
     * failure is reported from there, so the follow cannot preempt the work it is about.
     */
    private boolean plansAwaitingDecision(){if(awaitingDecision.isEmpty())return false;awaitingDecision.removeIf(id->queue.planStatus(id)!=PlanStatus.NEEDS_DECISION);return !awaitingDecision.isEmpty();}
    public Optional<String> savedQueue(){long active=current!=null&&current.jobId()>0?current.jobId():frozen!=null?frozen.jobId():0;String taskSave="";if(current!=null&&current.jobId()>0)taskSave=current.task().saveData();else if(frozen!=null)taskSave=frozen.task().saveData();if(queue.jobCount()==0&&queue.planCount()==0&&queue.archives().isEmpty())return Optional.empty();return Optional.of(QueueSave.encode(queue,taskSave));}
    public void restoreQueue(QueueSave.Decoded decoded, Map<Long, StepFactory> restoredFactories) {
        restoreQueue(decoded, restoredFactories, Map.of());
    }
    public void restoreQueue(QueueSave.Decoded decoded, Map<Long, StepFactory> restoredFactories,
                             Map<Long, Map<String, PlanStepFactory>> restoredPlanFactories) {
        queue = decoded.queue();
        factories.clear();
        factories.putAll(restoredFactories);
        structuredFactories.clear();
        restoredPlanFactories.forEach((id, map) -> structuredFactories.put(id, Map.copyOf(map)));
        current = null;
        frozen = null;
        if (companion.creativeMode()) {
            Set<Long> creativePlans = new HashSet<>();
            for (Job job : queue.jobs()) {
                if (!job.creativeSensitive() || job.state() == JobState.DONE || job.state() == JobState.CANCELLED
                        || job.state() == JobState.SKIPPED) continue;
                if (job.planId() > 0) creativePlans.add(job.planId());
                else queue.cancel(job.id(), "creative task cancelled after reload");
            }
            for (long planId : creativePlans) queue.cancelPlan(planId);
            if (!creativePlans.isEmpty() || queue.jobs().stream().anyMatch(j -> j.creativeSensitive()
                    && j.planId() == 0 && j.state() == JobState.CANCELLED
                    && "creative task cancelled after reload".equals(j.result()))) {
                notice("Saved creative tasks were cancelled after reload; submit them again.");
            }
        }
        Set<Long> expiredTradingPlans = new HashSet<>();
        for (Job job : List.copyOf(queue.jobs())) {
            if (!QueueTradeScope.requiresFreshAuthority(job.args(), false)) continue;
            factories.remove(job.id());
            if (job.planId() > 0) expiredTradingPlans.add(job.planId());
            else queue.cancel(job.id(), "trading authority expired after restart");
        }
        for (long planId : expiredTradingPlans) {
            queue.cancelPlan(planId);
            finishPlan(planId, "trading authority expired after restart", false);
        }
        if (decoded.activeJobId() > 0) {
            Job job = queue.job(decoded.activeJobId()).orElse(null);
            if (job != null && job.state() == JobState.RUNNING) {
                String save = decoded.activeTaskSave();
                Task task = null;
                boolean cancelled = false;
                if (!save.isBlank()) task = TaskFactory.restore(save, (ServerLevel) companion.level()).orElse(null);
                if (task == null && save.startsWith(dev.friendai.task.builtin.SearchResourceTask.SAVE_PREFIX)) {
                    java.util.UUID owner = companion.ownerUuid();
                    if (owner != null && owner.equals(job.principal())) {
                        task = dev.friendai.task.builtin.SearchResourceTask.restore(save);
                    }
                    if (task == null) {
                        interruptSearchLedger(save);
                        queue.cancel(job.id(), "saved search could not be resumed");
                        cancelled = true;
                    }
                }
                if (task == null && !cancelled) {
                    if (interruptedSearch(job, save)) {
                        interruptSearchLedger(save);
                        queue.cancel(job.id(), "interrupted by restart");
                    } else {
                        StepFactory factory = factories.get(job.id());
                        if (factory != null) task = factory.create(context());
                    }
                }
                if (task != null) {
                    CreativeActionContext actionContext = actionContext(job.principal(), job.creativeSensitive());
                    current = new Active((int) job.id(), job.id(), job.planId(), job.tool(), task, true, false,
                            false, activeTicks, actionContext, job.creativeSensitive());
                    try {
                        task.start(context());
                    } catch (RuntimeException e) {
                        current = null;
                        queue.fail(job.id(), "task crashed: " + e.getClass().getSimpleName());
                    }
                }
            }
        }        for(Job job:List.copyOf(queue.jobs()))if(job.state()==JobState.RUNNING&&(current==null||current.jobId()!=job.id()))queue.requeue(job.id());
        awaitingDecision.clear();
        for(PlanRecord plan:queue.plans())if(queue.planStatus(plan.id())==PlanStatus.NEEDS_DECISION)awaitingDecision.add(plan.id());
        nextPoll=0;
    }
    private static boolean requiresFreshTradingAuthority(Job job) {
        return QueueTradeScope.requiresFreshAuthority(job.args(), false);
    }
    private static boolean interruptedSearch(Job job,String save){if(save!=null&&save.startsWith("resource_session:v1:"))return true;if(job.tool().equals("search_resource")||job.tool().equals("find_target"))return true;return job.tool().equals("obtain_item")&&job.args().has("explore")&&job.args().get("explore").getAsBoolean();}
    private void interruptSearchLedger(String save){var owner=companion.ownerUuid();if(owner==null)return;var ledger=FriendaiServerState.get(((ServerLevel)companion.level()).getServer()).knowledge().book(owner).searchLedger();if(save!=null&&save.startsWith("resource_session:v1:")){try{ledger.interrupt(java.util.UUID.fromString(save.substring("resource_session:v1:".length())));return;}catch(IllegalArgumentException malformed){LOGGER.warn("Malformed saved resource session marker; interrupting active ledger sessions");}}ledger.interruptActive();}
    private void safeStop(Task task,TaskContext ctx){try{task.stop(ctx);}catch(RuntimeException e){LOGGER.warn("Task {} failed to stop",task.type(),e);}}
    private TaskContext context(){return new TaskContext(companion,(ServerLevel)companion.level(),this,current==null?null:current.creativeActionContext());}
    private CreativeActionContext actionContext(Caller caller, boolean creativeSensitive) {
        return caller == null || caller.uuid() == null || !creativeSensitive ? null
                : new CreativeActionContext(caller.uuid(), companion.creativeModeRevision());
    }
    private CreativeActionContext actionContext(java.util.UUID principal, boolean creativeSensitive) {
        return principal == null || !creativeSensitive ? null
                : new CreativeActionContext(principal, companion.creativeModeRevision());
    }
    private void requireCreativeAuthorization(java.util.UUID principal, boolean creativeSensitive) {
        if (!companion.creativeMode() || !creativeSensitive) return;
        CreativeActionContext context = actionContext(principal, true);
        if (!CreativeModeService.isActionAuthorized(companion, context, CreativeAction.WORLD_CHANGING_TASK)) {
            throw new IllegalArgumentException("creative mode requires its online operator owner for world-changing tasks");
        }
    }
}
