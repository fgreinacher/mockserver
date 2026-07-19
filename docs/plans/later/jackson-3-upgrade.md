# Jackson 2 → Jackson 3 Upgrade — Blocked Upstream

**Status:** Blocked — waiting on a Jackson 3 release line of `io.swagger.parser.v3:swagger-parser`. Our own side of the migration is understood and mechanical; nothing is blocked on MockServer.
**Created:** 2026-07-19
**Last verified against upstream:** 2026-07-19
**Scope:** Migrate MockServer's own serialization from `com.fasterxml.jackson.*` (Jackson 2) to `tools.jackson.*` (Jackson 3).
**Driver:** Spring Boot 4 ships Jackson 3, so consumers increasingly run Jackson 3 in their applications. Requested in [#1970](https://github.com/mock-server/mockserver-monorepo/issues/1970) and [#2426](https://github.com/mock-server/mockserver-monorepo/issues/2426) (closed as duplicate).

## The blocker

`swagger-parser` backs OpenAPI mock loading — a core MockServer feature we cannot drop — and has no Jackson 3 release. It transitively pins `swagger-core`, so both stay on Jackson 2.

**Upstream trackers to watch:**

| Tracker | State (2026-07-19) |
|---------|--------------------|
| [swagger-parser#2261](https://github.com/swagger-api/swagger-parser/issues/2261) — "Upgrade to Jackson 3" | Open since 2026-01-08. **Zero comments**, no PR, no branch. This is the one that actually gates us. |
| [swagger-core#4991](https://github.com/swagger-api/swagger-core/issues/4991) — "Support Jackson 3.x release line" | Open since 2025-10-10, no milestone. |
| [swagger-core#5031](https://github.com/swagger-api/swagger-core/pull/5031) — "migrate to jackson 3" (PR, 132 files) | Open since 2026-01-01. Merge conflicts against master; no maintainer response in 6+ months. |
| [swagger-core#5225](https://github.com/swagger-api/swagger-core/issues/5225) — "future support for Jackson 3" | Opened 2026-07-03 specifically about the lack of maintainer engagement on the above. |

**Versions at last check:**

| Library | Latest | Released | Jackson |
|---------|--------|----------|---------|
| `io.swagger.parser.v3:swagger-parser` | 2.1.45 | 2026-06-23 | `com.fasterxml` (2.x) only |
| `io.swagger.core.v3:swagger-core` | 2.2.52 | 2026-06-22 | `com.fasterxml` (2.x) only |
| `com.fasterxml.jackson.core:jackson-core` | 2.22.1 | 2026-07-07 | — (our current pin: `jackson.version` 2.22.1) |
| `tools.jackson.core:jackson-core` | 3.2.1 | 2026-07-10 | — |

**Rejected workaround:** `io.github.vpelikh` publishes a Jackson 3 fork of the swagger-core stack (4.0.0, 2026-07-06, confirmed on `tools.jackson.*`), but it ships **no `swagger-parser` artifact**. Swapping core alone still leaves the parser dragging the whole Jackson 2 tree in, so it does not unblock us.

## What is *not* blocking

- **Java version.** 7.0.0 moved to Java 17, clearing Jackson 3's Java 17 floor. This was the stated blocker in #1970 and no longer applies — swagger-parser is now the *only* one.
- **Two Jackson namespaces coexisting.** Already proven in production code: `com.networknt:json-schema-validator` 3.x (currently 3.0.6) runs on `tools.jackson` inside MockServer today — migrated in commit `2449bcab1` — alongside our Jackson 2 usage. Different Maven coordinates and different Java packages mean they cannot collide.
- **Consumers on Jackson 3.** For the same reason, MockServer depending on Jackson 2 does not prevent a Spring Boot 4 application from using Jackson 3. Reports of `ClassNotFoundException: com.fasterxml.jackson.databind.JsonSerializer` are Jackson 2 being *excluded* by a BOM, not a genuine incompatibility.

## Our side of the work, when unblocked

Mechanical, with no exotic API surface to redesign:

1. Namespace migration `com.fasterxml.jackson.*` → `tools.jackson.*` across the codebase (~361 files at the time of the #1970 investigation).
2. Update `jackson.version` / `jackson-annotations.version` in `mockserver/pom.xml` to the Jackson 3 coordinates. Note Jackson 3 keeps annotations on `com.fasterxml.jackson.core:jackson-annotations` — that split is expected, not a mistake.
3. Revisit the shade relocations in `mockserver/pom.xml` — `com.fasterxml` → `shaded_package.com.fasterxml` and `tools.jackson` → `shaded_package.tools.jackson` both already exist; the balance between them shifts.
4. Drop the Jackson 2/3 bridging currently needed in `JsonSchemaValidator` (string-based `getSchema` / `validate` calls exist purely to stop Jackson 2 `JsonNode` crossing into Jackson 3 APIs — see `2449bcab1`). Once everything is Jackson 3 this can go back to node-based calls.
5. Re-check `mockserver-client-java`'s parser exclusions and the `dependencyConvergence` guarantees established in 7.1.0 (#1970) — the transitive tree changes shape.

Major-version-worthy: it changes the transitive dependency coordinates of every published artifact.

## Re-check trigger

Re-evaluate when **swagger-parser publishes a Jackson 3 line**. Cheapest probe:

```bash
curl -s https://repo1.maven.org/maven2/io/swagger/parser/v3/swagger-parser/maven-metadata.xml | grep latest
# then fetch that version's POM and grep for tools.jackson
```

Watch [swagger-parser#2261](https://github.com/swagger-api/swagger-parser/issues/2261) — nothing else moves until that does.
