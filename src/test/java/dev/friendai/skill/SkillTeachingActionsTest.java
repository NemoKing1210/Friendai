package dev.friendai.skill;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonParser;
import dev.friendai.config.ServerConfig;
import dev.friendai.core.SkillService;
import dev.friendai.memory.MemoryStore;
import dev.friendai.tool.*;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkillTeachingActionsTest {
    @TempDir Path dir;
    @Test void teachingRequiresAnOutcomeAndCreatesPendingModelData() {
        UUID owner=UUID.randomUUID();var caller=new Caller(owner,"Owner",AccessLevel.OWNER);
        var config=ServerConfig.defaults();
        try(var service=new SkillService(dir.resolve("skills"),()->config,new MemoryStore(dir.resolve("memory"),()->0L))) {
            var args=JsonParser.parseString("{\"action\":\"create\",\"name\":\"Material check\",\"body\":\"Inspect materials before planning.\"}").getAsJsonObject();
            assertTrue(service.teach(owner,caller,args).error());
            service.setTeachingContextSource(id->new TeachingContext(owner,"goal:1",1,TeachingContext.OutcomeKind.GOAL_SUCCESS));
            assertFalse(service.teach(owner,caller,args).error());
            var skill=service.store().book(owner).snapshot().getFirst();
            assertEquals(InstructionSkill.Source.MODEL,skill.source());
            assertEquals(InstructionSkill.Activation.PENDING,skill.activation());
            args.addProperty("action","update");args.addProperty("id",skill.id());args.addProperty("expectedVersion",1);
            assertFalse(service.teach(owner,caller,args).error());
            assertEquals(InstructionSkill.Activation.PENDING,service.store().book(owner).find(skill.id()).orElseThrow().activation());
            assertTrue(service.teach(owner,caller,args).error(),"two successful writes consume the outcome allowance");
            service.setTeachingContextSource(id->null);
            assertTrue(service.teach(owner,caller,args).error());
        }
    }
}
