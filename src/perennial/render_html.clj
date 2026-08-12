(ns perennial.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2: this repo previously had NO demo page
  and no generator at all. This namespace drives the REAL actor stack
  (`perennial.operation/run-operation` -> `perennial.governor/check` ->
  `perennial.store`) through a scenario and renders the resulting store
  deterministically.

  Provenance, stated plainly because it matters for how the page should
  be read: this repo ships NO seed/demo data. `perennial.store` has no
  `seed-db`/`demo-data`, and `perennial.sim` (`clojure -M:dev:run`,
  which was run BEFORE this file was written) is still a stub that
  prints `not yet implemented`. So the cultivation lots below are seeded
  HERE, by `seed-store`. What is NOT invented here is everything the
  Governor actually reasons about: crop-operation types and their safety
  windows, jurisdictions and their evidence checklists, the confidence
  floor, the supply-order cost threshold, the closed op allowlist, and
  the phase sequence are all read at run time out of `perennial.facts` /
  `perennial.governor` / `perennial.phase`, and every lot's safety
  parameter is derived arithmetically from its own crop-operation type's
  published window (e.g. a pre-harvest-interval violation is seeded as
  `(dec pre-harvest-interval-days)` of that very type, never a typed-in
  number). Every disposition, hold, rule name and violation detail on
  the page is the Governor's own output for that request.

  Determinism: `render` is a pure function of the post-run store. No
  clock read, no timestamp and no random value reaches the page --
  license/calibration dates exist only as epoch millis inside the store
  (needed because `perennial.governor` calls the host clock itself), and
  the page shows the day OFFSET that produced them instead. Collections
  are sorted explicitly rather than relying on map iteration order, so
  two runs are byte-identical.

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [jp-go-dds.skin]
            [perennial.facts :as facts]
            [perennial.governor :as governor]
            [perennial.operation :as op]
            [perennial.phase :as phase]
            [perennial.store :as store]))

;; ───────────────────────────── seeding ─────────────────────────────

(def ^:private day-ms (* 24 60 60 1000))

(def ^:private context
  "The Governor-side context every request is driven with.
  `:hold-fact-fn` is what `perennial.operation/run-operation` calls to
  mint the audit fact for a non-passing verdict."
  {:actor-id "perennial-governor-1"
   :hold-fact-fn governor/hold-fact})

(def ^:private approver
  "The human who signs off escalated proposals in this scenario. There
  is no operator directory in this repo, so this id originates here --
  see the approver-attribution section of the rendered page, which
  measures where (if anywhere) it survives."
  "operator-01")

(def ^:private full-evidence
  "Every evidence item any jurisdiction in `perennial.facts/jurisdictions`
  requires -- derived from the catalog, not typed in."
  (vec (sort (distinct (mapcat :required-evidence (vals facts/jurisdictions))))))

(def ^:private partial-evidence
  "`full-evidence` minus its last two items: a checklist that cannot
  satisfy any jurisdiction in the catalog."
  (vec (drop-last 2 full-evidence)))

(defn- chem-window
  "Safety parameters for a clean chemical-application lot, derived from
  that crop-operation type's OWN published window in `perennial.facts`."
  [ct-id]
  (let [ct (facts/crop-operation-type-by-id ct-id)]
    {:applicator-license-offset-days 365
     :sprayer-calibration-offset-days -10
     :days-until-harvest (+ (:pre-harvest-interval-days ct) 6)
     :hours-until-reentry (+ (:restricted-entry-interval-hours ct) 12)
     :wind-speed-kmh (- (:max-wind-speed-kmh ct) 6.0)
     :buffer-zone-actual-m (+ (:min-buffer-zone-m ct) 5.0)}))

(def ^:private lot-specs
  "Ordered cultivation-lot seeds. `:demonstrates` describes what the
  scenario does with the lot (it documents the driver below, not the
  domain). `:override` values are all functions of the lot's own
  crop-operation-type window, so the seeded violation is exactly one
  step past the published limit.

  `:expects` is the scenario's DECLARED INTENT for that lot -- the exact
  set of HARD rules it is built to trip. It is authored (that is what
  makes it a claim) but it is not decorative: `-main` refuses to write
  the page unless the Governor's actual output for that subject matches
  it exactly, so the prose in `:demonstrates` cannot quietly drift away
  from what the code does."
  [{:id "lot-001" :field-id "field-42" :ha 3.5
    :ct :spray/herbicide-nursery-broadcast :j :jp/maff
    :expects #{:already-logged}
    :demonstrates "全 clean。schedule → order-supplies(閾値以下) → log(人間承認) → 二重 log で :already-logged"}
   {:id "lot-002" :field-id "field-77" :ha 8.0
    :ct :harvest/bamboo-culm :j :jp/maff
    :expects #{}
    :demonstrates "機械作業 clean。低 confidence の schedule、cost 未申告の order-supplies、log(人間承認)"}
   {:id "lot-003" :field-id "field-08" :ha 1.2
    :ct :spray/fungicide-ornamental-foliar :j :us/epa
    :override {:applicator-license-offset-days -3}
    :expects #{:applicator-license-expired}
    :demonstrates "散布者資格が 3 日前に失効 → :applicator-license-expired"}
   {:id "lot-004" :field-id "field-11" :ha 6.4
    :ct :spray/insecticide-bamboo-ground :j :eu/reg1107
    :override {:sprayer-calibration-offset-days -120}
    :expects #{:sprayer-calibration-overdue}
    :demonstrates "散布機器の校正が 120 日前(基準 90 日) → :sprayer-calibration-overdue"}
   {:id "lot-005" :field-id "field-15" :ha 2.1
    :ct :spray/herbicide-nursery-broadcast :j :jp/maff
    :override-fn (fn [ct] {:days-until-harvest (dec (:pre-harvest-interval-days ct))})
    :expects #{:pre-harvest-interval-violated}
    :demonstrates "収穫前日数が基準 -1 日 → :pre-harvest-interval-violated"}
   {:id "lot-006" :field-id "field-19" :ha 0.9
    :ct :spray/fungicide-ornamental-foliar :j :us/epa
    :override-fn (fn [ct] {:hours-until-reentry (dec (:restricted-entry-interval-hours ct))})
    :expects #{:restricted-entry-interval-violated}
    :demonstrates "再入場猶予が基準 -1 時間 → :restricted-entry-interval-violated"}
   {:id "lot-007" :field-id "field-23" :ha 12.0
    :ct :spray/insecticide-bamboo-ground :j :jp/maff
    :override-fn (fn [ct] {:wind-speed-kmh (+ (:max-wind-speed-kmh ct) 6.0)})
    :expects #{:wind-speed-exceeded}
    :demonstrates "散布時風速が上限 +6.0 km/h → :wind-speed-exceeded"}
   {:id "lot-008" :field-id "field-27" :ha 4.7
    :ct :spray/herbicide-nursery-broadcast :j :eu/reg1107
    :override-fn (fn [ct] {:buffer-zone-actual-m (- (:min-buffer-zone-m ct) 7.0)})
    :expects #{:buffer-zone-violated}
    :demonstrates "緩衝地帯が最小 -7.0 m → :buffer-zone-violated"}
   {:id "lot-009" :field-id "field-31" :ha 5.3
    :ct :harvest/cork-bark-strip :j :jp/maff
    :evidence partial-evidence
    :expects #{:evidence-incomplete}
    :demonstrates "必要書類 2 点欠落 → :evidence-incomplete(機械作業でも法域要件は効く)"}
   {:id "lot-010" :field-id "field-35" :ha 1.8
    :ct :maintenance/ornamental-pruning :j :jp/maff
    :concern? true
    :expects #{:crop-health-flag-unresolved}
    :demonstrates "未解決の作物健全性フラグ。flag は人間承認、log は :crop-health-flag-unresolved"}
   {:id "lot-011" :field-id "field-39" :ha 2.6
    :ct :spray/fungicide-ornamental-foliar :j :jp/maff
    :expects #{:no-spec-basis :effect-not-propose
               :field-equipment-or-pesticide-decision-blocked :op-not-allowed}
    :demonstrates "scope 系の hard 検査用 clean lot。引用無し / :effect 偽装 / 機器操作要求 / 許可外 op"}])

(def ^:private unregistered-lot-id
  "Deliberately NOT seeded into the store -- the registration invariant
  must hold for a subject the actor never checked in."
  "lot-012")

(def ^:private unregistered-lot-expects
  "Declared intent for `unregistered-lot-id`, checked the same way as
  every seeded lot's `:expects`."
  #{:cultivation-lot-not-registered})

(def hard-rule-catalog
  "Every hard rule `perennial.governor` is able to mint, read out of the
  Governor's OWN source on the classpath rather than re-typed here. This
  is what lets the page say `14 of 14` instead of `14`, and it grows by
  itself the day a fifteenth rule is added.

  Honest about its own reach: this is a textual scan for `:rule :x`
  violation literals in `perennial/governor.cljc`. A rule minted from
  another namespace, or with a computed keyword, would not appear. Both
  would be a departure from how this Governor is written today, and the
  per-subject `:expects` invariant would still hold the scenario to what
  actually fires."
  (->> (slurp (io/resource "perennial/governor.cljc"))
       (re-seq #":rule\s+:([a-z0-9?!-]+)")
       (map (comp keyword second))
       distinct
       sort
       vec))

(defn- build-lot
  "Materialize one `lot-specs` entry into a store record. Day offsets are
  turned into epoch millis against `base-ms` here (the Governor reads the
  host clock itself, so the store has to speak epochs); the offsets stay
  on the record under descriptive keys the Governor ignores, so the page
  can show a stable number instead of a timestamp."
  [{:keys [ct j field-id ha evidence concern? override override-fn]} base-ms]
  (let [ct-map (facts/crop-operation-type-by-id ct)
        chem? (true? (:chemical-application? ct-map))
        spec (cond-> {:crop-operation-type ct
                      :jurisdiction j
                      :field-id field-id
                      :field-area-hectares ha
                      :evidence-checklist (or evidence full-evidence)}
               concern? (assoc :crop-health-concern-raised? true)
               chem? (merge (chem-window ct))
               (and chem? override) (merge override)
               (and chem? override-fn) (merge (override-fn ct-map)))]
    (cond-> spec
      (:applicator-license-offset-days spec)
      (assoc :applicator-license-expiry-date
             (+ base-ms (* day-ms (:applicator-license-offset-days spec))))
      (:sprayer-calibration-offset-days spec)
      (assoc :sprayer-last-calibration-date
             (+ base-ms (* day-ms (:sprayer-calibration-offset-days spec)))))))

(defn seed-store
  "A fresh store holding every lot in `lot-specs` and an empty ledger."
  []
  (let [base-ms (System/currentTimeMillis)]
    {:cultivation-lots (into {} (map (juxt :id #(build-lot % base-ms)) lot-specs))
     :facts []}))

;; ──────────────────────────── the scenario ────────────────────────────

(defn- cites
  "A citation of the jurisdiction's own published name, straight out of
  `perennial.facts/jurisdictions` -- the Governor only checks that a
  citation is present, but there is no reason to cite a fiction."
  [j]
  [{:spec (:name (facts/jurisdiction-by-id j)) :jurisdiction j}])

(defn- steps
  "Every request this scenario drives, in order. One map per proposal:
  the request (`:op`/`:subject`) and the advisor's proposal exactly as
  `perennial.operation/run-operation` will receive it."
  []
  [;; ── lot-001: a full clean lifecycle ────────────────────────────
   {:op :schedule-field-operation :subject "lot-001"
    :proposal {:effect :propose :confidence 0.91 :cites (cites :jp/maff)
               :value {:jurisdiction :jp/maff}}}
   {:op :order-supplies :subject "lot-001"
    :proposal {:effect :propose :confidence 0.88 :cites (cites :jp/maff)
               :value {:jurisdiction :jp/maff :cost-usd 1200}}}
   {:op :log-cultivation-record :subject "lot-001"
    :proposal {:effect :propose :confidence 0.93 :cites (cites :jp/maff)
               :value {:jurisdiction :jp/maff}}}
   {:op :log-cultivation-record :subject "lot-001"
    :proposal {:effect :propose :confidence 0.93 :cites (cites :jp/maff)
               :value {:jurisdiction :jp/maff}}}

   ;; ── lot-002: mechanical harvest, soft gates ────────────────────
   {:op :schedule-field-operation :subject "lot-002"
    :proposal {:effect :propose :confidence 0.42 :cites (cites :jp/maff)
               :value {:jurisdiction :jp/maff}}}
   {:op :order-supplies :subject "lot-002"
    :proposal {:effect :propose :confidence 0.9 :cites (cites :jp/maff)
               :value {:jurisdiction :jp/maff}}}
   {:op :log-cultivation-record :subject "lot-002"
    :proposal {:effect :propose :confidence 0.9 :cites (cites :jp/maff)
               :value {:jurisdiction :jp/maff}}}

   ;; ── lot-003..lot-009: one safety/compliance rule each ──────────
   {:op :log-cultivation-record :subject "lot-003"
    :proposal {:effect :propose :confidence 0.9 :cites (cites :us/epa)
               :value {:jurisdiction :us/epa}}}
   {:op :log-cultivation-record :subject "lot-004"
    :proposal {:effect :propose :confidence 0.9 :cites (cites :eu/reg1107)
               :value {:jurisdiction :eu/reg1107}}}
   {:op :log-cultivation-record :subject "lot-005"
    :proposal {:effect :propose :confidence 0.9 :cites (cites :jp/maff)
               :value {:jurisdiction :jp/maff}}}
   {:op :log-cultivation-record :subject "lot-006"
    :proposal {:effect :propose :confidence 0.9 :cites (cites :us/epa)
               :value {:jurisdiction :us/epa}}}
   {:op :log-cultivation-record :subject "lot-007"
    :proposal {:effect :propose :confidence 0.9 :cites (cites :jp/maff)
               :value {:jurisdiction :jp/maff}}}
   {:op :log-cultivation-record :subject "lot-008"
    :proposal {:effect :propose :confidence 0.9 :cites (cites :eu/reg1107)
               :value {:jurisdiction :eu/reg1107}}}
   {:op :log-cultivation-record :subject "lot-009"
    :proposal {:effect :propose :confidence 0.9 :cites (cites :jp/maff)
               :value {:jurisdiction :jp/maff}}}

   ;; ── lot-010: crop-health concern, escalation then hard hold ────
   {:op :flag-crop-health-concern :subject "lot-010"
    :proposal {:effect :propose :confidence 0.95 :cites (cites :jp/maff)
               :value {:jurisdiction :jp/maff}}}
   {:op :log-cultivation-record :subject "lot-010"
    :proposal {:effect :propose :confidence 0.9 :cites (cites :jp/maff)
               :value {:jurisdiction :jp/maff}}}

   ;; ── lot-011: scope boundaries ──────────────────────────────────
   {:op :order-supplies :subject "lot-011"
    :proposal {:effect :propose :confidence 0.9 :cites (cites :jp/maff)
               :value {:jurisdiction :jp/maff :cost-usd 8400}}}
   {:op :log-cultivation-record :subject "lot-011"
    :proposal {:effect :propose :confidence 0.9 :cites []
               :value {:jurisdiction nil}}}
   {:op :schedule-field-operation :subject "lot-011"
    :proposal {:effect :actuate :confidence 0.9 :cites (cites :jp/maff)
               :value {:jurisdiction :jp/maff}}}
   {:op :schedule-field-operation :subject "lot-011"
    :proposal {:effect :propose :confidence 0.9 :cites (cites :jp/maff)
               :value {:jurisdiction :jp/maff :operate-field-equipment? true}}}
   {:op :operate-harvester :subject "lot-011"
    :proposal {:effect :propose :confidence 0.9 :cites (cites :jp/maff)
               :value {:jurisdiction :jp/maff}}}

   ;; ── lot-012: never registered ──────────────────────────────────
   {:op :schedule-field-operation :subject unregistered-lot-id
    :proposal {:effect :propose :confidence 0.9 :cites (cites :jp/maff)
               :value {:jurisdiction :jp/maff}}}])

(defn- escalation-basis
  "Why a human had to look, re-derived for DISPLAY from the Governor's
  public vars. The authoritative decision is the verdict's own
  `:escalate?`; this only names it."
  [{:keys [op]} proposal verdict]
  (let [cost (get-in proposal [:value :cost-usd])]
    (cond-> []
      (< (:confidence verdict) governor/confidence-floor)
      (conj :low-confidence)

      (contains? governor/high-stakes op)
      (conj :high-stakes-actuation)

      (and (contains? governor/always-escalate-ops op)
           (not (contains? governor/high-stakes op)))
      (conj :always-escalate-op)

      (and (= op :order-supplies) (not (number? cost)))
      (conj :supply-cost-not-stated)

      (and (= op :order-supplies) (number? cost)
           (> cost governor/supply-order-cost-threshold-usd))
      (conj :supply-cost-above-threshold))))

(defn- apply-commit
  "The store effect of a committed proposal. Only two of the four allowed
  ops have a store API at all (`log-cultivation-record` / `mark-scheduled`);
  a committed `:order-supplies` or `:flag-crop-health-concern` is
  ledger-only, which the rendered page states rather than hides."
  [db {:keys [op subject]} proposal approved-by]
  (case op
    :schedule-field-operation (store/mark-scheduled db subject)
    :log-cultivation-record
    (store/log-cultivation-record
     db subject
     (cond-> (merge (store/cultivation-lot db subject) (:value proposal))
       approved-by (assoc :approved-by approved-by)))
    db))

(defn- commit-fact [{:keys [op subject]} proposal]
  {:t :committed
   :op op
   :actor (:actor-id context)
   :subject subject
   :disposition :auto-commit
   :basis []
   :confidence (:confidence proposal)})

(defn- approval-fact [{:keys [op subject]} proposal verdict basis]
  {:t :approval-granted
   :op op
   :actor (:actor-id context)
   :subject subject
   :disposition :approved-then-commit
   :basis basis
   :approved-by approver
   :confidence (:confidence proposal 0.0)
   :escalate? (:escalate? verdict)})

(defn- run-step
  "Drive ONE proposal through the real actor entry point and fold the
  outcome back into the store. Three outcomes, all read off the
  Governor's verdict, never guessed:

    :ok?            -> auto-commit
    :hard? verdict  -> HARD hold; the actor's own hold fact lands and no
                       human is ever asked
    otherwise       -> escalation; the actor's hold fact lands (the
                       Governor pausing the proposal), a human signs off,
                       and the commit follows"
  [db {:keys [op subject proposal]}]
  (let [request {:op op :subject subject}
        res (op/run-operation request context proposal db governor/check)
        verdict (:verdict res)]
    (cond
      (:ok? res)
      (-> db
          (apply-commit request proposal nil)
          (store/append-fact (commit-fact request proposal)))

      (:hard? verdict)
      (reduce store/append-fact db (:facts res))

      :else
      (-> (reduce store/append-fact db (:facts res))
          (apply-commit request proposal approver)
          (store/append-fact
           (approval-fact request proposal verdict
                          (escalation-basis request proposal verdict)))))))

(defn- probe-pass-branch
  "MEASURE, rather than assume, how many audit facts the actor mints on
  a PASSING verdict. Runs one extra clean proposal through
  `run-operation` against the finished store and counts what came back.
  Pure -- the store is not touched."
  [db]
  (let [request {:op :schedule-field-operation :subject "lot-001"}
        proposal {:effect :propose :confidence 0.95 :cites (cites :jp/maff)
                  :value {:jurisdiction :jp/maff}}
        res (op/run-operation request context proposal db governor/check)]
    {:ok? (:ok? res) :fact-count (count (:facts res))}))

(defn- probes
  "Measurements about where a human approver's id ends up. Every value
  here is produced by executing/reflecting on this repo's own code, so
  the disclosure on the page self-corrects if the code changes."
  [db]
  (let [ledger (store/audit-trail db)
        approver-key? (fn [m] (boolean (some #(str/includes? (name %) "approv") (keys m))))
        actor-facts (filter #(= :governor-hold (:t %)) ledger)
        logged-lots (filter (comp :logged? val) (:cultivation-lots db))]
    {:store-mutators (vec (sort (map str (keys (ns-publics 'perennial.store)))))
     :commit-record-fn (some? (ns-resolve 'perennial.store 'commit-record!))
     :pass-branch (probe-pass-branch db)
     :actor-fact-count (count actor-facts)
     :actor-facts-with-approver (count (filter approver-key? actor-facts))
     :logged-lot-count (count logged-lots)
     :logged-lots-with-approver (count (filter (comp approver-key? val) logged-lots))
     :approver-keys-in-store (vec (sort (map str (distinct (mapcat #(filter (fn [k] (str/includes? (name k) "approv"))
                                                                            (keys (val %)))
                                                                   logged-lots)))))}))

(defn run-demo!
  "Seed a fresh store, drive every request in `steps` through the real
  actor stack, and return `{:db .. :probes ..}`. Nothing on the rendered
  page is typed in from a previous run -- it is all this value."
  []
  (let [db (reduce run-step (seed-store) (steps))]
    {:db db :probes (probes db)}))

;; ───────────────────────────── rendering ─────────────────────────────

(defn- unregistered-subjects
  "Subjects this scenario filed a proposal against that the store does NOT
  hold a cultivation-lot record for, measured against the finished store
  via `perennial.store/cultivation-lot-registered?` -- the same predicate
  the Governor's registration invariant uses. Derived rather than
  asserted, so adding or removing an unregistered subject in `steps`
  moves the number on the page by itself."
  [db]
  (vec (sort (distinct (remove #(store/cultivation-lot-registered? db %)
                               (map :subject (steps)))))))

(defn hard-holds
  "HARD governor holds on the ledger. A hold fact carries a non-empty
  `:basis` only when `perennial.governor/check` found real violations --
  `perennial.operation/run-operation` mints the SAME `:governor-hold`
  fact shape for a human escalation, where `:basis` is empty. That
  difference is the only thing separating the two in the ledger, so it is
  what this reads."
  [db]
  (filterv #(and (= :governor-hold (:t %)) (seq (:basis %)))
           (store/audit-trail db)))

(defn- fired-rules
  "subject -> the set of HARD rules the Governor actually minted for it in
  this run, read off the ledger."
  [db]
  (reduce (fn [m f] (update m (:subject f) (fnil into #{}) (:basis f)))
          {}
          (hard-holds db)))

(defn- declared-rules
  "subject -> the set of HARD rules this scenario DECLARES it is built to
  trip (`:expects`), covering the unregistered subject too."
  []
  (into {unregistered-lot-id unregistered-lot-expects}
        (map (juxt :id #(set (:expects %))) lot-specs)))

(defn expectation-mismatches
  "Every subject whose declared `:expects` does not equal what the
  Governor actually did. Empty is the only acceptable value -- `-main`
  throws otherwise, which is what keeps the `:demonstrates` prose on the
  page from becoming a claim nobody checks."
  [db]
  (let [declared (declared-rules)
        actual (fired-rules db)]
    (vec (for [s (sort (distinct (concat (keys declared) (keys actual))))
               :let [d (get declared s #{})
                     a (get actual s #{})]
               :when (not= d a)]
           {:subject s :declared d :actual a}))))

(defn uncovered-rules
  "Hard rules in `hard-rule-catalog` that this scenario never made fire."
  [db]
  (vec (remove (set (mapcat :basis (hard-holds db))) hard-rule-catalog)))

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- code [v] (str "<code>" (esc v) "</code>"))

(defn- ok [v] (str "<span class=\"ok\">" (esc v) "</span>"))
(defn- warn [v] (str "<span class=\"warn\">" (esc v) "</span>"))
(defn- crit [v] (str "<span class=\"critical\">" (esc v) "</span>"))
(defn- muted [v] (str "<span class=\"muted\">" (esc v) "</span>"))
(defn- num-cell [v] (str "<span class=\"num\">" (esc v) "</span>"))

(defn- tr [cells]
  (str "        <tr>" (str/join (map #(str "<td>" % "</td>") cells)) "</tr>"))

(defn- table [headers rows]
  (str "    <table>\n      <thead><tr>"
       (str/join (map #(str "<th>" (esc %) "</th>") headers))
       "</tr></thead>\n      <tbody>\n"
       (str/join "\n" rows)
       "\n      </tbody>\n    </table>\n"))

(defn- section [title lead body]
  (str "  <section class=\"card\">\n"
       "    <h2>" (esc title) "</h2>\n"
       (if lead (str "    <p class=\"muted\">" lead "</p>\n") "")
       body
       "  </section>\n"))

(defn- dash [] (muted "—"))

;; --- sections ---------------------------------------------------------

(defn- summary-section [db probes]
  (let [ledger (store/audit-trail db)
        hs (hard-holds db)
        rules (distinct (mapcat :basis hs))
        approvals (filter #(= :approval-granted (:t %)) ledger)
        commits (filter #(= :committed (:t %)) ledger)
        lots (:cultivation-lots db)
        unreg (unregistered-subjects db)]
    (section
     "運用サマリ（この実行の実測値）"
     (str "全て " (code "perennial.render-html/run-demo!") " の返り値から算出。"
          "HARD hold は人間に届かない — 承認では上書きできない。")
     (str
      (table ["指標" "値"]
             (map tr
                  [["登録済み cultivation lot" (num-cell (count lots))]
                   ["未登録のまま提案された subject"
                    (str (num-cell (count unreg)) " "
                         (str/join " " (map #(code %) unreg)))]
                   ["actor に投入した提案 (request)" (num-cell (count (steps)))]
                   ["監査台帳 fact 総数" (num-cell (count ledger))]
                   ["HARD hold（人間に届かない）" (crit (count hs))]
                   ["HARD hold で発火した異なる rule 種"
                    (str (crit (count rules)) " / "
                         (num-cell (count hard-rule-catalog))
                         " " (muted "(governor の全カタログ)"))]
                   ["人間承認まで上がった escalation" (warn (count approvals))]
                   ["Governor clean での自動確定" (ok (count commits))]
                   ["actor が合格時に鋳造する fact 数（実測）"
                    (num-cell (get-in probes [:pass-branch :fact-count]))]]))
      "    <p class=\"muted\">このページは次の 4 つが全て成り立った時だけ書き出される — "
      "成り立たなければ " (code "-main") " は throw し、ファイルは残らない（"
      (ok "コメントではなく build 時の不変条件") "）: "
      "① HARD hold が 1 件以上ある ② Governor clean の自動確定が 1 件以上ある "
      "③ 各 lot の宣言（" (code ":expects") "）と Governor の実出力が完全一致する "
      "④ カタログの hard rule が 1 種残らず発火している。"
      "</p>\n"))))

(defn- lots-section [db]
  (let [ledger (store/audit-trail db)
        last-fact (fn [id] (last (filter #(= id (:subject %)) ledger)))
        spec-by-id (into {} (map (juxt :id identity) lot-specs))
        status (fn [id]
                 (let [f (last-fact id)]
                   (cond
                     (nil? f) (dash)
                     (= :committed (:t f)) (ok "自動確定")
                     (= :approval-granted (:t f)) (ok "人間承認のうえ確定")
                     (and (= :governor-hold (:t f)) (seq (:basis f)))
                     (crit (str "HARD hold · " (name (first (:basis f)))))
                     (= :governor-hold (:t f)) (warn "人間承認待ち")
                     :else (dash))))
        row (fn [[id lot]]
              (let [ct (facts/crop-operation-type-by-id (:crop-operation-type lot))
                    j (facts/jurisdiction-by-id (:jurisdiction lot))]
                (tr [(code id)
                     (esc (:field-id lot))
                     (num-cell (:field-area-hectares lot))
                     (str (esc (:name ct)) "<br>" (muted (:crop-operation-type lot)))
                     (if (:chemical-application? ct) (warn "化学散布") (muted "機械作業"))
                     (esc (:name j))
                     (if (facts/required-evidence-satisfied? (:jurisdiction lot)
                                                             (:evidence-checklist lot))
                       (ok (str (count (:evidence-checklist lot)) " / "
                                (count (:required-evidence j))))
                       (crit (str (count (:evidence-checklist lot)) " / "
                                  (count (:required-evidence j)))))
                     (if (:logged? lot) (ok "記録済") (dash))
                     (if (:scheduled? lot) (ok "予定済") (dash))
                     (if (:crop-health-concern-raised? lot)
                       (if (:crop-health-concern-resolved? lot) (ok "解決済") (crit "未解決"))
                       (dash))
                     (status id)
                     (esc (:demonstrates (spec-by-id id)))])))]
    (section
     "Cultivation lots（operator 自身の圃場）"
     (str "この repo には seed データが無い（" (code "perennial.sim")
          " は stub のまま）ため、lot は " (code "perennial.render-html/seed-store")
          " が構築する。作業種別名・法域名・必要書類件数は "
          (code "perennial.facts") " から実行時に引いた値。")
     (table ["Lot" "圃場" "面積 ha" "作業種別" "区分" "法域" "書類" "記録" "予定" "作物健全性"
             "最終 disposition" "このシナリオでの役割"]
            (map row (sort-by key (:cultivation-lots db)))))))

(defn- safety-window-section [db]
  (let [lots (:cultivation-lots db)
        used (frequencies (map :crop-operation-type (vals lots)))
        row (fn [[id ct]]
              (tr [(code id)
                   (esc (:name ct))
                   (if (:chemical-application? ct) (warn "化学散布") (muted "機械作業"))
                   (if-let [v (:pre-harvest-interval-days ct)] (num-cell (str v " 日")) (dash))
                   (if-let [v (:restricted-entry-interval-hours ct)] (num-cell (str v " 時間")) (dash))
                   (if-let [v (:max-wind-speed-kmh ct)] (num-cell (str v " km/h")) (dash))
                   (if-let [v (:min-buffer-zone-m ct)] (num-cell (str v " m")) (dash))
                   (num-cell (get used id 0))]))]
    (section
     "作業種別と安全窓（perennial.facts/crop-operation-types）"
     (str "機械作業（竹稈収穫・コルクガシ樹皮採取・観賞樹剪定）には化学散布の安全窓が"
          "そもそも存在しない — Governor は該当検査を"
          (ok "捏造せず丸ごと省略する") "。")
     (table ["ID" "名称" "区分" "収穫前日数 (PHI)" "再入場禁止 (REI)" "風速上限" "最小緩衝地帯"
             "この実行での lot 数"]
            (map row (sort-by (comp str key) facts/crop-operation-types))))))

(defn- jurisdiction-section []
  (let [row (fn [[id j]]
              (tr [(code id)
                   (esc (:name j))
                   (num-cell (count (:required-evidence j)))
                   (str/join " " (map #(code %) (:required-evidence j)))]))]
    (section
     "法域と必要書類（perennial.facts/jurisdictions）"
     (str "書類が 1 点でも欠ければ " (code ":log-cultivation-record")
          " は HARD hold。法域要件を発明することはしない — 引用の無い提案自体が HARD hold になる。")
     (table ["ID" "法域" "必要書類数" "必要書類"]
            (map row (sort-by (comp str key) facts/jurisdictions))))))

(defn- gate-section []
  (let [note (fn [op]
               (cond
                 (contains? governor/high-stakes op)
                 (warn "常に人間承認（この actor 唯一の実行イベント）")
                 (contains? governor/always-escalate-ops op)
                 (warn "常に人間承認（confidence では自動解決しない）")
                 (= op :order-supplies)
                 (warn (str "cost-usd が数値として提示され "
                            governor/supply-order-cost-threshold-usd
                            " USD 以下の時だけ自動確定、それ以外は人間承認"))
                 :else
                 (ok (str "Governor clean かつ confidence ≥ " governor/confidence-floor
                          " で自動確定"))))
        row (fn [op] (tr [(code op) (note op)]))]
    (section
     "Governor gate contract（perennial.governor の公開値から導出）"
     (str "許可 op は閉じた allowlist。それ以外は — 圃場機器の直接操作を含め — 恒久ブロック。"
          "confidence floor " (code governor/confidence-floor)
          " / 供給発注閾値 " (code (str governor/supply-order-cost-threshold-usd " USD")) "。")
     (str (table ["許可 op" "gate"] (map row (sort-by str governor/allowed-ops)))
          "    <p class=\"muted\">"
          (code ":order-supplies") " の閾値 gate は "
          (ok "自己申告値を de-escalation には使わない")
          " — cost-usd が欠落・非数値でも人間承認へ上げる（省略で gate をすり抜けられない）。"
          "</p>\n"))))

(defn- hard-hold-section [db]
  (let [hs (hard-holds db)
        row (fn [f]
              (tr [(code (:op f))
                   (code (:subject f))
                   (str/join " " (map #(crit (name %)) (:basis f)))
                   (str/join "<br>" (map #(esc (:detail %)) (:violations f)))
                   (num-cell (:confidence f))]))]
    (section
     (str "HARD hold（" (count hs) " 件 / 人間には届かない）")
     (str "全て " (code "perennial.governor/hold-fact") " が鋳造した actor 自身の出力。"
          (crit "承認による上書き経路は存在しない")
          " — advisor の confidence がいくら高くても結果は変わらない。")
     (table ["op" "subject" "rule" "Governor の理由（原文）" "advisor confidence"]
            (map row hs)))))

(defn- rule-coverage-section [db]
  (let [hs (hard-holds db)
        by-rule (reduce (fn [m f]
                          (reduce #(update %1 %2 (fnil conj []) (:subject f)) m (:basis f)))
                        {} hs)
        uncovered (uncovered-rules db)
        row (fn [rule]
              (let [subjects (get by-rule rule)]
                (tr [(if (seq subjects) (crit (name rule)) (muted (name rule)))
                     (if (seq subjects) (num-cell (count subjects)) (crit 0))
                     (if (seq subjects)
                       (str/join " " (map #(code %) (sort (distinct subjects))))
                       (crit "このシナリオでは未発火"))])))]
    (section
     (str "HARD rule カバレッジ（" (count by-rule) " / " (count hard-rule-catalog) " 種）")
     (str "左列は "
          (code "perennial.governor")
          " の source から抽出した hard rule の全カタログ（再入力ではない）、"
          "件数と subject は台帳からの導出。"
          (if (seq uncovered)
            (crit (str "未発火: " (str/join " " (map name uncovered))))
            (ok "この実行はカタログの全 rule を実際に発火させている。"))
          " 発火が 1 種でも欠ければ "
          (code "-main")
          " はページを書かずに throw する。")
     (table ["rule（governor の全カタログ）" "この実行での発火数" "subject"]
            (map row hard-rule-catalog)))))

(defn- escalation-section [db]
  (let [ledger (store/audit-trail db)
        rows (filter #(= :approval-granted (:t %)) ledger)
        row (fn [f]
              (tr [(code (:op f))
                   (code (:subject f))
                   (num-cell (:confidence f))
                   (str/join " " (map #(warn (name %)) (:basis f)))
                   (ok "承認")
                   (code (:approved-by f))]))]
    (section
     (str "人間承認まで上がった escalation（" (count rows) " 件）")
     (str "HARD hold と違い、これは人間が見て通した提案。escalation の理由は "
          (code "perennial.governor") " の公開値から再導出した表示（判定自体は verdict の "
          (code ":escalate?") "）。")
     (table ["op" "subject" "confidence" "escalation 理由" "決定" "承認者"]
            (map row rows)))))

(defn- phase-section []
  (let [ps phase/all-phases
        row (fn [from]
              (tr (into [(code from)]
                        (map (fn [to]
                               (if (phase/can-transition? from to)
                                 (ok "可")
                                 (dash)))
                             ps))))]
    (section
     "Phase machine（perennial.phase）"
     (str "遷移は前方向のみ。行が遷移元、列が遷移先で、各セルは "
          (code "perennial.phase/can-transition?") " の実際の返り値。")
     (table (into ["from \\ to"] (map name ps))
            (map row ps)))))

(defn- ledger-section [db]
  (let [ledger (store/audit-trail db)
        origin (fn [f]
                 ;; derived, not tagged: :governor-hold is the only fact
                 ;; shape this actor mints (perennial.governor/hold-fact,
                 ;; called through perennial.operation/run-operation).
                 (if (= :governor-hold (:t f))
                   (ok "actor")
                   (muted "console driver")))
        kind (fn [f]
               (cond
                 (and (= :governor-hold (:t f)) (seq (:basis f))) (crit "HARD hold")
                 (= :governor-hold (:t f)) (warn "escalation（人間へ）")
                 (= :approval-granted (:t f)) (ok "承認 → 確定")
                 :else (ok "自動確定")))
        row (fn [i f]
              (tr [(num-cell (inc i))
                   (code (:t f))
                   (kind f)
                   (origin f)
                   (code (:op f))
                   (code (:subject f))
                   (code (:disposition f))
                   (if (seq (:basis f))
                     (str/join " " (map #(code (name %)) (:basis f)))
                     (dash))
                   (num-cell (:confidence f))]))]
    (section
     (str "監査台帳（append-only, " (count ledger) " fact）")
     (str "投入順そのまま。"
          (code "perennial.operation/run-operation")
          " は HARD hold と人間 escalation の両方に同じ "
          (code ":governor-hold") " fact 形を鋳造するので、両者の区別は "
          (code ":basis") " が空かどうかからしか取れない — この console はそこから導出している。")
     (table ["#" "fact" "種別" "鋳造元" "op" "subject" "disposition" "basis" "confidence"]
            (map-indexed row ledger)))))

(defn- approver-section [probes]
  (let [{:keys [store-mutators commit-record-fn pass-branch actor-fact-count
                actor-facts-with-approver logged-lot-count logged-lots-with-approver
                approver-keys-in-store]} probes
        retained? (pos? logged-lots-with-approver)
        verdict-line
        (cond
          (and retained? (zero? actor-facts-with-approver))
          (str (warn "承認者 id は store の lot record には残るが、actor 側の経路が入れたものではない")
               " — " (code "perennial.store/log-cultivation-record")
               " は呼び出し側が渡した map をそのまま格納する（whitelist が無い）ので残っただけで、"
               "承認者を供給したのはこの console driver。"
               (code "perennial.operation/run-operation") " は合格時に fact を "
               (num-cell (:fact-count pass-branch)) " 件しか返さず、"
               (code "perennial.store") " に " (code "commit-record!")
               " 相当の関数も無いため、"
               (crit "actor の実装だけを通して承認者 id が台帳や record に載る経路は存在しない") "。")
          retained?
          (ok "承認者 id は store record と actor の fact の両方に残る。")
          :else
          (crit "承認者 id はどこにも残らない — 誰も承認していないのか、store が捨てたのかを読者が区別できない。"))]
    (section
     "承認者の帰属（hard-code ではなく実測）"
     (str "この節は run 後の store と actor を実際に叩いて測った値だけで書かれている。"
          "実装が直れば表示も自動で直る。")
     (str (table ["測定" "値"]
                 (map tr
                      [[(str (code "perennial.store") " の公開関数")
                        (str/join " " (map #(code %) store-mutators))]
                       [(str (code "perennial.store/commit-record!") " は存在するか")
                        (if commit-record-fn (ok "存在する") (crit "存在しない"))]
                       [(str (code "run-operation") " が合格 verdict で返す fact 数")
                        (str (num-cell (:fact-count pass-branch))
                             " " (muted (str "(:ok? " (:ok? pass-branch) ")")))]
                       ["actor が鋳造した fact 数"
                        (num-cell actor-fact-count)]
                       ["そのうち承認者 key を持つ fact"
                        (if (pos? actor-facts-with-approver)
                          (ok actor-facts-with-approver)
                          (crit actor-facts-with-approver))]
                       ["記録確定した lot 数"
                        (num-cell logged-lot-count)]
                       ["そのうち承認者 key を持つ lot record"
                        (if (pos? logged-lots-with-approver)
                          (ok logged-lots-with-approver)
                          (crit logged-lots-with-approver))]
                       ["store に残った承認者 key"
                        (if (seq approver-keys-in-store)
                          (str/join " " (map #(code %) approver-keys-in-store))
                          (dash))]]))
          "    <p>" verdict-line "</p>\n"
          "    <p class=\"muted\">"
          (code ":order-supplies") " と " (code ":flag-crop-health-concern")
          " は承認されても store に書き戻す API が " (code "perennial.store")
          " に無く、台帳のみに残る。作物健全性フラグを「解決済」にする関数も無いので、"
          "lot-010 の懸念はこの実行を通して未解決のままで、その log 提案は HARD hold のままになる。"
          "これは demo 側で直すべきものではない（actor の SSoT 挙動が変わる）ので、そのまま表示する。"
          "</p>\n"))))

(defn render
  "Render the whole console from the value `run-demo!` returned. Pure:
  same input, same bytes."
  [{:keys [db probes]}]
  (str
   "<!doctype html>\n"
   "<html lang=\"ja\"><head><meta charset=\"utf-8\">\n"
   "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
   "<title>cloud-itonami-isic-0129 · 多年生作物栽培オペレーション console</title>\n"
   "<style>\n" (jp-go-dds.skin/dds+skin) "\n</style></head><body>\n"
   "<header class=\"bar\">\n"
   "  <h1>多年生作物（その他）栽培オペレーション — Operator Console</h1>\n"
   "</header>\n"
   "<p><span class=\"badge\">ISIC Rev.5 0129</span> "
   "<span class=\"badge\">read-only sample</span> "
   "<span class=\"badge\">governor-gated</span> "
   "<span class=\"badge\">圃場機器の直接操作は恒久ブロック</span></p>\n"
   "<p class=\"muted\">竹稈収穫・コルクガシ樹皮採取・観賞樹/苗木 — いずれも operator 自身が"
   "所有・栽培する圃場。他農家の作物を請け負う作業（ISIC 0161）ではない。"
   "このページは " (code "clojure -M:dev:render-html") " が "
   (code "perennial.operation") " → " (code "perennial.governor") " → "
   (code "perennial.store") " を実際に走らせて生成したもので、手書きの数値は無い。</p>\n"
   "<main>\n"
   (summary-section db probes)
   (lots-section db)
   (hard-hold-section db)
   (rule-coverage-section db)
   (escalation-section db)
   (gate-section)
   (safety-window-section db)
   (jurisdiction-section)
   (phase-section)
   (ledger-section db)
   (approver-section probes)
   "</main>\n"
   "<footer>\n"
   "  <p>生成: <code>clojure -M:dev:render-html</code>（<code>perennial.render-html</code>）。"
   "決定的 — 同じ seed なら byte 単位で同一。時刻・乱数はページに入らない。</p>\n"
   "  <p>HARD hold は人間承認で上書きできない。圃場機器（収穫機・散布機・施用機）の操作と"
   "農薬散布の最終決定は、この actor の権限に存在しない。</p>\n"
   "</footer>\n"
   "</body></html>\n"))

(defn -main
  "Render the console -- but only if the run it is describing actually
  demonstrated what the page claims. Four build-time invariants, each of
  which aborts before anything is written:

    1. at least one HARD hold reached the ledger
    2. at least one proposal auto-committed on a clean Governor verdict
    3. every lot's declared `:expects` equals the Governor's real output
    4. every rule in `hard-rule-catalog` fired at least once

  A console that shows no hold, or no clean commit, or whose prose has
  drifted from the code, is worse than no console -- so it is not
  written at all."
  [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        {:keys [db] :as result} (run-demo!)
        hs (hard-holds db)
        commits (filterv #(= :committed (:t %)) (store/audit-trail db))
        mismatches (expectation-mismatches db)
        uncovered (uncovered-rules db)]
    (when (empty? hs)
      (throw (ex-info "no governor hold fact on the ledger — refusing to write a console that shows no real hold"
                      {:ledger-facts (count (store/audit-trail db))})))
    (when (empty? commits)
      (throw (ex-info "no clean auto-commit on the ledger — a console showing only holds would misrepresent the gate"
                      {:ledger-facts (count (store/audit-trail db))})))
    (when (seq mismatches)
      (throw (ex-info "scenario expectation mismatch — a lot's declared :expects is not what the Governor did"
                      {:mismatches mismatches})))
    (when (seq uncovered)
      (throw (ex-info "hard rule never exercised — the page would claim full coverage it does not have"
                      {:uncovered uncovered :catalog-size (count hard-rule-catalog)})))
    (io/make-parents out)
    (spit out (render result))
    (println "wrote" out
             (str "(" (count (store/audit-trail db)) " ledger facts, "
                  (count hs) " HARD holds, "
                  (count (distinct (mapcat :basis hs))) " distinct hard rules, "
                  (count (:cultivation-lots db)) " cultivation lots)"))))
