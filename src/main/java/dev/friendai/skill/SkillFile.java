package dev.friendai.skill;

import com.google.gson.*;
import dev.friendai.store.*;
import java.util.*;
import java.util.function.UnaryOperator;

/** Versioned disk format; decoding enforces the same limits as writing. */
public final class SkillFile implements StoreCodec<SkillBook> {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final SkillLimits limits;
    private final UnaryOperator<String> redact;
    private final InstructionSkill.Scope scope;
    public SkillFile(SkillLimits limits, UnaryOperator<String> redact, InstructionSkill.Scope scope) {
        this.limits=limits; this.redact=redact; this.scope=scope;
    }
    static final class Envelope {
        int version=1;
        long revision, clockHighWater;
        List<InstructionSkill> skills=new ArrayList<>();
        Set<String> disabled=new TreeSet<>();
        List<Long> writes=new ArrayList<>();
        Map<String,Integer> outcomes=new LinkedHashMap<>();
    }
    @Override public SkillBook load(String json) {
        SkillBook book = new SkillBook(limits,redact,scope);
        if (json == null) return book;
        JsonObject raw = JsonParser.parseString(json).getAsJsonObject();
        if (!raw.has("version") || raw.get("version").getAsInt() != 1) throw new UnsupportedStoreVersionException();
        Envelope file = GSON.fromJson(raw,Envelope.class);
        int max = scope == InstructionSkill.Scope.SERVER ? limits.serverCount() : limits.ownerCount();
        if (file.skills == null || file.skills.size()>max || file.disabled==null || file.disabled.size()>128
                || file.writes==null || file.writes.size()>60 || file.outcomes==null || file.outcomes.size()>128
                || file.revision<0 || file.clockHighWater<0) throw new IllegalArgumentException("Invalid skill file.");
        long total=0;
        for (InstructionSkill s:file.skills) {
            SkillText.validate(s.draft(),redact);
            if (s.scope()!=scope || s.source()==InstructionSkill.Source.BUILTIN || s.author()==null
                    || book.records.putIfAbsent(s.id(),s)!=null) throw new IllegalArgumentException("Invalid skill metadata.");
            total+=SkillBook.textSize(s);
        }
        if(total>limits.totalChars()) throw new IllegalArgumentException("Skill file exceeds text limit.");
        for(String id:file.disabled) if(id==null || !id.matches("(?:builtin|server):[a-z0-9-]{1,80}")) throw new IllegalArgumentException("Invalid override.");
        for(Long t:file.writes) if(t==null || t<0) throw new IllegalArgumentException("Invalid write ledger.");
        for(var e:file.outcomes.entrySet()) if(e.getKey()==null || e.getKey().length()>120 || e.getValue()==null || e.getValue()<0 || e.getValue()>2) throw new IllegalArgumentException("Invalid outcome ledger.");
        book.disabled.addAll(file.disabled); book.writes.addAll(file.writes); book.outcomes.putAll(file.outcomes);
        book.clockHighWater=file.clockHighWater; book.revision=file.revision;
        return book;
    }
    @Override public String save(SkillBook book) {
        Envelope file=new Envelope(); file.skills=book.snapshot(); file.disabled=book.disabledIds();
        file.revision=book.revision(); file.clockHighWater=book.clockHighWater;
        file.writes=List.copyOf(book.writes); file.outcomes=Map.copyOf(book.outcomes);
        return GSON.toJson(file);
    }
    @Override public long revision(SkillBook book) { return book.revision(); }
    @Override public long maxBytes() { return SkillLimits.FILE_BYTES; }
}
