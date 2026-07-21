# `recoverAfter` on FORWARD actions — deferred (out of scope for the S tail)

**Status:** Investigated, deliberately **not implemented**. Recorded rather than half-done.
**Created:** 2026-07-21
**Scope:** Whether the `recoverAfter` retry/backoff recovery primitive (fail N times then succeed) can/should apply to `FORWARD`-family actions, not just `httpResponse`.
**Origin:** feature-value-analysis 2026-07 tail ("Close documented v1 deferrals: … `recoverAfter` on FORWARD"). The plan flagged its FORWARD coverage as *PARTIAL/unverified*.

## Outcome / Decision (TL;DR)

`recoverAfter` is **structurally an `HttpResponse`-only clause** and today applies to the `RESPONSE`
action — both the early-response and main dispatch paths call `selectRecoveryResponse(...)` inside
their `case RESPONSE` arms. It is **not expressible** on `RESPONSE_TEMPLATE` (`HttpTemplate`) or
`RESPONSE_CLASS_CALLBACK` (`HttpClassCallback`) — those actions carry no `HttpResponse` — nor on any
`FORWARD`-family action, for the same reason, and the forward dispatch never consults it. Extending it
to forwards is therefore **not a partial implementation with a small gap** — it is an absent-by-model
capability whose addition is an **M–L cross-cutting change**, not an S tail item. It is deferred.

## Evidence (verified 2026-07-21)

- `getRecoverAfter()` / `withRecoverAfter(...)` exist **only** on
  `mockserver-core/.../model/HttpResponse.java`. No forward action model
  (`HttpForward`, `HttpForwardTemplate` = `HttpTemplate`, `HttpForwardValidateAction`,
  `HttpForwardWithFallback`, `HttpOverrideForwardedRequest`) has the field.
- `HttpActionHandler.selectRecoveryResponse(...)` is called from exactly two sites, **both in a
  `case RESPONSE` arm** (`HttpActionHandler.java:233` early-response, `:549` main dispatch);
  `RESPONSE_TEMPLATE` and `RESPONSE_CLASS_CALLBACK` dispatch straight through
  `dispatchMockResponseWithBreakpoint` with no recovery selection. The six
  `dispatchForwardWithBreakpoint(...)` call sites (`FORWARD`, `FORWARD_TEMPLATE`,
  `FORWARD_CLASS_CALLBACK`, `FORWARD_REPLACE`, `FORWARD_VALIDATE`, `FORWARD_WITH_FALLBACK`) never
  reference `recoverAfter`.
- So a user cannot even construct "a forward that fails N times then succeeds" today — the clause
  has no home on a forward expectation.

## Why not implement now

1. **Cost is M–L, not S.** Making `recoverAfter` work on forwards means adding the field (plus its
   `RecoverAfterDTO` wiring, DTO serializer, and JSON-schema plumbing — the `recoverAfter.json`
   sub-schema is already loaded by the three validators `JsonSchemaExpectationValidator`,
   `JsonSchemaHttpRequestAndHttpResponseValidator`, `JsonSchemaHttpResponseValidator`, but a forward
   action's schema would need to reference it too) to the forward action models, then threading a
   `selectRecovery*`-equivalent selection into each of the six forward dispatch sites (they yield an
   `HttpForwardActionResult`, not an `HttpResponse`, so the "serve failResponse for the first N, then
   forward" logic is a different shape from the response path). Five of those six dispatch sites use a
   forward-specific model (`HttpForward`, `HttpTemplate` for `FORWARD_TEMPLATE`,
   `HttpForwardValidateAction`, `HttpForwardWithFallback`, `HttpOverrideForwardedRequest`); the sixth,
   `FORWARD_CLASS_CALLBACK`, reuses `HttpClassCallback` (shared with the response class-callback), so
   adding the field there would bleed into the response side. That is a broad, multi-file surface with
   its own round-trip tests per action type.
2. **Semantic overlap with FORWARD chaos.** Forward actions already have a fault-injection surface —
   `HttpChaosProfile` (`errorProbability`, `errorStatus`, `dropConnectionProbability`, time/degradation
   windows) is applied on the forward path via `forwardChaos`. "Fail the first N forward attempts with
   a synthetic error, then forward for real" is a count-windowed variant of what chaos already
   expresses; adding a parallel `recoverAfter` mechanism on forwards risks two overlapping ways to say
   nearly the same thing, and needs a deliberate design decision (unify vs. duplicate) rather than a
   mechanical port.
3. **Half-doing it is worse.** Implementing `recoverAfter` on only one forward action (e.g.
   `HttpForward`) would ship an inconsistent surface where the same clause works on some forward types
   and silently no-ops on others — exactly the kind of trap the plan's "record precisely rather than
   half-do" guidance warns against.

## If picked up later

Treat as a single M–L unit with an explicit design gate first: decide whether forward "fail-then-recover"
is a new `recoverAfter`-on-forward field or a count-window extension of `HttpChaosProfile`. If the
former, add the field + DTO + serializer wiring + forward-action schema references to the
forward-specific action models in one change (and decide how to treat the shared `HttpClassCallback`
used by `FORWARD_CLASS_CALLBACK`), thread a forward-shaped recovery selection into every
`dispatchForwardWithBreakpoint` call site, and add per-action round-trip + behavioural tests mirroring
`HttpActionHandlerRecoverAfterTest`. Keep the
`httpResponse` semantics (1-based attempt count, optional `idempotencyHeader` via
`RecoveryAttemptRegistry`, default `503` failResponse) identical so the clause behaves consistently
across action families.
