# cloud-itonami-isic-0129: Growing Of Other Perennial Crops Coordination Actor

**ISIC Rev. 5 0129** — Growing of Other Perennial Crops

A distributed actor for autonomous, compliant coordination of perennial-crop
cultivation operations: cultivation-lot intake → field/crop-condition survey
→ treatment/scheduling advice → bamboo-culm/cork-bark/ornamental-pruning/spray
field work → cultivation-record logging → compliance audit. Sealed LLM
advisor; independent Governor enforcement; append-only audit ledger. **Not
field-equipment operation.** Harvester/sprayer/applicator operation remains
exclusive to licensed field-equipment operators, and this actor never
finalizes a pesticide-application decision on its own.

## Scope

ISIC 0129 is the **residual (not-elsewhere-classified) perennial-crop growing
division**: perennial crops not already covered by 0121 (grapes), 0122
(tropical/subtropical fruits), 0123 (citrus fruits), 0124 (pome and stone
fruits), 0125 (other tree and bush fruits and nuts), 0126 (oleaginous
fruits), or 0127 (beverage crops). This build's concrete illustrative crops
are **bamboo** (culm harvest), **cork oak** (bark stripping), and
**ornamental/nursery trees and shrubs** — all grown and OWNED by the
operator, which is what distinguishes ISIC 0129 from ISIC 0161 (support
activities for crop production), a contractor that never owns the crop it
services.

This actor coordinates the operator's **own perennial-crop cultivation
operations**:

- Cultivation-record logging (planting/harvest batch, yield/quality data,
  safety/compliance parameters)
- Planting/harvesting/pruning field-operation scheduling proposals
- Crop-health concern escalation (pest/disease, always escalates)
- Fertilizer/pesticide/equipment procurement proposals

**Out of scope:**
- Direct field-equipment operation (harvester, sprayer, applicator —
  exclusive to licensed field-equipment operators)
- Finalizing a pesticide-application decision (permanent, un-overridable
  governor block)
- Custom farm work performed for OTHER farms' crops (that is ISIC 0161,
  support activities for crop production)
- Perennial crops already covered by 0121-0127 (grapes / tropical-
  subtropical fruits / citrus / pome-and-stone fruits / other tree-and-bush
  fruits and nuts / oleaginous fruits / beverage crops)
- Regulatory interpretation (proposals cite jurisdiction specifications; the
  Governor enforces only published requirements)

## Design

### Governor (Independent Compliance Layer)

The Governor is the separation-of-powers enforcement. It never trusts the
advisor's confidence for anything safety- or compliance-relevant, and it
always wins over the advisor.

- **Hard HOLD** (un-overridable):
  - Operation outside the closed allowlist (`:op-not-allowed`) — includes
    any proposal that would amount to direct field-equipment control
  - Proposal asserting an `:effect` other than `:propose`
    (`:effect-not-propose`)
  - Cultivation lot not independently verified/registered in the store —
    applies to ALL FOUR allowed ops (`:cultivation-lot-not-registered`)
  - No jurisdiction citation (`:no-spec-basis`)
  - Evidence checklist incomplete (`:evidence-incomplete`)
  - Pesticide-applicator license expired (`:applicator-license-expired`) —
    only for chemical-application crop-operation types
  - Sprayer/applicator equipment calibration overdue
    (`:sprayer-calibration-overdue`) — only for chemical-application
    crop-operation types
  - Pre-harvest interval violated (`:pre-harvest-interval-violated`) — only
    for chemical-application crop-operation types
  - Restricted-entry interval violated
    (`:restricted-entry-interval-violated`) — only for chemical-application
    crop-operation types
  - Wind speed exceeded the safe spray-drift ceiling
    (`:wind-speed-exceeded`) — only for chemical-application crop-operation
    types
  - Buffer zone narrower than the crop-operation type's minimum
    (`:buffer-zone-violated`) — only for chemical-application crop-operation
    types
  - Proposal covertly requests direct field-equipment control or a final
    pesticide-application decision
    (`:field-equipment-or-pesticide-decision-blocked`) — a HARD, PERMANENT
    block, defense-in-depth against every op
  - Unresolved crop-health concern (`:crop-health-flag-unresolved`)
  - Cultivation lot already logged (`:already-logged`, double-commit guard)
- **Escalate** (human sign-off always required):
  - `:log-cultivation-record` — the one real actuation event this actor
    performs, always requires human sign-off even when the Governor is
    otherwise clean
  - `:flag-crop-health-concern` — a crop-health concern (pest, disease) is
    never auto-resolved by advisor confidence alone
  - `:order-supplies` above `governor/supply-order-cost-threshold-usd`
    (5000 USD)
  - Low advisor confidence (below `governor/confidence-floor`, 0.6)
- **Commit** (advisor proposal approved; Governor clean; not a
  mandatory-escalation op):
  - Routine, low-stakes proposals only — in this actor's current allowlist
    that is effectively `:schedule-field-operation` when clean, or
    `:order-supplies` at or below the cost threshold

### Operations (Proposals)

Closed allowlist — the advisor may **only** ever propose these four
operation types, all `:effect :propose`:

- **`:log-cultivation-record`** — Log planting/harvest batch, yield/quality
  data, plus safety/compliance parameters, into cultivation records (always
  requires human sign-off)
- **`:schedule-field-operation`** — Propose planting/harvesting/pruning
  field-operation scheduling for the operator's own cultivation lot
  (routine, low risk)
- **`:flag-crop-health-concern`** — Surface a crop-health concern (e.g.
  pest infestation, disease); always escalates
- **`:order-supplies`** — Propose fertilizer/pesticide/equipment
  procurement (escalates above the cost threshold)

Any proposal for an operation outside this allowlist — most importantly
anything that would amount to direct field-equipment control — is refused
unconditionally by the Governor (`:op-not-allowed`), regardless of advisor
confidence. Any proposal that covertly requests direct field-equipment
control or a final pesticide-application decision, even nested inside an
otherwise-allowed op, is likewise refused unconditionally
(`:field-equipment-or-pesticide-decision-blocked`).

### Crop-Operation Types

`perennial.facts/crop-operation-types` splits into two safety shapes:

- **Mechanical** (no chemical-application safety window at all):
  `:harvest/bamboo-culm` (bamboo culm harvesting), `:harvest/cork-bark-strip`
  (cork-oak bark stripping), `:maintenance/ornamental-pruning`
  (ornamental/nursery tree and shrub pruning)
- **Chemical-application** (genuine pre-harvest interval, restricted-entry
  interval, maximum safe wind speed, minimum buffer zone):
  `:spray/herbicide-nursery-broadcast`, `:spray/fungicide-ornamental-foliar`,
  `:spray/insecticide-bamboo-ground`

## Testing

```bash
# Run full test suite
clojure -M:test

# Check code quality
clojure -M:lint

# Run demo simulation
clojure -M:run
```

## Standalone Use

This repo is **forkable outside the workspace**. If cloning standalone (not
in the kotoba-lang monorepo), override `:local/root` paths in `deps.edn`:

```clojure
{:deps {io.github.kotoba-lang/langchain {:git/url "https://github.com/kotoba-lang/langchain" :git/tag "v0.1.0"}
        io.github.kotoba-lang/langgraph {:git/url "https://github.com/kotoba-lang/langgraph" :git/tag "v0.1.0"}}}
```

## License

AGPL-3.0-or-later. Forking/contribution welcome; see `CONTRIBUTING.md`.

## Security

Report security issues to the issue tracker or private disclosure; see
`SECURITY.md`.

---

Part of **cloud-itonami**: autonomous actor fleet for regulated industries.
See [github.com/cloud-itonami](https://github.com/cloud-itonami).
