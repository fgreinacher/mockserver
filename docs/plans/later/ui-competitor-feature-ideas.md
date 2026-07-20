# UI competitor-inspired feature ideas — deferred backlog

The actionable findings from the 2026-07 UI detailed review all shipped across ~44 commits
(`9cede20ba..402909e84`). What remains here is the §5 residue: competitor-inspired feature
bets that were never scheduled in the review's own §6 sequencing. They are **not committed
scope** — each needs an individual greenlight before build. File paths and line numbers below
are verified against `mockserver-ui/src` at the time of writing.

---

## 1. GraphQL operation awareness

**Size estimate:** M (~2–3 days)

### Problem / user value

When MockServer receives a GraphQL request, all the meaningful signal is inside the body — the
operation name (`query GetUser { … }`, `mutation CreateOrder { … }`) and the operation type.
Today every GraphQL POST appears in Traffic and Log rows as an opaque `POST /graphql`, so users
cannot filter, breakpoint, or build expectations by operation without inspecting each body
manually.

### Current state

- SDL import exists: `components/GraphqlImportDialog.tsx:28` calls `lib/graphqlImport.ts:23`
  (`PUT /mockserver/graphql`). The dialog generates expectations that match GraphQL queries
  against the schema.
- Traffic rows display host + path only (`TrafficInspector.tsx:687-699`). There is no body
  parsing to extract a GraphQL operation name or type — confirmed: no `operationName`,
  `parseGraphql`, or equivalent symbol appears in `TrafficInspector.tsx`.
- Log rows (`LogEntry.tsx`) similarly carry no GraphQL-specific rendering.

### Proposed approach

1. Add a `parseGraphqlBody(body: string): { operationName: string | null; operationType: 'query' | 'mutation' | 'subscription' | null } | null` helper in `lib/`.
2. In Traffic row summary (`TrafficInspector.tsx` row renderer near line 687), call it when `Content-Type` is `application/graphql` or the body looks like a GraphQL document; surface the operation name as an additional column / badge.
3. Extend `lib/searchMatcher.ts` with an `operation:` field operator so `OperatorSearchField` can filter by name.
4. Expose operation name as a pre-fill in the breakpoint and expectation composer when the source row is a GraphQL request.

### Affected files / areas

`lib/graphqlImport.ts`, `lib/searchMatcher.ts`, `components/TrafficInspector.tsx`,
`components/LogEntry.tsx`, `components/OperatorSearchField.tsx` (docstring / placeholder update),
`components/BreakpointsPanel.tsx`, `components/ComposerView.tsx`

### Dependencies / risks

- Body parsing is client-side only; the server does not currently expose a parsed operation
  name on the log event, so this degrades silently for streamed / compressed bodies.
- Needs a reliable heuristic to avoid false positives on non-GraphQL JSON bodies that contain
  a `query` key.

---

## 2. Host focus / pin + host→path tree view

**Size estimate:** M (~2 days)

### Problem / user value

In proxy mode, a single session can capture traffic from dozens of upstream hosts. The current
Traffic view has no way to narrow down to one host or visualise the host→path shape of recorded
traffic, which is the primary "noise control" tool in Charles Proxy and Proxyman.

### Current state

- Each traffic row carries `summary.host` extracted from the `Host` header
  (`TrafficInspector.tsx:495-538`, `hostFromHeaders` helper). The host is rendered inline in
  each row title (`TrafficInspector.tsx:699`).
- `OperatorSearchField` (`TrafficInspector.tsx:2327`) accepts free-text and the
  `status:`/`method:`/`path:` operators but has no `host:` operator and no group-by-host view.
- No `hostFilter`, `pinnedHost`, or tree grouping exists anywhere in `TrafficInspector.tsx`.

### Proposed approach

1. Add a `host:` operator to `lib/searchMatcher.ts` (mirrors the existing `path:` glob
   operator).
2. Add a collapsible host-tree sidebar in `TrafficInspector.tsx`: group rows by unique host,
   show request count per host, clicking a host pins `host:<value>` into the search field.
3. Optionally persist the pinned host in the store so it survives a view switch.

### Affected files / areas

`lib/searchMatcher.ts`, `components/TrafficInspector.tsx`, `store/index.ts` (optional persist
of pinned host filter), `components/OperatorSearchField.tsx` (docstring update)

### Dependencies / risks

- The host tree is only useful in proxy mode; in mock-only mode all requests target
  `localhost`, so the sidebar would be noise. Consider showing it only when more than one
  distinct host appears.

---

## 3. Named network-condition presets per host

**Size estimate:** S–M (~1–2 days)

### Problem / user value

Teams want to reproduce "this API under 3G" or "this service with flaky wifi" with one click,
bound to a specific upstream host — the same workflow as Charles Proxy's Throttle Presets. The
server-side chaos engine already supports per-host fault injection; what is missing is a set of
named, one-click presets in the UI.

### Current state

- HTTP chaos: `ServiceChaosPanel.tsx` + `lib/serviceChaos.ts`. The `HttpChaosProfileDTO`
  carries `latency` (a `DelayDTO`), `dropConnectionProbability`, `errorStatus`, and
  `errorProbability`. The Quick Chaos strip (`serviceChaos.ts:241`, `QuickChaosMode = 'errors'
  | 'reset' | 'latency'`) is the nearest existing concept — three canned fault modes with a
  shared probability slider.
- TCP chaos: `lib/tcpChaos.ts:14` — `TcpChaosProfileDTO` carries `bandwidthBytesPerSec` and
  `latencyMs`, which together model bandwidth throttling and added latency.
- **Jitter and packet loss are not in any chaos DTO** (neither `HttpChaosProfileDTO` nor
  `TcpChaosProfileDTO` exposes these fields). Any "3G" or "flaky wifi" preset would therefore
  be approximated with latency + bandwidth alone until the server-side engine is extended.
- Named preset definitions do not exist anywhere in the UI.

### Proposed approach

1. Define a `NETWORK_PRESETS` constant (e.g. in `lib/serviceChaos.ts` or a new
   `lib/networkPresets.ts`):

   | Preset | TCP latency (ms) | TCP bandwidth (B/s) | HTTP drop % |
   |---|---|---|---|
   | 3G | 100 | 50 000 | 0 |
   | Flaky wifi | 50 | 500 000 | 5 |
   | High latency | 500 | — | 0 |
   | Packet loss | 20 | — | 10 |

2. Add a preset picker to the TCP chaos form in `ServiceChaosPanel.tsx` that populates the
   latency / bandwidth fields.
3. The per-host binding is already provided by the `host` field in the register form —
   no new server-side changes needed for latency + bandwidth.

### Affected files / areas

`lib/serviceChaos.ts` or new `lib/networkPresets.ts`, `components/ServiceChaosPanel.tsx`

### Dependencies / risks

- True jitter and packet loss require server-side `TcpChaosProfileDTO` extensions before the
  UI presets can expose them; this is a server-side gap, not a UI gap.
- Preset names ("3G", "flaky wifi") carry implicit bandwidth numbers that vary by era; document
  the values explicitly to avoid confusion.

---

## 4. Multi-tab workspaces

**Size estimate:** L (~1 week)

### Problem / user value

Power users running multiple MockServer instances — or investigating separate concerns in
parallel — want independent filter/layout state per tab, the way Charles Proxy's sessions and
Postman's workspaces work. Today the single-page state is shared across the entire window.

### Current state

The store (`store/index.ts`) maintains a single `ViewMode` string (one of 21 named views, line
14), persisted to `localStorage` under `mockserver-view`. There is no tab, workspace, or
per-pane state concept anywhere in the store. The dashboard panels (Log, Expectation, Request)
in `DashboardGrid.tsx` share one search string sourced from the store. The Traffic inspector
similarly holds its filter in local React state scoped to the single mounted component.

### Proposed approach

1. Introduce a `workspaces: WorkspaceState[]` slice in the Zustand store, where each workspace
   holds its own `view`, search strings, and connection params.
2. Render a tab bar above the `DashboardGrid` / main view area; each tab mounts an isolated
   instance.
3. Per-tab connection params would allow targeting different MockServer instances from one
   window — a significant additional value.

### Affected files / areas

`store/index.ts` (new slice), `components/DashboardGrid.tsx`, `App.tsx`, all panels that read
search state from the store, `components/AppBar.tsx` (tab bar placement)

### Dependencies / risks

- This is the highest-risk item: it touches the central Zustand store and every panel that
  reads from it. A botched refactor silently resets filter state on view switch.
- Multiple mounted WebSocket connections (one per tab per MockServer instance) need careful
  lifecycle management to avoid ghost subscriptions.
- Should only be attempted after a full store audit; the existing single-view design is not
  incidentally simple — it was a deliberate prior revert (see Explicitly Rejected below).

---

## 5. Inline execute-and-show-response widget in composer

**Size estimate:** S (~1 day)

### Problem / user value

When building an expectation in the composer, the user wants to immediately fire a real HTTP
request matching that rule and see the live response — "does this matcher actually catch my
request, and does the response look right?" — without switching to a terminal or a separate HTTP
client. Requestly's "Test" button in its rule editor is the competitor analogue.

### Current state

- **Matcher testing** exists: `components/MatcherPlaygroundDialog.tsx:71` is a standalone
  dialog that accepts a request JSON and calls `/mockserver/match` to check which expectations
  match. It is accessible from the composer (`ComposerView.tsx:4770`), the expectation list
  (`ExpectationPanel.tsx:391`), and the global app bar (`AppBar.tsx:844`).
- **Repeat / replay** exists: `RepeatAdvancedDialog.tsx:27` re-sends a previously *captured*
  request N times through MockServer's `/mockserver/retrieve?type=ACTIVE_EXPECTATIONS` +
  replay endpoint. This is for replaying traffic, not for firing a draft expectation.
- What is **missing**: there is no widget that fires a raw HTTP request (or a MockServer
  expectation-shaped request) directly from the composer and shows the response body/status
  inline. The matcher playground checks matching logic but does not execute the response action.

### Proposed approach

1. Add a "Try it" button in `ComposerView.tsx` near the existing "Test matcher" button
   (line 4770).
2. The button opens a small inline panel (not a full dialog) that builds the HTTP request
   from the expectation's `httpRequest` matcher fields, fires it against the MockServer proxy
   port, and renders the status + response body.
3. Optionally pre-fill headers from the composer's current matcher request definition.

### Affected files / areas

`components/ComposerView.tsx`, possibly a new `components/LiveResponseWidget.tsx`

### Dependencies / risks

- The UI must know which port MockServer is listening on as a proxy/mock target (different from
  the control-plane port). This is already available via `connectionParams` but should be
  confirmed for proxy mode.
- CORS: if the dashboard and the mock target are on different ports, the browser will block the
  fetch unless CORS is enabled on the mock port — a user-visible gotcha worth surfacing as a
  warning in the widget.

---

## 6. Full filter-DSL unification

**Size estimate:** M

### Problem / user value

The `status:`/`method:`/`path:` operator syntax in `OperatorSearchField` is only wired to the
Traffic inspector and the three dashboard mini-panels. Breakpoint intercept conditions, chaos
host-targeting, verification scope, and the clear/replay selectors each use their own bespoke
filter widgets with no shared operator vocabulary. Unifying to one DSL means users learn the
syntax once and apply it everywhere.

### Current state

`OperatorSearchField` (`components/OperatorSearchField.tsx:58`) is backed by
`lib/searchMatcher.ts` and is used in:

- `Panel.tsx:100` — drives the three dashboard mini-panels (LogPanel, ExpectationPanel,
  RequestPanel) via the shared `Panel` wrapper.
- `TrafficInspector.tsx:2327` — drives the Traffic inspector's search bar.

It is **not** used in:

- `BreakpointsPanel.tsx` — imports `FilterPanel.tsx`'s `SingleValueField`/`MultiValueField`
  (line 58) for its intercept-condition selectors.
- `VerificationView.tsx`, `ServiceChaosPanel.tsx`, `CassetteManager.tsx` — each has its own
  host/path/method fields.
- The `AuditPanel.tsx` deliberately avoids it (line 66 comment: "traffic-oriented
  status:/method:/path: operators of OperatorSearchField don't [apply to audit entries]").

### Proposed approach

1. Extract a `lib/filterDSL.ts` that generalises `lib/searchMatcher.ts` to support additional
   field namespaces (e.g. `host:`, `operation:`).
2. Replace `BreakpointsPanel.tsx`'s bespoke filter controls with `OperatorSearchField` using a
   restricted operator set relevant to breakpoints.
3. Add an `OperatorSearchField`-based scope input to the verification form and the chaos
   host-select, expanding the shared operator vocabulary as needed.
4. The `AuditPanel.tsx` should remain on its own search path — its entries are control-plane
   operations, not requests, so the traffic operators genuinely do not apply.

### Affected files / areas

`lib/searchMatcher.ts` → `lib/filterDSL.ts` (rename/extend), `components/BreakpointsPanel.tsx`,
`components/VerificationView.tsx`, `components/ServiceChaosPanel.tsx`,
`components/OperatorSearchField.tsx` (docstring update)

### Dependencies / risks

- The operator vocabulary for breakpoints (intercept by method/path/host) differs from chaos
  (target by host only at HTTP level) — a single DSL must handle these without misleading
  users with operators that silently do nothing in a given context.
- Any rename of `lib/searchMatcher.ts` touches three components and the bench tests; plan a
  clean interface shim to avoid a noisy diff.

---

## LOW / trivial notes (unshipped, low priority)

- **List virtualization** — `LogPanel.tsx`, `SessionInspector.tsx`, and `AuditPanel.tsx` use
  plain MUI lists with no windowing (no `react-window` or `@tanstack/virtual`). This only
  causes performance problems if the server-side WebSocket cap (currently ~100 items) is
  raised. Do not virtualize preemptively; re-evaluate if the cap is lifted.
- **Monaco idle prefetch** — `JsonEditorLazy.tsx` loads Monaco on first use; there is no
  `requestIdleCallback`-based prefetch in `main.tsx`. Adding one would eliminate the first-open
  latency, but Monaco is already behind a lazy import so the impact is marginal unless the
  editor is opened repeatedly in a session.
- **`resolveRequest` header clone** — `BreakpointsPanel.tsx:360,373,402` calls
  `client.resolveRequest(correlationId, item.request)` passing the request object directly.
  Cloning headers before mutation would be a defensive nicety, but the current code does not
  mutate the object in place after the call, so this is low risk.
- **ConfigurationDialog** — edits 18 curated properties (`lib/configuration.ts:236`,
  `EDITABLE_PROPERTIES`) out of the ~100 configuration keys available on the server. This is
  deliberate: only properties that are safe to change at runtime and meaningful to a typical
  user are exposed. Expanding the list to all runtime-mutable props risks overwhelming the UI
  with low-value entries.

---

## Explicitly rejected — do not reopen

**Pinned / recent view tabs** — a tab bar with pinned and recent views was implemented in
commit `d536433f1` and then **deliberately reverted** in `d55d7077d`. This is a closed
decision. The §4 Multi-tab workspaces idea above is a different concept (independent connection
state per tab) and should not be confused with the reverted nav-tab feature.
