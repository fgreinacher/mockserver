# MockServer website diagram tooling

Scripts to add new architecture / flow diagrams to the website **in the existing
house style** and render them to PNG.

## TL;DR

All site diagrams come from one editable source: **`../MockServerScenarios.pptx`**
(native PowerPoint vector shapes — Office theme, dashed Expectation boxes,
shadowed white action boxes, arrowed connectors). New diagrams are made by
**cloning an existing slide** so they inherit the exact look, then relabelling /
recolouring. PNGs are produced from the slide geometry (no PowerPoint needed).

```sh
python3 build_diagrams.py            # (re)generate the new slides in the .pptx
python3 render_diagrams.py           # render the flow-style diagrams -> PNG
python3 make_response_verification.py# render the architecture-style verify diagram -> PNG
```

Requires `python-pptx` and `Pillow` (`pip3 install python-pptx Pillow`).

## What each script does

| Script | Role |
|--------|------|
| `build_ai_diagrams.py` | **Additive.** Appends the AI / load-injection **flow** diagrams as NEW editable slides by cloning house-style flow slides and relabelling. Idempotent — drops anything at index ≥ `BASELINE` (19) and re-adds. Never touches the existing slides. Run `render_diagrams.py` after it to emit their PNGs. |
| `render_ai_architecture.py` | **Additive.** Builds + renders the **architecture-style** AI diagrams (AI overview, contract verification, centralised deployment) from a single box/arrow spec: emits BOTH a matching editable slide (appended, index ≥ 23) AND the shipped PNG, so slide and image stay in sync. Run after `build_ai_diagrams.py`. |
| `render_diagrams.py` | Renders the **flow-style** diagrams (Expectation/Proxy boxes, matcher, action, cylinders) straight from the slide geometry into PNGs in `../`. Read-only on the .pptx. `SLIDES` maps the current deck's flow-slide indices → output PNGs. |
| `make_response_verification.py` | Renders the **architecture-style** "verify responses" diagram by copying `system_under_test_with_mockserver_proxy.png` and swapping one label, so it matches the "Verifying Requests" diagram exactly. |
| `build_diagrams.py` | **LEGACY — do not run.** It assumes the deck has only 11 originals + 4 generated and would DELETE the now-genuine slides at idx 11–14 (TLS / single-page-app / retrieve-logs) and everything the additive scripts append. It now refuses to run unless `FORCE_REBUILD=1` (and you first re-sync `ORIGINAL_SLIDES` and the clone source indices to the current deck). Kept only as the historical definition of the first four generated diagrams. |

### Current deck layout (slide indices, 0-based)

- **0–14** genuine source slides (architecture + flow vocabulary, incl. TLS, single-page-app, retrieve-logs).
- **15–18** the four originally generated flow PNGs (chaos, LLM mocking, verify-responses, LLM optimise).
- **19–22** AI / load-injection **flow** diagrams appended by `build_ai_diagrams.py`.
- **23–26** AI **architecture** diagrams appended by `render_ai_architecture.py`.

To regenerate everything additively: `python3 build_ai_diagrams.py && python3 render_diagrams.py && python3 render_ai_architecture.py`.

## Current generated diagrams

| PNG | Style | Source slide | Made by |
|-----|-------|--------------|---------|
| `mockserver_chaos_action.png` | flow (action) | clone of slide 4 (error action), red fault arrow | `render_diagrams.py` |
| `mockserver_llm_mocking.png` | flow (action), purple | clone of slide 3 (response action) | `render_diagrams.py` |
| `mockserver_llm_optimisation.png` | flow (proxy), teal | clone of slide 7 (verification) | `render_diagrams.py` |
| `mockserver_response_verification.png` | architecture | clone of slide 2 (verify requests) | `make_response_verification.py` |

## House style reference

- Boxes: rounded `roundRect` (themed `accent1` blue) or plain `rect` (white, black border, drop shadow); Helvetica text.
- Dashed **Expectation** boxes = 3 stacked, offset, black dashed; dashed grey **Expectation / Proxy** box.
- Arrows = straight / bent connectors; default blue `#4F81BD`, response/fault arrows red `#C00000`, step labels dark blue `#1F497D`.
- Recolour a whole diagram by swapping `accent1` → another accent (`accent4` purple, `accent5` teal) in the slide's style refs (see `recolor()`).

## Adding a new diagram

1. In `build_diagrams.py`, add a block: `duplicate_slide(prs, <source idx>)`, then
   `relabel(...)` and optionally `recolor(...)`. Run it.
2. If it's a flow diagram, add its slide index to `SLIDES` in `render_diagrams.py`
   and run it. If it's an architecture diagram, derive it from an existing export
   like `make_response_verification.py` does.
3. Reference the new `images/<name>.png` from the relevant page.

## Pixel-perfect finals

The rendered PNGs match the house style closely and are what the site ships. For
absolute pixel fidelity you can instead open `MockServerScenarios.pptx` in
PowerPoint and `File > Export` the new slides to PNG yourself, then overwrite the
generated files. (Scripted PowerPoint export is unreliable on sandboxed macOS
PowerPoint, which is why these scripts render directly.)
