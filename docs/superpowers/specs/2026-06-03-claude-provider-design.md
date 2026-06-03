# Design: Claude/Anthropic als LLM-Provider

**Datum:** 2026-06-03
**Status:** Approved

## Ziel

Steve so erweitern, dass er mit einem Claude-API-Key funktioniert. Claude wird als
zusätzlicher Provider hinzugefügt und zum Default gemacht; OpenAI/Groq/Gemini bleiben
wählbar.

## Entscheidungen

- **Umfang:** Claude als Default dazu (nicht-invasiv), andere Provider bleiben erhalten.
- **Config:** Eigener `[anthropic]`-Block mit eigenem `apiKey` + `model`.
- **Pfade:** Nur der aktive Async-Pfad bekommt einen Claude-Client. (Ursprünglich waren
  beide Pfade geplant; der deprecated, ungenutzte Sync-Client wurde nachträglich wieder
  entfernt — YAGNI.)
- **Default-Modell:** `claude-haiku-4-5` (schnell/günstig, latenz-sensitiver Game-Agent;
  unterstützt `temperature` → Client bleibt symmetrisch zu den anderen).
- **Kein SDK:** Rohes `java.net.http.HttpClient`, wie alle bestehenden Clients.

## Architektur

Strategy-Pattern um `AsyncLLMClient` bleibt unverändert; Claude ist eine weitere Strategie.

### Neue Dateien

1. `llm/async/AsyncClaudeClient.java` — `implements AsyncLLMClient`, Spiegel von
   `AsyncOpenAIClient`, angepasst an die Anthropic Messages-API:
   - Endpoint: `https://api.anthropic.com/v1/messages`
   - Header: `x-api-key: <key>`, `anthropic-version: 2023-06-01`, `content-type: application/json`
   - Body: `{model, max_tokens, temperature, system, messages:[{role:"user", content}]}`
     — `system` ist **Top-Level**, nicht in `messages`.
   - Antwort: `content[0].text`; Tokens = `usage.input_tokens + usage.output_tokens`.
   - `providerId = "claude"`; gleiche Status-Code-Fehlerklassifizierung wie OpenAI-Client.
### Geänderte Dateien

3. `config/SteveConfig.java` — neuer `[anthropic]`-Block (`ANTHROPIC_API_KEY`,
   `ANTHROPIC_MODEL` = `claude-haiku-4-5`); `AI_PROVIDER`-Default → `"claude"`,
   Kommentar erweitert.
4. `llm/TaskPlanner.java` — base `AsyncClaudeClient` (in `ResilientLLMClient` gewrappt)
   instanziieren; `case "claude"` in `getAsyncClient()` (async). Im Async-`params`-Build
   das richtige Modell pro Provider wählen (Claude → `ANTHROPIC_MODEL`, sonst
   `OPENAI_MODEL`). Keys über `orPlaceholder()` robust gegen leere Werte.
5. `config/steve-common.toml.example` — `[anthropic]`-Sektion + `provider = "claude"`.

### Bewusst nicht geändert

- `LLMExecutorService` — `getExecutor()` wird nirgends aufgerufen (toter Pfad).
- Andere Provider-Clients, Resilience-Config (`ResilientLLMClient` baut Resilience-
  Objekte dynamisch aus `getProviderId()`, funktioniert für `"claude"` automatisch).

## Erwägung: temperature

Opus 4.7/4.8 lehnen `temperature` ab (HTTP 400). Default `claude-haiku-4-5` unterstützt
`temperature`, daher keine Sonderlogik nötig (YAGNI). Wird als Kommentar im Client
dokumentiert, falls später auf Opus gewechselt wird.

## Success-Kriterien

- `./gradlew build` grün (kompiliert + bestehende Tests bleiben grün).
- Neuer Unit-Test: Anthropic-Response-JSON → erwarteter Content; Request-Body-Aufbau
  (system top-level, korrekte Header, model/max_tokens) korrekt.
