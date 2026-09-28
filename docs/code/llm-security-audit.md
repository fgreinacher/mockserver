# LLM Mocking Feature -- Security Audit (M5)

**Date:** 2026-05-26
**Scope:** All code introduced in M0 (fa2a5bb05) through M4 (24668ed1d)
**Method:** Manual code review with targeted grep sweeps. CodeQL was not run locally (not installed); this audit covers the same categories.

## Summary

No vulnerabilities found. Previously known limitations (Ollama NDJSON, Bedrock binary framing) have been resolved. All checked categories passed.

## What was checked

### 1. Debug output leaks (`System.out.print` / `System.err.print`)

**Result: PASS** -- Zero instances found in any LLM-related source file.

Files checked:
- All files under `mockserver-core/src/main/java/org/mockserver/llm/`
- `HttpLlmResponse.java`, `Completion.java`, `ToolUse.java`, `StreamingPhysics.java`, `EmbeddingResponse.java`, `ConversationPredicates.java`
- `HttpLlmResponseActionHandler.java`, `LlmConversationMatcher.java`
- `LlmMockBuilder.java`, `LlmConversationBuilder.java`, `TurnBuilder.java`

### 2. JSON injection in hand-built SSE chunks

**Result: PASS** -- Every `withData(...)` call in every codec's `encodeStreaming()` method routes user-provided strings through `JsonEscape.escape()` (aliased as `escapeJson()` in each codec).

Audit of each codec:

| Codec | User strings in `withData()` | Escaped via |
|-------|------------------------------|-------------|
| `AnthropicCodec` | `modelName`, `token` (text chunks), `toolCall.getName()`, tool `args` | `escapeJson()` -> `JsonEscape.escape()` |
| `OpenAiChatCompletionsCodec` | `model`, `token`, `toolCall.getName()`, tool `args`, `finishReason` | `escapeJson()` via `buildChunk()` |
| `OpenAiResponsesCodec` | `modelName`, `token`, `text`, `toolCall.getName()`, tool `args` | `escapeJson()` |
| `GeminiCodec` | `token`, `modelName`, `toolCall.getName()`, tool `args` | `escapeJson()`; args also re-serialised via Jackson `writeValueAsString` |
| `BedrockCodec` | Delegates to `AnthropicCodec` | Same as Anthropic |
| `AzureOpenAiCodec` | Delegates to `OpenAiChatCompletionsCodec` | Same as OpenAI |
| `OllamaCodec` | `modelName`, `token` (text chunks) | `escapeJson()`; final chunk uses `OBJECT_MAPPER.writeValueAsString()` |

`JsonEscape.escape()` handles the seven RFC 8259 short escapes and emits `\\uXXXX` for control characters below U+0020. This is sufficient to prevent JSON injection in SSE data lines.

### 3. Secrets in log messages

**Result: PASS** -- No API keys, tokens, credentials, or authorization headers are logged anywhere in the LLM code paths.

The only body content that appears in logs is a truncated 256-byte sample in `LlmConversationMatcher` at DEBUG level, which is appropriate for diagnostics and does not include headers.

### 4. `@JsonIgnore` on sensitive fields

**Result: PASS** -- `HttpLlmResponse.conversationMatcher` (a transient evaluation-time object) is annotated with `@JsonIgnore` and declared `transient`. The `getType()` method is also `@JsonIgnore`. No sensitive fields (API keys, secrets) exist on any LLM model class.

### 5. Body-size cap enforcement

**Result: PASS** -- `LlmConversationMatcher.matches()` checks `request.getBodyAsRawBytes().length` against `ConfigurationProperties.maxLlmConversationBodySize()` **before** calling `codec.decode(request)`. Bodies exceeding the cap are treated as no-match and logged at DEBUG.

The cap is enforced with clamping in `ConfigurationProperties` (range: 16 KiB to 64 MiB, default 1 MiB).

### 6. Unbounded user input in error responses

**Result: PASS** -- Error responses in `HttpLlmResponseActionHandler` interpolate only:
- `provider` (a Java enum, always a safe constant name like `ANTHROPIC`)
- `provider.name()` (same as above)
- The literal string `null`
- `supportedProvidersJson()` which builds an array from enum names

No user-supplied strings (request bodies, headers, paths) are interpolated into error JSON.

### 7. Random ID generation

**Result: PASS (acceptable for test utility)** -- Random IDs (e.g., `msg_*`, `chatcmpl-*`, `toolu_*`) use `java.util.UUID.randomUUID()` which is backed by `SecureRandom` on modern JVMs. The `StreamingPhysicsExpander` uses `java.util.Random` for jitter timing, which is appropriate (timing jitter is not a security-sensitive value).

The embedding `deterministicFromInput()` deliberately uses `java.util.Random` seeded from a SHA-256 hash for reproducibility. This is a test utility, not a security primitive.

## Known limitations

### Ollama NDJSON wire format (RESOLVED)

**Resolved.** `OllamaCodec` now declares `StreamingFormat.NDJSON` and the `HttpSseResponseActionHandler` emits raw `<json>\n` lines (no SSE `data:` prefix) for Ollama streaming responses.

### BedrockCodec binary framing (RESOLVED)

**Compatibility limitation — actionable for raw HTTP clients.**

**Resolved** in G14. `BedrockCodec` now declares `StreamingFormat.AWS_EVENT_STREAM` and the `HttpSseResponseActionHandler` encodes each streaming chunk as a binary AWS event-stream message via `BedrockEventStreamEncoder`. Each message carries headers (`:event-type=chunk`, `:content-type=application/json`, `:message-type=event`), CRC32 integrity checks (prelude and message), and a payload of `{"bytes":"<base64(chunkJson)>"}` matching the `InvokeModelWithResponseStream` wire format. Raw (non-SDK) Bedrock streaming clients now work against MockServer.

The limitation is documented in the `BedrockCodec` javadoc. Not a security concern.

### Runtime LLM client — Bedrock SigV4 signing (RESOLVED)

**Resolved.** `BedrockLlmClient` now implements automatic AWS Signature Version 4 request signing via `AwsSigV4Signer`, a pure, stateless signer using only JDK crypto (SHA-256, HmacSHA256 -- no third-party dependencies).

**Credential sourcing:** AWS credentials are parsed from `LlmBackend.apiKey()` in the format `accessKeyId:secretAccessKey` (or `accessKeyId:secretAccessKey:sessionToken` for STS temporary credentials). When `apiKey` is null, blank, or does not contain a `:` separator, signing is skipped and the original escape-hatch behaviour is preserved (backward compatible).

**Region extraction:** The region is parsed from the `baseUrl` host (`bedrock-runtime.<region>.amazonaws.com`); defaults to `us-east-1` if the host does not match. The AWS service is `bedrock`.

**Authorization precedence:** When SigV4 credentials are present, the auto-generated `Authorization` header takes precedence over any `Authorization` supplied via the `LlmBackend.headers()` escape hatch. The escape hatch remains fully supported for pre-signed / signing-proxy setups when no credentials are provided.

**Test verification:** The signing-key derivation is verified against the AWS-published test vector (secret `wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY`, date `20120215`, region `us-east-1`, service `iam` -- expected signing key hex `f4780e2d9f65fa895f9c67b32ce1baf0b0d8a43505a000a1a9e090d414db404d`). Full end-to-end signing is tested for structural correctness, determinism, session-token inclusion, and body-sensitivity. The signing timestamp is injectable for offline test determinism.

### Gemini API key in query string (runtime client)

The runtime-LLM `GeminiLlmClient` passes the API key as a `?key=` query parameter, as Gemini's API-key auth requires. Unlike header credentials, query-string keys can surface in HTTP access/proxy logs. This is a property of the provider's API, not a MockServer choice; front the call with a gateway that injects the key after ingress in high-security environments. Documented in the `GeminiLlmClient` javadoc. Not a MockServer defect.

### Gemini tool-call argument re-serialisation

The `GeminiCodec.encodeStreaming()` method re-serialises tool-call arguments through Jackson (`OBJECT_MAPPER.readTree` + `writeValueAsString`) before embedding them in the SSE chunk. If the arguments are not valid JSON, they are wrapped in a `{"value":"<escaped>"}` object. This is a safe fallback that prevents malformed arguments from corrupting the JSON chunk. No action required.

### `whenContainsToolResultFor` E2E false-negative for Gemini and Ollama — RESOLVED

**Resolved.** This was previously reported as an E2E-only false-negative (the matcher unit tests passed for all providers, but the predicate was believed to fail through the full Netty pipeline for Gemini/Ollama turn-2 requests). It no longer reproduces: `LlmAgentLoopE2eTest.shouldMatchContainsToolResultForGeminiEndToEnd` and `…ForOllamaEndToEnd` drive turn 2 purely via `whenContainsToolResultFor` (not scenario ordering), through the real Netty pipeline, and both pass — Gemini's name-keyed correlation and Ollama's positional fallback work end-to-end. These regression tests guard against recurrence. (The earlier behaviour was fixed by subsequent matcher/codec work; the body is delivered to the matcher correctly E2E, as the Anthropic/OpenAI/Azure/Bedrock predicate-driven E2E tests also demonstrate.) Not a security issue.

## Outbound prompt redaction — `generateExpectation` and drift (2026-09-28)

**Outcome:** Prompts built for an external LLM backend are redacted before they leave the process. This closes a gap where `PUT /mockserver/generateExpectation`, with an LLM backend configured, sent the unmatched request's `Authorization`, `Cookie` and API-key headers, up to 2,000 characters of body, and the paths of up to 10 existing expectations to a third-party service unredacted.

Redaction here is **always on** and **independent of `redactSecretsInLog`** — that setting governs the local event log, whereas this governs data leaving the process to a third party — and it operates on **copies**: the served request, the event log and the returned expectations are never mutated.

### What is redacted before a prompt is sent

| Element | Rule |
|---------|------|
| Request / context headers | Values of `Authorization`, `Proxy-Authorization`, `Cookie`, `Set-Cookie`, `x-api-key`, `api-key` (reusing `FixtureRedactor.defaultSensitiveHeaders()`), plus `X-Auth-Token`, `X-Access-Token`, `X-Amz-Security-Token`, `X-Csrf-Token` and the configured `dataPlaneApiKeyAuthenticationHeader`, matched case-insensitively, are replaced with `FixtureRedactor.REDACTED_PLACEHOLDER` (`***REDACTED***`). Header names are kept; a multi-value header is collapsed to one masked value. The extra headers are added in `LlmPromptRedactor` only — `FixtureRedactor`'s defaults are unchanged. |
| Query string | Sensitive query parameters (`key`, `api_key`, `apikey`, `access_token`, `token`, `signature`, …) masked via `FixtureRedactor` (defence in depth). |
| JSON body | Values of credential-like fields (`password`, `passwd`, `secret`, `token`, `access_token`, `refresh_token`, `id_token`, `client_secret`, `api_key`, `apikey`, `authorization`, …) masked at any depth. Every JSON string leaf is also swept: a stringified-JSON value (`{"data":"{\"password\":\"x\"}"}`) is parsed and redacted recursively, and `key=value` credentials inside a string leaf are masked. |
| JWTs | `eyJ`-prefixed three-segment base64url tokens masked anywhere in a body. |
| URL userinfo | `scheme://user:pass@host` → `scheme://***REDACTED***@host` in request path, context paths and bodies. |
| Non-JSON body | `key=value` credential pairs (same field set) masked in form / plain-text bodies. A field matches when it is not preceded by an ASCII letter or digit (so `my_password=` / `x-api_key=` match) and is a whole word (so `tokenizer=on` is not treated as `token`). |
| Unparseable JSON | A body that declares or looks like JSON but cannot be parsed is **dropped** (`[body omitted: could not be parsed for redaction]`) — fail closed. |
| Binary body | Omitted from the prompt. |
| Body length | Redacted body then truncated to 2,000 characters. |

Implemented in `org.mockserver.llm.LlmPromptRedactor`, applied by `StubGenerationPromptBuilder` (constructed with the configured data-plane API-key header name in `HttpState.handleGenerateExpectation`) and by `SemanticDriftExtension.buildPrompt` (drift analysis sends response bodies and drift record values, not headers, so it redacts those). Header and query masking reuse `FixtureRedactor`; JSON-tree field masking, stringified-JSON descent, JWT, URL-userinfo and non-JSON `key=value` masking are done in `LlmPromptRedactor` because a prompt is free text rather than a structured expectation.

**Note on `EmbeddedCredentialRedaction`:** despite its name it masks configuration credentials by field/header *name* only and, by its own documented design, does **not** redact credentials embedded in a URL. URL-userinfo masking for prompts is therefore implemented in `LlmPromptRedactor`, not delegated to that class.
