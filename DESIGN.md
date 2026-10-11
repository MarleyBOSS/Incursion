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
  - **Rückruf:** Belagerungs-Mob > 24 Blöcke vom Kern, 90 s nutzlos → verschwindet, Platz für einen neuen.
  - **Luftlandung:** Phantome tragen Mobs (≤ 1 breit, ≤ 2,2 hoch) zu einem freien Platz mit offenem Himmel direkt am Kern. Wenn der Kern ≥ 8 Blöcke über dem Boden liegt oder die Belagerung im Patt ist: 50 % der Spawns; wenn kein Bodenplatz gefunden wird: immer.
  - **Spinnen** laufen direkt auf den Kern zu und klettern Wände hoch (Umweg bei Überhang). 50 % tragen einen Zombie oder ein Skelett, das nahe am Kern absteigt.
  - **Endermen** teleportieren sich nicht mehr vom Kern weg und springen, wenn sie feststecken, direkt neben den Kern.
  - **Schützen** nehmen nur Schusspositionen, zu denen ein Weg existiert; finden sie keine, folgen sie den Routen (auch Leitern) und prüfen unterwegs weiter.
  - **Hoher Kern:** Belagerungsgebiet reicht nach unten um die Kernhöhe über dem Boden weiter; gespawnt wird notfalls auf dem Boden darunter. Der Planer kann bis an die Weltobergrenze planen.
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
| v0.6 | Erreichbarkeit: Leitern, keine Selbstblockade, alle Zombies graben, Luftlandung, Spinnenreiter, Endermen-Teleport, Rückruf | gebaut |
| v0.7 | Schild-Kuppel, Sicherungsexplosion, Reparatur | geplant |
| v0.8 | Vorteile: Items, Energie, Buffs | geplant |
| danach | Config-Feinschliff, Balancing, Brennstoff, Create-Anbindung, eigene Items | geplant |

## Offene Entscheidungen

- Aktivierungs-Rohstoff und Upgrade-Kosten
- Mechanik zum Entschärfen
- Brennstoff-Verbrauch (ja/nein, welcher)
- Lizenz der Mod
