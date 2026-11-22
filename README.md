# shElo

Plugin Paper 1.21.11 pentru recompense economice si Elo la luptele PvP.

## Functionalitati

- killer: `+300$` si `+30 Elo` implicit;
- victima: `-50$` si `-10 Elo` la orice moarte;
- anunt global configurabil pentru kill-urile intre jucatori, cu prefix optional;
- protectie anti-farm configurabila, cu blocare sau reducerea recompensei;
- lumi dezactivate configurabile;
- valori configurabile in `config.yml`;
- mesaje MiniMessage configurabile in `messages.yml`;
- auto-updater versionat pentru `config.yml`, `messages.yml`, `colors.yml` si `menus.yml`;
- Elo persistent in MySQL sau SQLite (configurabil prin `database.storage-type`), cu pool HikariCP si operatii asincrone;
- sincronizare compensatorie intre tranzactiile Vault si modificarile Elo;
- reload asincron cu schimbare atomica a conexiunii MySQL;
- /elo, /elo <jucator>, /elo top [pagina], /elo reload si /elocolors;
- `/stats [jucator]` pentru Peak Elo, kills, deaths, K/D, streak si best streak;
- comenzi admin: `/elo set|add|remove <jucator> <valoare>` si `/elo reset <jucator>`;
- PlaceholderAPI: `%shelo_elo%` si `%shelo_elo_formatted%`, inclusiv pentru jucatori offline;
- culori Elo configurabile prin reguli `>=`, `<=`, `>`, `<`, `=` si `==` in `colors.yml`;
- mod `AUTOMATIC` bazat pe `colors.yml` sau `MENU` cu trepte inspirate de CS2 Premier Rating;
- culori disponibile doar cat timp Elo-ul actual indeplineste pragul configurat;
- notificare configurabila cand o culoare selectata este dezactivata prin scaderea Elo;
- toate mesajele accepta si placeholder-ele altor expansions.

## Cerinte

- Paper 1.21.11;
- Java 21;
- Vault si un provider de economie compatibil (de exemplu EssentialsX Economy).
- Pentru `database.storage-type: MYSQL` este necesar MySQL Server 8.0 sau mai nou; pentru
  `SQLITE` nu este nevoie de niciun server extern, datele sunt salvate intr-un fisier local.
- PlaceholderAPI 2.12.2 sau mai nou este optional, dar necesar pentru integrarea cu alte pluginuri.

Alege tipul de stocare din `database.storage-type` in `config.yml` inainte de prima
pornire (implicit `MYSQL`). Daca folosesti MySQL, completeaza si restul sectiunii
`database`. Tabelul/fisierul configurat este creat automat.

## Build

```powershell
.\gradlew.bat build
```

JAR-ul rezultat se afla in `build/libs/shElo-1.11.4.jar`.

## bStats

Pluginul poate raporta metrici anonime de utilizare (numar de servere, versiune Paper,
tip de storage folosit) prin [bStats](https://bstats.org/). Pentru a le activa:

1. Fa cont pe [bstats.org](https://bstats.org/) si inregistreaza un plugin nou de tip
   *Bukkit/Spigot/Paper* pentru a primi un **Plugin ID** numeric.
2. In [`ShEloPlugin.java`](src/main/java/cc/shockuletz/shelo/ShEloPlugin.java), inlocuieste
   valoarea `BSTATS_PLUGIN_ID` (implicit `0`, dezactivat) cu ID-ul primit.
3. Recompileaza pluginul. Metricile pornesc automat la `onEnable`.

Serverele au acest raportare dezactivabila global din `plugins/bStats/config.yml`
(`enabled: false`), indiferent de plugin.

## Licenta

Distribuit sub [GPL-3.0](LICENSE).
