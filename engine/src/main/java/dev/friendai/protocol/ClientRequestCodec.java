package dev.friendai.protocol;

import dev.friendai.features.instructions.InstructionSkill;
import dev.friendai.features.instructions.SkillDraft;
import dev.friendai.protocol.control.ControlRequest;
import dev.friendai.protocol.debug.DebugRequest;
import dev.friendai.protocol.instructions.InstructionRequest;
import dev.friendai.protocol.memory.MemoryRequest;
import dev.friendai.protocol.places.PlaceRequest;
import dev.friendai.protocol.speech.SpeechRequest;
import dev.friendai.protocol.trading.TradeRequest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class ClientRequestCodec {
    private ClientRequestCodec() {}

    public static byte[] encode(long requestId, ClientRequest request) {
        Objects.requireNonNull(request, "request");
        byte[] body;
        if (request instanceof ControlRequest control) body = encodeControl(control);
        else if (request instanceof MemoryRequest memory) body = encodeMemory(memory);
        else if (request instanceof PlaceRequest places) body = encodePlaces(places);
        else if (request instanceof InstructionRequest instructions) body = encodeInstructions(instructions);
        else if (request instanceof TradeRequest trade) body = encodeTrading(trade);
        else if (request instanceof DebugRequest debug) body = encodeDebug(debug);
        else if (request instanceof SpeechRequest speech) body = encodeSpeech(speech);
        else throw new IllegalArgumentException("unsupported client request family");
        return ProtocolFrameCodec.encode(
                ProtocolDirection.CLIENT_TO_SERVER,
                new ProtocolFrame(
                        ProtocolVersion.CURRENT, request.family().id(), request.operationId(), requestId, body));
    }

    public static DecodeResult<DecodedClientRequest> decode(byte[] encoded) {
        DecodeResult<ProtocolFrame> decoded = ProtocolFrameCodec.decode(ProtocolDirection.CLIENT_TO_SERVER, encoded);
        if (decoded instanceof DecodeResult.Malformed<?> malformed)
            return new DecodeResult.Malformed<>(malformed.reason());
        if (decoded instanceof DecodeResult.Unsupported<?> unsupported)
            return new DecodeResult.Unsupported<>(unsupported.reason());
        ProtocolFrame frame = (ProtocolFrame) ((DecodeResult.Valid<?>) decoded).value();
        try {
            ClientRequest request = switch (frame.familyId()) {
                case 1 -> decodeControl(frame.operationId(), frame.body());
                case 2 -> decodeMemory(frame.operationId(), frame.body());
                case 3 -> decodePlaces(frame.operationId(), frame.body());
                case 4 -> decodeInstructions(frame.operationId(), frame.body());
                case 5 -> decodeTrading(frame.operationId(), frame.body());
                case 6 -> decodeDebug(frame.operationId(), frame.body());
                case 7 -> decodeSpeech(frame.operationId(), frame.body());
                default -> throw new ProtocolDataException(DecodeResult.Reason.UNKNOWN_OPERATION);
            };
            return new DecodeResult.Valid<>(new DecodedClientRequest(frame.requestId(), request));
        } catch (ProtocolDataException malformed) {
            return new DecodeResult.Malformed<>(malformed.reason());
        } catch (IllegalArgumentException malformed) {
            return new DecodeResult.Malformed<>(DecodeResult.Reason.INVALID_FIELD);
        }
    }

    private static byte[] encodeMemory(MemoryRequest request) {
        ProtocolBodyWriter out = new ProtocolBodyWriter();
        if (request instanceof MemoryRequest.FactAdd add) {
            out.writeString(add.groupKey(), 36);
            writeCategory(out, add.categoryId());
            out.writeString(add.text(), 2_000);
        } else if (request instanceof MemoryRequest.FactEdit edit) {
            writePositiveInt(out, edit.factId());
            writeCategory(out, edit.categoryId());
            out.writeString(edit.text(), 2_000);
        } else if (request instanceof MemoryRequest.FactForget forget) writePositiveInt(out, forget.factId());
        else if (request instanceof MemoryRequest.FactClear clear) out.writeString(clear.groupKey(), 36);
        else if (request instanceof MemoryRequest.TrustSet trust) {
            out.writeUuid(trust.target());
            out.writeBoolean(trust.trusted());
        } else if (request instanceof MemoryRequest.PersonaSet persona) out.writeString(persona.text(), 1_200);
        else if (!(request instanceof MemoryRequest.Open
                || request instanceof MemoryRequest.Close
                || request instanceof MemoryRequest.TranscriptClear))
            throw new IllegalArgumentException("unsupported memory request");
        return out.toByteArray();
    }

    private static MemoryRequest decodeMemory(int operationId, byte[] body) {
        ProtocolBodyReader in = new ProtocolBodyReader(body);
        MemoryRequest request = switch (operationId) {
            case 1 -> new MemoryRequest.Open();
            case 2 -> new MemoryRequest.Close();
            case 3 -> new MemoryRequest.FactAdd(in.readString(36), readCategory(in), in.readString(2_000));
            case 4 -> new MemoryRequest.FactEdit(readPositiveInt(in), readCategory(in), in.readString(2_000));
            case 5 -> new MemoryRequest.FactForget(readPositiveInt(in));
            case 6 -> new MemoryRequest.FactClear(in.readString(36));
            case 7 -> new MemoryRequest.TrustSet(in.readUuid(), in.readBoolean());
            case 8 -> new MemoryRequest.PersonaSet(in.readString(1_200));
            case 9 -> new MemoryRequest.TranscriptClear();
            default -> throw new ProtocolDataException(DecodeResult.Reason.UNKNOWN_OPERATION);
        };
        in.requireEnd();
        return request;
    }

    private static byte[] encodePlaces(PlaceRequest request) {
        ProtocolBodyWriter out = new ProtocolBodyWriter();
        if (request instanceof PlaceRequest.Rename rename) {
            out.writeString(rename.oldName(), 128);
            out.writeString(rename.newName(), 128);
        } else if (request instanceof PlaceRequest.Forget forget) out.writeString(forget.name(), 128);
        else if (!(request instanceof PlaceRequest.Open || request instanceof PlaceRequest.Close))
            throw new IllegalArgumentException("unsupported places request");
        return out.toByteArray();
    }

    private static PlaceRequest decodePlaces(int operationId, byte[] body) {
        ProtocolBodyReader in = new ProtocolBodyReader(body);
        PlaceRequest request = switch (operationId) {
            case 1 -> new PlaceRequest.Open();
            case 2 -> new PlaceRequest.Close();
            case 3 -> new PlaceRequest.Rename(in.readString(128), in.readString(128));
            case 4 -> new PlaceRequest.Forget(in.readString(128));
            default -> throw new ProtocolDataException(DecodeResult.Reason.UNKNOWN_OPERATION);
        };
        in.requireEnd();
        return request;
    }

    private static byte[] encodeInstructions(InstructionRequest request) {
        ProtocolBodyWriter out = new ProtocolBodyWriter();
        if (request instanceof InstructionRequest.Page page) out.writeUnsignedByte(page.page());
        else if (request instanceof InstructionRequest.Detail detail) out.writeString(detail.skillId(), 128);
        else if (request instanceof InstructionRequest.Create create) {
            writeDraft(out, create.draft());
            out.writeBoolean(create.serverScope());
        } else if (request instanceof InstructionRequest.Edit edit) {
            out.writeString(edit.skillId(), 128);
            writePositiveInt(out, edit.expectedVersion());
            writeDraft(out, edit.draft());
            out.writeBoolean(edit.serverScope());
        } else if (request instanceof InstructionRequest.Duplicate action)
            writeInstructionAction(out, action.skillId(), action.expectedVersion(), action.serverScope());
        else if (request instanceof InstructionRequest.Delete action)
            writeInstructionAction(out, action.skillId(), action.expectedVersion(), action.serverScope());
        else if (request instanceof InstructionRequest.Enable action)
            writeInstructionAction(out, action.skillId(), action.expectedVersion(), action.serverScope());
        else if (request instanceof InstructionRequest.Disable action)
            writeInstructionAction(out, action.skillId(), action.expectedVersion(), action.serverScope());
        else if (request instanceof InstructionRequest.Approve action)
            writeInstructionAction(out, action.skillId(), action.expectedVersion(), action.serverScope());
        else if (request instanceof InstructionRequest.Reject action)
            writeInstructionAction(out, action.skillId(), action.expectedVersion(), action.serverScope());
        else if (request instanceof InstructionRequest.Export action)
            writeInstructionAction(out, action.skillId(), action.expectedVersion(), action.serverScope());
        else if (request instanceof InstructionRequest.Import imported) {
            writeDraft(out, imported.draft());
            out.writeBoolean(imported.serverScope());
        } else if (request instanceof InstructionRequest.DescriptionSet description) {
            writeText(out, description.summary(), 300, 1_200);
            writeText(out, description.details(), 4_000, 16_000);
            writeText(out, description.languageTag(), 35, 140);
            writeText(out, description.address(), 80, 320);
        } else if (!(request instanceof InstructionRequest.Open || request instanceof InstructionRequest.Close))
            throw new IllegalArgumentException("unsupported instructions request");
        return out.toByteArray();
    }

    private static InstructionRequest decodeInstructions(int operationId, byte[] body) {
        ProtocolBodyReader in = new ProtocolBodyReader(body);
        InstructionRequest request = switch (operationId) {
            case 1 -> new InstructionRequest.Open();
            case 2 -> new InstructionRequest.Page(in.readUnsignedByte());
            case 3 -> new InstructionRequest.Detail(in.readString(128));
            case 4 -> new InstructionRequest.Close();
            case 16 -> new InstructionRequest.Create(readDraft(in), in.readBoolean());
            case 17 ->
                new InstructionRequest.Edit(in.readString(128), readPositiveInt(in), readDraft(in), in.readBoolean());
            case 18 -> readInstructionAction(in, operationId, InstructionRequest.Duplicate::new);
            case 19 -> readInstructionAction(in, operationId, InstructionRequest.Delete::new);
            case 20 -> readInstructionAction(in, operationId, InstructionRequest.Enable::new);
            case 21 -> readInstructionAction(in, operationId, InstructionRequest.Disable::new);
            case 22 -> readInstructionAction(in, operationId, InstructionRequest.Approve::new);
            case 23 -> readInstructionAction(in, operationId, InstructionRequest.Reject::new);
            case 24 -> readInstructionAction(in, operationId, InstructionRequest.Export::new);
            case 25 -> new InstructionRequest.Import(readDraft(in), in.readBoolean());
            case 26 ->
                new InstructionRequest.DescriptionSet(
                        readText(in, 300, 1_200),
                        readText(in, 4_000, 16_000),
                        readText(in, 35, 140),
                        readText(in, 80, 320));
            default -> throw new ProtocolDataException(DecodeResult.Reason.UNKNOWN_OPERATION);
        };
        in.requireEnd();
        return request;
    }

    private static void writeInstructionAction(
            ProtocolBodyWriter out, String skillId, int expectedVersion, boolean serverScope) {
        out.writeString(skillId, 128);
        writePositiveInt(out, expectedVersion);
        out.writeBoolean(serverScope);
    }

    private static InstructionRequest readInstructionAction(
            ProtocolBodyReader in, int operationId, InstructionActionFactory factory) {
        String skillId = in.readString(128);
        int expectedVersion = readPositiveInt(in);
        boolean serverScope = in.readBoolean();
        return factory.create(skillId, expectedVersion, serverScope);
    }

    private static void writeDraft(ProtocolBodyWriter out, SkillDraft draft) {
        if (draft == null) throw new ProtocolDataException(DecodeResult.Reason.INVALID_FIELD);
        writeText(out, draft.name(), 80, 320);
        writeText(out, draft.description(), 200, 800);
        writeText(out, draft.body(), 4_000, 16_000);
        out.writeBoolean(draft.enabled());
        out.writeBoolean(draft.alwaysOn());
        if (draft.turnKinds().size() > 6) throw new ProtocolDataException(DecodeResult.Reason.OVERSIZED_FRAME);
        List<InstructionSkill.Kind> kinds = draft.turnKinds().stream()
                .sorted(Comparator.comparingInt(ClientRequestCodec::kindId))
                .toList();
        out.writeUnsignedByte(kinds.size());
        for (InstructionSkill.Kind kind : kinds) out.writeUnsignedByte(kindId(kind));
        writeTerms(out, draft.triggers());
        writeTerms(out, draft.tags());
        out.writeBoolean(draft.includeProfile());
    }

    private static SkillDraft readDraft(ProtocolBodyReader in) {
        String name = readText(in, 80, 320);
        String description = readText(in, 200, 800);
        String body = readText(in, 4_000, 16_000);
        boolean enabled = in.readBoolean();
        boolean alwaysOn = in.readBoolean();
        int kindCount = in.readUnsignedByte();
        if (kindCount > 6) throw new ProtocolDataException(DecodeResult.Reason.OVERSIZED_FRAME);
        Set<InstructionSkill.Kind> kinds = EnumSet.noneOf(InstructionSkill.Kind.class);
        for (int i = 0; i < kindCount; i++) {
            InstructionSkill.Kind kind = kindForId(in.readUnsignedByte());
            if (!kinds.add(kind)) throw new ProtocolDataException(DecodeResult.Reason.INVALID_ENUM);
        }
        List<String> triggers = readTerms(in);
        List<String> tags = readTerms(in);
        boolean includeProfile = in.readBoolean();
        return new SkillDraft(name, description, body, enabled, alwaysOn, kinds, triggers, tags, includeProfile);
    }

    private static void writeTerms(ProtocolBodyWriter out, List<String> values) {
        if (values == null || values.size() > 12) throw new ProtocolDataException(DecodeResult.Reason.OVERSIZED_FRAME);
        out.writeUnsignedByte(values.size());
        for (String value : values) writeText(out, value, 40, 160);
    }

    private static List<String> readTerms(ProtocolBodyReader in) {
        int count = in.readUnsignedByte();
        if (count > 12) throw new ProtocolDataException(DecodeResult.Reason.OVERSIZED_FRAME);
        List<String> values = new ArrayList<>(count);
        for (int i = 0; i < count; i++) values.add(readText(in, 40, 160));
        return List.copyOf(values);
    }

    private static void writeText(ProtocolBodyWriter out, String value, int maxCharacters, int maxBytes) {
        if (value == null || value.length() > maxCharacters)
            throw new ProtocolDataException(DecodeResult.Reason.INVALID_FIELD);
        out.writeString(value, maxBytes);
    }

    private static String readText(ProtocolBodyReader in, int maxCharacters, int maxBytes) {
        String value = in.readString(maxBytes);
        if (value.length() > maxCharacters) throw new ProtocolDataException(DecodeResult.Reason.INVALID_FIELD);
        return value;
    }

    private static int kindId(InstructionSkill.Kind kind) {
        return switch (kind) {
            case CHAT -> 1;
            case EVENT -> 2;
            case PLANNING -> 3;
            case FREE_TIME -> 4;
            case BUILDING -> 5;
            case COMBAT -> 6;
        };
    }

    private static InstructionSkill.Kind kindForId(int id) {
        return switch (id) {
            case 1 -> InstructionSkill.Kind.CHAT;
            case 2 -> InstructionSkill.Kind.EVENT;
            case 3 -> InstructionSkill.Kind.PLANNING;
            case 4 -> InstructionSkill.Kind.FREE_TIME;
            case 5 -> InstructionSkill.Kind.BUILDING;
            case 6 -> InstructionSkill.Kind.COMBAT;
            default -> throw new ProtocolDataException(DecodeResult.Reason.INVALID_ENUM);
        };
    }

    private static byte[] encodeTrading(TradeRequest request) {
        ProtocolBodyWriter out = new ProtocolBodyWriter();
        if (request instanceof TradeRequest.Subscribe subscribe) out.writeUuid(subscribe.queryId());
        else if (request instanceof TradeRequest.Query query) {
            out.writeUuid(query.queryId());
            out.writeString(query.itemId(), 128);
            out.writeString(query.componentsJson(), 8_192);
            out.writeUnsignedByte(directionId(query.direction()));
            out.writeUnsignedByte(query.page());
        } else if (request instanceof TradeRequest.Close close) out.writeUuid(close.queryId());
        else if (request instanceof TradeRequest.Inspect inspect) {
            out.writeUuid(inspect.queryId());
            out.writeUuid(inspect.traderId());
        } else if (request instanceof TradeRequest.Refresh refresh) {
            if (refresh.traderIds().size() > 20) throw new ProtocolDataException(DecodeResult.Reason.OVERSIZED_FRAME);
            out.writeUuid(refresh.queryId());
            out.writeUnsignedByte(refresh.traderIds().size());
            for (var traderId : refresh.traderIds()) out.writeUuid(traderId);
        } else if (request instanceof TradeRequest.Preview preview)
            writeTradeOrder(out, preview.queryId(), preview.argumentsJson());
        else if (request instanceof TradeRequest.Submit submit)
            writeTradeOrder(out, submit.queryId(), submit.argumentsJson());
        else if (request instanceof TradeRequest.Wait wait) writeTradeOrder(out, wait.queryId(), wait.argumentsJson());
        else if (request instanceof TradeRequest.Barter barter)
            writeTradeOrder(out, barter.queryId(), barter.argumentsJson());
        else if (request instanceof TradeRequest.Forget forget) {
            out.writeUuid(forget.queryId());
            out.writeUuid(forget.traderId());
            out.writeUnsignedByte(forget.page());
        } else throw new IllegalArgumentException("unsupported trading request");
        return out.toByteArray();
    }

    private static TradeRequest decodeTrading(int operationId, byte[] body) {
        ProtocolBodyReader in = new ProtocolBodyReader(body);
        TradeRequest request = switch (operationId) {
            case 1 -> new TradeRequest.Subscribe(in.readUuid());
            case 2 ->
                new TradeRequest.Query(
                        in.readUuid(),
                        in.readString(128),
                        in.readString(8_192),
                        directionForId(in.readUnsignedByte()),
                        in.readUnsignedByte());
            case 3 -> new TradeRequest.Close(in.readUuid());
            case 4 -> new TradeRequest.Inspect(in.readUuid(), in.readUuid());
            case 5 -> readTradeRefresh(in);
            case 6 -> new TradeRequest.Preview(in.readUuid(), in.readString(8_192));
            case 7 -> new TradeRequest.Submit(in.readUuid(), in.readString(8_192));
            case 8 -> new TradeRequest.Wait(in.readUuid(), in.readString(8_192));
            case 9 -> new TradeRequest.Forget(in.readUuid(), in.readUuid(), in.readUnsignedByte());
            case 10 -> new TradeRequest.Barter(in.readUuid(), in.readString(8_192));
            default -> throw new ProtocolDataException(DecodeResult.Reason.UNKNOWN_OPERATION);
        };
        in.requireEnd();
        return request;
    }

    private static TradeRequest.Refresh readTradeRefresh(ProtocolBodyReader in) {
        var queryId = in.readUuid();
        int count = in.readUnsignedByte();
        if (count > 20) throw new ProtocolDataException(DecodeResult.Reason.OVERSIZED_FRAME);
        List<java.util.UUID> traders = new ArrayList<>(count);
        for (int i = 0; i < count; i++) traders.add(in.readUuid());
        return new TradeRequest.Refresh(queryId, traders);
    }

    private static void writeTradeOrder(ProtocolBodyWriter out, java.util.UUID queryId, String argumentsJson) {
        out.writeUuid(queryId);
        out.writeString(argumentsJson, 8_192);
    }

    private static int directionId(TradeRequest.Direction direction) {
        if (direction == null) throw new ProtocolDataException(DecodeResult.Reason.INVALID_ENUM);
        return switch (direction) {
            case BUY -> 1;
            case SELL -> 2;
        };
    }

    private static TradeRequest.Direction directionForId(int id) {
        return switch (id) {
            case 1 -> TradeRequest.Direction.BUY;
            case 2 -> TradeRequest.Direction.SELL;
            default -> throw new ProtocolDataException(DecodeResult.Reason.INVALID_ENUM);
        };
    }

    private static byte[] encodeDebug(DebugRequest request) {
        ProtocolBodyWriter out = new ProtocolBodyWriter();
        if (request instanceof DebugRequest.MovementStreamSet movement) out.writeBoolean(movement.enabled());
        else if (!(request instanceof DebugRequest.PromptGet))
            throw new IllegalArgumentException("unsupported debug request");
        return out.toByteArray();
    }

    private static DebugRequest decodeDebug(int operationId, byte[] body) {
        ProtocolBodyReader in = new ProtocolBodyReader(body);
        DebugRequest request = switch (operationId) {
            case 1 -> new DebugRequest.PromptGet();
            case 2 -> new DebugRequest.MovementStreamSet(in.readBoolean());
            default -> throw new ProtocolDataException(DecodeResult.Reason.UNKNOWN_OPERATION);
        };
        in.requireEnd();
        return request;
    }

    private static byte[] encodeSpeech(SpeechRequest request) {
        ProtocolBodyWriter out = new ProtocolBodyWriter();
        if (request instanceof SpeechRequest.Transcript transcript) out.writeString(transcript.text(), 500);
        else throw new IllegalArgumentException("unsupported speech request");
        return out.toByteArray();
    }

    private static SpeechRequest decodeSpeech(int operationId, byte[] body) {
        ProtocolBodyReader in = new ProtocolBodyReader(body);
        if (operationId != 1) throw new ProtocolDataException(DecodeResult.Reason.UNKNOWN_OPERATION);
        SpeechRequest request = new SpeechRequest.Transcript(in.readString(500));
        in.requireEnd();
        return request;
    }

    @FunctionalInterface
    private interface InstructionActionFactory {
        InstructionRequest create(String skillId, int expectedVersion, boolean serverScope);
    }

    private static void writeCategory(ProtocolBodyWriter out, int categoryId) {
        if (categoryId < 1 || categoryId > 5) throw new ProtocolDataException(DecodeResult.Reason.INVALID_ENUM);
        out.writeUnsignedByte(categoryId);
    }

    private static int readCategory(ProtocolBodyReader in) {
        int categoryId = in.readUnsignedByte();
        if (categoryId < 1 || categoryId > 5) throw new ProtocolDataException(DecodeResult.Reason.INVALID_ENUM);
        return categoryId;
    }

    private static void writePositiveInt(ProtocolBodyWriter out, int value) {
        if (value <= 0) throw new ProtocolDataException(DecodeResult.Reason.INVALID_FIELD);
        out.writeInt(value);
    }

    private static int readPositiveInt(ProtocolBodyReader in) {
        int value = in.readInt();
        if (value <= 0) throw new ProtocolDataException(DecodeResult.Reason.INVALID_FIELD);
        return value;
    }

    private static byte[] encodeControl(ControlRequest request) {
        ProtocolBodyWriter out = new ProtocolBodyWriter();
        if (request instanceof ControlRequest.Rename rename) out.writeString(rename.name(), 128);
        else if (request instanceof ControlRequest.SetAutoRespawn toggle) out.writeBoolean(toggle.enabled());
        else if (request instanceof ControlRequest.SetCreativeMode toggle) out.writeBoolean(toggle.enabled());
        else if (request instanceof ControlRequest.SetFollowMode toggle) out.writeBoolean(toggle.enabled());
        else if (request instanceof ControlRequest.SendChat chat) out.writeString(chat.text(), 500);
        else if (request instanceof ControlRequest.InvokeTool tool) {
            out.writeString(tool.name(), 500);
            out.writeString(tool.argumentsJson(), 2_000);
        } else if (!(request instanceof ControlRequest.Subscribe
                || request instanceof ControlRequest.Unsubscribe
                || request instanceof ControlRequest.Summon
                || request instanceof ControlRequest.Dismiss
                || request instanceof ControlRequest.Stop
                || request instanceof ControlRequest.Teleport
                || request instanceof ControlRequest.FreeTimeToggle)) {
            throw new IllegalArgumentException("unsupported control request");
        }
        return out.toByteArray();
    }

    private static ControlRequest decodeControl(int operationId, byte[] body) {
        ProtocolBodyReader in = new ProtocolBodyReader(body);
        ControlRequest request = switch (operationId) {
            case 1 -> new ControlRequest.Subscribe();
            case 2 -> new ControlRequest.Unsubscribe();
            case 3 -> new ControlRequest.Summon();
            case 4 -> new ControlRequest.Dismiss();
            case 5 -> new ControlRequest.Stop();
            case 6 -> new ControlRequest.Teleport();
            case 7 -> new ControlRequest.Rename(in.readString(128));
            case 8 -> new ControlRequest.SetAutoRespawn(in.readBoolean());
            case 9 -> new ControlRequest.SetCreativeMode(in.readBoolean());
            case 10 -> new ControlRequest.SetFollowMode(in.readBoolean());
            case 11 -> new ControlRequest.FreeTimeToggle();
            case 12 -> new ControlRequest.SendChat(in.readString(500));
            case 13 -> new ControlRequest.InvokeTool(in.readString(500), in.readString(2_000));
            default -> throw new ProtocolDataException(DecodeResult.Reason.UNKNOWN_OPERATION);
        };
        in.requireEnd();
        return request;
    }
}
