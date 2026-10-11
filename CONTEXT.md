# Incursion – Kontext für Claude

Diese Datei ist der Einstieg für jeden neuen Chat zu diesem Projekt. Lies sie zuerst, dann `DESIGN.md` für Details.

## Zusammenarbeit

- Der Nutzer (MarleyBOSS) ist **Programmier-Anfänger** und spricht **Deutsch**. Antworten auf Deutsch, Code und Commits auf Englisch.
- **Claude schreibt den gesamten Code.** Der Nutzer baut nichts lokal und hat keine IDE. Er lädt die fertige `.jar` herunter und testet im Spiel.
- **Ehrlichkeit vor allem:** Der Nutzer hatte mit einer anderen KI schlechte Erfahrungen mit nicht funktionierendem Code. Unsicherheiten offen sagen, nichts als fertig melden, was nicht gebaut und geprüft ist.
- **Nutzungslimit schonen:** knapp antworten, keine langen Zusammenfassungen, nur relevante Dateien lesen. Pro Version am besten ein neuer Chat.
- Nach jeder Version: kurze Testanleitung (was craften, was klicken, was sollte passieren).

## Projekt

- Eigenständige Mod (`.jar`), **kein KubeJS**. Mod-ID `incursion`, Paket `com.marleyboss.incursion`.
- **Minecraft 1.21.1, NeoForge 21.1.259+**, ModDevGradle 2.0.148, Gradle 9.2.1, Java 21, Parchment 2024.11.17.
- Repo: https://github.com/MarleyBOSS/Incursion (Branch `main`). Lizenz vorerst „All Rights Reserved“.
- Gespielt wird im Modpack **Craftoria** (1.21.1, NeoForge, ca. 520 Mods, inkl. Create). Getestet wird zuerst in einem leeren NeoForge-Profil.

## Build und Release

- Gebaut wird **nur über GitHub Actions** (`.github/workflows/build.yml`). Jeder Push auf `main` ersetzt das Pre-Release **„Development build“** (Tag `dev`) mit der neuen `incursion-<version>.jar`.
- In Claudes Cloud-Umgebung funktioniert `./gradlew build` **nicht** (die NeoForge/Gradle-Mavens sind gesperrt). Also sorgfältig gegen die 1.21.1-APIs schreiben, pushen, CI-Ergebnis prüfen.
- `gh run list/view` funktioniert. GraphQL-Befehle (`gh release view/download`) und Actions-Artifact-Downloads sind gesperrt. Die Jar holt man über REST:
  `gh api repos/MarleyBOSS/Incursion/releases/tags/dev` → Asset-ID → `gh api -H "Accept: application/octet-stream" repos/MarleyBOSS/Incursion/releases/assets/<id> > incursion.jar`
- Commit-Nachrichten enden mit den Attributionszeilen, die die Umgebung vorgibt.

## Code-Struktur (Stand v0.1)

| Datei | Zweck |
|---|---|
| `Incursion.java` | `@Mod`, Registrierung, Creative Tab, Server-Config |
| `IncursionClient.java` | Config-Bildschirm im Mods-Menü |
| `Config.java` | ModConfigSpec (SERVER): `MAX_LEVEL`, `ACTIVATION_ITEM`, `UPGRADE_ITEM`, Kostenformel `base + perLevel * max(0, ziel-2)` |
| `registry/ModBlocks, ModItems, ModBlockEntities` | DeferredRegister |
| `block/CoreBlock.java` | Property `ACTIVE`, Aktivieren/Upgraden per Rechtsklick, Status, aktiv = nicht abbaubar |
| `block/CoreBlockEntity.java` | speichert `CoreLevel` (NBT) |
| `data/CoreWorldData.java` | SavedData `incursion_core`: Dimension und Position des einzigen aktiven Cores |

Ressourcen: Blockstates/Modelle (`core`, `core_active`), Platzhalter-Texturen, Loot Table, Rezept (Weinender Obsidian / Enderaugen / Diamantblock), Tags, Sprachdateien `en_us` und `de_de`.

## Konzept in Kürze (Details in DESIGN.md)

- **Core** mit Stufen 0–20. Stufe 0 ist friedlich, eine Ressource aktiviert ihn. Jedes Upgrade kostet Ressourcen.
- **Belohnungen** pro Stufe: Item-Generierung, FE-Energie (für Create über Create Crafts & Additions), beaconartige Buffs.
- **Belagerung:** dauerhaft, keine Wellen, keine Zeitskalierung, nur an die Stufe gekoppelt. Läuft auch bei Abwesenheit (eigener Chunkloader). Belagerungs-Mobs despawnen nicht. Chat-Warnungen bei Schaden am Core und beim Schildbruch.
- **Drei Zonen:**
  - Kernzone: Mobs ignorieren Spieler und greifen den Core an.
  - Belagerungsgebiet (= geladener Bereich, wächst mit der Stufe): Mobs greifen nahe Spieler an, sonst den Core.
  - Außerhalb: normales Verhalten.
- **Alle feindlichen Mobs**, auch modded, greifen auf ihre eigene Art an. Bosse lassen sich per Config ausnehmen. Creeper zerstören am Core keine Blöcke (konfigurierbar). Der Core hat Lebenspunkte, die mit der Stufe steigen.
- **Ein aktiver Core pro Welt.**
- **Aktiver Core = scharfe Bombe:** nicht verschiebbar. Abbau oder Zerstörung löst eine riesige, schrittweise erzeugte Explosion aus, die einen Krater und eine verseuchte Zone hinterlässt (standardmäßig dauerhaft). Entschärfen (Mechanik offen) setzt den Core auf Stufe 0 zurück.
- **Schild:** Partikelkuppel, deren Radius mit der Stufe wächst. Durchbricht ein Mob den Schild, tötet ein Puls alle Mobs im Radius (ohne Blockschaden), danach ist der Schild kaputt. Reparatur kostet 1 Core-Stufe und geht erst ab einer Mindeststufe (Standard 5).

## Roadmap

| Version | Inhalt | Status |
|---|---|---|
| v0.1 | Core, Aktivierung, Upgrades, Status, 1 pro Welt | gebaut, **Test durch Nutzer ausstehend** |
| v0.2 | Belagerung, Zonen-Targeting, Core-Lebenspunkte, Chunkloader, persistente Mobs, Warnungen | als Nächstes |
| v0.3 | Große Explosion, Verseuchung, Entschärfen | |
| v0.4 | Schildkuppel, Puls, Reparatur | |
| v0.5 | Items, Energie, Buffs | |
| danach | Config-Feinschliff, Balancing, Brennstoff, Create-Integration | |

## Offene Entscheidungen

- Aktivierungs- und Upgrade-Kosten (vorerst Lohenrute bzw. Diamanten)
- Entschärf-Mechanik
- Brennstoff ja/nein
- Lizenz
