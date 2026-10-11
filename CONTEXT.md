# Incursion – Kontext für Claude

Diese Datei ist der Einstieg für jeden neuen Chat zu diesem Projekt. Lies sie zuerst, dann `DESIGN.md` für Details.

## Zusammenarbeit

- Der Nutzer (MarleyBOSS) ist **Programmier-Anfänger** und spricht **Deutsch**. Antworten auf Deutsch, Code und Commits auf Englisch.
- **Claude schreibt den gesamten Code.** Der Nutzer baut nichts lokal und hat keine IDE. Er lädt die fertige `.jar` herunter und testet im Spiel.
- **Ehrlichkeit vor allem:** Der Nutzer hatte mit einer anderen KI schlechte Erfahrungen mit nicht funktionierendem Code. Unsicherheiten offen sagen, nichts als fertig melden, was nicht gebaut und geprüft ist.
- **Nutzungslimit schonen:** knapp antworten, keine langen Zusammenfassungen, nur relevante Dateien lesen. Pro Version am besten ein neuer Chat.
- Nach jeder Version: kurze Testanleitung (was craften, was klicken, was sollte passieren).
- **Diese Datei aktuell halten:** Nach jedem Änderungswunsch, jeder Entscheidung, jedem Testergebnis und jeder fertigen Version die passenden Abschnitte hier aktualisieren (Roadmap-Status, offene Entscheidungen, Code-Struktur, „Letzter Stand“), dann committen und pushen. Kurz halten, nur das Nötige. Der Nutzer startet jeden neuen Chat nur mit: „Lies CONTEXT.md und mach weiter.“

## Letzter Stand

- v0.1 und v0.2 vom Nutzer getestet ✔.
- v0.3 vom Nutzer getestet: Grundfunktionen gehen, aber Mob-KI zu dumm. Feedback (Schwert-Zombies greifen Spieler nicht an und bauen ab, kein Ausweichen auf weiche Blöcke, Patt wenn nur Krieger übrig, Kern in der Luft unerreichbar, Spawns in der Base) ist in v0.4 umgesetzt.
- **v0.4 „Belagerungs-KI“** gebaut, CI grün, als „Development build“ (`incursion-0.4.0.jar`) veröffentlicht. **Test durch Nutzer ausstehend.** Ungetestet und am riskantesten: Routen-Laufen (MoveControl Knoten für Knoten), Treppenbau, Enderman-Teleport, manuelle Pfeilschüsse.
- Geänderte Defaults (Rollen-Gewichte, Enderman ab Stufe 6) greifen nur in neuen Welten bzw. nach Löschen von `serverconfig/incursion-server.toml`.
- Schild auf v0.5 verschoben (Nutzerwunsch KI hatte Vorrang).

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

## Code-Struktur (Stand v0.4)

| Datei | Zweck |
|---|---|
| `Incursion.java` | `@Mod`, Registrierung, Creative Tab, Server-Config, Events, Ticket-Controller |
| `IncursionClient.java` | Config-Bildschirm, Screen- und Renderer-Registrierung |
| `Config.java` | SERVER-Config: `core`, `siege`, `digging`, `zombies`, `skeletons`, `endermen`, `tactics`, `wards`, `explosion`, `defuse` + Formeln |
| `registry/*` | Blocks (`core`, `ward`), Items, BlockEntities, Entities (`core_target`), Menus |
| `block/CoreBlock.java`, `CoreActions.java`, `CoreBlockEntity.java` | Kern: Aktivieren/Upgraden, HP, Chunk-Radius, Ziel-Entity, Entschärfen; Entfernen eines aktiven Kerns = Explosion |
| `block/WardBlock.java`, `WardBlockEntity.java` | Schutztotem: kein Spawn im Radius, grüner Partikelring, statische Liste geladener Totems |
| `entity/CoreTargetEntity.java` | Unsichtbare LivingEntity am Kern, Schaden von Enemy-Mobs → Kern-HP (× Rollen-Multiplikator), meldet Fortschritt |
| `siege/SiegeManager.java` | Jede Sekunde: Chunks, Regeneration, Targeting, Rollen zählen, TNT, Ringe, Spawns (Bedarf, Totems, Patt-Verstärkung) |
| `siege/SiegeEvents.java` | Target-Regeln, Explosionen (Kern/siege_proof geschützt, Belagerungs-Explosionen schonen Belagerungs-Mobs), Rollen-Schaden |
| `siege/Targeting.java` | Wer greift was an: Arbeiter → Kern, Krieger → Spieler (jede Zone), Schützen → Kern oder Deckungsfeuer |
| `siege/MobRoles.java` | Rollen per Scoreboard-Tag (Zombie: Krieger/Gräber/Brecher/Baumeister; Skelett: Spreng/Feuer/normal; Enderman: Pionier), ausgewogene Vergabe, Ausrüstung |
| `siege/SiegeAI.java` | Pro Mob alle 5 Ticks: Feststecken → Route teilen/helfen/planen, Route abarbeiten (graben, bauen, Creeper-Durchbruch, Enderman nimmt Block), Pionier-TNT, Sprengschuss, Selbstzerstörung |
| `siege/SiegeDriveGoal.java` | Goal (Prio 1, MOVE+LOOK) an jedem feindlichen Bodenmob; steuert Bewegung, wenn SiegeAI einen Auftrag hat |
| `siege/RoutePlanner.java`, `Route.java` | A* über Blockpositionen (Kosten: Laufen, Härte, Bauen; Treppen statt Säulen), geteilte Routen, Arbeits-/Bedarfsmarker |
| `siege/SiegeProgress.java` | Letzter Fortschritt (Patt), letzte Aktionsorte (Begleiter), Bedarf an Rollen |
| `siege/SiegeExplosives.java` | Feuer-/Explosionspfeile, manuelle Schüsse, TNT-Verfolgung und Zündung |
| `siege/Digging.java` | Abbau mit gemeinsamem Fortschritt und Rissen, Grabzeit-Formel; Tag `incursion:siege_proof` |
| `siege/SpawnRings.java` | Partikelringe (Totem grün, Kern lila) |
| `siege/ActiveSiege.java`, `SiegeRules.java`, `ChunkLoading.java` | Zonen-Schnappschuss (inkl. Stufe), Mob-Listen, Kern-Chunkloader |
| `fallout/*` | Große Explosion, Krater-Jobs, Verseuchung (SavedData) |
| `menu/CoreMenu.java`, `client/CoreScreen.java` | GUI ohne Slots; Aktivieren/Upgraden/Entschärfen |
| `data/CoreWorldData.java` | SavedData `incursion_core`: der eine aktive Core |

Ressourcen: Blockstates/Modelle (`core`, `core_active`), Platzhalter-Texturen (`core`, `core_active`, `ward`), Loot Table, Rezepte (Kern: Weinender Obsidian / Enderaugen / Diamantblock; Schutztotem: Enderperle / Gold / Steinziegel), Tags, Sprachdateien `en_us` und `de_de`.

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
| v0.1 | Core, Aktivierung, Upgrades, Status, 1 pro Welt | getestet ✔ |
| v0.2 | Belagerung, Zonen-Targeting, Core-Lebenspunkte, Chunkloader, persistente Mobs, Warnungen, **Core-GUI** | getestet ✔ |
| v0.3 | Große Explosion, Verseuchung, Entschärfen, grabende Mobs, Zombie-Rollen | getestet (KI-Feedback → v0.4) |
| v0.4 | Belagerungs-KI: Routenplaner, Baumeister, Rollen, Skelett-Klassen, Pioniere, Patt-Regel, Schutztotem | gebaut, **Test ausstehend** |
| v0.5 | Schildkuppel, Puls, Reparatur | als Nächstes |
| v0.6 | Items, Energie, Buffs (Anzeige in der GUI vorgesehen) | |
| danach | Config-Feinschliff, Balancing, Brennstoff, Create-Integration | |

## Offene Entscheidungen

- Aktivierungs- und Upgrade-Kosten (vorerst Lohenrute bzw. Diamanten)
- Entschärf-Mechanik (v0.3-Platzhalter: GUI-Button, Countdown 10 s + 2 s/Stufe, Spieler muss ≤ 8 Blöcke bleiben)
- Brennstoff ja/nein
- Schutztotem-Rezept und Preis (vorerst günstig, +2 Mobs pro Totem)
- Wo stehen wartende Mobs (aktuell 3 Schritte hinter der Arbeit)
- Lizenz
