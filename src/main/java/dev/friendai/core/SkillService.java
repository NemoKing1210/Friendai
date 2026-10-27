package dev.friendai.core;

import dev.friendai.brain.*;
import dev.friendai.config.*;
import dev.friendai.debug.SecretRedactor;
import dev.friendai.memory.*;
import dev.friendai.skill.*;
import dev.friendai.tool.*;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import java.util.function.*;

/** Server-owned bridge; all calls are serialized by the server thread. */
public final class SkillService implements AutoCloseable {
    private final Supplier<ServerConfig> config;
    private final MemoryStore memory;
    private final SkillStore store;
    private final UnaryOperator<String> redact;
    private final SkillActions actions;
    public SkillService(Path dir,Supplier<ServerConfig> config,MemoryStore memory) {
        this.config=config;this.memory=memory;
        redact=SecretRedactor.of(()->config.get().profiles.values().stream()
                .map(p->SecretResolver.resolve(p.apiKey,System::getenv)).toList());
        store=new SkillStore(dir,SkillLimits.defaults(),redact);
        actions=new SkillActions(store,BuiltinSkills.load(SkillService.class.getClassLoader()),Clock.systemUTC());
    }
    public SkillStore store() { return store; }
    public SkillActions actions() { return actions; }
    public List<InstructionSkill> listed(UUID owner) { return safe(actions.listed(owner)); }
    private List<InstructionSkill> safe(List<InstructionSkill> skills) {
        return skills.stream().filter(s->{ try { SkillText.validate(s.draft(),redact);return true; }
            catch(RuntimeException e) { return false; } }).toList();
    }
    public SkillPromptSession begin(UUID owner,Caller caller,String message,TurnKind turn,String taskId,boolean freeTime,boolean planning) {
        if(!config.get().skills.enabled)return SkillPromptSession.NONE;
        try {
            Set<InstructionSkill.Kind> kinds=EnumSet.of(turn==TurnKind.PLAYER?InstructionSkill.Kind.CHAT:InstructionSkill.Kind.EVENT);
            if(freeTime)kinds.add(InstructionSkill.Kind.FREE_TIME);
            if(planning)kinds.add(InstructionSkill.Kind.PLANNING);
            if(taskId.contains("build")||taskId.contains("place"))kinds.add(InstructionSkill.Kind.BUILDING);
            if(taskId.contains("attack")||taskId.contains("combat"))kinds.add(InstructionSkill.Kind.COMBAT);
            MemoryBook book=memory.book(owner);
            boolean owns=caller!=null&&caller.access()==AccessLevel.OWNER&&owner.equals(caller.uuid());
            OwnerProfile profile=owns?book.ownerProfile():OwnerProfile.EMPTY;
            CompanionDescription description=owns?book.description():CompanionDescription.EMPTY;
            SkillContext context=new SkillContext(caller,message,taskId,kinds,owns&&profile.language().equals("auto"),description,profile);
            return new SkillPromptSession(safe(actions.effective(owner)),context,config.get().agent.promptBudget,config.get().skills.promptBudget);
        } catch(RuntimeException e) { return SkillPromptSession.NONE; }
    }
    public ToolResult teach(UUID owner,Caller caller,com.google.gson.JsonObject args) {
        try {
            if(!config.get().skills.enabled)return ToolResult.error("Instruction skills are disabled.");
            var serverContext=teachingContextSource.apply(owner);
            if(!TeachingPolicy.authorize(serverContext,caller,serverContext==null?-1:serverContext.generation()))return ToolResult.error("Skills may only be learned after a completed goal or shift.");
            SkillBook book=store.book(owner);
            TeachingLedger ledger=new TeachingLedger(book);
            var cfg=config.get().skills;
            long now=System.currentTimeMillis();
            if(!ledger.available(serverContext.outcomeId(),now,cfg.modelWritesPerHour,cfg.writesPerOutcome))return ToolResult.error("Instruction write allowance exhausted.");
            String action=args.get("action").getAsString();
            InstructionSkill changed=null;
            if(action.equals("create")) {
                changed=book.create(SkillTransfer.read(args.toString()),InstructionSkill.Source.MODEL,owner,now,cfg.requireApproval);
            } else {
                String id=args.get("id").getAsString();
                InstructionSkill old=book.find(id).orElseThrow();
                if(old.source()!=InstructionSkill.Source.MODEL||!owner.equals(old.author()))return ToolResult.error("Only your own learned procedures can be changed.");
                int version=args.get("expectedVersion").getAsInt();
                switch(action) {
                    case "update" -> changed=book.edit(id,version,SkillTransfer.read(args.toString()),now);
                    case "disable" -> { var d=old.draft();changed=book.edit(id,version,new SkillDraft(d.name(),d.description(),d.body(),false,d.alwaysOn(),d.turnKinds(),d.triggers(),d.tags(),d.includeProfile()),now); }
                    case "delete" -> book.delete(id,version);
                    default -> { return ToolResult.error("Unknown instruction action."); }
                }
            }
            ledger.recordSuccessfulWrite(serverContext.outcomeId(),now);
            return ToolResult.ok(changed==null?"Instruction skill deleted.":"Instruction skill "+changed.id()+" v"+changed.version()+" "+changed.activation());
        } catch(RuntimeException|StackOverflowError e) { return ToolResult.error("Invalid instruction change; check fields, limits and version."); }
    }
    private java.util.function.Function<UUID,TeachingContext> teachingContextSource=owner->null;
    public void setTeachingContextSource(java.util.function.Function<UUID,TeachingContext> source) { teachingContextSource=source; }

    public void tick() { store.flushIfDue(); }
    @Override public void close() { store.close(); }
}
