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
        actions=new SkillActions(store,List.of(),Clock.systemUTC());
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
    public void tick() { store.flushIfDue(); }
    @Override public void close() { store.close(); }
}
