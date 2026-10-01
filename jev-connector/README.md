# Boomi JEV Connector

A Boomi custom connector that sends text-based documents to the [JEV](https://huggingface.co/blog/sora-2/jev-ai-api-tutorial-build-your-first-structured-de) decision API and returns a structured, confidence-gated result for each document. It handles auth, request building, retries and response parsing, so a Boomi process only has to route on `DECIDED` vs `NEEDS_REVIEW`.

```
Start ─► [JEV · Review Document] ─► Decision (status = DECIDED?) ─► auto-route
                                             └──────────────────► human review
```

## Build

Requirements: JDK 11+ and Maven 3.6+. The Boomi SDK is pulled from `https://boomisdk.s3.amazonaws.com/releases`, which is already declared in the POM.

```bash
cd jev-connector
mvn clean verify
```

Outputs:

| File | Purpose |
|---|---|
| `target/jev-connector-1.0.0-car.zip` | Connector archive (CAR): upload as the connector **archive** |
| `src/main/resources/connector-descriptor.xml` | Upload as the connector **descriptor** (it is also inside the CAR under `META-INF/`) |

The bytecode targets Java 11, so it runs on any current Boomi runtime.

## Deploy to Boomi

1. **Settings → Account Information and Setup → Publisher**: fill in publisher details (one-time setup).
2. **Developer → Connector Groups → Add**: upload the CAR and the descriptor, then pick a group/connector name such as *JEV*.
3. In a process, add a connector shape, choose the JEV connector, and create a **Connection** and a **Review Document** operation.
4. Optional: on the operation, use **Import** to generate a typed JSON response profile from your Question Set.

## Connection fields

All of these are ordinary connection fields, so they can be set per environment through **Environment Extensions**.

| Field | Default | Notes |
|---|---|---|
| Base URL | `https://api.typesafe.ai` | JEV host. Published docs disagree on the host, so set yours. |
| Decision Endpoint Path | `/v1/systemone` | |
| API Key | — | Password field, never logged |
| Auth Header Name | `Authorization` | |
| Auth Scheme | `Bearer` | Blank sends the raw key |
| Default Model | `jev-latest` | |
| Connect / Read Timeout (ms) | 10000 / 60000 | |
| Max Retries | 2 | Retries 429/502/503/504 and network errors; honours `Retry-After` |

**Test Connection** sends a one-question request to check the URL, key and model.

## Operation: Review Document

| Field | Default | Per-document override* |
|---|---|---|
| Request Mode | Document Review | |
| Question Set (JSON) | — | ✔ |
| Model | *(connection default)* | ✔ |
| Document Format | Auto-detect | |
| State Wrapper Key | *(none)* | |
| Confidence Threshold | `0.8` | ✔ |
| Include Raw JEV Response | false | |
| Max Document Size (KB) | 1024 | |

\* Fields marked overrideable can be set per document with a **Set Properties** shape (Dynamic Operation Properties). This lets one process run different question sets.

**Request modes**
- **Document Review**: the input document becomes JEV `state` and the Question Set is attached.
- **Raw Request**: the input document already is a full JEV request (`state` + `questions`, `model` optional).

**Document Format** (Document Review only)
- **Auto-detect**: a JSON object or array is sent as JSON; anything else (plain text, CSV, XML, Markdown…) is sent as a string.
- **JSON**: the document must be valid JSON.
- **Text**: always sent as a string.

Binary documents (containing NUL bytes or invalid UTF-8) are rejected before any JEV call.

### Question Set example

```json
{
  "department": {
    "type": "choice",
    "instructions": "Which team should handle this request?",
    "criteria": {
      "billing": "Payments, invoices, refunds, or payouts",
      "technical": "Bugs, outages, or integration failures",
      "sales": "Pricing, upgrades, or new accounts"
    }
  },
  "needs_human": { "type": "noul",  "instructions": "Does this request require human review?" },
  "urgency":     { "type": "score", "instructions": "How urgent is this request?" }
}
```

### Output document

```json
{
  "status": "DECIDED",
  "model": "jev-1.13.0",
  "confidenceThreshold": 0.8,
  "results": {
    "department":  { "type": "choice", "value": "billing", "confidence": 0.92, "passed": true,
                     "probabilities": { "billing": 0.94, "technical": 0.05, "sales": 0.01 } },
    "needs_human": { "type": "noul", "value": true, "probability": 0.87, "confidence": 0.87, "passed": true }
  },
  "reviewReasons": [],
  "usage": { "input_tokens": 180, "output_tokens": 24 }
}
```

- `status` is `NEEDS_REVIEW` when any answer is below the threshold, missing, or has no selected option. `reviewReasons` says which and why.
- **choice** confidence is JEV's `confidence`, falling back to the top probability.
- **noul** `value` is `probability >= 0.5`; confidence is JEV's `confidence`, falling back to `max(p, 1-p)`.
- **score** passes through JEV's value; it is gated only if JEV returns a `confidence`.

### Document results

Every input document produces exactly one result, so a single bad document never fails the batch.

| Status | Code | When |
|---|---|---|
| Success | `200` | JEV answered; message is `DECIDED` / `NEEDS_REVIEW` |
| Application Error | `INVALID_INPUT` | Binary/oversized/empty document, bad Question Set, bad threshold |
| Application Error | HTTP status (e.g. `401`, `422`, `429`) | JEV returned an error; JEV's body is the payload |
| Application Error | `CONNECTION_ERROR` | JEV unreachable after retries |
| Application Error | `INVALID_RESPONSE` | JEV returned non-JSON |

## Project layout

```
src/main/java/com/boomi/custom/jev/
  JevConnector.java          entry point (connector-config.xml)
  JevConnection.java         connection fields → JevClient
  JevBrowser.java            object type, profile import, Test Connection
  JevReviewOperation.java    EXECUTE operation, one JEV call per document
  client/                    HTTP client, settings, retry
  review/                    document reading, question set, request building, result mapping, output schema
src/test/java/...            unit tests + end-to-end tests via the SDK ConnectorTester and an in-process fake JEV server
```

## Assumptions to verify against your JEV account

The request and response shapes follow the published JEV examples (`state` / `model` / `questions` in; `answers.<id>` with `choice` / `noul` / `score`, `probabilities`, `confidence` out). If your account's API differs, the changes are isolated to `RequestBuilder` and `ResultMapper`.
