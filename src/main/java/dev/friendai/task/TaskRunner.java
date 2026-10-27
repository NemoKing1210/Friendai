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
import dev.friendai.queue.PlanStatus;
import dev.friendai.queue.QueueLimits;
import dev.friendai.queue.QueuePolicy;
import dev.friendai.queue.QueueSave;
import dev.friendai.queue.QueueText;
import dev.friendai.queue.QueuedStep;
import dev.friendai.task.builtin.FollowTask;
import dev.friendai.task.plan.StepFactory;
import dev.friendai.task.plan.PlanEntry;
import dev.friendai.task.plan.SequenceTask;
import dev.friendai.task.queue.CompanionGuardFacts;
import dev.friendai.tool.Caller;
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
    private JobQueue queue;
    private final Map<Long, StepFactory> factories = new HashMap<>();
    private final Map<Long, String> blockedPlans = new HashMap<>();
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
        this.companion=companion; this.timeoutSec=timeoutSec; this.defaultFollow=defaultFollow;
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
    /** The tool that started the running task, empty when nothing runs. */
    public String currentToolName() { return current==null?"":current.toolName(); }
    /** True while a task that is neither the default activity nor free time's runs: owner work comes first. */
    public boolean busyWithOther() { return current!=null && !current.defaultActivity() && !autonomousActive(); }
    public Optional<Job> activeJob() { return queue.active(); }
    public int pendingJobs() { return (int)queue.jobs().stream().filter(j->j.state()==JobState.PENDING).count(); }
    public boolean hasWork() { return queue.jobCount()>0 || (current!=null&&!current.defaultActivity()); }
    public java.util.List<dev.friendai.debug.QueueRow> queueRows() { return queue.jobCount()==0?java.util.List.of():dev.friendai.debug.QueueRows.build(queue,facts(),current==null?null:current.task().describe()); }
    public String queueSummary() { return queue.jobCount()==0?current().map(Task::describe).orElse("none"):QueueText.summary(queue,facts(),current==null?null:current.task().describe()); }

    public long enqueue(QueuedStep step, StepFactory factory) {
        requireCreativeAuthorization(step.principal(), step.creativeSensitive());
        Job job=queue.addDirect(step); factories.put(job.id(),factory); wakeQueue();
        if(current==null || current.defaultActivity()) startNextQueued(); return job.id();
    }
    public long enqueuePlan(String goal,List<QueuedStep> steps,List<StepFactory> stepFactories,PlanOptions options) { return enqueuePlan(goal,steps,stepFactories,options,id->{}); }
    /** {@code prepare} runs with the new plan id after it is queued but before any of its jobs can start. */
    public long enqueuePlan(String goal,List<QueuedStep> steps,List<StepFactory> stepFactories,PlanOptions options,java.util.function.LongConsumer prepare) {
        if(steps.size()!=stepFactories.size())throw new IllegalArgumentException("steps and factories differ");
        for (QueuedStep step : steps) requireCreativeAuthorization(step.principal(), step.creativeSensitive());
        JobQueue.Added added=queue.addPlan(goal,steps,options); for(int i=0;i<stepFactories.size();i++)factories.put(added.jobIds().get(i),stepFactories.get(i));
        prepare.accept(added.planId());
        wakeQueue(); if(current==null || current.defaultActivity())startNextQueued(); return added.planId();
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
        queue.clear();factories.clear();startDefault();
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
        frozenElapsed = 0;
        if (cleanup != null) begin(cleanup, "scaffold_cleanup", false, false);
        else startDefault();
    }

    public void stopAll(String reason) {
        Active active=current;
        Task cleanup=active==null?null:active.task().cleanupAfterCancel(context());
        if(frozen!=null)cleanup=combineCleanup(cleanup,frozen.task().cleanupAfterCancel(context()));
        if(active!=null)cancelCurrent(reason,false);
        for(PlanRecord p:queue.plans()) finishPlan(p.id(),"stopped by request",false);
        queue.clear(); factories.clear(); frozen=null;
        if(cleanup!=null)begin(cleanup,"scaffold_cleanup",false,false);else startDefault();
    }
    public String clearQueue(String reason) {
        Active active=current;
        Task cleanup=active!=null&&active.jobId()>0?active.task().cleanupAfterCancel(context()):null;
        if(frozen!=null)cleanup=combineCleanup(cleanup,frozen.task().cleanupAfterCancel(context()));
        if(active!=null&&active.jobId()>0)cancelCurrent(reason,false);
        for(PlanRecord plan:queue.plans())finishPlan(plan.id(),reason,false);
        String result=queue.clear().text(); factories.clear(); frozen=null;
        if(cleanup!=null)begin(cleanup,"scaffold_cleanup",false,false);else startNextOrDefault(); return result;
    }
    public void cancelJob(long id,String reason) {
        Job original=queue.job(id).orElse(null);Active active=current;
        Task cleanup=null;
        if(active!=null&&active.jobId()==id) { cleanup=active.task().cleanupAfterCancel(context()); cancelCurrent(reason,false); queue.cancel(id); factories.remove(id); }
        else { if(frozen!=null&&frozen.jobId()==id){cleanup=frozen.task().cleanupAfterCancel(context());frozen=null;} queue.cancel(id); factories.remove(id); }
        Job j=queue.job(id).orElse(null); if(j!=null&&j.planId()==0)queue.remove(id);else if(j!=null&&j.planId()>0&&queue.planStatus(j.planId())!=PlanStatus.ACTIVE)finishPlan(j.planId(),"cancelled",false);
        if(original!=null&&original.planId()==0&&(active==null||active.jobId()!=id)&&listener!=null)listener.onCancelled((int)original.id(),original.tool(),reason);
        if(cleanup!=null)begin(cleanup,"scaffold_cleanup",false,false);else if(current==null)startNextOrDefault();
    }
    public JobQueue.Result skipJob(long id) {
        Job job=queue.job(id).orElse(null);if(job==null)return JobQueue.Result.error("unknown job #"+id);
        JobQueue.Result result=queue.skip(id);if(!result.ok())return result;
        if(job.planId()==0){queue.remove(id);factories.remove(id);}
        else if(queue.planStatus(job.planId())==PlanStatus.DONE||queue.planStatus(job.planId())==PlanStatus.FAILED)finishPlan(job.planId(),QueueText.planSummary(queue,job.planId()),queue.planStatus(job.planId())==PlanStatus.DONE);
        wakeQueue();if(current==null)startNextOrDefault();return result;
    }
    public void cancelPlan(long id,String reason) {
        Active active=current; Task cleanup=null;
        if(active!=null&&active.planId()==id){cleanup=active.task().cleanupAfterCancel(context());cancelCurrent(reason,false);}
        if(frozen!=null&&frozen.planId()==id){cleanup=combineCleanup(cleanup,frozen.task().cleanupAfterCancel(context()));frozen=null;}
        queue.cancelPlan(id); finishPlan(id,"cancelled",false);
        if(cleanup!=null)begin(cleanup,"scaffold_cleanup",false,false);else if(current==null)startNextOrDefault();
    }
    public Optional<String> stopNamed(String name,String reason) {
        Active a=current; if(a==null||!(a.toolName().equals(name)||a.task().matches(name)))return Optional.empty();
        String description=a.task().describe(); stop(reason); return Optional.of(description);
    }

    public void tick() {
        if(paused||suspended||held())return;
        queue.advanceClock(); expireDeadlines();
        if(current==null) { if(!pollQueue())startDefault(); return; }
        if(current.defaultActivity() && pollQueue())return;
        activeTicks++; Active active=current; TaskStatus status; String summary=null;
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
            }
            else if(queue.planStatus(active.planId())==PlanStatus.NEEDS_DECISION) notifyBlocked(active.planId(),active.jobId(),result);
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
        if(queue.jobCount()==0||queue.clock()<nextPoll)return false;
        boolean started=startNextQueued(); if(!started)nextPoll=queue.clock()+POLL_INTERVAL_TICKS; return started;
    }
    private GuardFacts facts() { if(sharedFacts==null)sharedFacts=new CompanionGuardFacts(companion); sharedFacts.refresh(); return sharedFacts; }
    private boolean startNextQueued() {
        if(queue.jobCount()==0||paused||suspended||held())return false;
        GuardFacts facts=facts(); skipUnmet(facts); expireDeadlines();
        Optional<Job> candidate=QueuePolicy.next(queue,facts);
        if(candidate.isEmpty()){maybeNoticeBlocked();return false;}
        if(current!=null&&current.defaultActivity())clearCurrentSilently(); else if(current!=null)return false;
        Job job=candidate.get(); StepFactory factory=factories.get(job.id());
        if(factory==null){queue.fail(job.id(),"task factory is unavailable");if(job.planId()>0)notifyBlocked(job.planId(),job.id(),"task factory is unavailable");return startNextQueued();}
        CreativeActionContext actionContext = actionContext(job.principal(), job.creativeSensitive());
        if (companion.creativeMode() && job.creativeSensitive()
                && !CreativeModeService.isActionAuthorized(companion, actionContext, CreativeAction.WORLD_CHANGING_TASK)) {
            cancelForCapabilityChange();
            return false;
        }
        try { Task task=factory.create(new TaskContext(companion,(ServerLevel)companion.level(),this,actionContext));queue.markRunning(job.id());Active active=new Active((int)job.id(),job.id(),job.planId(),job.tool(),task,true,false,false,activeTicks,actionContext,job.creativeSensitive());current=active;task.start(context());blockedSince=-1;return true; }
        catch(RuntimeException e){String reason=e instanceof IllegalArgumentException?e.getMessage():"task crashed: "+e.getClass().getSimpleName();var failed=queue.fail(job.id(),reason);if(!failed.retried()&&job.planId()>0){if(queue.planStatus(job.planId())==PlanStatus.NEEDS_DECISION)notifyBlocked(job.planId(),job.id(),reason);else if(queue.planStatus(job.planId())==PlanStatus.FAILED)finishPlan(job.planId(),reason,false);}return startNextQueued();}
    }
    private void skipUnmet(GuardFacts facts) { for(Job j:queue.jobs())if(j.state()==JobState.PENDING&&j.onUnmet()==GuardAction.SKIP&&j.guards().stream().anyMatch(g->!g.holds(facts))){queue.skip(j.id());if(j.planId()>0&&queue.planStatus(j.planId())!=PlanStatus.ACTIVE)finishPlan(j.planId(),QueueText.planSummary(queue,j.planId()),queue.planStatus(j.planId())==PlanStatus.DONE);} }
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
    private void finishPlan(long id,String summary,boolean success) { PlanRecord p=queue.plan(id).orElse(null);if(p==null)return;String goal=p.goal();queue.archivePlan(id,success,summary);factories.entrySet().removeIf(e->queue.job(e.getKey()).isEmpty());blockedPlans.remove(id);if(listener!=null)listener.onPlanFinished(id,goal,success,summary); }
    private void expireDeadlines() { for(long id:queue.expiredPlans()){Active a=current;if(a!=null&&a.planId()==id){Task cleanup=a.task().cleanupAfterCancel(context());cancelCurrent("ran out of time",false);if(cleanup!=null)begin(cleanup,"scaffold_cleanup",false,false);}queue.cancelPlan(id);finishPlan(id,"ran out of time",false);} }
    private void resumeFrozen(){Active old=frozen;frozen=null;if(old!=null&&queue.job(old.jobId()).filter(j->j.state()==JobState.RUNNING).isPresent()){current=new Active(old.id(),old.jobId(),old.planId(),old.toolName(),old.task(),old.emit(),false,false,activeTicks-frozenElapsed,old.creativeActionContext(),old.creativeSensitive());}else{if(old!=null)safeStop(old.task(),context());startNextOrDefault();}}
    private void startNextOrDefault(){if(current!=null)return;if(frozen!=null){resumeFrozen();return;}if(!startNextQueued())startDefault();}
    private Task combineCleanup(Task first,Task second){if(first==null)return second;if(second==null)return first;return new SequenceTask("scaffold cleanup",List.of(new PlanEntry("scaffold_cleanup",ctx->first),new PlanEntry("scaffold_cleanup",ctx->second)),()->timeoutSec.getAsInt()*20);}
    private void startDefault(){if(!paused&&!held()&&!suspended&&current==null&&defaultFollow.getAsBoolean()){Active a=new Active(0,0,0,"follow",new FollowTask(true),false,true,false,activeTicks);current=a;try{a.task().start(context());}catch(RuntimeException e){current=null;LOGGER.warn("Default follow failed to start",e);}}}
    public Optional<String> savedQueue(){long active=current!=null&&current.jobId()>0?current.jobId():frozen!=null?frozen.jobId():0;String taskSave="";if(current!=null&&current.jobId()>0)taskSave=current.task().saveData();else if(frozen!=null)taskSave=frozen.task().saveData();if(queue.jobCount()==0&&queue.archives().isEmpty())return Optional.empty();return Optional.of(QueueSave.encode(queue,taskSave));}
    public void restoreQueue(QueueSave.Decoded decoded, Map<Long, StepFactory> restoredFactories) {
        queue = decoded.queue();
        factories.clear();
        factories.putAll(restoredFactories);
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
        if (decoded.activeJobId() > 0) {
            Job job = queue.job(decoded.activeJobId()).orElse(null);
            if (job != null && job.state() == JobState.RUNNING) {
                if (interruptedSearch(job, decoded.activeTaskSave())) {
                    interruptSearchLedger(decoded.activeTaskSave());
                    queue.cancel(job.id(), "interrupted by restart");
                } else {
                    Task task = null;
                    if (!decoded.activeTaskSave().isBlank()) {
                        task = TaskFactory.restore(decoded.activeTaskSave(), (ServerLevel) companion.level()).orElse(null);
                    }
                    if (task == null) {
                        StepFactory factory = factories.get(job.id());
                        if (factory != null) task = factory.create(context());
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
            }
        }
        for(Job job:List.copyOf(queue.jobs()))if(job.state()==JobState.RUNNING&&(current==null||current.jobId()!=job.id()))queue.requeue(job.id());
        nextPoll=0;}
    private static boolean interruptedSearch(Job job,String save){if(save!=null&&save.startsWith("resource_session:v1:"))return true;if(job.tool().equals("search_resource"))return true;return job.tool().equals("obtain_item")&&job.args().has("explore")&&job.args().get("explore").getAsBoolean();}
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
