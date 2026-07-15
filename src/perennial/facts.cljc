(ns perennial.facts
  "Reference facts for other-perennial-crop (ISIC Rev.5 0129) cultivation
  operations: crop-operation safety windows (applicator-license currency,
  sprayer-equipment calibration, pre-harvest interval, restricted-entry
  interval, wind-speed drift ceiling, buffer-zone minimum), jurisdiction
  evidence-checklist requirements. This namespace contains pure lookup
  functions for perennial-crop cultivation safety compliance checks -- the
  Governor calls these to independently validate proposals; the advisor's
  confidence is never sufficient on its own.

  ISIC 0129 is the RESIDUAL (not-elsewhere-classified) perennial-crop
  growing division: perennial crops not already covered by 0121 (grapes),
  0122 (tropical/subtropical fruits), 0123 (citrus fruits), 0124 (pome and
  stone fruits), 0125 (other tree and bush fruits and nuts), 0126
  (oleaginous fruits), or 0127 (beverage crops). The concrete illustrative
  crops chosen for this build are BAMBOO (culm harvest), CORK OAK (bark
  stripping), and ornamental/nursery trees and shrubs -- all grown and
  OWNED by the operator, which is what distinguishes this actor from
  ISIC 0161 (support activities for crop production), a contractor that
  never owns the crop it services.

  Crop-operation types split into two safety shapes:
    - Mechanical harvest/maintenance operations (bamboo culm harvesting,
      cork-oak bark stripping, ornamental pruning) have NO
      chemical-application safety window at all -- pre-harvest-interval /
      restricted-entry-interval / max-wind-speed / min-buffer-zone are all
      nil for these, and the Governor's corresponding checks are skipped
      entirely rather than fabricating a target.
    - Chemical-application operations (herbicide/fungicide/insecticide
      spraying) carry a genuine pre-harvest interval (days before harvest
      a residue must clear), restricted-entry interval (hours before
      workers may safely re-enter the treated field), maximum safe wind
      speed (spray-drift risk), and minimum buffer zone (distance to
      sensitive sites such as water bodies, schools, or residences)."
  (:require [clojure.set :as set]))

(def crop-operation-types
  "Valid perennial-crop cultivation-operation categories and their safety
  windows. `pre-harvest-interval-days`/`restricted-entry-interval-hours`/
  `max-wind-speed-kmh`/`min-buffer-zone-m` are nil for mechanical
  (non-chemical) crop-operation types -- the Governor's corresponding
  checks are skipped entirely for those types rather than fabricating a
  target."
  {:harvest/bamboo-culm
   {:id :harvest/bamboo-culm
    :name "竹稈収穫作業"
    :chemical-application? false
    :pre-harvest-interval-days nil
    :restricted-entry-interval-hours nil
    :max-wind-speed-kmh nil
    :min-buffer-zone-m nil}

   :harvest/cork-bark-strip
   {:id :harvest/cork-bark-strip
    :name "コルクガシ樹皮採取作業"
    :chemical-application? false
    :pre-harvest-interval-days nil
    :restricted-entry-interval-hours nil
    :max-wind-speed-kmh nil
    :min-buffer-zone-m nil}

   :maintenance/ornamental-pruning
   {:id :maintenance/ornamental-pruning
    :name "観賞樹・苗木剪定作業"
    :chemical-application? false
    :pre-harvest-interval-days nil
    :restricted-entry-interval-hours nil
    :max-wind-speed-kmh nil
    :min-buffer-zone-m nil}

   :spray/herbicide-nursery-broadcast
   {:id :spray/herbicide-nursery-broadcast
    :name "苗畑除草剤散布(ブロードキャスト)"
    :chemical-application? true
    :pre-harvest-interval-days 14
    :restricted-entry-interval-hours 12
    :max-wind-speed-kmh 24.0
    :min-buffer-zone-m 15.0}

   :spray/fungicide-ornamental-foliar
   {:id :spray/fungicide-ornamental-foliar
    :name "観賞樹殺菌剤葉面散布"
    :chemical-application? true
    :pre-harvest-interval-days 7
    :restricted-entry-interval-hours 12
    :max-wind-speed-kmh 24.0
    :min-buffer-zone-m 15.0}

   :spray/insecticide-bamboo-ground
   {:id :spray/insecticide-bamboo-ground
    :name "竹林地上防除(殺虫剤散布)"
    :chemical-application? true
    :pre-harvest-interval-days 21
    :restricted-entry-interval-hours 24
    :max-wind-speed-kmh 16.0
    :min-buffer-zone-m 30.0}})

(defn crop-operation-type-by-id [id]
  (get crop-operation-types id))

(def jurisdictions
  "Perennial-crop cultivation jurisdictions and their evidence-checklist
  requirements."
  {:jp/maff
   {:id :jp/maff
    :name "日本 (農薬取締法・農林水産省)"
    :required-evidence
    [:cultivation-lot-record
     :field-boundary-map
     :application-record
     :applicator-license
     :weather-log
     :buffer-zone-assessment]}

   :us/epa
   {:id :us/epa
    :name "United States (FIFRA / EPA Pesticide Regulation)"
    :required-evidence
    [:cultivation-lot-record
     :field-boundary-map
     :application-record
     :applicator-license
     :weather-log
     :buffer-zone-assessment]}

   :eu/reg1107
   {:id :eu/reg1107
    :name "European Union (Regulation (EC) No 1107/2009 on plant protection products)"
    :required-evidence
    [:cultivation-lot-record
     :field-boundary-map
     :application-record
     :applicator-license
     :weather-log
     :buffer-zone-assessment]}})

(defn jurisdiction-by-id [id]
  (get jurisdictions id))

(defn required-evidence-satisfied?
  "Verify that every item in the jurisdiction's `:required-evidence` list
  is present in `evidence`. `jurisdiction` may be a resolved jurisdiction
  map (as returned by `jurisdiction-by-id`) or a raw jurisdiction id --
  both call conventions are in use (tests pass a resolved map; the
  Governor passes the raw id straight off cultivation-lot metadata)."
  [jurisdiction evidence]
  (let [j (if (map? jurisdiction) jurisdiction (jurisdiction-by-id jurisdiction))]
    (if-not j
      false
      (set/subset? (set (:required-evidence j)) (set evidence)))))

(defn applicator-license-current?
  "Positive-sense convenience predicate: is the applicator license valid
  (not yet expired) as of `now-epoch-ms`? Returns false when the
  crop-operation type has no chemical-application license requirement at
  all -- there is nothing to be 'current' about for a mechanical harvest
  or maintenance operation."
  [expiry-epoch-ms now-epoch-ms crop-operation-type]
  (boolean
   (and (some? crop-operation-type)
        (true? (:chemical-application? crop-operation-type))
        (some? expiry-epoch-ms)
        (>= expiry-epoch-ms now-epoch-ms))))

(defn sprayer-calibration-current?
  "Positive-sense convenience predicate: was the sprayer/applicator
  equipment calibrated within the safety interval (90 days) of
  `now-epoch-ms`? Returns false when the crop-operation type has no
  chemical-application equipment-calibration requirement at all."
  [last-calibration-epoch-ms now-epoch-ms crop-operation-type]
  (boolean
   (and (some? crop-operation-type)
        (true? (:chemical-application? crop-operation-type))
        (some? last-calibration-epoch-ms)
        (<= (- now-epoch-ms last-calibration-epoch-ms)
            (* 90 24 60 60 1000)))))

(defn pre-harvest-interval-satisfied?
  "Positive-sense convenience predicate: does `days-until-harvest` meet
  or exceed the crop-operation type's pre-harvest interval? Returns false
  when the crop-operation type has no pre-harvest-interval spec at all
  (mechanical harvest/maintenance operation -- nothing to satisfy)."
  [days-until-harvest crop-operation-type]
  (boolean
   (and (some? crop-operation-type)
        (some? (:pre-harvest-interval-days crop-operation-type))
        (some? days-until-harvest)
        (>= days-until-harvest (:pre-harvest-interval-days crop-operation-type)))))

(defn restricted-entry-interval-satisfied?
  "Positive-sense convenience predicate: does `hours-until-reentry` meet
  or exceed the crop-operation type's restricted-entry interval? Returns
  false when the crop-operation type has no restricted-entry-interval
  spec at all."
  [hours-until-reentry crop-operation-type]
  (boolean
   (and (some? crop-operation-type)
        (some? (:restricted-entry-interval-hours crop-operation-type))
        (some? hours-until-reentry)
        (>= hours-until-reentry (:restricted-entry-interval-hours crop-operation-type)))))

(defn wind-speed-in-range?
  "Positive-sense convenience predicate: does `actual-kmh` stay at or
  below the crop-operation type's maximum safe spray-drift wind speed?
  Returns false when the crop-operation type has no wind-speed ceiling at
  all."
  [actual-kmh crop-operation-type]
  (boolean
   (and (some? crop-operation-type)
        (some? (:max-wind-speed-kmh crop-operation-type))
        (some? actual-kmh)
        (<= actual-kmh (:max-wind-speed-kmh crop-operation-type)))))

(defn buffer-zone-in-range?
  "Positive-sense convenience predicate: does `actual-m` meet or exceed
  the crop-operation type's minimum buffer-zone distance? Returns false
  when the crop-operation type has no buffer-zone minimum at all."
  [actual-m crop-operation-type]
  (boolean
   (and (some? crop-operation-type)
        (some? (:min-buffer-zone-m crop-operation-type))
        (some? actual-m)
        (>= actual-m (:min-buffer-zone-m crop-operation-type)))))
