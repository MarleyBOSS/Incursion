# Incursion – Kontext für Claude

Diese Datei ist der Einstieg für jeden neuen Chat zu diesem Projekt. Lies sie zuerst, dann `DESIGN.md` für Details.

## Zusammenarbeit

- Der Nutzer (MarleyBOSS) ist **Programmier-Anfänger** und spricht **Deutsch**. Antworten auf Deutsch, Code und Commits auf Englisch.
- **Claude schreibt den gesamten Code.** Der Nutzer baut nichts lokal und hat keine IDE. Er lädt die fertige `.jar` herunter und testet im Spiel.
- **Ehrlichkeit vor allem:** Der Nutzer hatte mit einer anderen KI schlechte Erfahrungen mit nicht funktionierendem Code. Unsicherheiten offen sagen, nichts als fertig melden, was nicht gebaut und geprüft ist.
- **Nutzungslimit schonen:** knapp antworten, keine langen Zusammenfassungen, nur relevante Dateien lesen. Pro Version am besten ein neuer Chat.
- **Versionsnummern:** Solange das Mob-/KI-Problem (Kern zuverlässig erreichen und zerstören) nicht gelöst ist, keine neue Hauptversion, sondern nur Patch-Versionen: 0.7.1, 0.7.2, 0.7.3 … (`mod_version` in `gradle.properties`). Erst wenn der Nutzer das Problem als gelöst bestätigt, geht es mit v0.8 (Schild) weiter.
- Nach jeder Version: kurze Testanleitung (was craften, was klicken, was sollte passieren).
- **Diese Datei aktuell halten:** Nach jedem Änderungswunsch, jeder Entscheidung, jedem Testergebnis und jeder fertigen Version die passenden Abschnitte hier aktualisieren (Roadmap-Status, offene Entscheidungen, Code-Struktur, „Letzter Stand“), dann committen und pushen. Kurz halten, nur das Nötige. Der Nutzer startet jeden neuen Chat nur mit: „Lies CONTEXT.md und mach weiter.“

## Letzter Stand

- v0.1 und v0.2 getestet ✔. v0.3–v0.7: KI-Iterationen.
- **Priorität des Nutzers:** Der Kern muss auf **jeder Stufe und in jeder Höhe** zuverlässig zerstört werden können. Erst danach geht es weiter (Schild usw.).
- v0.6 getestet: Kern wird fast immer zerstört, aber v. a. durch Phantome (zu oft, machen Mauern sinnlos). Baumeister bauten Treppen (keine Leitern); Mobs blockierten sich auf der Treppe, Baumeister kam nach Sturz nicht wieder hoch; viele Mobs in Höhlen weit weg, die nie starben (Performance). Wunsch: Mobs als Einheit, mehrere Treppen, Phantome nur vereinzelt / hohe Stufe / ohne Alternative. Höhlenbasen sollen möglich bleiben.
- v0.7 getestet: **schlechter**. Kern (in der Luft, in Obsidian eingebaut, auf Holzstamm) wurde nicht zerstört. Baumeister baute Leitern (hässlich) bis *über* den Kern, oben standen alle nur rum (konnten nicht nach unten graben). Viele Mobs in Höhlen („sammelt sich“, „wartet auf Baumeister“), die blieben. Mehrere Angreifer, aber nur ein Baumeister. Nutzer-Wunsch: Mobs sollen **als Gruppe analysieren und planen** (Kern in der Luft + eingebaut → Treppe auf Kernhöhe + Plattform, frontal freilegen), **effizient und langfristig**, Spawns passend zum Plan.
- **v0.7.1 „Kommandant“** gebaut, **Test ausstehend**: KI grundlegend umgebaut (siehe DESIGN.md): ein zentraler Plan pro Front statt Einzel-Routen pro Mob. Planer sucht rückwärts vom Kern (Angriffsplatz seitlich am Kern, Hülle dort aufbrechen) zum Sammelplatz auf natürlichem Boden (Oberfläche, ≥ Kernzone + 6). Nur Treppen/Brücken/Graben, **keine Leitern mehr**. Plattform (bis 5 Felder) am Angriffsplatz. Spawns nur am Sammelplatz; erst Baumeister/Gräber, max. 2 Begleiter, Angreifer erst wenn der Weg (fast) offen ist; fehlt ein Arbeiter, wird ein Zombie umgeschult. Wer den Sammelplatz 30 s nicht erreicht, verschwindet (auch natürliche Mobs ohne Namen). Natürliche Höhlen-Spawns im Gebiet ganz aus (außer Kern unterirdisch). Spitzhacke gräbt Obsidian ohne Tier-Strafe. Offline-Simulation des Planers (4 Szenarien) ok.
- Ungetestet/riskant: Laufen auf 1 breiter Treppe (Hüpf-Fallback vorhanden), Plattformbau, zweite Front, Rechenzeit großer Pläne (läuft verteilt, 20 000 Knoten/s).
- Neuer Config-Schlüssel: `maxFronts` (2). `laddersEnabled` ungenutzt. KI-Anzeige: Plan steht im Chat.
- KI-Anzeige im Kern-GUI zum Testen: Nutzer schickt Screenshots.

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

## Code-Struktur (Stand v0.7.1)

| Datei | Zweck |
|---|---|
| `Incursion.java` | `@Mod`, Registrierung, Creative Tab, Server-Config, Events, Ticket-Controller |
| `IncursionClient.java` | Config-Bildschirm, Screen- und Renderer-Registrierung |
| `Config.java` | SERVER-Config: `core`, `siege`, `digging`, `zombies`, `skeletons`, `endermen`, `tactics`, `wards`, `explosion`, `defuse` + Formeln |
| `registry/*` | Blocks (`core`, `ward`, `siege_stone`, `siege_ladder`), Items, BlockEntities, Entities (`core_target`), Menus |
| `block/CoreBlock.java`, `CoreActions.java`, `CoreBlockEntity.java` | Kern: Aktivieren/Upgraden, HP, Chunk-Radius, Ziel-Entity, Entschärfen, Schalter Grenzen/KI-Anzeige; Entfernen eines aktiven Kerns = Explosion |
| `block/WardBlock.java`, `WardBlockEntity.java` | Schutztotem: kein Spawn im Radius, statische Liste geladener Totems |
| `entity/CoreTargetEntity.java` | Unsichtbare LivingEntity am Kern, Schaden von Enemy-Mobs → Kern-HP (× Rollen-Multiplikator), meldet Fortschritt |
| `siege/SiegeManager.java` | Jede Sekunde: Chunks, Regeneration, Targeting, Rollen zählen, TNT, Ringe, Spawns (Bedarf, Totems, Patt-Verstärkung) |
| `siege/SiegeEvents.java` | Target-Regeln, Explosionen (Kern/siege_proof geschützt, Belagerungs-Explosionen schonen Belagerungs-Mobs), Rollen-Schaden |
| `siege/Targeting.java` | Wer greift was an: Arbeiter → Kern, Krieger → Spieler (jede Zone), Schützen → Kern (Deckungsfeuer nur ohne Schussposition) |
| `siege/MobRoles.java` | Rollen per Scoreboard-Tag (Zombie: Krieger/Gräber/Brecher/Baumeister; Skelett: Spreng/Feuer/normal; Enderman: Pionier), ausgewogene Vergabe, Ausrüstung |
| `siege/SiegeCommander.java` | Zentraler Plan (1/s): Fronten planen (verteilt), prüfen, neu planen; Arbeit zählen; welche Rolle als Nächstes spawnt; Zombies umschulen; Chat-Meldung bei KI-Anzeige |
| `siege/SiegeFront.java` | Eine Angriffslinie: Route, Seite, Plattform, Arbeits- und Rollenzähler |
| `siege/SiegeAI.java` | Pro Mob alle 5 Ticks: Reiter, direkter Kernangriff, Spinnen, Endermen-Teleport, Front zuordnen → zum Sammelplatz laufen (sonst nach 30 s weg) → Weg abarbeiten (graben, bauen + Anheben, Creeper-Durchbruch), Warten/Sammeln, Plattform, Hüpfen bei Hängern, Pionier-TNT, Selbstzerstörung |
| `siege/SiegeTeam.java` | Scoreboard-Team `incursion_siege` (kein Schieben untereinander), Aufräumen beim Entfernen |
| `siege/SiegeAirDrop.java` | Phantom-Luftlandung: Kernhöhe über Boden, Landeplatz mit offenem Himmel, Flug (Position pro Tick), Absetzen |
| `siege/SiegeArchers.java` | Schützen: von hier schießen, erreichbare Schussposition suchen/hinlaufen, Sprengschützen sprengen Wand am Kern; sonst `false` → Routen |
| `siege/SiegeAim.java` | Sichtprüfung auf den Kern-Block (Seitenflächen), ballistische Pfeil-Berechnung und Flugbahn-Prüfung |
| `siege/SiegeDebug.java` | KI-Anzeige: Namensschilder mit Rolle/Tätigkeit, Routen-Partikel |
| `siege/SiegeDriveGoal.java` | Goal (Prio 1, MOVE+LOOK) an jedem feindlichen Bodenmob; steuert Bewegung, wenn SiegeAI einen Auftrag hat |
| `siege/RoutePlanner.java`, `Route.java` | A* rückwärts vom Kern zum Sammelplatz (Laufen, Treppe hoch/runter, Fallen 2–3, Graben nach Härte, Bauen), keine Selbstblockade, andere Fronten meiden; Route = Weg mit Mitgliedern, Arbeit aus der Welt gelesen |
| `siege/SiegeProgress.java` | Letzter Fortschritt (Patt), letzte Aktionsorte (Begleiter), Bedarf an Rollen |
| `siege/SiegeExplosives.java` | Feuer-/Explosionspfeile, manuelle Schüsse, Pfeile fliegen durch eigene Mobs, TNT-Verfolgung und Zündung |
| `siege/Digging.java` | Abbau mit gemeinsamem Fortschritt und Rissen, Grabzeit-Formel; Tag `incursion:siege_proof` |
| `siege/SpawnRings.java` | Partikelringe (Totem grün, Kern lila), Partikel mit großer Reichweite senden |
| `siege/ActiveSiege.java`, `SiegeRules.java`, `ChunkLoading.java` | Zonen-Schnappschuss (inkl. Stufe), Mob-Listen, Kern-Chunkloader |
| `fallout/*` | Große Explosion, Krater-Jobs, Verseuchung (SavedData) |
| `menu/CoreMenu.java`, `client/CoreScreen.java` | GUI ohne Slots; Aktivieren/Upgraden/Entschärfen |
| `data/CoreWorldData.java` | SavedData `incursion_core`: der eine aktive Core |

Ressourcen: Blockstates/Modelle (`core`, `core_active`, `siege_stone`, `siege_ladder` mit Vanilla-Leitertextur, Tag `minecraft:climbable`), Platzhalter-Texturen (`core`, `core_active`, `ward`, `siege_stone`), Loot Table, Rezepte (Kern: Weinender Obsidian / Enderaugen / Diamantblock; Schutztotem: Enderperle / Gold / Steinziegel), Tags, Sprachdateien `en_us` und `de_de`.

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
| v0.4 | Belagerungs-KI: Routenplaner, Baumeister, Rollen, Skelett-Klassen, Pioniere, Patt-Regel, Schutztotem | getestet: Mobs erreichen Kern nicht |
| v0.5 | KI-Neubau: direkter Kernangriff, Schusspositionen, Routen-Hüpfen, Belagerungsstein, GUI-Schalter Grenzen/KI-Anzeige | getestet: Skelette gut, Baumeister schwach |
| v0.6 | Erreichbarkeit: Leitern, keine Selbstblockade, alle graben, Luftlandung, Spinnenreiter, Endermen-Teleport, Rückruf | getestet: Kern fällt, aber Phantome zu stark, Gedränge, Höhlen-Mobs |
| v0.7 | Einheit: kein Schieben, Sammeln/Stürmen, mehrere Wege, Leitern, verirrte Mobs, Oberflächen-Spawns, seltene Luftlandung | getestet: schlechter (Kern nicht zerstört) |
| v0.7.1 | Kommandant: zentraler Plan, Treppe + Plattform, Spawns am Sammelplatz nach Bedarf, Umschulen, Höhlen-Mobs weg | gebaut, **Test ausstehend** |
| v0.8 | Schildkuppel, Puls, Reparatur | erst wenn Kern zuverlässig zerstört wird |
| v0.9 | Items, Energie, Buffs (Anzeige in der GUI vorgesehen) | |
| danach | Config-Feinschliff, Balancing, Brennstoff, Create-Integration | |

## Offene Entscheidungen

- Aktivierungs- und Upgrade-Kosten (vorerst Lohenrute bzw. Diamanten)
- Entschärf-Mechanik (v0.3-Platzhalter: GUI-Button, Countdown 10 s + 2 s/Stufe, Spieler muss ≤ 8 Blöcke bleiben)
- Brennstoff ja/nein
- Schutztotem-Rezept und Preis (vorerst günstig, +2 Mobs pro Totem)
- Lizenz
