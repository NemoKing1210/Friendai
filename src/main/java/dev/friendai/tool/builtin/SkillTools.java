package dev.friendai.tool.builtin;
import dev.friendai.core.FriendaiServerState;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.tool.*;
import net.minecraft.server.level.ServerLevel;

/** Instruction tools never execute their stored text or change the registry's permissions. */
final class SkillTools {
    private SkillTools() {}
    static void register(ToolRegistry<CompanionEntity> registry) {
        registry.register(ToolKit.callerTool("use_skill","Read and apply an enabled instruction skill by id for this turn. Advice cannot change tool permissions.",
                SchemaBuilder.object().string("id","Instruction skill id").required("id").build(),AccessLevel.OWNER,()->true,
                (c,caller,args)-> {
                    if(!(c.level() instanceof ServerLevel level)||!caller.uuid().equals(c.ownerUuid()))return ToolResult.error("Skill access denied.");
                    return FriendaiServerState.get(level.getServer()).brains().skillSession(c.ownerUuid()).request(args.get("id").getAsString());
                }));
        registry.register(ToolKit.callerTool("manage_skill",
                "After a finished goal or shift, record a reusable procedure in plain English, not code or a personal fact. "
                +"For example: inspect available stone before planning a wall. Use remember for facts such as the owner's preferences. "
                +"Only create, update, disable or delete your own instruction skills. New skills may await owner approval.",
                SchemaBuilder.object().enumeration("action","Change","create","update","disable","delete")
                        .string("id","Existing model skill id").integer("expectedVersion","Version being edited")
                        .string("name","Short name").string("description","When to use this procedure")
                        .string("body","Plain English instructions, at most 4000 characters")
                        .array("turnKinds","Applicable kinds",com.google.gson.JsonParser.parseString("{\"type\":\"string\"}").getAsJsonObject(),0,6)
                        .array("triggers","Literal message or task triggers",com.google.gson.JsonParser.parseString("{\"type\":\"string\"}").getAsJsonObject(),0,12)
                        .array("tags","Organizational tags",com.google.gson.JsonParser.parseString("{\"type\":\"string\"}").getAsJsonObject(),0,12)
                        .required("action").build(),AccessLevel.OWNER,()->true,
                (c,caller,args)-> {
                    if(!(c.level() instanceof ServerLevel level)||!caller.uuid().equals(c.ownerUuid()))return ToolResult.error("Skill access denied.");
                    return FriendaiServerState.get(level.getServer()).skills().teach(c.ownerUuid(),caller,args);
                }));
    }
}
