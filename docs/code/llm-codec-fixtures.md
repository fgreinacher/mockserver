# LLM Codec Golden-File Testing

This document describes the automated golden-file drift-detection process for
LLM provider codecs. Golden files are **codec-generated** (no API keys needed),
normalized to remove volatile fields, and committed to the repository. The test
`LlmCodecGoldenFileTest` asserts them on every test run to catch wire-format
drift in our codecs.

> **Two complementary tests, one deliberate division of labour.** The golden
> bodies are regenerated *from the codec itself* (see *Regenerating golden
> files* below), so on their own they only prove the codec is **consistent with
> itself** — a structural defect (renamed field, wrong SSE event name, dropped
> `finish_reason`) bakes straight into its own golden and the drift test then
> passes forever. Two things break that self-derivation and must **not** be
> weakened by anyone regenerating goldens:
>
> 1. **`LlmCodecGoldenFileTest.shouldEncodeCanonicalTokenUsageCounts`** pins the
>    **token-count values** against hand-authored `Usage` numbers (the goldens
>    normalise usage to `0`).
> 2. **`LlmCodecStructuralContractTest`** pins the **wire body/streaming
>    structure** against hand-authored, provider-schema-sourced expectations. It
>    reads live codec output only — it never reads the golden files and is not
>    affected by `-Dmockserver.updateLlmGoldens=true`. See
>    [Structural contract test](#structural-contract-test-breaks-self-derivation).

## How it works

1. **Fixed canonical inputs** -- the test encodes two fixed `Completion` objects
   (text-completion and tool-call) through each provider codec's `encode()` and
   `encodeStreaming()` methods.
2. **Normalization** -- volatile fields (IDs, timestamps, usage counts) are
   replaced with deterministic placeholders so goldens are stable across runs.
3. **Golden comparison** -- the normalized output is compared byte-for-byte
   against the committed golden file. Any mismatch fails the test with a
   line-by-line diff.
4. **Refresh switch** -- when system property `mockserver.updateLlmGoldens`
   (or env `MOCKSERVER_UPDATE_LLM_GOLDENS`) is `true`, the test writes the
   normalized output to the golden files instead of asserting, then passes.

## Structural contract test (breaks self-derivation)

`LlmCodecStructuralContractTest` is the answer to the golden files'
self-derivation weakness. Where `LlmCodecGoldenFileTest` compares codec output
against a golden **that was generated from that same codec**,
`LlmCodecStructuralContractTest` compares codec output against **hand-authored
structural expectations taken from each provider's published API schema**.

Key properties a future maintainer must preserve:

- **It never reads the golden files.** It encodes the same canonical
  completions and asserts the live codec output directly (like the token-count
  test). Running `-Dmockserver.updateLlmGoldens=true` therefore cannot silence
  it — regenerating goldens does not touch it. **Do not "fix" a failure of this
  test by regenerating goldens.** A failure means the codec no longer matches
  the provider's real wire contract; either the codec regressed (fix the codec)
  or the provider genuinely changed its API (update the hand-authored assertion
  *and* the golden together, deliberately).
- **The assertions are external knowledge, not derived from our output.** They
  pin required fields, JSON types, the enum discriminators each provider uses
  (`object` / `type` / `finish_reason` / `stop_reason` / `finishReason` /
  `status` / `done`), the tool-call envelope shape (arguments as a JSON *string*
  for OpenAI/Responses vs a structured *object* for Anthropic/Gemini/Ollama),
  and the exact SSE event-name sequence for the event-typed providers
  (Anthropic/Bedrock `message_start … content_block_delta … message_stop`;
  Responses `response.created … response.output_text.delta …
  response.completed`).
- **Streaming text is asserted by reassembly, not by chunk boundaries.** The
  test concatenates the streamed deltas and asserts the whole text, so it is not
  coupled to streaming physics / token chunking (which `LlmAgentLoopE2eTest`
  covers over a real socket).

### Coverage and the two families

Seven chat/completion providers are pinned. Because Azure OpenAI delegates to
`OpenAiChatCompletionsCodec` and Bedrock delegates to `AnthropicCodec`, the
assertions are grouped by wire family, so a defect in a base codec is caught for
both the base and the delegating provider.

### Proven to bite

Each defect class named in the coverage-gap ticket was injected into a codec and
confirmed to turn this test red **without** regenerating any golden:

| Provider (family)        | Injected defect                                   | Caught by |
|--------------------------|---------------------------------------------------|-----------|
| OpenAI (→ Azure)         | renamed `finish_reason` → `finishReason`          | non-streaming body |
| Anthropic (→ Bedrock)    | SSE event `content_block_delta` → `content_delta` | streaming framing |
| Gemini                   | dropped `finishReason` emission                   | non-streaming body |
| OpenAI-Responses         | renamed `status` → `state`                        | non-streaming body |
| Ollama                   | renamed terminal `done` → `finished`              | non-streaming body |

## Where fixtures live

```
mockserver/mockserver-core/src/test/resources/llm/fixtures/<provider>/
```

One subdirectory per provider: `anthropic`, `openai`, `openai-responses`,
`gemini`, `bedrock`, `azure-openai`, `ollama`.

Each directory contains:

- `text-completion.json` -- normalized non-streaming encode of a text completion
- `tool-call.json` -- normalized non-streaming encode of a tool-call completion
- `streaming-text.jsonl` -- normalized streaming encode of a text completion (one event per line)
- `streaming-tool-call.jsonl` -- normalized streaming encode of a tool-call completion

## Regenerating golden files

After intentional codec changes, regenerate goldens:

```bash
mvn -pl mockserver/mockserver-core test \
  -Dtest=LlmCodecGoldenFileTest \
  -Dmockserver.updateLlmGoldens=true
```

Or set the environment variable:

```bash
MOCKSERVER_UPDATE_LLM_GOLDENS=true mvn -pl mockserver/mockserver-core test \
  -Dtest=LlmCodecGoldenFileTest
```

Then review the diff with `git diff` and commit the updated golden files
alongside the codec changes.

> **Regeneration cannot make a structural regression pass.**
> `-Dmockserver.updateLlmGoldens=true` rewrites the golden bodies but has **no
> effect on `LlmCodecStructuralContractTest`** (it reads live codec output, not
> the goldens). If that test is red after a codec change, regenerating goldens
> will not turn it green — and should not be attempted as a workaround. Fix the
> codec, or, if the provider genuinely changed its API, update the hand-authored
> structural assertion deliberately in the same change as the golden refresh.

## Asserting golden files (normal test run)

```bash
mvn -pl mockserver/mockserver-core test \
  -Dtest=LlmCodecGoldenFileTest
```

The test also runs as part of the standard `mvn test` suite.

## Normalization rules

The following fields are replaced with deterministic placeholders before
writing/comparing:

| Field | Applies to | Placeholder |
|-------|-----------|-------------|
| `id`, `item_id`, `tool_call_id` | All providers | `"<id>"` |
| String values matching `chatcmpl-*`, `msg_*`, `resp_*`, `call_*`, `toolu_*`, `fc_*` | All providers | `"<id>"` |
| `created` (numeric) | OpenAI, Azure OpenAI, OpenAI Responses | `0` |
| `created_at` (numeric) | OpenAI Responses | `0` |
| `created_at` (ISO 8601 string) | Ollama | `"<timestamp>"` |
| `system_fingerprint` | OpenAI | `"<fp>"` |
| `usage.*`, `usageMetadata.*` (numeric values) | All providers | `0` (structure preserved) |
| `*_duration` fields | Ollama | `0` |
| `prompt_eval_count`, `eval_count`, `prompt_eval_duration`, `eval_duration` | Ollama | `0` |

## Streaming golden file format

Streaming goldens use JSONL (one entry per line):

- **SSE with event types** (Anthropic, OpenAI Responses): each line is a JSON
  object `{"event":"<type>","data":<normalized-json>}` capturing both the SSE
  event name and normalized payload.
- **SSE without event types** (OpenAI, Azure OpenAI, Gemini): each line is the
  normalized JSON payload directly. Non-JSON sentinels (e.g. `[DONE]`) are
  quoted as JSON strings.
- **NDJSON** (Ollama): each line is the normalized JSON chunk directly.
- **AWS_EVENT_STREAM** (Bedrock): uses the same SSE-with-events format as
  Anthropic (since Bedrock delegates to the Anthropic codec internally). The
  binary event-stream framing is not exercised in this test -- it is covered by
  `BedrockEventStreamEncoderTest`.

## Streaming physics

The test passes `null` for `StreamingPhysics` so no timing delays are injected.
This ensures the golden files capture only the wire-format content, not timing
behavior.

## Live provider capture (optional)

The golden-file test does NOT require API keys -- it tests our codec output.
For additional confidence, you can optionally diff against real provider
responses. The `curl` recipes below require real API keys:

### Anthropic

```bash
curl -s https://api.anthropic.com/v1/messages \
  -H "x-api-key: $ANTHROPIC_API_KEY" \
  -H "anthropic-version: 2024-10-22" \
  -H "content-type: application/json" \
  -d '{
    "model": "claude-sonnet-4-20250514",
    "max_tokens": 256,
    "messages": [{"role": "user", "content": "Say hello in one sentence."}]
  }' | jq .
```

### OpenAI Chat Completions

```bash
curl -s https://api.openai.com/v1/chat/completions \
  -H "Authorization: Bearer $OPENAI_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "gpt-4o",
    "messages": [{"role": "user", "content": "Say hello in one sentence."}]
  }' | jq .
```

Focus on structural fields when comparing live output: presence/absence of
keys, value types, nesting depth. Exact token counts, IDs, and timestamps
will differ.

## Refresh cadence

Regenerate goldens whenever codec encode logic changes. Also check when
providers announce breaking API changes. After regenerating, run the full
codec test suite to confirm no regressions:

```bash
mvn -pl mockserver/mockserver-core test \
  -Dtest="*CodecTest" \
  -Dsurefire.failIfNoSpecifiedTests=false
```
