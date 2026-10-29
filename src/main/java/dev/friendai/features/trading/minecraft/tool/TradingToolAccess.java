package dev.friendai.features.trading.minecraft.tool;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.features.trading.TradeAuthority;
import dev.friendai.features.trading.TradeBook;
import dev.friendai.features.trading.TraderRecord;
import dev.friendai.features.trading.TradingRuntimeSettings;
import java.util.List;
import java.util.UUID;

/** Narrow server capability consumed by trading tools; implementations live in server composition. */
public interface TradingToolAccess {
    TradingToolAccess UNAVAILABLE = new TradingToolAccess() {
        private IllegalStateException unavailable() {
            return new IllegalStateException("trading tools are not connected to server services");
        }

        @Override
        public TradeBook tradeBook(UUID owner) {
            throw unavailable();
        }

        @Override
        public TradeAuthority tradeAuthorityFor(UUID owner) {
            throw unavailable();
        }

        @Override
        public TradingRuntimeSettings tradingSettings() {
            throw unavailable();
        }

        @Override
        public List<TraderRecord> observeTraders(CompanionEntity companion) {
            throw unavailable();
        }
    };

    TradeBook tradeBook(UUID owner);

    TradeAuthority tradeAuthorityFor(UUID owner);

    TradingRuntimeSettings tradingSettings();

    List<TraderRecord> observeTraders(CompanionEntity companion);
}
