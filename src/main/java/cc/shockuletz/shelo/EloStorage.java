package cc.shockuletz.shelo;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface EloStorage extends AutoCloseable {

    CompletableFuture<Void> ensurePlayer(UUID playerId, String playerName, int startingElo);

    CompletableFuture<Integer> getElo(UUID playerId, int startingElo);

    CompletableFuture<Integer> preloadAllPlayers();

    Integer getCachedEloIfPresent(UUID playerId);

    void ensureEloCached(UUID playerId, int startingElo);

    CompletableFuture<Integer> addElo(UUID playerId, String playerName, int amount,
                                       int startingElo, int minimumElo);

    CompletableFuture<Integer> setElo(UUID playerId, String playerName, int value, int minimumElo);

    CompletableFuture<Optional<PlayerElo>> findByName(String playerName);

    CompletableFuture<Void> recordPvpResult(UUID killerId, String killerName,
                                             UUID victimId, String victimName,
                                             int startingElo);

    CompletableFuture<PlayerStats> getOrCreateStats(UUID playerId, String playerName, int startingElo);

    CompletableFuture<Optional<PlayerStats>> findStatsByName(String playerName);

    CompletableFuture<LeaderboardPage> getLeaderboard(int page, int pageSize);

    int getCachedElo(UUID playerId, int startingElo);

    int getCachedHighestElo(UUID playerId, int fallback);

    String getCachedSelectedColor(UUID playerId);

    CompletableFuture<Void> setSelectedColor(UUID playerId, String colorId);

    @Override
    void close();

    record PlayerElo(UUID uuid, String name, int elo) {
    }

    record PlayerStats(UUID uuid, String name, int highestElo, long kills, long deaths,
                        long currentStreak, long bestStreak) {
        public double kd() {
            return kills / (double) Math.max(1L, deaths);
        }
    }

    record LeaderboardEntry(UUID uuid, String name, int elo) {
    }

    record LeaderboardPage(List<LeaderboardEntry> entries, int page,
                            long totalPages, long totalPlayers) {
    }
}
