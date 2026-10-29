package dev.friendai.server.net;

import dev.friendai.protocol.ClientRequest;
import dev.friendai.protocol.control.ControlRequest;
import dev.friendai.protocol.debug.DebugRequest;
import dev.friendai.protocol.instructions.InstructionRequest;
import dev.friendai.protocol.memory.MemoryRequest;
import dev.friendai.protocol.places.PlaceRequest;
import dev.friendai.protocol.speech.SpeechRequest;
import dev.friendai.protocol.trading.TradeRequest;

/** Routes typed operations after the transport has authenticated their connection and sequence. */
public final class ServerRequestDispatcher {
    private ServerRequestDispatcher() {}

    public static void handle(AuthenticatedRequestContext context, ClientRequest request) {
        if (request instanceof ControlRequest
                || request instanceof MemoryRequest
                || request instanceof PlaceRequest
                || request instanceof DebugRequest) {
            CompanionRequestHandler.handle(context, request);
        } else if (request instanceof InstructionRequest instruction) {
            SkillRequestHandler.handle(context, instruction);
        } else if (request instanceof TradeRequest trade) {
            TradeRequestHandler.handle(context, trade);
        } else if (request instanceof SpeechRequest.Transcript transcript) {
            VoiceTranscriptHandler.handle(context, transcript.text());
        }
    }
}
