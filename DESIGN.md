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

1. **Kernzone** (nah am Kern): Alle feindlichen Mobs ignorieren Spieler und greifen nur den Kern an.
2. **Belagerungsgebiet**: Ist ein Spieler in normaler Wahrnehmungsreichweite eines Mobs, greift der Mob den Spieler an. Sonst läuft er zum Kern.
3. **Außerhalb**: normales Verhalten.

- Das Belagerungsgebiet entspricht dem Bereich, den der Kern geladen hält, und **wächst mit der Stufe**.
- **Alle feindlichen Mobs** sind betroffen, nicht nur die von der Belagerung gespawnten – auch natürliche Spawns und Mobs anderer Mods.
- Angriffsart je nach Mob: Zombies und Spinnen schlagen zu, Skelette schießen, Creeper explodieren. Unbekannte Mobs greifen im Nahkampf an.
- Der Kern hat **Lebenspunkte**, die mit der Stufe steigen.
- **Ausnahmeliste** in der Config: Bosse (Wither, Warden, Cataclysm-Bosse …) sind standardmäßig ausgenommen.
- Creeper-Explosionen in der Kernzone verursachen **keinen Blockschaden** (Config umstellbar).
- **Graben (v0.3):** Nahkampf-Mobs, die auf dem Weg zum Kern feststecken, bauen Blöcke ab. Dauer hängt von der Blockhärte ab (Erde schnell, Tiefenschiefer langsam, Obsidian sehr langsam, Grundgestein nie) und vom Mob: mit passendem Werkzeug wie ein Spieler (abgeschwächt), sonst nach Angriffsschaden. Mehrere Mobs am selben Block addieren sich.
  - Fernkämpfer (Skelette, Plünderer, Hexen …), Spinnen und Flieger graben nicht.
  - Creeper opfern sich an der Wand und sprengen ein Loch (diese Explosion zerstört Blöcke, nie den Kern).
  - Endermen nehmen den Block weg.
- **Zombie-Rollen (v0.3):** Belagerungs-Zombies sind Krieger (Schwert, mehr Schaden an Spielern), Gräber (Spitzhacke, graben schnell) oder Brecher (Axt, mehr Schaden am Kern). Werkzeug-Material steigt mit der Kernstufe: Stein → Eisen (ab 5) → Diamant (ab 10) → Netherit (ab 16). Die Werkzeuge droppen nicht.

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
| v0.4 | Schild-Kuppel, Sicherungsexplosion, Reparatur | geplant |
| v0.5 | Vorteile: Items, Energie, Buffs | geplant |
| danach | Config-Feinschliff, Balancing, Brennstoff, Create-Anbindung, eigene Items | geplant |

## Offene Entscheidungen

- Aktivierungs-Rohstoff und Upgrade-Kosten
- Mechanik zum Entschärfen
- Brennstoff-Verbrauch (ja/nein, welcher)
- Lizenz der Mod
