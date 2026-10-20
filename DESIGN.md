# Incursion – Design-Dokument

Stand: 11.10.2026 · Minecraft 1.21.1 · NeoForge

Dieses Dokument hält das Konzept und alle getroffenen Entscheidungen fest. Es wird mit jeder Entscheidung aktualisiert.

## Grundidee

Der Spieler baut den **Incursion Core**, einen Block, der von Stufe 0 bis Stufe 20 aufgewertet werden kann. Jede Stufe bringt stärkere Vorteile, löst aber auch eine härtere, **dauerhafte Belagerung** aus. Der Spieler entscheidet selbst, ob sich das nächste Upgrade lohnt.

## Kern (Core)

- **Stufe 0 ist friedlich.** Der Kern wird erst aktiv, wenn ein bestimmter Rohstoff eingeworfen wird. Ab dann läuft die Belagerung.
- **Stufen 0 bis 20** (Maximum in der Config einstellbar).
- **Jedes Upgrade kostet Ressourcen.** Welche genau, ist noch offen (Vanilla-Items oder ein eigenes Item der Mod). Vorerst Platzhalter.
- **Ein aktiver Kern pro Welt.**
- **Ab der Aktivierung ist der Kern eine scharfe Bombe:**
  - Er lässt sich nicht umziehen.
  - Wird er abgebaut – egal ob von Spieler oder Mob – folgt die große Explosion.
  - Wird er von den Angreifern zerstört (Lebenspunkte auf 0), folgt die große Explosion.
- **Entschärfen** (Platzhalter v0.3): Button in der GUI startet einen Countdown (10 s + 2 s pro Stufe). Der Spieler muss in der Nähe bleiben (8 Blöcke), sonst bricht es ab. Danach Stufe 0, Belagerungs-Mobs verschwinden.
- **Später möglich:** Der Kern verbraucht wie ein Ofen laufend einen Brennstoff.

### Die große Explosion

- Großer Krater, das Gebiet wird danach **unbewohnbar** (verseuchte Zone: Ödland-Blöcke, Schaden für alles darin).
- Die Verseuchung bleibt standardmäßig **für immer**, in der Config einstellbar (Dauer, Radius, abschaltbar).
- Technisch: Der Krater wird schrittweise über mehrere Ticks erzeugt, damit das Spiel nicht einfriert.
- **Upgrade (v0.17):** Auch jedes Upgrade hat eine kleine Version dieser Animation (Aufladen passend zur Musik, danach eine Welle aus Licht), ohne Feuer und Zerstörung.
- **Meltdown (v0.16):** Der Kern explodiert nicht auf einen Schlag. Er überlädt 5,5 Sekunden lang (Kern wird größer, heller, weiß; Ringe, Blitze, Funken, Kamerawackeln, Warnung in der Action-Bar), dann folgt die animierte Explosion (Flash, Feuerball, Schockwellen, Lichtsäule, Funkenregen). Der Krater entsteht erst mit der Explosion.
- **Ewiges Feuer (v0.16/0.17):** Nach der Explosion brennt das Land mit normalem Vanilla-Feuer auf Netherrack (brennt ewig, Regen löscht es nicht). Wird es gelöscht, zündet es kurz darauf an anderer Stelle in der Nähe neu, damit das Gebiet wirklich unbelebar bleibt. Dichte und Abschaltbarkeit in der Config (`burnEnabled`, `fireDensity`).
- Umgesetzt (v0.3): Kraterradius 10 + 1,5 pro Stufe (max. 48), Verseuchung bis 1,5 × Kraterradius: Boden wird Grobe Erde/Kies, Blätter verschwinden, Kraterboden aus Schwarzstein/Basalt/Magma. In der Zone gibt es Wither (Untote immun) und Ascheregen.

## Vorteile pro Stufe

Mit jeder Stufe stärker:

- Item-Erzeugung
- Energie-Erzeugung (FE) – nutzbar mit Create über den Elektromotor aus Create Crafts & Additions und mit allen anderen FE-Mods
- Buffs im Umkreis wie beim Beacon

## Belagerung

- **Keine Wellen, keine Zeitskalierung.** Anzahl und Stärke der Angreifer hängen **ausschließlich von der Kernstufe** ab.
- Die Belagerung läuft **dauerhaft**, auch wenn der Spieler unterwegs ist. Der aktive Kern hält sein Gebiet selbst geladen (eigener Chunkloader).
- Belagerungs-Mobs verschwinden nicht (Despawn deaktiviert).
- Warnung per Chatnachricht, wenn der Kern Schaden nimmt oder der Schild bricht.

### Zielverhalten – drei Zonen

1. **Kernzone** (nah am Kern): Arbeiter greifen nur den Kern an. Krieger und Schützen dürfen Spieler angreifen (siehe Rollen).
2. **Belagerungsgebiet**: Ist ein Spieler in normaler Wahrnehmungsreichweite eines Mobs, greift der Mob den Spieler an. Sonst läuft er zum Kern.
3. **Außerhalb**: normales Verhalten.

- Das Belagerungsgebiet entspricht dem Bereich, den der Kern geladen hält, und **wächst mit der Stufe**.
- **Alle feindlichen Mobs** sind betroffen, nicht nur die von der Belagerung gespawnten – auch natürliche Spawns und Mobs anderer Mods.
- Der Kern hat **Lebenspunkte**, die mit der Stufe steigen.
- **Ausnahmeliste** in der Config: Bosse sind standardmäßig ausgenommen.
- Creeper-Explosionen in der Kernzone verursachen **keinen Blockschaden** (Config umstellbar). Ausnahme: Durchbruch-Creeper an einer Wand.

### Belagerungs-KI (v0.4, überarbeitet in v0.5 und v0.6)

- **Routenplaner (A*):** Feststeckende Mobs (3 s kein Fortschritt) bekommen den *billigsten* Weg zum Kern. Kosten: Laufen, Abbauen nach Härte (Erde billig, Obsidian teuer, Grundgestein/`siege_proof` unmöglich), Bauen (fester Preis). Erlaubte Schritte: gehen, 1 hoch (Treppenstufe), 1 runter, 2–4 fallen, ab v0.6 senkrecht klettern (Belagerungsleiter). Ist der Kern nicht im Suchbudget erreichbar, führt die Route zum nächstbesten Punkt und wird dort neu geplant. Ziel ist jeder Platz, von dem aus eine freie Kernseite erreichbar ist (daneben, darauf, darunter).
- **Kern treffen (v0.5):** Nahkämpfer, die eine freie Seite des Kern-*Blocks* in Reichweite haben, schlagen direkt zu (eigene Sichtprüfung auf den Block, nicht Vanilla-Sichtlinie auf die Ziel-Entity). Creeper zünden direkt am Kern und machen vollen Schaden.
- **Bewegung (v0.5):** Auf der Route wird bei Stufen aktiv gesprungen; wer 2 s nicht zum nächsten Schritt kommt, hüpft dorthin. Steht beim Bauen jemand im Weg, wird er auf den neuen Block gehoben.
- **Zusammenarbeit:** Routen werden geteilt (Mob in ≤ 2 Blöcken Abstand steigt ein). Wer den nächsten Arbeitsschritt nicht kann, wartet 3 Schritte dahinter und meldet den Bedarf. Gräber/Baumeister laufen zu Routen, auf denen jemand auf sie wartet; Begleiter laufen zu Routen, an denen gearbeitet wird.
- **Rollen (Zombies):** Krieger (Schwert; jagt Spieler im Umkreis von 24 Blöcken in jeder Zone; gräbt ab v0.6 mit, wenn kein Spieler da ist), Gräber (Spitzhacke), Brecher (Axt, 2,5× Kernschaden), **Baumeister** (Belagerungsstein in der Hand, setzt Belagerungsstein für Treppen und Brücken und Belagerungsleitern; explosionsfest, droppt nichts; gräbt ab v0.6 auch). Werkzeug-Material steigt mit der Stufe.
- **Rollen (Skelette):** Sprengschützen (rote Lederrüstung komplett, Explosionspfeile, schießen sich durch Wände zum Kern), Feuerschützen (nur roter Lederhelm, brennende Pfeile, zünden TNT), Schützen (Kettenhemd). **v0.5:** Schützen suchen sich eine Schussposition mit freier Bahn auf den Kern (ballistisch gezielt) und schießen von dort. Ist der Kern rundum eingemauert, suchen Sprengschützen eine Position, von der sie die Wand direkt am Kern treffen, und sprengen sie auf. Nur wenn keine Position existiert, geben sie Deckungsfeuer auf Spieler; sehr nahe Spieler werden immer beschossen. Belagerungs-Pfeile fliegen durch eigene Mobs.
- **Pioniere (Endermen, 60 %):** tragen TNT, setzen es neben den Kern (zur Not anstelle eines Wandblocks, teleportieren sich heran). Feuerschützen zünden es; nach 90 s zündet es von selbst.
- **Ausgewogenes Spawnen:** Neue Mobs bekommen die Rolle, die im Vergleich zur Gewichtung am meisten fehlt. Dringender Bedarf (kein Gräber/Baumeister/Feuerschütze da) kommt zuerst, auch beim Mob-Typ.
- **Patt-Regel:** 120 s kein Fortschritt (Kernschaden, Block abgebaut/gebaut, Spieler getroffen) → Chat-Meldung, +50 % Mob-Limit, Gräber und Baumeister zuerst. Ein Mob, der 300 s nichts erreicht (auch nicht als Begleiter in der Nähe von Arbeit), sprengt sich selbst (Stärke 2,5, zerstört Blöcke, nie den Kern).
- **Explosionen der Belagerung** (Creeper, Explosionspfeile, ihr TNT, Selbstzerstörung) schaden Belagerungs-Mobs nicht.
- **Schutztotem:** Block, in dessen Radius (16) keine Belagerungs-Mobs spawnen. Jedes Totem im Gebiet erhöht das Mob-Limit um 2.
- **Nacht-Spawns (v0.9.1):** Neue Angreifer (Sammelplatz-Spawns, Notfall-Spawns, Jagd-Arbeiter, Luftlandung) erscheinen nur im Spawn-Fenster der Tageszeit (Config `nightOnlySpawns` an, `nightSpawnStart` 13000, `nightSpawnEnd` 23000; Start > Ende = Fenster über Mitternacht der Uhr, gleich = immer). Außerhalb wartet der Kern auf die Dämmerung; vorhandene Mobs bleiben und arbeiten weiter. Warten zählt nicht als Patt (`stalled` nur im Fenster), der Patt-Zeitgeber startet beim Öffnen des Fensters neu. Nether/End nutzen die Overworld-Uhr (`Config.isSpawnTime`, geprüft in `SiegeManager.tickCore`). Wellen, Pause, Waffenruhe, Schlafsperre bleiben v0.15.
- **v0.6 „Erreichbarkeit“** (Ziel: Kern auf jeder Stufe und in jeder Höhe zuverlässig erreichbar):
  - **Belagerungsleiter:** Baumeister bauen auch senkrecht (Leiter in der Zelle, in der sie stehen; keine Kollision, explosionsfest, droppt nichts). Alle Bodenmobs klettern auf der Route mit.
  - **Keine Selbstblockade:** Eine Route setzt nie einen Block dorthin, wo ein früherer Schritt (oder eine andere aktive Route) Platz braucht, und gräbt keinen Boden weg, auf dem ein früherer Schritt steht.
  - **Alle Zombies graben** (Krieger, wenn kein Spieler da ist, und Baumeister auch). Bedarf an Rollen wird nur für Arbeit ≤ 40 Blöcke vom Kern gemeldet. Helfer laufen nur „vorwärts“ (Arbeit näher am Kern als sie selbst).
  - **Rückruf:** (v0.7 ersetzt durch „Verirrte Mobs“)
  - **Luftlandung:** Phantome tragen Mobs (≤ 1 breit, ≤ 2,2 hoch) zu einem freien Platz mit offenem Himmel direkt am Kern. (Häufigkeit ab v0.7 siehe unten.)
  - **Spinnen** laufen direkt auf den Kern zu und klettern Wände hoch (Umweg bei Überhang). 50 % tragen einen Zombie oder ein Skelett, das nahe am Kern absteigt.
  - **Endermen** teleportieren sich nicht mehr vom Kern weg und springen, wenn sie feststecken, direkt neben den Kern.
  - **Schützen** nehmen nur Schusspositionen, zu denen ein Weg existiert; finden sie keine, folgen sie den Routen (auch Leitern) und prüfen unterwegs weiter.
  - **Hoher Kern:** Belagerungsgebiet reicht nach unten um die Kernhöhe über dem Boden weiter; gespawnt wird notfalls auf dem Boden darunter. Der Planer kann bis an die Weltobergrenze planen.
- **v0.7 „Einheit statt Gedränge“:**
  - **Schieben (ab v0.9.0.3):** Normale Angreifer schieben sich wie Vanilla. Nur **Baumeister** stehen im Scoreboard-Team `incursion_builders` (Kollisionsregel NEVER): sie werden nicht geschoben und schieben niemanden, damit sie sich durch Menschenmengen arbeiten können. Alte Welten: Team `incursion_siege` wird geleert. (Versuche „keiner schiebt“ und „Radius um Spieler“ in v0.9.0.4/.5 vom Nutzer verworfen: Mobs standen ineinander.)
  - **Treppenform (ab v0.9.0.6–.9):** Treppen 3 Blöcke breit (Config `stairWidth`, Zusatzspalten links/rechts); Zusatzspalten nur, wo darüber 2 Felder frei bleiben. Der Planer bevorzugt gerade Läufe von mindestens `stairFlight` (4) Stufen; Kurven nur auf ebener Fläche (Zug in die Ecke und aus der Ecke eben), dort entsteht mit den Zusatzspalten und `cornerPads` ein 3×3-Podest wie in einem Treppenhaus. Der Planer zahlt Extrakosten für jede Seite, auf der die Breite verloren geht (`lostWings`), und Baumeister bessern ausgelassene Zusatzspalten der letzten 10 Stufen nach.
  - **Sammeln und Stürmen:** Solange eine Route gebaut wird, arbeiten vorne höchstens 2 Arbeiter, dahinter warten 2 Mobs; alle anderen sammeln sich am Fuß der Route (letzter Schritt auf natürlichem Boden) und laufen erst los, wenn der Weg offen ist.
  - **Mehrere Wege:** Max. 8 Mobs pro Route. Ist sie voll, plant der nächste Mob eine zweite Route daneben (Schritte auf anderen Routen kosten extra). Überzählige Baumeister wechseln zu Routen ohne Baumeister.
  - **Leitern bevorzugt:** Leiter setzen kostet im Planer 40 % eines Treppenblocks → zum Hochkommen wird senkrecht mit Leitern gebaut statt Wendeltreppen.
  - **Gruppen-Spawn:** Die Mobs einer Spawn-Runde erscheinen nah beieinander auf einer Seite (nächste Runde woanders).
  - **Spawnplätze:** Nur an der Oberfläche (höchstens 16 über bis Kernhöhe über Boden + 12 unter dem Kern), nie in Höhlen. Liegt der Kern unter der Erde (> 12 Blöcke Fels darüber), auch in Höhlen/Gängen ±5 Blöcke um die Kernhöhe.
  - **Keine natürlichen Höhlen-Spawns** im Belagerungsgebiet, außer ±10 Blöcke um die Kernhöhe.
  - **Verirrte Mobs:** > 24 Blöcke vom Kern und 60 s weder näher gekommen noch getroffen/geschossen/gearbeitet, und die eigene Route kommt nicht voran → Belagerungs-Mobs verschwinden (Ersatz spawnt), natürliche Mobs verlassen die Belagerung (normales Verhalten, können despawnen). Belagerungs-Mobs außerhalb des Gebiets verschwinden sofort.
  - **Luftlandung seltener:** 3 % + 1 % pro Stufe (Stufe 20: 23 %), im Patt 50 %, immer wenn kein Bodenplatz gefunden wird.
- **v0.7.1 „Kommandant“** (ersetzt Einzel-Routen pro Mob, Leitern, Routen-Teilen/Helfen, Gruppen-Spawn an zufälliger Seite):
  - **Analyse und Plan:** Ein zentraler Kommandant plant eine **Front**: A*-Suche rückwärts vom Kern. Start = Angriffsplätze direkt seitlich am Kern (Füße oder Kopf auf Kernhöhe; die Hülle dort wird aufgebrochen). Ziel = **Sammelplatz** auf natürlichem, festem Boden an der Oberfläche (bei unterirdischem Kern auch Höhle ±6 um die Kernhöhe), mindestens Kernzone + 6 Blöcke vom Kern, nicht im Totem-Radius. Dazwischen der billigste Weg: laufen, Treppe hoch/runter (1 Block pro Schritt), 2–3 fallen, graben (nach Härte), Boden setzen (Belagerungsstein). Keine Leitern, keine Säulen. Gerade Treppen werden leicht bevorzugt. Die Suche läuft verteilt (20 000 Positionen pro Sekunde, max. 60 000).
  - **Plattform:** Bis zu 5 Felder neben und hinter dem Angriffsplatz, damit mehrere Mobs gleichzeitig zuschlagen. Baumeister bauen sie, sobald der Weg offen ist.
  - **Spawns nach Plan:** Alle Mobs erscheinen am Sammelplatz. Reihenfolge: zuerst fehlende Baumeister (1, ab 10 Blöcken und Limit ≥ 6: 2), dann Gräber (1–3, 3 bei harten Blöcken wie Obsidian), höchstens 2 Begleiter, solange gebaut wird; Angreifer erst, wenn höchstens 3 Arbeitsschritte übrig sind. Hat eine Front keinen Baumeister/Gräber, wird ein Zombie der Front umgeschult; geht das nicht, darf 1 Arbeiter über dem Limit spawnen (max. +2).
  - **Ablauf am Weg:** wie v0.7 (vorne max. 2 Bauende bzw. 3 Grabende, 2 Wartende, Rest am Fuß; Sturm, wenn offen). Langsame Gräber (z. B. Baumeister an Obsidian) überlassen den Block einem mit passendem Werkzeug, wenn einer da ist.
  - **Neu planen:** Weg unmöglich (z. B. Grundgestein gesetzt) oder 3 min ohne Fortschritt → neuer Plan (vorhandene Treppen werden mitgenutzt). Weitere Fronten: siehe v0.7.2.
  - **Höhlen-Mobs:** Wer seinen Sammelplatz 30 s lang nicht näher kommt, verschwindet (Belagerungs-Mobs und natürliche Mobs ohne Namensschild). Natürliche Spawns im Belagerungsgebiet unter der Oberfläche sind ganz aus (außer bei unterirdischem Kern ±10).
  - **Graben:** Eine Spitzhacke gräbt Obsidian ohne Tier-Strafe (Stein-Spitzhacke ca. 54 s pro Block und Gräber, mehrere Gräber addieren sich).
  - **Luftlandung:** nur noch Grundchance (3 % + 1 %/Stufe); ohne möglichen Plan wie bisher häufig.
  - **KI-Anzeige:** Plan im Chat (Seite, Blöcke bauen/abbauen, Sammelplatz), Partikel: gelb Sammelplatz, weiß Weg, lila Plattform, rot/blau nächster Arbeitsschritt.
- **v0.7.2** (Test v0.7.1: Baumeister baut sicher zum Kern, aber nur eine schmale Treppe, zu wenige Mobs, Höhlen-Mobs, Zombies jagen Dorfbewohner, kaum Skelette/Creeper):
  - **Mehrere Fronten gleichzeitig:** Config `fronts` (Standard 4): Stufe 0–4 → 1, 5–9 → 2, 10–14 → 3, ab 15 → 4 Treppen, jede auf einer anderen Kernseite, höchstens Limit/5 Fronten. Weitere Fronten werden 10 s nach der vorigen geplant (nicht erst wenn die erste offen ist). Jede Front hat ihre eigenen Baumeister/Gräber.
  - **Angreifer ohne Begleiter-Limit:** Alle Mobs bis zum Limit (Stufe 20: 42) werden gespawnt, auch während gebaut wird (v0.7.1 hielt sie zurück → nur 4 Angreifer). Offene Fronten bekommen sie zuerst; wer vor einer unfertigen Treppe wartet, läuft zu einer offenen Front. Pro Spawn-Runde höchstens 1 Luftlandung.
  - **Breite Treppe:** Neben jedem gesetzten Treppenblock setzt der Baumeister einen zweiten (2 Blöcke breit, wenn Platz ist). Plattform am Kern 3×3 statt 5 Felder.
  - **„Unterirdisch“ strenger:** Nur noch Kerne unter Meereshöhe − 8 mit Fels darüber; sonst nie Sammelplatz in Höhlen. Sammelplatz nicht am Grund einer Schlucht/Grube (die meisten Nachbarsäulen ≥ 3 höher).
  - **Höhlen-Mobs weg:** Siege-Mob > 5 Blöcke unter der Oberfläche und tiefer als Kern − 2, nicht auf einer Route → verschwindet sofort (Ersatz spawnt); natürliche Mobs verlassen die Belagerung.
  - **Dorfbewohner ignoriert:** Belagerungs-Mobs bekommen als Ziel nur Spieler, den Kern oder etwas, das sie angreift (Golem, Angreifer). Dorfbewohner, Tiere usw. werden umgeleitet.
  - **Umschulen/Zählen:** Nur eigene Siege-Mobs zählen für den Plan; umgeschult wird nur ein Zombie am Weg oder am Sammelplatz.
- **v0.7.3** (Test v0.7.2: viel besser, 3 Treppen gebaut und benutzt, Baumeister zuverlässig, Mobs nur noch an der Oberfläche; aber keine Plattformen, Skelette positionieren sich unzuverlässig, Sprengschützen tun nichts):
  - **Zwischenplattformen („Landings“):** Der Planer legt entlang jeder gebauten Treppe alle `landingSpacing` (6) Stufen eine Plattform neben die Treppe (bis zu 2 Felder links und rechts der Stufe, Boden wird gebaut), die letzte kurz unter dem Kern. Nur auf gebauten Stufen, nur auf freien Feldern (es wird nichts dafür gegraben), nie in den Weg einer Front. Config `landingsEnabled`, `landingSpacing`.
  - **Plattform am Kern:** jetzt bis 5 breit × 3 tief vor dem Angriffsplatz, aber nur auf freien Feldern. Ein in Obsidian eingebauter Kern bekommt seine Plattform deshalb vor dem Loch (letzte Zwischenplattform außerhalb der Hülle), nicht mehr „Obsidian abbauen“ (das hatte vermutlich die Plattform in v0.7.2 blockiert).
  - **Baumeister wägen ab:** Der erste/zweite Baumeister baut die Treppe. Ein zusätzlicher Baumeister pro Front (ab 8 Mobs Limit, solange Landings fehlen) und jeder, der an der Treppe nicht gebraucht wird, baut die Zwischenplattformen, die schon erreichbar sind (niedrigste zuerst). Ab der 3. Front wird eine weitere Treppe erst geplant, wenn die Landings/Plattformen der vorhandenen Fronten fertig sind (höchstens 2 Minuten Wartezeit). Ist nichts mehr zu bauen, greift der Baumeister am Kern an.
  - **Teams:** Kämpfer (Krieger und Skelette) sind zu `hunterPercent` (40 %) **Jäger**, der Rest **Leibwächter** (Namensschild zeigt es in der KI-Anzeige). Arbeiter (Baumeister, Gräber, Brecher, Sprenger) gehören immer zum Kern-Team und kämpfen nie gegen Spieler. Jäger greifen Spieler bis `hunterRange` (32) an und jeden Spieler nahe am Kern (bis 64 Blöcke Entfernung). Leibwächter greifen nur Spieler innerhalb `bodyguardRange` (10) an; Leibwächter-Schützen geben außerdem Deckungsfeuer, solange sie keine Schussposition haben.
  - **Jäger-Schützen halten Posten:** Ein Jäger-Skelett ohne Schussposition auf den Kern geht auf eine Zwischenplattform, hält dort den Posten (KI-Anzeige: „hält einen Posten“) und beschießt, was auf die Treppe kommt. Alle Schützen bevorzugen Zwischenplattformen als Schussposition (Bonus 8 Blöcke), wenn der Kern von dort getroffen werden kann.
  - **Sprengschützen repariert:** Ursache: Bei einem Kern in Obsidian (nicht sprengbar) fanden sie weder Schussposition noch sprengbare Wand und waren vom Deckungsfeuer ausgenommen, taten also nichts außer Laufen. Jetzt geben sie wie alle Schützen Deckungsfeuer bzw. jagen als Jäger und halten Posten.
- **v0.7.4** (Test v0.7.3: Kern fällt zuverlässig, keine Höhlen-Mobs, aber weiterhin keine Plattformen):
  - **Plattformen parallel:** Kein Warten mit dem Angriff. Baumeister haben Vorrang beim Bauen: Solange Plattform- oder Landing-Arbeit offen ist, greifen sie den Kern nicht an. Sie gehen zum nächstgelegenen Landing mit Arbeit, auch treppab (Stufe für Stufe), der zweite Baumeister kommt früher (Mob-Limit ≥ 6; bei offener Front mit ≥ 8 Feldern Plattformarbeit). Mit der KI-Anzeige meldet der Plan die Zahl der Plattformfelder und Landings (Diagnose: 0 = Planerproblem).
  - **Spielerjagd:** Ist ein Spieler in Kernnähe (`huntRadius` 48, begrenzt durch das Belagerungsgebiet, bis `huntMaxPlayers` 2 Spieler), plant der Planer einen Weg zu ihm (Turm, Loch, Bunker, Gräben). Das **Jäger-Team** bekommt eigene Baumeister und Gräber (`huntWorkers` 3 pro Spieler, zusätzlich zum Mob-Limit, spawnen am Sammelplatz des Wegs). Die Jäger laufen wie bisher normal auf den Spieler; sobald die Treppe steht, finden sie den Weg. Steht der Spieler erreichbar, braucht der Plan keine Arbeit, und niemand wird geschickt. Der Plan wird neu gemacht, wenn der Spieler sich bewegt (> 3 Blöcke, frühestens alle 5 s). Auf Tour (außerhalb von `huntRadius`) gibt es keine Jagd.
  - **Dynamische Teams:** Einmal pro Sekunde: Sind Spieler in Kernnähe, sind `hunterPercent` der Kämpfer Jäger (die Nächsten am Spieler), sonst gehören **alle** zum Kern-Team; kommt ein Spieler, splitten sie sich wieder auf. Jäger-Baumeister/-Gräber ohne Aufgabe (Weg fertig oder keine Jagd) gehen ins Kern-Team über und bekommen eine Kern-Front. Jäger jagen auch ohne Sichtkontakt (Reichweite 96).
- **Kern-GUI (v0.5):** Schalter „Grenzen“ (grüner Ring um Totems, lila Ring um den Kern, weit sichtbar) und „KI-Anzeige“ (Namensschild mit Rolle und Tätigkeit über jedem Angreifer, Routen als Partikel: weiß Weg, rot abbauen, blau bauen, gelb Leiter).

## Schild (v0.8.0)

- Bis zu **drei Plasma-Kuppeln** (gerenderte, leuchtende Kugeln mit Hexagon-Textur, keine Partikel) hintereinander (außen nach innen): Schild 1 ab Stufe 5 (Radius 10), Schild 2 ab Stufe 10 (Radius 5), Schild 3 ab Stufe 15 (Radius 2). Stufe, Radius und Schalter sind in der Config (`shields`) einstellbar. Stufe-20-Sonderschild folgt später.
- **Durchbruch:** Betritt ein feindlicher Angreifer den äußersten intakten Schild, bricht dieser.
- **Puls:** Er tötet alle Angreifer im Umkreis (Schildradius + 12 Blöcke) und entfernt die Baumeister-Blöcke (Belagerungsstein, Belagerungsleitern) außerhalb des Schildes. Kein Blockschaden, Spielerblöcke bleiben unberührt. Chat-Meldung an alle.
- **Kernschaden:** Solange ein Schild intakt ist, nimmt der Kern keinen Schaden (Pfeile von weitem nützen nichts).
- Ein gebrochener Schild bleibt gebrochen, weitere Angreifer kommen durch, bis der nächste innere Schild sie aufhält.
- **Reparatur** (Knopf im Kern-GUI) repariert den äußersten kaputten Schild und kostet **eine Kernstufe**. Möglich ab Mindeststufe (Standard 5) und nur, wenn der Schild danach noch existiert (z. B. Schild 1 ab Stufe 6).
- Entschärfen und Neu-Aktivieren setzen alle Schilde zurück.

## Belohnungen (v0.9)

- **Kernsplitter** sind die Währung der Mod. Für jede **verteidigte Nacht** zahlt der aktive Kern Splitter in sein **Guthaben** (Standard `shardsPerNightBase` 1 + `shardsPerNightPerLevel` 1,0 × (Stufe − 1), abgerundet). Eine Nacht zählt, wenn der Kern sie als aktiver Kern beginnen sah (Fenster von 2 Minuten nach Einbruch der Nacht, 13000 Ticks) und am Morgen (oder nach dem Schlafen) noch steht. Wer den Kern mitten in der Nacht aktiviert, bekommt für diese Nacht nichts. Nether/End nutzen die Uhr der Oberwelt. Bis zum Nacht-Rhythmus (v0.15) zählt jede Minecraft-Nacht.
- **Guthaben im Kern** (Anzeige im GUI), per Knopf „Splitter abheben“ als Item (bis 576 pro Klick), per **Schleichen + Rechtsklick** auf den Kern mit Splittern wieder einzahlen (normaler Rechtsklick öffnet das GUI). Wird ein **inaktiver** Kern abgebaut, kommen die Splitter als Items heraus; ein aktiver Kern explodiert, das Guthaben ist weg.
- **Statistik im Kern-GUI:** verteidigte Nächte, getötete Belagerungs-Mobs (jeder Tod eines Mobs mit Belagerungs-Tag zählt, auch Lava/Sturz). Bleibt beim Entschärfen erhalten.
- **Beute-Kiste:** eigener Block (54 Plätze, sieht aus und öffnet sich wie eine Kiste mit Deckel-Animation; **nur für Mob-Loot**, Splitter kommen immer aus dem Kern), den der Spieler selbst in der Nähe des Kerns aufstellt (Standard bis 16 Blöcke, `lootChestRange`). Solange der Kern aktiv ist, landen die normalen Drops getöteter Belagerungs-Mobs dort statt auf dem Boden (nächstgelegene Kiste zuerst). Ohne Kiste oder wenn alle voll sind, fällt der Rest normal. Erfahrung (XP) fällt unverändert. Die Kiste steht im Tag `incursion:siege_proof` (Mobs/Explosionen können sie nicht abbauen). Rezept (Platzhalter): Kiste, Enderperle, 3 Obsidian.
- Config-Gruppe `rewards`: `shardsEnabled`, `shardsPerNightBase`, `shardsPerNightPerLevel`, `lootChestEnabled`, `lootChestRange`.
- Verwendung der Splitter (Schilde/Totems reparieren, Kern-Tisch, Skilltree, Händler) folgt in späteren Versionen; bis dahin sind sie nur sammelbar.

## Kern-Tisch (v0.10.1)

- Eigener Block `core_table` (Rechtsklick öffnet das GUI), 12/16 hoch wie der Vanilla-Verzauberungstisch. Er braucht den **aktiven Kern der Welt** (egal wo); Splitter kommen aus dessen Guthaben, die Kernstufe bestimmt die Obergrenze.
- **Slot:** nimmt jederzeit ein verzaubertes oder verzauberbares Item. Darunter **alle** Verzauberungen, die das Item haben kann (Vanilla-Max ≥ II, nicht im Tag `incursion:table_blocked`), als Buttons. Auswählbar ist nur, was das Item auf Vanilla-Maximum (oder höher) hat (= „+1 über Vanilla“); der Rest ist ausgegraut, mit Begründung beim Überfahren.
- **Auswahl:** Button anklicken → grüner Haken bestätigt (reiner Schutz vor Fehlklicks), rotes Kreuz setzt die Auswahl zurück. Kein Freischalten mehr.
- **Pro Item nur eine** Verzauberung über Vanilla. Obergrenze = Vanilla + min(Kernstufe / `tableLevelsPerExtra` (4), `tableMaxExtra` (10), Eigenlimit aus `tableLimits`, Standard Effizienz +2).
- **Abends und mit Timer:** Start nur im Abendfenster (`tableStart`/`tableEnd`, Standard 11000–14000, abschaltbar). Dauer 60 s + 60 s je weiterer Stufe über Vanilla. Das Item bleibt beim Verlassen im Tisch (Block-Entity), schwebt, dreht sich und funkelt; bei Fertigstellung Sound, Partikel, Meldung; das Item wird entnommen.
- **Kosten** (beim Start): Splitter (`tableShardBase` 2 + `tableShardPerLevel` 2 je weiterer Stufe) und XP-Stufen (`tableXpBase` 5 + `tableXpPerLevel` 3). Creative zahlt nichts, ignoriert das Abendfenster, 10 s.
- **Optik und Klang (v0.10.1.1):** animierte Tisch-Texturen, immer sichtbare leuchtende Runenkreise (auch ohne Item), Lichtsäule beim Arbeiten, Partikel-Spiralen/Glyphen-Strom/Crescendo, eigene Sounds (Start, Schleife mit steigender Tonhöhe, Knistern, Fertig, leises Ruhe-Klingen). GUI: zwei Spalten ohne Scrollen, drei Zustände (bereit / blockiert durch Kern oder Zeit = orange / für das Item noch nicht möglich = grau) mit Legende.
- **Geplant (v0.10.2):** Resonanz (gespeicherte Stufen nur bei aktivem Kern, gedeckelt durch die aktuelle Kernstufe) und Ladung (Zusatzstufen verbrauchen sich pro Benutzung stufenweise bis Vanilla, Aufladen am Tisch, sichtbarer Balken, Sound/Partikel). Kern-Haste (v0.11) wird gedeckelt.
- Technik: `table/CoreTableLogic`, `table/CoreTableBlock` (+ Zustand `job`), `table/CoreTableBlockEntity`, `menu/CoreTableMenu` (Data-Slots, Bitmasken für freigegebene Buttons), `client/CoreTableScreen`, `client/CoreTableRenderer`. Config-Gruppe `table`.

## Lore und Design des Kerns (v0.10.1.3)

**Was der Kern ist.** Vor langer Zeit fiel der **Ankerstein** aus dem Nichts zwischen den Welten: das violette Kristallherz (ein Oktaeder) einer untergegangenen Welt. Ein Ankerstein hält eine Welt an ihrem Platz. Wo er einschlug, riss die Wirklichkeit, und aus dem Riss strömen seither die **Eindringlinge** (Incursion) in unsere Welt.

**Warum die Mobs ihn wollen.** Sie wollen nicht zerstören, sie wollen **heimkehren**: Der Ankerstein ist der Schlüssel zu ihrer Welt. Solange er schläft, ist das Tor zu. Wird er geweckt, leuchtet er wie ein Leuchtfeuer durch den Riss, und die Eindringlinge spüren ihn jede Nacht („Nacht der Heimsuchung“). Gelingt es ihnen, ihn zu zerbrechen, reißt das Tor endgültig auf (die Explosion, wenn ein aktiver Kern zerstört oder gewaltsam entfernt wird).

**Der Sarkophag (ersetzt das alte Siegel, v0.10.1.4).** Ein Würfel aus Obsidian (dunkles Violett mit türkisen Diamant-Akzenten) mit schmaler, rauer Goldeinfassung aus kleinen Würfeln (Rezept: Obsidian in den Ecken, Diamanten in den Seitenmitten). Die untere Hälfte hat auf jeder Seite eine V-Kerbe bis zur halben Höhe (die vier Ecken ragen als Spitzen auf), die obere Hälfte ist eine Haut aus Deckplatte und vier Glasdreiecken, die in den Kerben sitzen. Im Hohlraum liegt, was das Ritual verlangt.

**Ritual (Reihenfolge frei, 8 Zustände).** Kernrune (schwebt in der Mitte), Schwarzpulver (Nord-Süd-Rinne) und Lohenstaub (Ost-West-Rinne, glimmt) werden per Rechtsklick eingelegt. Pulver je 24 Stück (Config `sarcophagusPowder`). Sind alle drei drin, ist der Sarkophag bereit.

**Aktivieren = Mythischen Trank werfen.** Der Trank auf den fertigen Sarkophag löst die Kettenreaktion aus: ein Blitz schlägt ein, die obere Hälfte hebt sich ab und dreht sich um 35°, die Einlagen verglühen, die goldene Feuerkugel zündet, goldene Strahlen verbinden sie mit den Ecken, der Kern summt. Ohne Sarkophag verbrennt der Trank nur. Bild und Maße: `docs/sarkophag_referenz.png`.

**Splitter und Handwerk.** Übersteht der Kern eine Nacht, bricht er **Kernsplitter** ab (kondensierte Resonanz). Die Wächter schmieden daraus: Kern-Tisch (Verzauberungen über die Natur hinaus), Upgrade-Werkzeuge (stimmen den Kern höher), Schilde (Plasma-Gitter des Siegels), Schutztotems (Wächtersäulen) und die Beute-Kiste (Ankerkiste, sammelt, was die Eindringlinge mitbringen).

**Umsetzung im Design.** Geschlossen: ein voller Block (Unterschale, vier Platten mit Scharnier und Tuch-Einlage, Deckel mit dem Siegel-„Auge“, animierte Runen, Nahtlinie in Türkis). Aktiv: nur die Unterschale steht als Blockmodell, den Rest zeichnet `client/CoreShellRenderer` (Deckel, Kolben, Platten, Kristall, Laser, Siegelkreis); die Aufklapp-Zeit ist `OPEN_TICKS` (50). Sounds `core_open` (Entsiegeln) und `core_hum` (Summen, alle ~4 s), Partikel `block/CoreEffects`. Texturen: `tools/gen_core_textures.py`. Für die übrigen Blöcke und Items (Totem, Beute-Kiste, Upgrade-Werkzeuge, Splitter) gilt dieselbe Handschrift: Obsidian + violett + Gold + Türkis, animierte Runen, Siegel-Bezug.

## Evolution des Kerns: was jedes Element in der Lore bedeutet (18.10.)

- **Siegelkreis** (Runenring, der flach um den Kern dreht): die Bindrunen des Sarkophags. Sie halten das Feuer des Ankersteins im Sarkophag. Sie sind **keine** Tafeln.
- **Ankerketten** (Stufe 1): halten den Kern am Boden, damit die Eindringlinge ihn nicht mitnehmen können.
- **Flammenbögen, Plasmakugeln, Funkenringe** (Kern): der Ankerstein erwacht Stufe um Stufe, das Feuer wird zur Sonnenmasse und am Ende zur Supernova (Energie wächst, bleibt aber Feuer).
- **Deckelstufen, Sockel, Rippenkrone** (Sarkophag): der Sarkophag wächst in seinem eigenen Stil (Obsidian, Gold, Perlen) vom Grab zum Thron.
- **Wurzelpfeiler** (Stufe 2 bis 4): sie ziehen Resonanz aus der Erde (die Energie steigt in den Rillen auf, Wellen laufen über den Boden) und füttern die roten Energiekristalle; die Strahlen der Kristalle halten den Kern im Schacht. Splitter kreisen ab Stufe 3 um die Kristalle.
- **Resonanzwellen und Goldringe im Boden**: der Siegelkreis der Plattform, aus dem der Kern seine Kraft zieht.
- **Riss-Siegel** (Stufe 4): das Tor, durch das die Eindringlinge kamen; der voll erwachte Anker hält es geschlossen. Die Lichtsäule ist das Leuchtfeuer, das der Anker durch den Riss schickt.
- **Regel für die Evolution (Wunsch des Spielers, 18.10.):** Jede Stufe bringt ein **neues Objekt**, das es vorher nicht gab (nicht nur mehr vom Alten), nichts wird einfach kopiert, **kein weiterer Laser**, und weniger ist mehr: Der Kern soll Feuer bleiben, nicht überladen oder weiß ausgebrannt wirken. Alles Leuchten wird geblendet, nie addiert (sonst brennt es auf dem hellen Himmel zu Weiß aus).
- **Siegelplatten** (Stufe 1 bis 4): Vier Fassungen in den Wänden des Sarkophags; leere Fassungen sind dunkel und zeigen **kein** Zeichen (kein Spoiler). Die Tafel jeder Stufe gleitet in ihre Fassung (schwarzer Marmor) und leuchtet in der Farbe ihres Wächters (Elder Guardian türkis, Warden eisblau, Enderdrache violett, Wither aschgrau).
- **Grundsatz Energie-Logik (Wunsch des Spielers, 19.10.):** Alles Sichtbare hat eine erklärbare Funktion, und auf jeder Stufe sieht man, dass ein Boss und ein General besiegt wurden und ihre Energie in den Kern fließt (oder abgesaugt wird).
- **Aktiver Boss + Trophäen (Wunsch des Spielers, 19.10.):** Auf jeder Stufe ist nur der gerade erlegte Boss aktiv (Stufe 1 Elder Guardian, 2 Warden, 3 Enderdrache, 4 Wither). Beim nächsten Upgrade nimmt der frühere Boss einen festen Platz zwischen zwei Pfeilern ein (magisch mit den Pfeilern verbunden), schaut zum Kern und speist den Sarkophag-Deckel mit einem Strahl. Bosse werden mit den Original-Modellen des Spiels gezeichnet (identisch, nicht nachgebaut). Der Kern bleibt unverändert.
- **Antenne (Wunsch des Spielers, 19.10.):** Die Energie aller aktiven und passiven Bosse läuft zuerst in eine Antenne auf dem Deckel und von dort als dicker Strahl in den Kern; der Beacon von Stufe 4 geht von der Antenne aus. Trophäen schweben hoch zwischen zwei Pfeilern in einem Ringkäfig, von dunkler Materie aus den Pfeilern gehalten, damit alle vier Wege zum Kern frei bleiben. Der Elder Guardian ist von Anfang an im Käfig gefangen und wird vom Kern gedreht. Enderaugen gehören nur zu Stufe 3 (Drache), Stufe 4 trägt Wither-Schädel.
- **Käfige (Wunsch des Spielers, 20.10.):** Jeder Boss hängt sofort in einem goldenen Ringkäfig zwischen zwei Pfeilern (N Elder Guardian, O Warden, S Enderdrache, W Wither) und schaut zum Core: kein Herumlaufen oder Umkreisen, das braucht zu viel Platz. Aktiv ist nur der zuletzt besiegte (Energie in die Antenne, Saugschläuche); die Trophäen werden zusätzlich vom neutralen Goldstrahl der Pfeiler-Kristalle gehalten. Das Gefängnis unter der Plattform gibt es nicht mehr.
- **Stufe 0**: Der Kern ist ein Stern: dünne goldene Strahlen bis zum Rahmen, Flare in der Mitte, Runenring.
- **Stufe 1, Späher + Elder Guardian**: Vier Schattenwächter (Kapuze, leeres Gesicht, Schlitzaugen, dunkle violette Adern = Varkhuls Übernahme) stehen weiter außen auf den dunklen Pads; Schatten (violette Pakete) läuft über die Ketten in den Sarkophag. Das Auge der ersten Tafel schickt den gedrehten Strahl des Elder Guardian (zwei Spiralstränge, Bogen über die Wand) in den Kern.
- **Stufe 2, Brecher + Warden**: Lava (Vanilla-Lavatextur, animiert) läuft vom Hammer (Lavaflächen, Naht, Bänder, Nieten) am Schaft herab, sammelt sich in Becken und läuft durch verkohlte Risse zum Kern. Vier Warden-Schreine auf den Achsen (Deepslate-Tiles, verstärkter Deepslate, Sculk; Rippenkäfig mit türkisem Herz, zwei Hörner) schlagen im Doppelschlag und schicken Pulse über eine Ader im Boden in den Kern.
- **Stufe 3, Seelenwirker + Enderdrache**: Der Geisterdrache blitzt (elektrische, flackernde Bögen) in die Kristalle der Käfig-Pfeiler, violette Energie fließt den Schaft hinab in ein Enderauge (Rahmen, grüner Iris-Ring), von dort ein Blitz in den Kern. Der Seelenwirker flicht Doppelfäden (verflochten) zwischen den Augen. Keine Laternen mehr auf dieser Stufe.
- **Stufe 4, Wither** (Varkhul hat auf Stufe 20 keinen Bezug mehr; er wird später der Prestige-Kampf nach Level 20, ein vierter General ist noch zu erfinden): Wirbelsäule, fünf Rippenreifen, drei Schädel (blicken den Spieler an, violette Augen), Seelen steigen aus der Ebene in die Rippen, ein **Seelentrichter** (neuer Mechanismus: Spirale) führt sie in den Kern, violette Seelenlaternen speisen den Kern über Bänder, der Kern bekommt violette Flammenzungen und schickt einen **Beacon-Strahl** in den Himmel.
- **Kern**: heller Materiekern (Flare, weißgelber Kern, orange Hülle). Flammenbögen wie Sonnenprotuberanzen (Aufbau, Halten, Rückfall in den Kern, Periode ca. 200 Ticks; 3/4/5 Bögen), bleiben innerhalb des Hohlraums.
- **Stufenübergang:** Alles wird kontinuierlich animiert (`SarcophagusRenderer`, „visuelle Stufe“): Pfeiler wachsen aus dem Boden und wandern nach außen, Teile wachsen aus der vorherigen Form. Beim Entschärfen fällt die visuelle Stufe mit dem Countdown zurück (Abbruch baut sie wieder auf); danach verschwindet die Plattform Ring für Ring von außen nach innen.

## Upgrade-Fluss mit Boss-Nacht (Idee 18.10.)

Spieler setzt tagsüber oder nachts die Tafel mit dem Upgrade-Werkzeug ein (Stufe 5, 10, 15). Das Upgrade startet, schließt aber erst nach der **darauffolgenden Nacht** ab (ca. 24 Ingame-Stunden). In dieser Nacht erscheint der **Boss der Stufe** (Späher, Brecher, Seelenwirker; bei Stufe 20 Varkhul). Der Boss droppt ein **Boss-Item**; erst wenn der Spieler es in den Kern einfügt, wird das Upgrade fertig und die **Evolution des Sarkophags** (`CoreEvolution`) läuft, sodass man die Animation wirklich sieht. Schafft man den Boss nicht vor Ende der Nacht, verflüchtigt er sich und kommt in der nächsten Nacht wieder, bis er besiegt ist. **Entschieden:** Wartezustand liegt im Kern; der Kern bleibt bis zum Abschluss auf der alten Stufe; bei Einsetzen der Tafel in der Nacht kommt der Boss erst in der Nacht darauf; der Boss wird nie schwächer; das Boss-Item fällt als abbaubares Objekt (nicht despawnbar, nicht sprengbar), das beim Abbau das Item mit Animation droppt; der Test-Tab bekommt später einen Boss-Spawn-Button. Die Features der Evolution sollen an den Boss der Stufe erinnern. Offen: Abbruch bei zerstörtem Kern, Anzeige im GUI, Testmodus überspringt die Wartezeit.

## Laufen auf der Route (v0.8.3.2)

- Die Wege sind aus rechtwinkligen Schritten gebaut, aber Mobs laufen sie **nicht Schritt für Schritt** ab: Die normale Mob-Wegfindung (`getNavigation().moveTo`) führt sie zu einem Schritt ihres Weges bis zu 12 Schritte voraus (nie über das hinaus, was begehbar bereit ist, `walkLimit`). Sie bewegen sich dadurch wie jeder Mob, der etwas jagt. Findet die Wegfindung keinen Weg (oder im tiefen Wasser, als Passagier), läuft der Mob die Schritte wie vorher. Arbeiten (bauen, graben) und Plattformen laufen unverändert.

## Safezone (v0.8.2/0.8.3)

- Die Safezone ist das Quadrat um den Kern (Halbseite Kernzone + 6, **auf allen Stufen gleich**, Standard 8 + 6 = 14) und um jedes Schutztotem (Halbseite `wardRadius`); alles zusammen ist **ein** Gebiet. „Grenzen“ im Kern-GUI zeigt den Gesamtumriss mit grünen Strahlen an den Ecken und hellgrünem Boden.
- **Nichts spawnt dort:** weder Belagerer (Sammelplätze, Notfall-Spawns, Jagd, Phantom-Start) noch natürliche Monster (Config `safeZoneBlocksNaturalSpawns`). Belagerer laufen hinein, um den Kern anzugreifen; die Luftlandung setzt Mobs bewusst nahe am Kern ab.

## Config (geplant)

Maximale Stufe, Upgrade-Kosten, Aktivierungs-Rohstoff, Kernzonen- und Belagerungsradius, Mob-Anzahl und -Stärke pro Stufe, Lebenspunkte des Kerns, Schild-Radius und Reparatur-Mindeststufe, Explosionsradius und Verseuchung, Creeper-Blockschaden, Ausnahmeliste.

## Fahrplan

| Version | Inhalt | Status |
|---|---|---|
| v0.1 | Kern-Block, Aktivierung mit Rohstoff, Stufen-Upgrades (Platzhalter-Kosten), Statusanzeige, ein Kern pro Welt | fertig |
| v0.2 | Belagerung abhängig von der Stufe, Zielverhalten, Lebenspunkte des Kerns, Chunkloader, GUI | fertig |
| v0.3 | Große Explosion, Verseuchung, Entschärfen (Platzhalter), grabende Mobs, Zombie-Rollen | gebaut |
| v0.4 | Belagerungs-KI: Routenplaner, Baumeister, Rollen, Skelett-Klassen, Pioniere, Patt-Regel, Schutztotem | getestet: Mobs erreichen Kern nicht |
| v0.5 | KI-Neubau: direkter Kernangriff, Schusspositionen, Routen-Hüpfen, Belagerungsstein, GUI-Schalter Grenzen/KI-Anzeige | getestet: Baumeister zu schwach |
| v0.6 | Erreichbarkeit: Leitern, keine Selbstblockade, alle Zombies graben, Luftlandung, Spinnenreiter, Endermen-Teleport, Rückruf | getestet: Kern fällt (v. a. durch Phantome), Gedränge, Höhlen-Mobs |
| v0.7 | Einheit: kein Schieben, Sammeln/Stürmen, mehrere Wege, Leitern bevorzugt, verirrte Mobs, Oberflächen-Spawns, seltene Luftlandung | getestet: schlechter |
| v0.7.1 | Kommandant: zentraler Plan, Treppe + Plattform, Spawns am Sammelplatz nach Bedarf | getestet: baut sicher, aber nur 1 Treppe, zu wenige Mobs |
| v0.7.2 | Mehrere Fronten, alle Angreifer sofort, breite Treppe, 3×3-Plattform, keine Höhlen-Sammelplätze, Dorfbewohner ignoriert | getestet: viel besser, aber keine Plattformen, Skelette unzuverlässig |
| v0.7.3 | Zwischenplattformen, Baumeister bauen aus, Teams Jäger/Leibwächter, Posten für Schützen, Sprengschützen repariert | getestet: Kern fällt, aber keine Plattformen |
| v0.7.4 | Plattformbau parallel, Spielerjagd mit eigenen Baumeistern/Gräbern, dynamische Teams | gebaut, **Test ausstehend** |
| v0.8 | Schilde (3 Kuppeln), Puls, Reparatur | gebaut, Test ausstehend |
| v0.9 | Kernsplitter pro verteidigter Nacht (Guthaben im Kern, abhebbar), Statistik, Beute-Kiste | gebaut, Test ausstehend |
| v0.9.2.1 | Upgrade-Werkzeug (4 Stufen à 5 Kernstufen, Rechtsklick mit dem Werkzeug in der Hand, Config `upgradeToolRequired`/`upgradeToolLevels`) | getestet ✔ |
| danach | Config-Feinschliff, Balancing, Brennstoff, Create-Anbindung, eigene Items | geplant |

## Offene Entscheidungen

- Aktivierungs-Rohstoff und Upgrade-Kosten
- Mechanik zum Entschärfen
- Brennstoff-Verbrauch (ja/nein, welcher)
- Lizenz der Mod
- **Einheitliche Linie (Wunsch des Spielers, 20.10.):** Jeder Boss schickt genau einen Strahl in die Antenne, in seiner Farbe (Elder Guardian beige, Warden blau, Drache violett, Wither weiß); sonst keine Strahlen. Besiegte Bosse hängen an goldenen Ketten zwischen den Pfeilern. Keine Schattenwächter-Statuen, keine Villager-Köpfe. Die frühere Stufe 15 ist die Endgröße (Stufe 20), die anderen Stufen wachsen proportional dorthin. Musik: ruhiger Dauerklang ohne Akkordwechsel (immer D-Klangraum), damit das Upgrade-Stück an jeder Stelle passt; das Finale kommt immer nach 6 Sekunden.
