# Incursion – Kontext für Claude

Diese Datei ist der Einstieg für jeden neuen Chat zu diesem Projekt. Lies sie zuerst, dann `DESIGN.md` für Details.

## Zusammenarbeit

- Der Nutzer (MarleyBOSS) ist **Programmier-Anfänger** und spricht **Deutsch**. Antworten auf Deutsch, Code und Commits auf Englisch.
- **Claude schreibt den gesamten Code.** Der Nutzer baut nichts lokal und hat keine IDE. Er lädt die fertige `.jar` herunter und testet im Spiel.
- **Ehrlichkeit vor allem:** Der Nutzer hatte mit einer anderen KI schlechte Erfahrungen mit nicht funktionierendem Code. Unsicherheiten offen sagen, nichts als fertig melden, was nicht gebaut und geprüft ist.
- **Nutzungslimit schonen:** knapp antworten, keine langen Zusammenfassungen, nur relevante Dateien lesen. Pro Version am besten ein neuer Chat.
- **Versionsnummern:** Solange das Mob-/KI-Problem (Kern zuverlässig erreichen und zerstören) nicht gelöst ist, keine neue Hauptversion, sondern nur Patch-Versionen: 0.7.1, 0.7.2, 0.7.3, 0.7.4 … (`mod_version` in `gradle.properties`). Erst wenn der Nutzer das Problem als gelöst bestätigt, geht es mit v0.8 (Schild) weiter.
- **Ideen-Dokument:** Feature-Ideen und Pläne stehen im Doc „Incursion – Ideen & Features“ (https://claude.ai/code/artifact/edbf3231-706d-41fa-a66f-2c515b86609c). **Bei jeder Anfrage mit den Docs-Tools lesen**, v. a. den Abschnitt „Neue Ideen“ und Kommentare. Besprochene Ideen dort in den passenden Versions-Abschnitt verschieben. CONTEXT.md = Technik und Stand, das Doc = Ideen.
- Nach jeder Version: kurze Testanleitung (was craften, was klicken, was sollte passieren).
- **Diese Datei aktuell halten:** Nach jedem Änderungswunsch, jeder Entscheidung, jedem Testergebnis und jeder fertigen Version die passenden Abschnitte hier aktualisieren (Roadmap-Status, offene Entscheidungen, Code-Struktur, „Letzter Stand“), dann committen und pushen. Kurz halten, nur das Nötige. Der Nutzer startet jeden neuen Chat nur mit: „Lies CONTEXT.md und mach weiter.“

## Letzter Stand

- v0.1 und v0.2 getestet ✔. v0.3–v0.7: KI-Iterationen.
- **Priorität des Nutzers:** Der Kern muss auf **jeder Stufe und in jeder Höhe** zuverlässig zerstört werden können. Erst danach geht es weiter (Schild usw.).
- v0.6 getestet: Kern wird fast immer zerstört, aber v. a. durch Phantome (zu oft, machen Mauern sinnlos). Baumeister bauten Treppen (keine Leitern); Mobs blockierten sich auf der Treppe, Baumeister kam nach Sturz nicht wieder hoch; viele Mobs in Höhlen weit weg, die nie starben (Performance). Wunsch: Mobs als Einheit, mehrere Treppen, Phantome nur vereinzelt / hohe Stufe / ohne Alternative. Höhlenbasen sollen möglich bleiben.
- v0.7 getestet: **schlechter**. Kern (in der Luft, in Obsidian eingebaut, auf Holzstamm) wurde nicht zerstört. Baumeister baute Leitern (hässlich) bis *über* den Kern, oben standen alle nur rum (konnten nicht nach unten graben). Viele Mobs in Höhlen („sammelt sich“, „wartet auf Baumeister“), die blieben. Mehrere Angreifer, aber nur ein Baumeister. Nutzer-Wunsch: Mobs sollen **als Gruppe analysieren und planen** (Kern in der Luft + eingebaut → Treppe auf Kernhöhe + Plattform, frontal freilegen), **effizient und langfristig**, Spawns passend zum Plan.
- **v0.7.1 „Kommandant“** getestet: Baumeister baut jetzt **sicher** zum Kern (3 von 3), aber nur **eine schmale Treppe** (nur 1 Baumeister/Front), nur 4 von 42 Angreifer (Spawns wurden zurückgehalten), viele Mobs in Höhlen (Sammelplatz in Höhle, weil Kern als „unterirdisch“ galt), Zombies gingen auf Dorfbewohner, kaum Skelette/Creeper. Wunsch: mehrere Treppen/Türme (Spinnen klettern, Zombies/Skelette stehen), Plattform, Spawnpunkt höher wenn der Kern nicht tief liegt. Aufbau von v0.7.1: KI grundlegend umgebaut (siehe DESIGN.md): ein zentraler Plan pro Front statt Einzel-Routen pro Mob. Planer sucht rückwärts vom Kern (Angriffsplatz seitlich am Kern, Hülle dort aufbrechen) zum Sammelplatz auf natürlichem Boden (Oberfläche, ≥ Kernzone + 6). Nur Treppen/Brücken/Graben, **keine Leitern mehr**. Plattform (bis 5 Felder) am Angriffsplatz. Spawns nur am Sammelplatz; erst Baumeister/Gräber, max. 2 Begleiter, Angreifer erst wenn der Weg (fast) offen ist; fehlt ein Arbeiter, wird ein Zombie umgeschult. Wer den Sammelplatz 30 s nicht erreicht, verschwindet (auch natürliche Mobs ohne Namen). Natürliche Höhlen-Spawns im Gebiet ganz aus (außer Kern unterirdisch). Spitzhacke gräbt Obsidian ohne Tier-Strafe. Offline-Simulation des Planers (4 Szenarien) ok.
- Ungetestet/riskant: Laufen auf 1 breiter Treppe (Hüpf-Fallback vorhanden), Plattformbau, zweite Front, Rechenzeit großer Pläne (läuft verteilt, 20 000 Knoten/s).
- **v0.7.2** getestet: **viel besser.** Keine Mobs mehr unter der Erde, viele Mobs, gut gemischt, 3 Treppen gebaut und benutzt, Baumeister zuverlässig. Mängel: keine Plattform gebaut (nur Treppen); Skelette positionieren sich unzuverlässig; Baumeister bauen scheinbar nur diagonal (Treppen), nicht horizontal; Sprengschützen (Explosivpfeile) tun nichts (weder Spieler noch Kern). Wünsche: Zwischenplattformen entlang der Treppe (Schussposition für Skelette, Spieler abhalten) und oben am Kern (nicht einfach runterschießbar); Baumeister sollen abwägen (4. Treppe oder Plattformen zum Ausbau einer vorhandenen Treppe); Teams: eines jagt/verteidigt gegen den Spieler, das andere geht auf den Kern, mit Leibwächtern, die nahe Spieler angreifen.
- **v0.7.3** gebaut (**lokal nicht kompilierbar, nur Syntax-Check mit javac; CI prüfen**), **Test ausstehend**: Zwischenplattformen („Landings“, alle 6 Stufen, `landingsEnabled`/`landingSpacing`); Plattform am Kern 5×3 nur auf freien Feldern (kein Obsidian-Abbau mehr); zusätzlicher Baumeister baut die Landings, ab der 3. Front erst Ausbau, dann weitere Treppe; Teams Jäger (`hunterPercent` 40 %, `hunterRange` 32) / Leibwächter (`bodyguardRange` 10), Arbeiter immer Kern-Team; Jäger-Schützen halten Posten auf Landings; Schützen bevorzugen Landings; Sprengschützen geben jetzt Deckungsfeuer (Ursache: bei Obsidian-Kern keine Aufgabe). Details in DESIGN.md.
- **v0.7.3** getestet: Kern wird zuverlässig zerstört, keine Mobs in Höhlen; **aber immer noch keine Plattformen** (weder am Kern noch Zwischenplattformen). Wünsche: Angriff und Plattformbau **gleichzeitig** (kein Warten, Baumeister haben Vorrang beim Bauen, nicht beim Kern-Angriff); Spieler-Team mit **eigenem Baumeister** (Spieler im Turm/Bunker erreichen), Spieler egal wo **in Kernnähe** jagen, nicht auf Tour; Teams **dynamisch**: Ohne Spieler in Kernnähe gehen alle ins Kern-Team, kommt ein Spieler, splitten sie sich wieder auf; niemand soll sinnlos herumstehen.
- **v0.7.4** gebaut (**lokal nicht kompilierbar, CI prüfen**), **Test ausstehend**: Ursache der fehlenden Plattformen nicht sicher gefunden (Vermutung: Kern stirbt, bevor der Baumeister fertig ist; Baumeister überspringen Landings hinter sich). Änderungen: Baumeister bauen Landings zum nächstgelegenen Landing auch **treppab** (Laufen Stufe für Stufe in `SiegeAI.drive`), greifen den Kern nie an, solange Plattform-/Landing-Arbeit offen ist, zweiter Baumeister früher (ab Limit 6, offene Front mit ≥ 8 Feldern Arbeit). Bei eingeschalteter KI-Anzeige meldet der Plan jetzt Plattformfelder/Landings/Felder (`message.incursion.plan.extras`): **Wenn dort 0 steht, liegt der Fehler im Planer (`RoutePlanner.platform/landings/usableSpot`)**. Neu: `PlayerHunt` (Jagd): in Kernnähe (`huntRadius` 48, max `huntMaxPlayers` 2) plant ein Planer-Lauf einen Weg zum Spieler (Ziel = Spielerposition), Jäger-Baumeister/-Gräber (Tag `incursion_hunt_worker`, max `huntWorkers` 3 pro Spieler, zusätzlich zum Mob-Limit) bauen/graben ihn, die Jäger laufen normal (Vanilla-Pfad findet die fertige Treppe). Teams dynamisch (`MobRoles.rebalanceTeams`, 1×/s): Spieler in Kernnähe → `hunterPercent` der Kämpfer sind Jäger, sonst alle Kern-Team. Jäger-Arbeiter ohne Aufgabe gehen ins Kern-Team über (Tag weg, Kommandant gibt ihnen eine Kern-Front). Jäger jagen auch ohne Sichtkontakt, Reichweite 96.
- **v0.7.4** getestet: **Kern wird immer zerstört → KI-Problem vom Nutzer als gelöst bestätigt.** Plattformen werden weiterhin nicht gebaut; Nutzer will das **nicht weiter verfolgen** (zu viel Zeit). Spielerjagd/dynamische Teams: kein gesondertes Feedback.
- **Nächster Schritt: v0.8 (Schild).** Balancing erst nach v0.9 (siehe Roadmap „Belagerungs-Ausbau“).
- **Idee v0.10 „Tag/Nacht und Pausen“** (Grund: auf hoher Stufe kann man sonst nicht mehr bauen/normal spielen; bewusst erst nach v0.9, weil zeitunabhängige Angriffe leichter zu testen sind). Kein Wellen-System mit steigender Schwierigkeit.
  - Angriffe nur nachts, tagsüber Zeit für Reparatur/Umbau.
  - **Nacht pausieren** (Button im Kern-GUI): diese Nacht keine Belagerung, Schlafen möglich. Gilt nur für eine Nacht, danach automatisch wieder aktiv. Strafe stapelt sich: nächste Nacht ×2, erneut pausiert ×4, dann ×8 … Eine verteidigte Nacht setzt den Faktor auf ×1 zurück.
  - **Schlafen** nur, wenn die Nacht pausiert ist oder Waffenruhe läuft (Annahme: sonst in der ganzen Welt blockiert, damit man nicht woanders schläft).
  - **Waffenruhe** (Button im Kern-GUI, gratis): 3 Tage + Nächte Ruhe. Danach müssen **7 Nächte verteidigt** werden, bis sie wieder aufgeladen ist. Pausierte Nächte, Waffenruhe-Nächte und Zeit im entschärften Zustand zählen nicht.
  - **Entschärfen** = unbegrenzte Pause: kostet **80 % der Stufe** (Stufe 20 → 4; Annahme: abrunden). Ersetzt die alte Idee „Kern pausieren kostet 2 Stufen“ und den bisherigen Reset auf Stufe 0.
  - Offen: Was machen Mobs tagsüber (verschwinden / Belagerungslager / stehen)? Brennen in der Sonne? Dimensionen ohne Nacht (Nether/End)? Was heißt „×2“ (Mobanzahl, Limit, Stärke)?
  - **Belohnungen:** entschärft keine Items/Energie/Buffs. Während pausierter Nacht und Waffenruhe Ausgabe **−20 %**.
- Neue Nutzer-Ideen (nach v0.10): **Wächter** ab bestimmter Kernstufe; **stufenabhängige Mob-Auswahl** statt „alle Mobs auf jeder Stufe, nur mehr“ (einzelne Mobs evtl. ganz raus).
- `laddersEnabled` ungenutzt. KI-Anzeige: Plan steht im Chat.
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

## Code-Struktur (Stand v0.7.4)

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
| `siege/Targeting.java` | Wer greift was an: Arbeiter → Kern; Jäger → Spieler (32 Blöcke, nahe am Kern bis 64); Leibwächter → nur Spieler ≤ 10; Schützen → Kern (Deckungsfeuer nur ohne Schussposition) |
| `siege/MobRoles.java` | Rollen und Teams (Tag `incursion_hunter` = Jäger, sonst Leibwächter/Kern-Team) per Scoreboard-Tag (Zombie: Krieger/Gräber/Brecher/Baumeister; Skelett: Spreng/Feuer/normal; Enderman: Pionier), ausgewogene Vergabe, Ausrüstung |
| `siege/SiegeCommander.java` | Zentraler Plan (1/s): Fronten planen (verteilt), prüfen, neu planen; Arbeit zählen; welche Rolle als Nächstes spawnt; Zombies umschulen; Chat-Meldung bei KI-Anzeige |
| `siege/PlayerHunt.java` | Spielerjagd: pro Spieler in Kernnähe ein Weg (Planer mit Spielerposition als Ziel), Jäger-Baumeister/-Gräber spawnen/zählen/ins Kern-Team übergeben, Teams per `MobRoles.rebalanceTeams` |
| `siege/SiegeFront.java` | Eine Angriffslinie: Route, Seite, Plattform, Landings (Zwischenplattformen), Arbeits- und Rollenzähler |
| `siege/SiegeAI.java` | Pro Mob alle 5 Ticks: Reiter, direkter Kernangriff, Spinnen, Endermen-Teleport, Front zuordnen → zum Sammelplatz laufen (sonst nach 30 s weg) → Weg abarbeiten (graben, bauen + Anheben, Creeper-Durchbruch), Warten/Sammeln, Plattform, Hüpfen bei Hängern, Pionier-TNT, Selbstzerstörung |
| `siege/SiegeTeam.java` | Scoreboard-Team `incursion_siege` (kein Schieben untereinander), Aufräumen beim Entfernen |
| `siege/SiegeAirDrop.java` | Phantom-Luftlandung: Kernhöhe über Boden, Landeplatz mit offenem Himmel, Flug (Position pro Tick), Absetzen |
| `siege/SiegeArchers.java` | Schützen: von hier schießen, erreichbare Schussposition suchen/hinlaufen, Landings bevorzugt, Jäger halten Posten, Sprengschützen sprengen Wand am Kern; sonst `false` → Routen |
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
| v0.7.1 | Kommandant: zentraler Plan, Treppe + Plattform, Spawns am Sammelplatz nach Bedarf, Umschulen, Höhlen-Mobs weg | getestet: baut sicher, aber 1 Treppe, zu wenige Mobs, Höhlen |
| v0.7.2 | Mehrere Fronten gleichzeitig, alle Angreifer sofort, breite Treppe, 3×3-Plattform, keine Höhlen-Sammelplätze, Dorfbewohner ignoriert | getestet: viel besser; Plattformen fehlen, Skelette unzuverlässig |
| v0.7.3 | Zwischenplattformen, Ausbau durch Baumeister, Teams Jäger/Leibwächter, Posten für Schützen, Sprengschützen repariert | getestet: Kern fällt zuverlässig, aber keine Plattformen |
| v0.7.4 | Plattformbau parallel zum Angriff (Vorrang für Baumeister), Spielerjagd mit eigenen Baumeistern/Gräbern, dynamische Teams | getestet ✔ Kern fällt immer (Plattformen fehlen, akzeptiert) |
| v0.8 | Schildkuppel, Puls, Reparatur | **als Nächstes** |
| v0.9 | Items, Energie, Buffs (Anzeige in der GUI vorgesehen) | |
| v0.10 | Tag/Nacht und Pausen (Details unter „Letzter Stand“) | Idee |
| v0.11 | Belagerungs-Ausbau: Wächter ab Stufe X, Mob-Pool pro Stufe (welche Mobs ab welcher Stufe, per Config) | Idee |
| danach | Balancing (Zahlen), Config-Feinschliff, Brennstoff, Create-Integration | |

## Offene Entscheidungen

- Aktivierungs- und Upgrade-Kosten (vorerst Lohenrute bzw. Diamanten)
- Entschärf-Mechanik (v0.3-Platzhalter: GUI-Button, Countdown 10 s + 2 s/Stufe, Spieler muss ≤ 8 Blöcke bleiben). Entschieden: Kosten künftig 80 % der Stufe statt Reset auf 0 (v0.10)
- Brennstoff ja/nein
- Schutztotem-Rezept und Preis (vorerst günstig, +2 Mobs pro Totem)
- Lizenz
- Wächter: was genau (Boss-Mob? Anführer der Belagerung?), ab welcher Stufe
- Welche Mobs ab welcher Stufe bzw. ganz raus
