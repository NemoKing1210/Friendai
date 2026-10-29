package dev.friendai.protocol.trading;

import dev.friendai.protocol.ClientRequest;
import dev.friendai.protocol.ProtocolFamily;
import java.util.List;
import java.util.UUID;

public sealed interface TradeRequest extends ClientRequest
        permits TradeRequest.Subscribe,
                TradeRequest.Query,
                TradeRequest.Close,
                TradeRequest.Inspect,
                TradeRequest.Refresh,
                TradeRequest.Preview,
                TradeRequest.Submit,
                TradeRequest.Wait,
                TradeRequest.Forget,
                TradeRequest.Barter {
    @Override
    default ProtocolFamily family() {
        return ProtocolFamily.TRADING;
    }

    enum Direction {
        BUY,
        SELL
    }

    record Subscribe(UUID queryId) implements TradeRequest {
        @Override
        public int operationId() {
            return 1;
        }
    }

    record Query(UUID queryId, String itemId, String componentsJson, Direction direction, int page)
            implements TradeRequest {
        @Override
        public int operationId() {
            return 2;
        }
    }

    record Close(UUID queryId) implements TradeRequest {
        @Override
        public int operationId() {
            return 3;
        }
    }

    record Inspect(UUID queryId, UUID traderId) implements TradeRequest {
        @Override
        public int operationId() {
            return 4;
        }
    }

    record Refresh(UUID queryId, List<UUID> traderIds) implements TradeRequest {
        public Refresh {
            traderIds = List.copyOf(traderIds);
        }

        @Override
        public int operationId() {
            return 5;
        }
    }

    record Preview(UUID queryId, String argumentsJson) implements TradeRequest {
        @Override
        public int operationId() {
            return 6;
        }
    }

    record Submit(UUID queryId, String argumentsJson) implements TradeRequest {
        @Override
        public int operationId() {
            return 7;
        }
    }

    record Wait(UUID queryId, String argumentsJson) implements TradeRequest {
        @Override
        public int operationId() {
            return 8;
        }
    }

    record Forget(UUID queryId, UUID traderId, int page) implements TradeRequest {
        @Override
        public int operationId() {
            return 9;
        }
    }

    record Barter(UUID queryId, String argumentsJson) implements TradeRequest {
        @Override
        public int operationId() {
            return 10;
        }
    }
}
