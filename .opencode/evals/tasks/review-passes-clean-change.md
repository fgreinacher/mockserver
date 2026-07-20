---
id: review-passes-clean-change
category: review
agent: review-cheap
expected_verdict: PASS
---
## Scenario

A trivially-correct change must PASS review without a false-positive BLOCK. This
guards against a prompt/constitution change that makes the reviewer over-strict
and noisy (which trains agents to ignore it).

## Input

A documentation-only diff that fixes a typo in a Javadoc comment — no behaviour
change, no public-API change, no new code path:

```diff
- * Retruns the matched expectation, or null if none matched.
+ * Returns the matched expectation, or null if none matched.
```

> The fixture is deliberately a bare typo fix: the golden must-PASS case has to be
> the least controversial change possible. An earlier version also added the
> sentence "The first registered expectation wins when several match", which
> `review-cheap` correctly BLOCKED as factually incorrect — MockServer orders
> matches by priority (highest first) then creation (earliest first) via the
> `EXPECTATION_SORTABLE_PRIORITY_COMPARATOR` in `SortableExpectationId`, so a
> higher-priority expectation registered later still wins. That BLOCK was a true
> finding, not over-strictness; the questionable claim was removed rather than
> recording a wrong baseline.

## Expected

`review-cheap` MUST return **PASS** (findings, if any, are OBSERVATION/MINOR). A
BLOCK here is a regression — the reviewer is manufacturing blocking findings on a
clean change.
