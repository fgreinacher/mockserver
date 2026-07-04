# Analytics scripts

Tooling for the dashboard's cookieless usage analytics (PostHog Cloud EU). See
the feature itself in `mockserver-ui/src/lib/analytics.ts` and the consumer
disclosure page `jekyll-www.mock-server.com/mock_server/dashboard_privacy.html`.

## `create_posthog_dashboard.py`

Provisions the **"MockServer - UI Feature Usefulness"** PostHog dashboard so its
definition lives in version control instead of being hand-clicked.

### Why a script (and not the ingest key)

The analytics key baked into released images (`phc_...`) is a **write-only
ingest key** - it can send events but cannot create dashboards or insights.
Creating them needs a **personal API key** (`phx_...`), which you mint yourself
at *PostHog -> Settings -> Personal API keys*, scoped to `insight:write` +
`dashboard:write` for the project. Keys are never committed.

### Run

```bash
export POSTHOG_PERSONAL_API_KEY=phx_...        # required; revoke it when done
export POSTHOG_HOST=https://eu.posthog.com     # optional (default EU app host)
export POSTHOG_PROJECT_ID=209901               # optional (auto-resolved if unset)

python3 scripts/analytics/create_posthog_dashboard.py            # create it
python3 scripts/analytics/create_posthog_dashboard.py --dry-run  # print payloads, POST nothing
```

It prints the new dashboard URL on success. Re-running creates a **new**
dashboard each time (PostHog has no natural unique key for dashboards) - delete
the previous one in the UI if you are iterating on the tiles.

### The 9 tiles

| # | Tile | Answers |
|---|------|---------|
| 1 | Feature usefulness - ranked (uses / reach / **adoption %** / depth) | the headline: which features the most sessions actually use |
| 2 | Most-used features (volume) | raw usage counts |
| 3 | Feature reach (distinct sessions) | breadth vs. volume |
| 4 | Most-visited tabs (`view_change`) | which of the 21 dashboard tabs get opened |
| 5 | Feature usage trend (daily) | which features are growing / fading |
| 6 | Quick vs Advanced mode | which path users take |
| 7 | Friction - errors by category | a feature that errors a lot is not "useful" |
| 8 | Engagement - features per session | how deeply a session engages |
| 9 | Distribution & version mix | context for reading the numbers |

Tiles are SQL insights (HogQL), so they render as tables by default - use the
chart-type selector to switch the ranking / trend tiles to bar or line. The
rolling window is 30 days; edit the `WINDOW` constant in the script to change it.

### What the data can and cannot tell you

The analytics is **cookieless** (`persistence:'memory'`), so `distinct_id` is a
fresh random id per page-load. That means:

- `count(DISTINCT distinct_id)` = **unique sessions**, never people.
- **No cross-session retention / DAU-of-people** is possible - the tiles are
  deliberately usage-, adoption-, and trend-based, not stickiness-based.
- Ingestion goes directly to `eu.i.posthog.com`, which ad-blockers block, so
  real numbers **undercount** users on uBlock / Brave / Firefox-strict.

### Event schema the tiles read

```
app_open     {app_version, surface, distribution, theme}
view_change  {view}          # one of the 21 tabs (store ALL_VIEWS)
feature_used {feature, mode}  # feature in a closed 7-value set; mode quick|advanced
error_shown  {category}       # coarse bucket, never a free-text message
```
