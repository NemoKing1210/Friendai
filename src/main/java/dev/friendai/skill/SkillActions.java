package dev.friendai.skill;

import dev.friendai.tool.AccessLevel;
import java.time.Clock;
import java.util.*;

/** Shared server action implementation for menu and commands; no UI dependency. */
public final class SkillActions {
    private final SkillStore store;
    private final List<InstructionSkill> builtins;
    private final Clock clock;
    public SkillActions(SkillStore store,List<InstructionSkill> builtins,Clock clock) {
        this.store=store;this.builtins=List.copyOf(builtins);this.clock=clock;
    }
    public List<InstructionSkill> effective(UUID owner) {
        SkillBook local=store.book(owner);
        List<InstructionSkill> all=new ArrayList<>(builtins);all.addAll(store.global().snapshot());all.addAll(local.snapshot());
        return all.stream().filter(s->!local.disabledIds().contains(s.id())).sorted(Comparator.comparing(InstructionSkill::id)).toList();
    }
    public List<InstructionSkill> listed(UUID owner) {
        List<InstructionSkill> all=new ArrayList<>(builtins);all.addAll(store.global().snapshot());all.addAll(store.book(owner).snapshot());
        return all.stream().sorted(Comparator.comparing(InstructionSkill::id)).toList();
    }
    public SkillActionResult apply(SkillActor actor,SkillAction action) {
        try {
            if(actor.caller()==null || actor.caller().access()!=AccessLevel.OWNER
                    || !actor.owner().equals(actor.caller().uuid()) || (action.server()&&!actor.operator())) return SkillActionResult.error();
            SkillBook local=store.book(actor.owner());
            SkillBook target=action.server()?store.global():local;
            long now=Math.max(0,clock.millis());
            if(action.operation()==SkillAction.Operation.CREATE || action.operation()==SkillAction.Operation.IMPORT) {
                SkillDraft d=action.operation()==SkillAction.Operation.IMPORT?SkillTransfer.read(action.text()):action.draft();
                return SkillActionResult.ok(target.create(d,InstructionSkill.Source.OWNER,actor.owner(),now,false));
            }
            InstructionSkill old=(action.server()?store.global().snapshot():listed(actor.owner())).stream()
                    .filter(s->s.id().equals(action.id())).findFirst().orElseThrow();
            if(old.version()!=action.expectedVersion())return SkillActionResult.error();
            boolean external=old.source()==InstructionSkill.Source.BUILTIN || (!action.server()&&old.scope()==InstructionSkill.Scope.SERVER);
            switch(action.operation()) {
                case EXPORT -> { return new SkillActionResult(dev.friendai.memory.MenuStatus.ok("friendai.menu.skills.exported"),Optional.empty(),SkillTransfer.write(old)); }
                case DUPLICATE -> {
                    InstructionSkill copy=target.create(old.draft(),InstructionSkill.Source.OWNER,actor.owner(),now,false);
                    if(external)local.override(old.id(),false);
                    return SkillActionResult.ok(copy);
                }
                case TOGGLE -> {
                    if(external) { local.override(old.id(),local.disabledIds().contains(old.id())); return SkillActionResult.ok(old); }
                    SkillDraft d=old.draft();
                    return SkillActionResult.ok(target.edit(old.id(),old.version(),new SkillDraft(d.name(),d.description(),d.body(),!d.enabled(),d.alwaysOn(),d.turnKinds(),d.triggers(),d.tags(),d.includeProfile()),now));
                }
                case EDIT -> {
                    if(external) {
                        InstructionSkill copy=local.create(action.draft(),InstructionSkill.Source.OWNER,actor.owner(),now,false);
                        local.override(old.id(),false);return SkillActionResult.ok(copy);
                    }
                    return SkillActionResult.ok(target.edit(old.id(),old.version(),action.draft(),now));
                }
                case APPROVE -> { if(external||old.source()!=InstructionSkill.Source.MODEL) return SkillActionResult.error();target.approve(old.id(),old.version()); }
                case DELETE, REJECT -> { if(external)return SkillActionResult.error();target.delete(old.id(),old.version()); }
                default -> { return SkillActionResult.error(); }
            }
            return SkillActionResult.ok(null);
        } catch(RuntimeException | StackOverflowError e) { return SkillActionResult.error(); }
    }
}
