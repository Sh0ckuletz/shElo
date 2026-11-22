# shElo

A Paper 1.21.11 plugin that adds money rewards and Elo ratings to PvP fights.

## Features

- Killer gets `+$300` and `+30 Elo` by default.
- Victim loses `$50` and `10 Elo` on any death.
- Optional global kill announcement with a configurable prefix.
- Anti-farm protection that either blocks or reduces the reward for repeated kills.
- Per-world disabling.
- All values live in `config.yml`; all messages (MiniMessage) in `messages.yml`.
- Versioned auto-updater for `config.yml`, `messages.yml`, `colors.yml` and `menus.yml`.
- Persistent Elo in MySQL or SQLite (`database.storage-type`), with a HikariCP pool and async queries.
- Compensating rollback when a Vault transaction and an Elo update get out of sync.
- Async reload that swaps the MySQL connection atomically.
- Commands: `/elo`, `/elo <player>`, `/elo top [page]`, `/elo reload`, `/elocolors`.
- `/stats [player]` shows peak Elo, kills, deaths, K/D, streak and best streak.
- Admin commands: `/elo set|add|remove <player> <value>` and `/elo reset <player>`.
- PlaceholderAPI: `%shelo_elo%` and `%shelo_elo_formatted%`, including for offline players.
- Elo colors defined by rules (`>=`, `<=`, `>`, `<`, `=`, `==`) in `colors.yml`.
- `AUTOMATIC` mode follows `colors.yml`; `MENU` mode offers tiers inspired by CS2 Premier Rating.
- A color stays available only while the player's Elo meets its threshold.
- Optional notification when a selected color is disabled by an Elo drop.
- Messages can use placeholders from other PlaceholderAPI expansions.

## Requirements

- Paper 1.21.11
- Java 21
- Vault and a compatible economy provider (for example EssentialsX Economy)
- MySQL Server 8.0 or newer for `database.storage-type: MYSQL`. `SQLITE` needs no external
  server and keeps data in a local file.
- PlaceholderAPI 2.12.2 or newer (optional, needed for integration with other plugins)

Pick the storage type in `config.yml` under `database.storage-type` before the first start
(the default is `MYSQL`). If you use MySQL, fill in the rest of the `database` section too.
The table or file is created automatically.

## Building

```powershell
.\gradlew.bat build
```

The jar is written to `build/libs/shElo-1.11.4.jar`.

## bStats

The plugin reports anonymous usage metrics (server count, Paper version, storage type)
through [bStats](https://bstats.org/). You can turn this off for all plugins in
`plugins/bStats/config.yml` by setting `enabled: false`.

## License

Distributed under [GPL-3.0](LICENSE).
