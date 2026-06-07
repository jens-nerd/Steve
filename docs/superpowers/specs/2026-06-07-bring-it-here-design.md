# Design: "bring it here" — Items sammeln & abliefern

**Datum:** 2026-06-07
**Status:** Genehmigt (Brainstorming abgeschlossen)

## Ziel

Steve soll auf Befehle reagieren, die die Phrase **"bring it here"** enthalten (z.B.
"mine some iron and bring it here"): er sammelt die bei der Arbeit anfallenden Items in
ein Inventar, platziert **einmalig eine Kiste direkt vor dem auftraggebenden Spieler**
und pendelt — immer wenn er einen **halben Stack (32)** gesammelt hat — zur Kiste,
lagert ein und macht weiter. Voraussetzung, die es heute nicht gibt: Steve hat **kein
Inventar** und **sammelt nichts** ein (`MineBlockAction` lässt Drops via
`destroyBlock(pos, true)` auf den Boden fallen). Dieses Feature baut das Fundament
(Einsammeln) und darauf das Abliefern.

## Entscheidungen (aus dem Brainstorming)

- **Auslösung:** deterministisch im Code per Phrase `"bring it here"` (case-insensitive),
  **kein** LLM-Ermessen. Das LLM generiert weiterhin nur die Arbeit (z.B. `mine`).
- **Ende-Bedingung:** an die **Auftragsmenge** gekoppelt — z.B. "mine 5 iron and bring
  it here" → 5 Eisen abbauen, zwischendurch in 32er-Portionen abliefern, am Ende Rest
  abliefern, fertig.
- **Eingesammelt wird**, was Steve **aktiv abbaut** (kein Aufsaugen loser Boden-Items im
  Umkreis — bewusste YAGNI-Vereinfachung, kann später ergänzt werden).
- **Schwelle:** fix 32 Items (halber Stack); pro Liefer-Trip wird **das gesamte**
  gesammelte Inventar in die Kiste geschoben.
- **Threading:** Alle Welt-Operationen (Kiste platzieren, Einlagern, Block entfernen)
  laufen auf dem **Server-Thread** (in `tick()` / Actions). Der Befehls-Thread
  (`tellSteve` → `processNaturalLanguageCommand`) merkt sich nur Spieler + Flags.

## Architektur & Datenfluss

```
Befehl "… bring it here" (vom Spieler über GUI → "steve tell <name> <cmd>")
  → tellSteve kennt source-Spieler → processNaturalLanguageCommand(cmd, player)
  → ActionExecutor: erkennt "bring it here" → deliveryMode=true, deliveryTarget=player
  → LLM plant die Arbeit (mine) wie bisher → Mining-Action läuft
     · MineBlockAction legt Erz-Drops in Steves Inventar (statt auf den Boden)
  → tick()-Orchestrierung:
     · einmalig: Kiste vor dem Spieler platzieren (chestPos merken)
     · wenn collectedCount() ≥ 32: Mining PAUSIEREN (nicht weiter ticken),
       DeliverToChestAction (zur Kiste laufen, einlagern), danach Mining FORTSETZEN
     · Mining fertig (Auftragsmenge erreicht): Rest abliefern → fertig
```

Das Pause/Resume nutzt aus, dass eine `BaseAction` ihren Zustand selbst hält: Mining
wird pausiert, indem es schlicht nicht getickt wird (behält `minedCount`,
`currentTunnelPos`), während die `DeliverToChestAction` tickt; danach wird Mining
weiter getickt. Kein Fortschrittsverlust.

## Komponenten

| Datei | Änderung | Verantwortung |
|---|---|---|
| `entity/SteveEntity.java` | ändern | `SimpleContainer` (27 Slots) + Zugriff: `getInventory()`, `addToInventory(ItemStack)`, `collectedCount()`. |
| `action/actions/MineBlockAction.java` | ändern | Beim Abbau des Ziel-Erzes Drops via `Block.getDrops(...)` ins Inventar legen und Block ohne Welt-Drop entfernen, statt `destroyBlock(pos, true)`. |
| `command/SteveCommands.java` | ändern | `tellSteve` reicht den source-Spieler an `processNaturalLanguageCommand(cmd, player)` weiter. |
| `action/ActionExecutor.java` | ändern | `deliveryMode`, `deliveryTarget`, `chestPos`; Phrasen-Erkennung; Kiste platzieren; Pause/Resume-Orchestrierung; 32er-Schwelle; Final-Deposit. |
| `action/actions/DeliverToChestAction.java` | **neu** | Zur Kiste laufen und Steves Inventar in den Kisten-Container schieben; meldet komplett. |

## Detail-Design

### Inventar (SteveEntity)
- `private final SimpleContainer inventory = new SimpleContainer(27);`
- `collectedCount()` = Summe der Stack-Größen über alle Slots.
- `addToInventory(ItemStack)` legt einen Stack ab (Caller stellt sicher, dass Platz ist;
  bei vollem Inventar wird ausgelagert bevor weiter gesammelt wird — Slots reichen für
  reale Mengen, ein voller 27-Slot-Container ist Edge-Case).

### Einsammeln (MineBlockAction)
- Statt `steve.level().destroyBlock(currentTarget, true)` (Zeile ~167):
  1. Drops berechnen: `Block.getDrops(state, serverLevel, pos, blockEntity, steve, tool)`
     (tool = Steves Eisen-Spitzhacke; exakte 26.1-Signatur beim Implementieren prüfen).
  2. Block ohne Welt-Drop entfernen: `destroyBlock(pos, false)` bzw. `removeBlock`.
  3. Jeden Drop-Stack via `steve.addToInventory(stack)` ins Inventar.
- Nur der **Ziel-Erz-Abbau** wird eingesammelt; Tunnel-/Schacht-Geröll bleibt
  unverändert (`destroyBlock(..., true)` oder wie bisher) — es ist nicht das Auftragsgut.

### Auftraggeber durchreichen (SteveCommands.tellSteve)
- `Player player = source.getEntity() instanceof Player p ? p : null;` (vor dem Thread
  capturen) → `steve.getActionExecutor().processNaturalLanguageCommand(command, player)`.
- Neue Überladung `processNaturalLanguageCommand(String, Player)`; die alte 1-arg-Version
  delegiert mit `null`.

### Orchestrierung (ActionExecutor)
- Felder: `boolean deliveryMode`, `Player deliveryTarget`, `BlockPos chestPos`,
  `BaseAction deliveryAction`.
- In `processNaturalLanguageCommand(cmd, player)`: `deliveryMode = player != null &&
  cmd.toLowerCase().contains("bring it here"); deliveryTarget = player; chestPos = null;`
- In `tick()` (Server-Thread), wenn `deliveryMode`:
  - **Kiste:** wenn `chestPos == null` → `chestPos = placeChestInFrontOf(deliveryTarget)`
    (Spieler-BlockPos `.relative(player.getDirection())`, Boden via Abwärts-Scan; setze
    `Blocks.CHEST` wenn Platz; sonst nächstes freies Feld).
  - **Pendeln:** wenn `deliveryAction != null` → nur diese ticken; bei Complete →
    `deliveryAction = null` (Mining wird danach normal weiter getickt). Sonst, wenn
    `currentAction` (Mining) läuft und `steve.collectedCount() >= 32` →
    `deliveryAction = new DeliverToChestAction(steve, chestPos); deliveryAction.start();`
    (Mining wird in diesem Zustand nicht getickt = pausiert).
  - **Final-Deposit:** wenn die Mining-Action fertig ist und `collectedCount() > 0` →
    eine letzte `DeliverToChestAction` ausführen, danach `deliveryMode = false`.
- Die normale "ein Action zur Zeit"-Schleife bleibt; `deliveryAction` ist ein
  Vorrang-Slot, der `currentAction` temporär pausiert.

### DeliverToChestAction
- `onStart`: Pfad/Teleport Richtung `chestPos` (in Reichweite, ~2 Blöcke).
- `onTick`: wenn nah genug → Items aus `steve.getInventory()` in den Container an
  `chestPos` schieben (`ChestBlockEntity`/`Container`; Kapazität beachten — Überlauf
  bleibt im Inventar), dann `result = success`.
- `onCancel`: Navigation stoppen.

### Kisten-Position (reine Funktion, testbar)
- `static BlockPos chestPositionInFront(BlockPos playerPos, Direction facing)` →
  `playerPos.relative(facing)`. Der Boden-Scan (welt-abhängig) bleibt außerhalb dieser
  reinen Funktion.

## Tests

**Rein in Java (ohne Minecraft-Laufzeit):**
- `SteveEntity.collectedCount()` / `addToInventory`: Stacks hinzufügen, Summe prüfen
  (`SimpleContainer` braucht keine Welt).
- Phrasen-Erkennung: `containsBringItHere("mine iron and BRING IT HERE")` etc.
  (reine String-Funktion; ggf. als kleine Hilfsmethode extrahiert).
- `chestPositionInFront(playerPos, facing)` für alle vier Richtungen.

**In-Game-Gate (MC-gekoppelt):** Kiste vor dem Spieler erscheint; Steve mint, pendelt
bei 32 zur Kiste, lagert ein, macht weiter; am Ende Rest abgeliefert.

## Offene Detailpunkte (kein Architektur-Blocker)
- Exakte 26.1-Signatur von `Block.getDrops` und der Container-Insert-Hilfen beim
  Implementieren gegen die entschlüsselte NeoForge-Jar verifizieren.
- Voller 27-Slot-Container: extrem selten bei realen Auftragsmengen; bei Überlauf bleibt
  Rest im Welt-Drop bzw. wird beim nächsten Trip nicht eingesammelt (akzeptiert).
