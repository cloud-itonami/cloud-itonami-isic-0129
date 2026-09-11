(ns perennial.governor
  "Perennial-Crop Operations Governor -- the independent compliance layer
  that earns the PerennialAdvisor the right to commit. The LLM has no
  notion of:
    - Whether a cultivation lot has been independently verified/registered
      in the store at all, for ANY of this actor's four proposal ops
    - Whether the applicator's pesticide-application license is current
      (only meaningful for chemical-application crop-operation types)
    - Whether the sprayer/applicator equipment's calibration is current
      (only meaningful for chemical-application crop-operation types)
    - Whether the gap between application and this lot's expected harvest
      date meets the crop-operation type's pre-harvest interval (only
      meaningful for chemical-application crop-operation types)
    - Whether the planned gap before workers re-enter a treated field
      meets the crop-operation type's restricted-entry interval (only
      meaningful for chemical-application crop-operation types)
    - Whether the actual wind speed at time of application exceeded the
      crop-operation type's maximum safe spray-drift wind speed (only
      meaningful for chemical-application crop-operation types)
    - Whether the actual buffer-zone distance to the nearest sensitive
      site met the crop-operation type's minimum (only meaningful for
      chemical-application crop-operation types)
    - Whether a proposal is covertly requesting direct field-equipment
      control or a final pesticide-application decision
    - Whether a previously-raised crop-health concern has been resolved
    - Whether a cultivation lot has already been logged (double-commit)

  This MUST be a separate system able to *reject* a proposal and fall back
  to HOLD.

  Unlike direct field-equipment operation (harvester, sprayer, applicator
  -- NEVER done by this actor) or finalizing a pesticide-application
  decision (NEVER done by this actor -- both are HARD, permanent governor
  blocks, never overridable by human approval), the Governor operates on
  cultivation-lot metadata: field identity, crop-operation parameters, and
  safety/compliance flags. This is perennial-crop-cultivation FARM
  OPERATIONS COORDINATION, not direct field-equipment operation authority.

  CRITICAL: `:flag-crop-health-concern` ALWAYS escalates to human sign-off
  at every phase, regardless of advisor confidence -- a pest/disease
  concern is never auto-resolved by advisor confidence alone.

  Hard violations (always HOLD, no override):
    1. Operation outside the closed allowlist (`:op-not-allowed`) --
       includes any proposal that would amount to direct field-equipment
       control
    2. Proposal asserting an `:effect` other than `:propose`
       (`:effect-not-propose`)
    3. Cultivation lot not independently verified/registered in the store
       -- applies to ALL FOUR allowed ops
       (`:cultivation-lot-not-registered`)
    4. No jurisdiction citation (`:no-spec-basis`)
    5. Evidence checklist incomplete (`:evidence-incomplete`)
    6. Pesticide-applicator license expired (`:applicator-license-expired`
       -- only when the crop-operation type is a chemical application)
    7. Sprayer/applicator equipment calibration overdue
       (`:sprayer-calibration-overdue` -- only when the crop-operation
       type is a chemical application)
    8. Pre-harvest interval violated (`:pre-harvest-interval-violated` --
       only when the crop-operation type is a chemical application)
    9. Restricted-entry interval violated
       (`:restricted-entry-interval-violated` -- only when the
       crop-operation type is a chemical application)
   10. Wind speed exceeded the safe spray-drift ceiling
       (`:wind-speed-exceeded` -- only when the crop-operation type is a
       chemical application)
   11. Buffer zone narrower than the crop-operation type's minimum
       (`:buffer-zone-violated` -- only when the crop-operation type is a
       chemical application)
   12. Proposal covertly requests direct field-equipment control or a
       final pesticide-application decision
       (`:field-equipment-or-pesticide-decision-blocked` -- a HARD,
       PERMANENT block, never overridable by human approval, evaluated
       against every op as defense-in-depth even though those actions are
       already outside the closed allowlist)
   13. Unresolved crop-health concern (`:crop-health-flag-unresolved`)
   14. Cultivation lot already logged (`:already-logged`, double-commit
       guard)

  Soft gates (always escalate for human):
    - Low confidence
    - `:log-cultivation-record` -- the one real actuation event this
      actor performs (logging completed planting/harvest batch,
      yield/quality data into records)
    - `:flag-crop-health-concern` -- never auto-resolved by confidence
      alone
    - `:order-supplies` above the cost threshold
      (`supply-order-cost-threshold-usd`)

  This design mirrors `cropsupport.governor` (ISIC 0161, support
  activities for crop production) in overall shape but specializes on
  perennial-crop-CULTIVATION safety concerns -- applicator licensing,
  equipment calibration, pre-harvest/restricted-entry intervals,
  spray-drift wind, and buffer zones -- for the operator's OWN bamboo,
  cork-oak, and ornamental/nursery crop, never a client farm's."
  (:require [perennial.facts :as facts]
            [perennial.registry :as registry]
            [perennial.store :as store]))

(def confidence-floor 0.6)

(def supply-order-cost-threshold-usd
  "Supply orders (fertilizer/pesticide/equipment procurement) at or below
  this cost may auto-commit when the Governor is otherwise clean; orders
  above this threshold always require human sign-off, regardless of
  advisor confidence."
  5000)

(def high-stakes
  "Stakes grave enough to always require a human, even when clean.
  Logging a completed cultivation record (`:log-cultivation-record`) is
  the one real-world actuation event this actor performs -- it commits
  planting/harvest batch, yield/quality data (and, transitively, the
  safety/compliance facts that accompanied it) into the permanent
  record."
  #{:log-cultivation-record})

(def always-escalate-ops
  "Operations that always require human sign-off, even when the
  Governor's hard checks are clean and confidence is high: the high-
  stakes actuation event (`high-stakes`) plus `:flag-crop-health-concern`
  -- a crop-health concern (pest, disease) is never auto-resolved by
  advisor confidence alone, it always needs a human look."
  (conj high-stakes :flag-crop-health-concern))

(def allowed-ops
  "Closed allowlist of proposal operations this actor may ever make. Any
  proposal for an operation outside this set -- most importantly direct
  field-equipment operation (harvester, sprayer, applicator) -- is a
  hard, permanent block: this actor coordinates perennial-crop
  cultivation operations, it does not operate field equipment."
  #{:log-cultivation-record :schedule-field-operation :flag-crop-health-concern :order-supplies})

;; ────────────────────────── Checks ──────────────────────────

(defn- op-not-allowed-violations
  "HARD, permanent block: any proposal outside the closed operation
  allowlist (e.g. direct field-equipment operation) is refused
  unconditionally -- this actor has no authority to make such a proposal
  at all, let alone commit it."
  [{:keys [op]} _proposal]
  (when-not (contains? allowed-ops op)
    [{:rule :op-not-allowed
      :detail (str op " はこのactorの許可された提案種別 (log-cultivation-record/"
                  "schedule-field-operation/flag-crop-health-concern/order-supplies) "
                  "に含まれない -- 圃場機器の直接操作はこのactorに無い")}]))

(defn- effect-not-propose-violations
  "HARD invariant: this actor's proposals are always `:effect :propose` --
  it never claims direct write/actuation authority for itself. A proposal
  asserting any other effect is refused unconditionally."
  [_request proposal]
  (when-let [effect (:effect proposal)]
    (when (not= effect :propose)
      [{:rule :effect-not-propose
        :detail (str "この actor の提案は :propose 以外の :effect を持てない (got " effect ")")}])))

(defn- cultivation-lot-not-registered-violations
  "HARD invariant: a cultivation-lot/field record must be independently
  verified/registered in the store BEFORE any of this actor's four
  proposal ops can be made against it -- coordinating work for a lot this
  actor never checked in is out of scope. Evaluated across ALL FOUR
  allowed ops, not just one."
  [{:keys [op subject]} st]
  (when (contains? allowed-ops op)
    (when-not (store/cultivation-lot-registered? st subject)
      [{:rule :cultivation-lot-not-registered
        :detail (str subject " は独立に検証・登録されたcultivation-lot記録が無い -- いかなる提案も進められない")}])))

(defn- spec-basis-violations
  "A proposal with no jurisdiction citation is a HARD violation -- never
  invent a jurisdiction's perennial-crop cultivation safety
  requirements."
  [{:keys [op]} proposal]
  (when (contains?
         #{:log-cultivation-record :order-supplies :flag-crop-health-concern}
         op)
    (let [value (:value proposal)]
      (when (or (empty? (:cites proposal))
                (and (contains? value :jurisdiction) (nil? (:jurisdiction value))))
        [{:rule :no-spec-basis
          :detail "公式仕様の引用が無い提案は法域要件として扱えない"}]))))

(defn- evidence-incomplete-violations
  "For `:log-cultivation-record`, verify the cultivation lot's evidence
  checklist is complete per jurisdiction requirements."
  [{:keys [op subject]} st]
  (when (= op :log-cultivation-record)
    (let [l (store/cultivation-lot st subject)]
      (when-not (and l
                     (facts/required-evidence-satisfied?
                      (:jurisdiction l)
                      (:evidence-checklist l)))
        [{:rule :evidence-incomplete
          :detail "法域の必要書類(cultivation-lot-record/field-boundary-map/application-record/applicator-license等)が充足していない状態での提案"}]))))

(defn- applicator-license-expired-violations
  "For `:log-cultivation-record`, INDEPENDENTLY verify the applicator's
  license has not expired via `registry/applicator-license-expired?`.
  Only evaluated when the crop-operation type actually requires a
  license (chemical-application crop-operation types) -- mechanical
  harvest/maintenance crop-operation types have nothing to check here,
  never a fabricated requirement."
  [{:keys [op subject]} st now-ms]
  (when (= op :log-cultivation-record)
    (let [l (store/cultivation-lot st subject)
          ct (when l (facts/crop-operation-type-by-id (:crop-operation-type l)))]
      (when (and l ct (:chemical-application? ct) (:applicator-license-expiry-date l)
                 (registry/applicator-license-expired? (:applicator-license-expiry-date l) now-ms))
        [{:rule :applicator-license-expired
          :detail (str subject " の農薬散布者資格(applicator license)が失効している -- 記録提案は進められない")}]))))

(defn- sprayer-calibration-overdue-violations
  "For `:log-cultivation-record`, INDEPENDENTLY verify the
  sprayer/applicator equipment's calibration is current via
  `registry/sprayer-calibration-overdue?`. Only evaluated when the
  crop-operation type actually requires calibration
  (chemical-application crop-operation types)."
  [{:keys [op subject]} st now-ms]
  (when (= op :log-cultivation-record)
    (let [l (store/cultivation-lot st subject)
          ct (when l (facts/crop-operation-type-by-id (:crop-operation-type l)))]
      (when (and l ct (:chemical-application? ct) (:sprayer-last-calibration-date l)
                 (registry/sprayer-calibration-overdue? (:sprayer-last-calibration-date l) now-ms))
        [{:rule :sprayer-calibration-overdue
          :detail (str subject " の散布機器の校正が期限切れ -- 記録提案は進められない")}]))))

(defn- pre-harvest-interval-violated-violations
  "For `:log-cultivation-record`, INDEPENDENTLY verify that the gap
  between application and this lot's expected harvest date meets the
  crop-operation type's pre-harvest interval via
  `registry/pre-harvest-interval-violated?`. Only evaluated when the
  crop-operation type actually has a pre-harvest-interval spec
  (chemical-application crop-operation types) -- mechanical
  harvest/maintenance crop-operation types have nothing to check here,
  never a fabricated target."
  [{:keys [op subject]} st]
  (when (= op :log-cultivation-record)
    (let [l (store/cultivation-lot st subject)
          ct (when l (facts/crop-operation-type-by-id (:crop-operation-type l)))]
      (when (and l ct (:pre-harvest-interval-days ct) (:days-until-harvest l)
                 (registry/pre-harvest-interval-violated?
                  (:days-until-harvest l)
                  (:pre-harvest-interval-days ct)))
        [{:rule :pre-harvest-interval-violated
          :detail (str subject " の収穫前日数(" (:days-until-harvest l)
                      "日)が農薬使用基準の収穫前日数を下回る -- 記録提案は進められない")}]))))

(defn- restricted-entry-interval-violated-violations
  "For `:log-cultivation-record`, INDEPENDENTLY verify that the planned
  gap before workers re-enter a treated field meets the crop-operation
  type's restricted-entry interval via
  `registry/restricted-entry-interval-violated?`. Only evaluated when the
  crop-operation type actually has a restricted-entry-interval spec."
  [{:keys [op subject]} st]
  (when (= op :log-cultivation-record)
    (let [l (store/cultivation-lot st subject)
          ct (when l (facts/crop-operation-type-by-id (:crop-operation-type l)))]
      (when (and l ct (:restricted-entry-interval-hours ct) (:hours-until-reentry l)
                 (registry/restricted-entry-interval-violated?
                  (:hours-until-reentry l)
                  (:restricted-entry-interval-hours ct)))
        [{:rule :restricted-entry-interval-violated
          :detail (str subject " の再入場までの猶予(" (:hours-until-reentry l)
                      "時間)が再入場禁止期間基準を下回る -- 記録提案は進められない")}]))))

(defn- wind-speed-exceeded-violations
  "For `:log-cultivation-record`, INDEPENDENTLY verify that the actual
  wind speed at time of application did not exceed the crop-operation
  type's maximum safe spray-drift wind speed via
  `registry/wind-speed-exceeded?`. Only evaluated when the crop-operation
  type actually has a wind-speed ceiling."
  [{:keys [op subject]} st]
  (when (= op :log-cultivation-record)
    (let [l (store/cultivation-lot st subject)
          ct (when l (facts/crop-operation-type-by-id (:crop-operation-type l)))]
      (when (and l ct (:max-wind-speed-kmh ct) (:wind-speed-kmh l)
                 (registry/wind-speed-exceeded?
                  (:wind-speed-kmh l)
                  (:max-wind-speed-kmh ct)))
        [{:rule :wind-speed-exceeded
          :detail (str subject " の散布時風速(" (:wind-speed-kmh l)
                      "km/h)が飛散防止基準を超過 -- 記録提案は進められない")}]))))

(defn- buffer-zone-violated-violations
  "For `:log-cultivation-record`, INDEPENDENTLY verify that the actual
  distance maintained to the nearest sensitive site met the
  crop-operation type's minimum buffer zone via
  `registry/buffer-zone-violated?`. Only evaluated when the
  crop-operation type actually has a buffer-zone minimum."
  [{:keys [op subject]} st]
  (when (= op :log-cultivation-record)
    (let [l (store/cultivation-lot st subject)
          ct (when l (facts/crop-operation-type-by-id (:crop-operation-type l)))]
      (when (and l ct (:min-buffer-zone-m ct) (:buffer-zone-actual-m l)
                 (registry/buffer-zone-violated?
                  (:buffer-zone-actual-m l)
                  (:min-buffer-zone-m ct)))
        [{:rule :buffer-zone-violated
          :detail (str subject " の緩衝地帯距離(" (:buffer-zone-actual-m l)
                      "m)が最小基準を下回る -- 記録提案は進められない")}]))))

(defn- field-equipment-or-pesticide-decision-blocked-violations
  "HARD, PERMANENT block, defense-in-depth: any proposal whose `:value`
  covertly requests direct field-equipment control
  (`:operate-field-equipment?` true) or a final pesticide-application
  decision (`:finalize-pesticide-application-decision?` true) is refused
  unconditionally, regardless of which op it is nominally filed under and
  regardless of advisor confidence. Never overridable by human approval
  -- this is a scope boundary, not a risk judgment."
  [_request proposal]
  (let [value (:value proposal)]
    (when (or (true? (:operate-field-equipment? value))
              (true? (:finalize-pesticide-application-decision? value)))
      [{:rule :field-equipment-or-pesticide-decision-blocked
        :detail "圃場機器の直接操作または農薬散布の最終決定はこのactorの範囲外 -- 恒久的にブロックされる"}])))

(defn- crop-health-flag-unresolved-violations
  "An unresolved crop-health flag is a HARD, un-overridable hold. Crop-
  health concerns (suspected pest infestation, disease) raised during
  cultivation must be resolved before the cultivation lot can be logged.
  Evaluated UNCONDITIONALLY at `:log-cultivation-record`."
  [{:keys [op subject]} st]
  (when (= op :log-cultivation-record)
    (let [l (store/cultivation-lot st subject)]
      (when (and (true? (:crop-health-concern-raised? l))
                 (not (true? (:crop-health-concern-resolved? l))))
        [{:rule :crop-health-flag-unresolved
          :detail (str subject " は未解決の作物健全性フラグがある -- 記録提案は進められない")}]))))

(defn- already-logged-violations
  "For `:log-cultivation-record`, refuse to log the SAME cultivation lot
  twice, off a dedicated `:logged?` fact (never a `:status` value)."
  [{:keys [op subject]} st]
  (when (= op :log-cultivation-record)
    (when (store/cultivation-lot-already-logged? st subject)
      [{:rule :already-logged
        :detail (str subject " は既に記録済み")}])))

(defn- now-epoch-ms
  "Current time in epoch milliseconds, portable across Clojure/
  ClojureScript. Isolated to this single call site so the rest of the
  namespace (and all of `perennial.registry`) stays free of host-clock
  calls."
  []
  #?(:clj (System/currentTimeMillis)
     :cljs (js/Date.now)))

(defn- high-cost-supply-order?
  "Soft-gate helper: a `:order-supplies` proposal escalates to a human
  unless its `:cost-usd` can be established to be BELOW `supply-order-cost-threshold-usd`.

  Note the direction. This gate used to read `:cost-usd` out of the
  advisor's OWN proposal and escalate only when that number exceeded
  the threshold, which made the gate's only input the very number it
  existed to doubt:

    - an advisor understating bought itself an auto-commit wherever
      `:order-supplies` was `:auto`-eligible -- no human saw it;
    - `(some-> amount (> threshold))` returned nil when the field was
      ABSENT, so omitting `:cost-usd` skipped the gate entirely.

  There is no filed catalog in this actor's store to recompute the
  figure from -- the advisor states it directly -- so a self-declared
  value cannot be verified. An unverifiable number is worthless as a
  DE-escalation signal: it may raise the alarm, it must never silence
  it. The gate now escalates whenever the value is absent, non-numeric,
  or above the threshold, and stands down only for one that is present,
  numeric and below it."
  [{:keys [op]} proposal]
  (when (= op :order-supplies)
    (let [v (get-in proposal [:value :cost-usd])]
      (or (not (number? v))
          (> v supply-order-cost-threshold-usd)))))

(defn check
  "Censors a PerennialAdvisor proposal against the Governor rules.
  Returns {:ok? bool :violations [..] :confidence c :escalate? bool
  :high-stakes? bool :hard? bool}.

  Stakes (high-stakes actuation vs. always-escalate vs. high-cost supply
  order) are read off the REQUEST's `:op` (and, for supply-order cost,
  the proposal's own declared value) -- not off the advisor's self-
  reported stake -- since the operation being proposed is what determines
  whether a human must sign off."
  [request _context proposal st]
  (let [now-ms (now-epoch-ms)
        hard (into []
                   (concat (op-not-allowed-violations request proposal)
                           (effect-not-propose-violations request proposal)
                           (cultivation-lot-not-registered-violations request st)
                           (spec-basis-violations request proposal)
                           (evidence-incomplete-violations request st)
                           (applicator-license-expired-violations request st now-ms)
                           (sprayer-calibration-overdue-violations request st now-ms)
                           (pre-harvest-interval-violated-violations request st)
                           (restricted-entry-interval-violated-violations request st)
                           (wind-speed-exceeded-violations request st)
                           (buffer-zone-violated-violations request st)
                           (field-equipment-or-pesticide-decision-blocked-violations request proposal)
                           (crop-health-flag-unresolved-violations request st)
                           (already-logged-violations request st)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        actuation? (boolean (high-stakes (:op request)))
        escalate-op? (or (boolean (always-escalate-ops (:op request)))
                          (boolean (high-cost-supply-order? request proposal)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not escalate-op?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? escalate-op?))
     :high-stakes? actuation?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
