# Design: Steve führt LLM-generierten Code aus („auf alles reagieren")

**Datum:** 2026-06-03
**Status:** Genehmigt (Brainstorming abgeschlossen)

## Ziel

Steve soll auf beliebige natürlichsprachliche Befehle reagieren können — nicht nur
auf einen festen Satz vorprogrammierter Aktionen. Statt dass das LLM einen festen
JSON-Task-Plan ausgibt (heutiger Stand: scheitert an allem, was nicht `build/mine/
attack/follow/pathfind` ist — z. B. „make a sunflower field" → 0 Tasks), generiert
das LLM für **jeden** Befehl ein kleines JavaScript-Programm, das gegen eine
kontrollierte `SteveAPI` läuft und Primitiv-Aktionen plant.

## Entscheidungen (aus dem Brainstorming)

- **Ansatz:** Maximal offen via Code — die bereits vorhandene, aber ungenutzte
  GraalVM-JS-Engine (`CodeExecutionEngine` + `SteveAPI`) wird fertig verdrahtet.
- **Ausführungsstil:** Block-für-Block, immersiv. Das Skript *plant nur* (reiht
  Aktionen in die Queue); Steve läuft hin und platziert nacheinander über die Ticks.
- **Pipeline:** Ein Pfad — Code-Gen ersetzt den festen JSON-Task-Pfad. Das Programm
  darf High-Level- (`build('house')`, `mine('iron')`) und Low-Level-API (`place`,
  `move`) mischen.
- **Modell:** Bleibt Haiku 4.5 (konfigurierbar). Folge: robuste Retry-Logik nötig.

## Architektur & Datenfluss

```
Befehl → Async-LLM (Haiku) schreibt JS  →  Syntax prüfen → (Retry bei Fehler, max 2)
       → Skript läuft in GraalVM-Sandbox → ruft steve.place/move/mine/build…
       → diese füllen einen Puffer       → bei Erfolg: Puffer in die echte Aktions-Queue committen
       → Tick-Loop läuft Steve hin & platziert Block für Block (unverändert)
```

Der bestehende async-Einstieg (`ActionExecutor.processNaturalLanguageCommand`) und der
Tick-basierte Queue-Drain bleiben erhalten. Geändert wird nur, **was** beim Eintreffen
des LLM-Ergebnisses passiert: nicht mehr JSON parsen, sondern das Skript ausführen,
das die Queue füllt.

## Komponenten

| Datei | Änderung | Verantwortung |
|---|---|---|
| `llm/CodePromptBuilder.java` | **neu** | System-Prompt mit dokumentierter JS-API + 2–3 Few-Shot-Beispielen (Sonnenblumenfeld per Schleife, Haus per `build('house')`, Eisen minen) + Weltkontext (Steve-Position, nahe Blöcke/Spieler). Anweisung: nur ein JS-Codeblock, kein Prosa-Text. |
| `execution/SteveAPI.java` | ändern | `@HostAccess.Export` an jede öffentliche Methode; Enqueues laufen in einen **Puffer** statt direkt in die Queue; Op-/Enqueue-Zähler mit Cap. |
| `execution/CodeExecutionEngine.java` | ändern | Kontext mit `HostAccess.EXPLICIT` statt `allowHostAccess(null)`; Statement-/Zeit-Limit; Code-Extraktion + Ausführung pro Befehl. |
| `action/ActionExecutor.java` | ändern | Beim fertigen LLM-Result Engine ausführen lassen → Queue füllen; async Retry-Kette. Tick-Drain unverändert. |
| `config/SteveConfig.java` | ändern | Neue Grenzen: max. Aktionen/Skript, Skript-Timeout, Platzierungs-Radius, Retry-Anzahl. Modell-Default bleibt Haiku. |
| `llm/PromptBuilder.java`, `llm/ResponseParser.java` | bleibt | Im Live-Pfad ungenutzt, **nicht gelöscht** (Entscheidung bewusst dem Nutzer überlassen). |

## Zwei kritische Fixes (sonst läuft die Engine grundsätzlich nicht)

1. **GraalVM-Sichtbarkeit:** Heute ist die `SteveAPI` für JS unsichtbar
   (`allowHostAccess(null)`, keine Annotationen) → JS könnte keine einzige Methode
   aufrufen. Fix: `@HostAccess.Export` an die beabsichtigten öffentlichen Methoden +
   Kontext mit `HostAccess.EXPLICIT`. Sandbox bleibt dicht (kein Reflection/Java-/
   Datei-/Thread-Zugriff), nur die annotierte API ist sichtbar.

2. **Threading (Ansatz B1, gewählt):** Das Skript wird **auf dem Server-Thread** in
   `tick()` ausgeführt (mit hartem Timeout), sobald der async-LLM-Future fertig ist.
   Dann sind Weltlesungen (`getNearbyBlocks`/`getNearbyEntities`) korrekt und das
   Enqueue ist threadsicher. Weil das Skript nur *plant* (kein Warten/Bauen), läuft es
   in Millisekunden.
   *Verworfene Alternative B2:* Skript off-thread mit vorab erstellten Welt-Snapshots —
   komplexer und liefert dem Skript veraltete Weltdaten.

## Sicherheitsgrenzen

Single-Player (eigene Welt), aber gegen Endlosschleifen / Speicher-/Müll-Explosionen:

- Skript-Timeout (Default 250 ms) + GraalVM-Statement-Limit.
- Max. Enqueues pro Skript (Default 1024) — danach wirft die API → Skript stoppt.
- Optionaler Platzierungs-Radius um Steve (Default 64 Blöcke), konfigurierbar.

## Fehler- & Retry-Strategie

- **Atomar:** Enqueues sammeln sich in einem Puffer; erst bei *fehlerfreiem*
  Skriptende werden sie in die echte Queue committet → keine halb-gebauten Reste bei
  Skript-Absturz.
- Syntaxfehler oder Laufzeit-`PolyglotException` → bis zu **2 Retries**, die
  Fehlermeldung wird ans Modell zurückgegeben („dein Code warf X, korrigiere").
- Nach erschöpften Retries: GUI-Meldung „Das kriege ich nicht hin" + nichts enqueuen.

## Tests (rein in Java, ohne Minecraft-Laufzeit)

- `SteveAPI`: Puffer wird nur bei Erfolg committet; Enqueue-Cap wirft bei Überschreitung.
- `CodeExecutionEngine`: Syntax-Validierung erkennt Fehler; Sandbox verweigert Datei-/
  Java-Zugriff; Timeout greift bei Endlosschleife.
- Code-Extraktion: ` ```js `-Fences werden korrekt gestrippt.
- Retry-Logik: erster Versuch wirft → Re-Prompt mit Fehler → zweiter Versuch erfolgreich.

## Offener Detailpunkt (kein Architektur-Blocker)

Sonnenblumen/Doppelpflanzen sind 2 Blöcke hoch — ein einzelnes `setBlock` der
Unterhälfte platziert die Pflanze ggf. nicht korrekt. Das ist ein Content-Detail in
`PlaceBlockAction` (bzw. der Block-Platzierungslogik), das bei Bedarf nachgezogen wird;
die Architektur bleibt davon unberührt.
