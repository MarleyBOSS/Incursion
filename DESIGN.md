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
  - **Kein Schieben:** Angreifer kommen ins Scoreboard-Team `incursion_siege` (Kollisionsregel „nur andere Teams schieben“). Sie blockieren sich auf Treppen und Leitern nicht mehr; Spieler schieben normal. Tote Mobs werden aus dem Team entfernt.
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

## Schild

- Visuelle **Partikel-Kuppel** um den Kern, Radius wächst mit der Stufe.
- Durchbricht ein Mob die Kuppel: **Sicherungsexplosion**, die alle Mobs im Umkreis tötet – **ohne Blockschaden**.
- Danach ist der Schild kaputt, weitere Angreifer kommen ungehindert durch.
- **Reparatur kostet eine Kernstufe**, möglich ab einer Mindeststufe (Config, Standard: Stufe 5).

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
| v0.8 | Schild-Kuppel, Sicherungsexplosion, Reparatur | geplant |
| v0.9 | Vorteile: Items, Energie, Buffs | geplant |
| danach | Config-Feinschliff, Balancing, Brennstoff, Create-Anbindung, eigene Items | geplant |

## Offene Entscheidungen

- Aktivierungs-Rohstoff und Upgrade-Kosten
- Mechanik zum Entschärfen
- Brennstoff-Verbrauch (ja/nein, welcher)
- Lizenz der Mod
