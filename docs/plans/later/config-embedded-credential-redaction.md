# Redact Value-Embedded Credentials in `GET /mockserver/configuration`

**Status:** Deferred follow-up — the gap is documented and pinned, not silently open.
**Created:** 2026-07-20
**Scope:** Mask credentials that live *inside* a structured configuration value, not just those whose
entire value is a credential.

## The gap

The configuration control plane masks write-only credential properties on read
(`GET /mockserver/configuration` returns `***REDACTED***`). That masking is keyed on the **whole
property name** — see `ConfigurationEnforcementClassificationTest.WRITE_ONLY_CREDENTIALS` and the
guard in `ConfigurationDTOCredentialMaskingTest`. It covers the three name-obvious credentials
(`llmApiKey`, `prometheusRemoteWriteBasicAuthPassword`, `prometheusRemoteWriteBearerToken`).

Two properties added in the configuration-enforcement unit carry secrets **embedded in their
values**, so whole-name masking does not reach them and `GET` returns them in clear once set:

| Property | Where the secret lives |
|----------|------------------------|
| `llmBackendsConfig` | JSON document; each backend entry holds an `apiKey` (parsed at `LlmBackendResolver` `node.path("apiKey")`) |
| `prometheusRemoteWriteHeaders` | arbitrary header list; typically `Authorization` or an `Api-Key` (consumer-docs example: `Api-Key=NRAK-xxxx`) |

On a control plane left unauthenticated (the default), any reader can retrieve these once set. This
is **documented behaviour**, recorded in `changelog.md` (KNOWN GAP bullet) and pinned by
`ConfigurationDTOCredentialMaskingTest.embeddedValueCredentialsAreNotYetMaskedOnRead()`, which
asserts the *current unmasked* behaviour so it is visible and cannot regress to "assumed masked".

## The fix (when picked up)

Per-field / per-header redaction rather than whole-value:

- `llmBackendsConfig` — parse the JSON on read, replace each `apiKey` (and any similar secret field)
  with `ConfigurationProperties.REDACTED_VALUE`, re-serialize. On write (`applyTo`), treat a masked
  `apiKey` the same way whole-value masking is treated today: a masked field is ignored, not written
  over the real one, so a `GET`-then-`PUT` round trip cannot destroy a working key.
- `prometheusRemoteWriteHeaders` — redact the value of any header whose name matches the existing
  sensitive-name shape (`ConfigurationProperties.isSensitivePropertyName` / the `Authorization` /
  `*key*` heuristics), with the same masked-on-write-is-ignored round-trip semantics.

### Landing checklist

1. Implement per-field/per-header redaction on the two DTO getters and the corresponding `applyTo`
   ignore-if-masked logic.
2. Move `llmBackendsConfig` and `prometheusRemoteWriteHeaders` into `WRITE_ONLY_CREDENTIALS` (both
   copies — the classification test keeps them in sync).
3. Flip `ConfigurationDTOCredentialMaskingTest.embeddedValueCredentialsAreNotYetMaskedOnRead()` from
   asserting the leak to asserting redaction (or delete it and let the reflective guard cover them).
4. Update the `changelog.md` KNOWN GAP bullet to record the gap as closed.

## Related (out of scope here, worth capturing)

The `WRITE_ONLY_CREDENTIALS` masking design assumes a credential is either a whole value or a named
field/header. Any future property that embeds secrets in a third shape (e.g. a URL with inline
`user:pass@`) would need the same per-shape treatment — the guard test is name-keyed and will not
catch it automatically.
