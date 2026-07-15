(ns perennial.facts-test
  (:require [clojure.test :refer [deftest is testing]]
            [perennial.facts :as facts]))

;; ──────────────────────── Crop-Operation-Type Lookups ──────────────────────

(deftest crop-operation-type-by-id-test
  (testing "herbicide-nursery-broadcast crop-operation type exists"
    (let [s (facts/crop-operation-type-by-id :spray/herbicide-nursery-broadcast)]
      (is (some? s))
      (is (= (:id s) :spray/herbicide-nursery-broadcast))
      (is (true? (:chemical-application? s)))
      (is (= (:pre-harvest-interval-days s) 14))))

  (testing "bamboo-culm harvest crop-operation type exists and has no chemical spec"
    (let [s (facts/crop-operation-type-by-id :harvest/bamboo-culm)]
      (is (some? s))
      (is (false? (:chemical-application? s)))
      (is (nil? (:pre-harvest-interval-days s)))
      (is (nil? (:max-wind-speed-kmh s)))))

  (testing "cork-bark-strip crop-operation type exists and has no chemical spec"
    (let [s (facts/crop-operation-type-by-id :harvest/cork-bark-strip)]
      (is (some? s))
      (is (false? (:chemical-application? s)))))

  (testing "ornamental-pruning crop-operation type exists and has no chemical spec"
    (let [s (facts/crop-operation-type-by-id :maintenance/ornamental-pruning)]
      (is (some? s))
      (is (false? (:chemical-application? s)))))

  (testing "nonexistent crop-operation type returns nil"
    (is (nil? (facts/crop-operation-type-by-id :nonexistent/type)))))

;; ──────────────────────── Jurisdiction Lookups ──────────────────────

(deftest jurisdiction-by-id-test
  (testing "JP MAFF jurisdiction exists"
    (let [j (facts/jurisdiction-by-id :jp/maff)]
      (is (some? j))
      (is (contains? (set (:required-evidence j)) :applicator-license))))

  (testing "US EPA jurisdiction exists"
    (let [j (facts/jurisdiction-by-id :us/epa)]
      (is (some? j))
      (is (contains? (set (:required-evidence j)) :buffer-zone-assessment))))

  (testing "EU REG1107 jurisdiction exists"
    (let [j (facts/jurisdiction-by-id :eu/reg1107)]
      (is (some? j))
      (is (contains? (set (:required-evidence j)) :weather-log))))

  (testing "nonexistent jurisdiction returns nil"
    (is (nil? (facts/jurisdiction-by-id :xx/unknown)))))

;; ──────────────────────── Perennial-Crop Cultivation Safety Predicates ──────

(deftest applicator-license-current-test
  (let [herbicide (facts/crop-operation-type-by-id :spray/herbicide-nursery-broadcast)
        bamboo (facts/crop-operation-type-by-id :harvest/bamboo-culm)]
    (testing "license expiring in the future is current"
      (is (true? (facts/applicator-license-current? 2000 1000 herbicide))))

    (testing "license expiring in the past is not current"
      (is (false? (facts/applicator-license-current? 500 1000 herbicide))))

    (testing "mechanical harvest crop-operation type never needs a license"
      (is (false? (facts/applicator-license-current? 2000 1000 bamboo))))))

(deftest sprayer-calibration-current-test
  (let [herbicide (facts/crop-operation-type-by-id :spray/herbicide-nursery-broadcast)
        bamboo (facts/crop-operation-type-by-id :harvest/bamboo-culm)
        now 1000000
        ten-days-ago (- now (* 10 24 60 60 1000))
        hundred-days-ago (- now (* 100 24 60 60 1000))]
    (testing "recent calibration is current"
      (is (true? (facts/sprayer-calibration-current? ten-days-ago now herbicide))))

    (testing "overdue calibration is not current"
      (is (false? (facts/sprayer-calibration-current? hundred-days-ago now herbicide))))

    (testing "mechanical harvest crop-operation type never needs calibration"
      (is (false? (facts/sprayer-calibration-current? ten-days-ago now bamboo))))))

(deftest pre-harvest-interval-satisfied-test
  (let [herbicide (facts/crop-operation-type-by-id :spray/herbicide-nursery-broadcast)
        bamboo (facts/crop-operation-type-by-id :harvest/bamboo-culm)]
    (testing "days-until-harvest at or above PHI passes"
      (is (true? (facts/pre-harvest-interval-satisfied? 14 herbicide)))
      (is (true? (facts/pre-harvest-interval-satisfied? 20 herbicide))))

    (testing "days-until-harvest below PHI fails"
      (is (false? (facts/pre-harvest-interval-satisfied? 5 herbicide))))

    (testing "mechanical harvest crop-operation type has no PHI to satisfy"
      (is (false? (facts/pre-harvest-interval-satisfied? 5 bamboo))))))

(deftest restricted-entry-interval-satisfied-test
  (let [insecticide (facts/crop-operation-type-by-id :spray/insecticide-bamboo-ground)
        bamboo (facts/crop-operation-type-by-id :harvest/bamboo-culm)]
    (testing "hours-until-reentry at or above REI passes"
      (is (true? (facts/restricted-entry-interval-satisfied? 24 insecticide)))
      (is (true? (facts/restricted-entry-interval-satisfied? 48 insecticide))))

    (testing "hours-until-reentry below REI fails"
      (is (false? (facts/restricted-entry-interval-satisfied? 6 insecticide))))

    (testing "mechanical harvest crop-operation type has no REI to satisfy"
      (is (false? (facts/restricted-entry-interval-satisfied? 6 bamboo))))))

(deftest wind-speed-in-range-test
  (let [insecticide (facts/crop-operation-type-by-id :spray/insecticide-bamboo-ground)
        bamboo (facts/crop-operation-type-by-id :harvest/bamboo-culm)]
    (testing "wind speed at or below ceiling passes"
      (is (true? (facts/wind-speed-in-range? 16.0 insecticide)))
      (is (true? (facts/wind-speed-in-range? 5.0 insecticide))))

    (testing "wind speed above ceiling fails"
      (is (false? (facts/wind-speed-in-range? 20.0 insecticide))))

    (testing "mechanical harvest crop-operation type has no wind-speed ceiling"
      (is (false? (facts/wind-speed-in-range? 5.0 bamboo))))))

(deftest buffer-zone-in-range-test
  (let [insecticide (facts/crop-operation-type-by-id :spray/insecticide-bamboo-ground)
        bamboo (facts/crop-operation-type-by-id :harvest/bamboo-culm)]
    (testing "buffer distance at or above minimum passes"
      (is (true? (facts/buffer-zone-in-range? 30.0 insecticide)))
      (is (true? (facts/buffer-zone-in-range? 50.0 insecticide))))

    (testing "buffer distance below minimum fails"
      (is (false? (facts/buffer-zone-in-range? 10.0 insecticide))))

    (testing "mechanical harvest crop-operation type has no buffer-zone minimum"
      (is (false? (facts/buffer-zone-in-range? 50.0 bamboo))))))

;; ──────────────────────── Evidence Completeness ──────────────────────

(deftest required-evidence-satisfied-test
  (testing "complete evidence checklist passes"
    (let [j (facts/jurisdiction-by-id :jp/maff)
          evidence [:cultivation-lot-record :field-boundary-map :application-record
                    :applicator-license :weather-log :buffer-zone-assessment]]
      (is (true? (facts/required-evidence-satisfied? j evidence)))))

  (testing "incomplete evidence fails"
    (let [j (facts/jurisdiction-by-id :jp/maff)
          evidence [:cultivation-lot-record :field-boundary-map]]
      (is (false? (facts/required-evidence-satisfied? j evidence)))))

  (testing "raw jurisdiction id call convention also works"
    (let [evidence [:cultivation-lot-record :field-boundary-map :application-record
                    :applicator-license :weather-log :buffer-zone-assessment]]
      (is (true? (facts/required-evidence-satisfied? :us/epa evidence)))))

  (testing "unknown jurisdiction never satisfies"
    (is (false? (facts/required-evidence-satisfied? :xx/unknown [])))))
