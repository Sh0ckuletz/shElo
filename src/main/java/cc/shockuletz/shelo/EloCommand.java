package cc.shockuletz.shelo;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public final class EloCommand implements CommandExecutor, TabCompleter {
    private static final List<String> ADMIN_ACTIONS = List.of("set", "add", "remove", "reset");
    private final ShEloPlugin plugin;

    public EloCommand(ShEloPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                plugin.messages().send(sender, "usage");
                return true;
            }
            sendElo(sender, "elo-self", player.getUniqueId(), null);
            return true;
        }

        String action = args[0].toLowerCase(Locale.ROOT);
        if (action.equals("reload")) {
            if (args.length != 1) {
                plugin.messages().send(sender, "usage");
                return true;
            }
            if (!sender.hasPermission("shelo.reload")) {
                plugin.messages().send(sender, "no-permission");
                return true;
            }
            if (plugin.isReloadInProgress()) {
                plugin.messages().send(sender, "reload-in-progress");
                return true;
            }
            plugin.messages().send(sender, "reload-start");
            plugin.reloadPluginAsync().whenComplete((result, error) -> plugin.runSync(() -> {
                if (error != null) {
                    plugin.messages().send(sender, "reload-error");
                    plugin.getLogger().severe("Async reload failed: " + error.getMessage());
                    return;
                }
                String messageKey = switch (result) {
                    case SUCCESS -> "reload";
                    case ALREADY_IN_PROGRESS -> "reload-in-progress";
                    case CONFIG_ERROR -> "reload-config-error";
                    case DATABASE_ERROR, DISABLED -> "reload-error";
                };
                plugin.messages().send(sender, messageKey);
            }));
            return true;
        }

        if (action.equals("top")) {
            handleLeaderboard(sender, args);
            return true;
        }

        if (ADMIN_ACTIONS.contains(action)) {
            handleAdminCommand(sender, action, args);
            return true;
        }

        if (args.length == 1) {
            OfflinePlayer target = findPlayer(args[0]);
            if (target != null) {
                String targetName = target.getName() == null ? args[0] : target.getName();
                sendElo(sender, "elo-other", target.getUniqueId(), targetName);
                return true;
            }
            findStoredPlayer(sender, args[0]);
            return true;
        }

        plugin.messages().send(sender, "usage");
        return true;
    }

    private void handleLeaderboard(CommandSender sender, String[] args) {
        if (!sender.hasPermission("shelo.top")) {
            plugin.messages().send(sender, "no-permission");
            return;
        }
        if (args.length > 2) {
            plugin.messages().send(sender, "leaderboard-usage");
            return;
        }

        int requestedPage;
        if (args.length == 2) {
            try {
                requestedPage = Integer.parseInt(args[1]);
                if (requestedPage < 1) {
                    throw new NumberFormatException("non-positive");
                }
            } catch (NumberFormatException exception) {
                plugin.messages().send(sender, "leaderboard-invalid-page",
                        MessageManager.text("page", args[1]),
                        MessageManager.text("max_page", "?"));
                return;
            }
        } else {
            requestedPage = 1;
        }

        int pageSize = plugin.settings().leaderboardPageSize();
        plugin.database().getLeaderboard(requestedPage, pageSize)
                .whenComplete((leaderboard, error) -> plugin.runSync(() -> {
                    if (error != null) {
                        databaseFailure(sender, error);
                        return;
                    }
                    if (leaderboard.totalPlayers() == 0) {
                        plugin.messages().send(sender, "leaderboard-empty");
                        return;
                    }
                    if (requestedPage > leaderboard.totalPages()) {
                        plugin.messages().send(sender, "leaderboard-invalid-page",
                                MessageManager.text("page", Integer.toString(requestedPage)),
                                MessageManager.text("max_page", Long.toString(leaderboard.totalPages())));
                        return;
                    }

                    plugin.messages().send(sender, "leaderboard-header",
                            MessageManager.text("page", Integer.toString(requestedPage)),
                            MessageManager.text("pages", Long.toString(leaderboard.totalPages())),
                            MessageManager.text("total_players", Long.toString(leaderboard.totalPlayers())));

                    long firstRank = (requestedPage - 1L) * pageSize + 1L;
                    for (int index = 0; index < leaderboard.entries().size(); index++) {
                        EloStorage.LeaderboardEntry entry = leaderboard.entries().get(index);
                        plugin.messages().send(sender, "leaderboard-entry",
                                MessageManager.text("rank", Long.toString(firstRank + index)),
                                MessageManager.text("player", entry.name()),
                                MessageManager.text("elo", Integer.toString(entry.elo())),
                                MessageManager.parsed("elo_formatted",
                                        plugin.formatElo(entry.uuid(), entry.elo())));
                    }

                    long previousPage = Math.max(1L, requestedPage - 1L);
                    long nextPage = Math.min(leaderboard.totalPages(), requestedPage + 1L);
                    plugin.messages().send(sender, "leaderboard-footer",
                            MessageManager.text("page", Integer.toString(requestedPage)),
                            MessageManager.text("pages", Long.toString(leaderboard.totalPages())),
                            MessageManager.text("previous_page", Long.toString(previousPage)),
                            MessageManager.text("next_page", Long.toString(nextPage)));
                }));
    }

    private void handleAdminCommand(CommandSender sender, String action, String[] args) {
        if (!sender.hasPermission("shelo.admin." + action)) {
            plugin.messages().send(sender, "no-permission");
            return;
        }

        int expectedArguments = action.equals("reset") ? 2 : 3;
        if (args.length != expectedArguments) {
            plugin.messages().send(sender, "admin-usage");
            return;
        }

        int amount = plugin.settings().startingElo();
        if (!action.equals("reset")) {
            try {
                amount = Integer.parseInt(args[2]);
                if (amount < 0) {
                    throw new NumberFormatException("negative");
                }
            } catch (NumberFormatException exception) {
                plugin.messages().send(sender, "admin-invalid-number");
                return;
            }
        }

        String requestedName = args[1];
        OfflinePlayer target = findPlayer(requestedName);
        if (target != null) {
            String targetName = target.getName() == null ? requestedName : target.getName();
            updateElo(sender, action, new AdminTarget(target.getUniqueId(), targetName), amount);
            return;
        }

        int finalAmount = amount;
        plugin.database().findByName(requestedName)
                .whenComplete((result, error) -> plugin.runSync(() -> {
                    if (error != null) {
                        databaseFailure(sender, error);
                        return;
                    }
                    Optional<EloStorage.PlayerElo> storedPlayer = result;
                    if (storedPlayer.isEmpty()) {
                        plugin.messages().send(sender, "player-not-found",
                                MessageManager.text("player", requestedName));
                        return;
                    }
                    EloStorage.PlayerElo stored = storedPlayer.get();
                    updateElo(sender, action, new AdminTarget(stored.uuid(), stored.name()), finalAmount);
                }));
    }

    private void updateElo(CommandSender sender, String action, AdminTarget target, int amount) {
        int previousElo = plugin.database().getCachedElo(
                target.uuid(), plugin.settings().startingElo());
        CompletableFuture<Integer> update = switch (action) {
            case "set" -> plugin.database().setElo(
                    target.uuid(), target.name(), amount, plugin.settings().minimumElo());
            case "add" -> plugin.database().addElo(
                    target.uuid(), target.name(), amount,
                    plugin.settings().startingElo(), plugin.settings().minimumElo());
            case "remove" -> plugin.database().addElo(
                    target.uuid(), target.name(), -amount,
                    plugin.settings().startingElo(), plugin.settings().minimumElo());
            case "reset" -> plugin.database().setElo(
                    target.uuid(), target.name(), plugin.settings().startingElo(),
                    plugin.settings().minimumElo());
            default -> throw new IllegalArgumentException("Unknown Elo action: " + action);
        };

        update.whenComplete((newElo, error) -> plugin.runSync(() -> {
            if (error != null) {
                databaseFailure(sender, error);
                return;
            }

            plugin.messages().send(sender, "admin-update",
                    MessageManager.text("action", actionLabel(action)),
                    MessageManager.text("player", target.name()),
                    MessageManager.text("amount", Integer.toString(amount)),
                    MessageManager.text("new_elo", Integer.toString(newElo)));

            Player onlineTarget = Bukkit.getPlayer(target.uuid());
            if (onlineTarget != null) {
                plugin.messages().send(onlineTarget, "admin-target-update",
                        MessageManager.text("action", action),
                        MessageManager.text("amount", Integer.toString(amount)),
                        MessageManager.text("new_elo", Integer.toString(newElo)));
                plugin.colorMenu().notifyIfLostAccess(onlineTarget, previousElo, newElo);
            }
        }));
    }

    private String actionLabel(String action) {
        return switch (action) {
            case "set" -> "set";
            case "add" -> "increased";
            case "remove" -> "decreased";
            case "reset" -> "reset";
            default -> action;
        };
    }

    private void sendElo(CommandSender sender, String messageKey, UUID playerId,
                         @Nullable String playerName) {
        CompletableFuture<Integer> future = plugin.database().getElo(
                playerId, plugin.settings().startingElo());
        future.whenComplete((elo, error) -> plugin.runSync(() -> {
            if (error != null) {
                databaseFailure(sender, error);
                return;
            }
            if (playerName == null) {
                plugin.messages().send(sender, messageKey,
                        MessageManager.text("elo", Integer.toString(elo)));
            } else {
                plugin.messages().send(sender, messageKey,
                        MessageManager.text("player", playerName),
                        MessageManager.text("elo", Integer.toString(elo)));
            }
        }));
    }

    private void findStoredPlayer(CommandSender sender, String requestedName) {
        plugin.database().findByName(requestedName)
                .whenComplete((result, error) -> plugin.runSync(() -> {
                    if (error != null) {
                        databaseFailure(sender, error);
                        return;
                    }
                    Optional<EloStorage.PlayerElo> player = result;
                    if (player.isEmpty()) {
                        plugin.messages().send(sender, "player-not-found",
                                MessageManager.text("player", requestedName));
                        return;
                    }
                    EloStorage.PlayerElo stored = player.get();
                    plugin.messages().send(sender, "elo-other",
                            MessageManager.text("player", stored.name()),
                            MessageManager.text("elo", Integer.toString(stored.elo())));
                }));
    }

    private void databaseFailure(CommandSender sender, Throwable error) {
        plugin.messages().send(sender, "database-error");
        Throwable cause = error instanceof CompletionException && error.getCause() != null
                ? error.getCause() : error;
        plugin.getLogger().severe("Database error in /elo command: " + cause.getMessage());
    }

    @Nullable
    private OfflinePlayer findPlayer(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online;
        }
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        return cached != null && (cached.hasPlayedBefore() || cached.isOnline()) ? cached : null;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                 @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            String input = args[0].toLowerCase(Locale.ROOT);
            List<String> suggestions = new ArrayList<>();
            if (sender.hasPermission("shelo.reload") && "reload".startsWith(input)) {
                suggestions.add("reload");
            }
            if (sender.hasPermission("shelo.top") && "top".startsWith(input)) {
                suggestions.add("top");
            }
            for (String action : ADMIN_ACTIONS) {
                if (sender.hasPermission("shelo.admin." + action) && action.startsWith(input)) {
                    suggestions.add(action);
                }
            }
            addOnlinePlayers(suggestions, input);
            return suggestions;
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("top")
                && sender.hasPermission("shelo.top")) {
            return "1".startsWith(args[1]) ? List.of("1") : List.of();
        }

        if (args.length == 2 && ADMIN_ACTIONS.contains(args[0].toLowerCase(Locale.ROOT))
                && sender.hasPermission("shelo.admin." + args[0].toLowerCase(Locale.ROOT))) {
            List<String> suggestions = new ArrayList<>();
            addOnlinePlayers(suggestions, args[1].toLowerCase(Locale.ROOT));
            return suggestions;
        }
        return List.of();
    }

    private void addOnlinePlayers(List<String> suggestions, String input) {
        Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(input))
                .forEach(suggestions::add);
    }

    private record AdminTarget(UUID uuid, String name) {
    }
}
