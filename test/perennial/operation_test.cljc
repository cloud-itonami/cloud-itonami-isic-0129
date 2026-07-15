(ns perennial.operation-test
  (:require [clojure.test :refer [deftest is testing]]
            [perennial.operation :as operation]
            [perennial.governor :as governor]))

(def ^:private now-ms #?(:clj (System/currentTimeMillis) :cljs (.now js/Date)))
(def ^:private ten-days-ago (- now-ms (* 10 24 60 60 1000)))
(def ^:private ten-days-from-now (+ now-ms (* 10 24 60 60 1000)))

(def ^:private clean-herbicide-lot
  {:crop-operation-type :spray/herbicide-nursery-broadcast
   :jurisdiction :jp/maff
   :field-id "field-42"
   :applicator-license-expiry-date ten-days-from-now
   :sprayer-last-calibration-date ten-days-ago
   :days-until-harvest 20
   :hours-until-reentry 24
   :wind-speed-kmh 10.0
   :buffer-zone-actual-m 20.0
   :evidence-checklist [:cultivation-lot-record :field-boundary-map :application-record
                        :applicator-license :weather-log :buffer-zone-assessment]})

(deftest run-operation-commit-test
  (testing "clean, non-actuation proposal commits with no hold facts"
    (let [store {:cultivation-lots {"lot-001" clean-herbicide-lot}}
          request {:op :schedule-field-operation :subject "lot-001"}
          proposal {:cites [{:spec "Field-Schedule"}]
                    :value {:jurisdiction :jp/maff}
                    :effect :propose
                    :confidence 0.9}
          context {:actor-id "op-1" :hold-fact-fn governor/hold-fact}
          result (operation/run-operation request context proposal store governor/check)]
      (is (true? (:ok? result)))
      (is (= [] (:facts result))))))

(deftest run-operation-hold-test
  (testing "hard-violating proposal (already-logged lot) produces a hold fact"
    (let [store {:cultivation-lots {"lot-002" {:crop-operation-type :spray/herbicide-nursery-broadcast
                                                :logged? true}}}
          request {:op :log-cultivation-record :subject "lot-002"}
          proposal {:cites [{:spec "ISO-12345"}]
                    :value {:jurisdiction :jp/maff}
                    :effect :propose
                    :confidence 0.9}
          context {:actor-id "op-1" :hold-fact-fn governor/hold-fact}
          result (operation/run-operation request context proposal store governor/check)]
      (is (false? (:ok? result)))
      (is (= 1 (count (:facts result))))
      (is (= :governor-hold (:t (first (:facts result)))))
      (is (true? (:hard? (:verdict result)))))))

(deftest run-operation-escalate-test
  (testing "clean but high-stakes proposal is not auto-ok (escalation required)"
    (let [store {:cultivation-lots {"lot-003" clean-herbicide-lot}}
          request {:op :log-cultivation-record :subject "lot-003"}
          proposal {:cites [{:spec "ISO-12345"}]
                    :value {:jurisdiction :jp/maff}
                    :effect :propose
                    :confidence 0.95}
          context {:actor-id "op-1" :hold-fact-fn governor/hold-fact}
          result (operation/run-operation request context proposal store governor/check)]
      (is (false? (:ok? result)))
      (is (false? (:hard? (:verdict result))))
      (is (true? (:escalate? (:verdict result))))
      ;; operation.cljc has a single :ok?/not-ok? gate today; both hard-hold
      ;; and escalate-only verdicts route through the same hold-fact-fn.
      ;; Callers distinguish the two by inspecting `(:verdict result)`.
      (is (= 1 (count (:facts result)))))))

(deftest run-operation-crop-health-concern-always-escalates-test
  (testing "a clean flag-crop-health-concern proposal is never auto-ok"
    (let [store {:cultivation-lots {"lot-004" clean-herbicide-lot}}
          request {:op :flag-crop-health-concern :subject "lot-004"}
          proposal {:cites [{:spec "Field-Report"}]
                    :value {:jurisdiction :jp/maff}
                    :effect :propose
                    :confidence 0.99}
          context {:actor-id "op-1" :hold-fact-fn governor/hold-fact}
          result (operation/run-operation request context proposal store governor/check)]
      (is (false? (:ok? result)))
      (is (false? (:hard? (:verdict result))))
      (is (true? (:escalate? (:verdict result)))))))

(deftest run-operation-op-not-allowed-test
  (testing "an out-of-allowlist op (e.g. direct field-equipment operation) is a hard, permanent block"
    (let [store {:cultivation-lots {"lot-005" clean-herbicide-lot}}
          request {:op :operate-harvester :subject "lot-005"}
          proposal {:cites [{:spec "Harvester-Manual"}]
                    :value {:jurisdiction :jp/maff}
                    :effect :propose
                    :confidence 0.99}
          context {:actor-id "op-1" :hold-fact-fn governor/hold-fact}
          result (operation/run-operation request context proposal store governor/check)]
      (is (false? (:ok? result)))
      (is (true? (:hard? (:verdict result))))
      (is (some #(= (:rule %) :op-not-allowed) (:violations (:verdict result)))))))

(deftest run-operation-effect-not-propose-test
  (testing "a proposal asserting a non-:propose effect is a hard, permanent block"
    (let [store {:cultivation-lots {"lot-006" clean-herbicide-lot}}
          request {:op :schedule-field-operation :subject "lot-006"}
          proposal {:cites [{:spec "Field-Schedule"}]
                    :value {:jurisdiction :jp/maff}
                    :effect :commit
                    :confidence 0.9}
          context {:actor-id "op-1" :hold-fact-fn governor/hold-fact}
          result (operation/run-operation request context proposal store governor/check)]
      (is (false? (:ok? result)))
      (is (true? (:hard? (:verdict result))))
      (is (some #(= (:rule %) :effect-not-propose) (:violations (:verdict result)))))))

(deftest run-operation-cultivation-lot-not-registered-test
  (testing "any op against a never-registered cultivation lot is a hard block"
    (let [store {:cultivation-lots {}}
          request {:op :schedule-field-operation :subject "lot-999"}
          proposal {:cites []
                    :value {}
                    :effect :propose
                    :confidence 0.9}
          context {:actor-id "op-1" :hold-fact-fn governor/hold-fact}
          result (operation/run-operation request context proposal store governor/check)]
      (is (false? (:ok? result)))
      (is (true? (:hard? (:verdict result))))
      (is (some #(= (:rule %) :cultivation-lot-not-registered) (:violations (:verdict result)))))))

(deftest run-operation-high-cost-supply-order-escalates-test
  (testing "a supply order above the cost threshold is not auto-ok"
    (let [store {:cultivation-lots {"lot-007" clean-herbicide-lot}}
          request {:op :order-supplies :subject "lot-007"}
          proposal {:cites [{:spec "Supplier-Catalog"}]
                    :value {:jurisdiction :jp/maff :cost-usd 10000}
                    :effect :propose
                    :confidence 0.9}
          context {:actor-id "op-1" :hold-fact-fn governor/hold-fact}
          result (operation/run-operation request context proposal store governor/check)]
      (is (false? (:ok? result)))
      (is (false? (:hard? (:verdict result))))
      (is (true? (:escalate? (:verdict result)))))))

(deftest run-operation-low-cost-supply-order-commits-test
  (testing "a supply order at or below the cost threshold commits when clean"
    (let [store {:cultivation-lots {"lot-008" clean-herbicide-lot}}
          request {:op :order-supplies :subject "lot-008"}
          proposal {:cites [{:spec "Supplier-Catalog"}]
                    :value {:jurisdiction :jp/maff :cost-usd 1000}
                    :effect :propose
                    :confidence 0.9}
          context {:actor-id "op-1" :hold-fact-fn governor/hold-fact}
          result (operation/run-operation request context proposal store governor/check)]
      (is (true? (:ok? result)))
      (is (= [] (:facts result))))))
