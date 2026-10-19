package dev.juanse.tiktokmobs.plugin.config;

import dev.juanse.tiktokmobs.core.config.MappingTable;

/** Config ya validada. El resto del plugin no vuelve a tocar el YAML. */
public record PluginSettings(SourceType sourceType,
                             String tikfinityUrl,
                             int reconnectMaxSeconds,
                             TargetMode targetMode,
                             String targetPlayerName,
                             String targetWorld,
                             double targetX,
                             double targetY,
                             double targetZ,
                             int maxLiveSpawned,
                             int maxActionsPerTick,
                             double tpsFloor,
                             int queueCapacity,
                             long dedupTtlMs,
                             boolean broadcast,
                             boolean scoreboard,
                             int scoreboardSize,
                             boolean goalBar,
                             boolean titleFeed,
                             long titleGapMs,
                             int chaosEvery,
                             boolean chatEnabled,
                             int chatMaxPerSecond,
                             int chatMinCoins,
                             boolean overlayEnabled,
                             String overlayBind,
                             int overlayPort,
                             String overlayToken,
                             int overlayAlertMinCoins,
                             long overlayStateRefreshTicks,
                             java.util.List<StreamSource> sources,
                             java.util.Map<String, dev.juanse.tiktokmobs.core.boss.BossDefinition> bosses,
                             MappingTable mapping) {

    public enum SourceType {
        FAKE,
        TIKFINITY
    }

    public enum TargetMode {
        PLAYER,
        FIRST_ONLINE,
        LOCATION
    }
}
