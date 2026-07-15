(ns perennial.store-test
  (:require [clojure.test :refer [deftest is testing]]
            [perennial.store :as store]))

;; ──────────────────────── Cultivation-Lot Retrieval ──────────────────────

(deftest cultivation-lot-test
  (testing "retrieve an existing cultivation lot"
    (let [lot-data {:crop-operation-type :spray/herbicide-nursery-broadcast :field-id "field-42"}
          st {:cultivation-lots {"lot-001" lot-data}}
          result (store/cultivation-lot st "lot-001")]
      (is (= result lot-data))))

  (testing "nonexistent cultivation lot returns nil"
    (let [st {:cultivation-lots {}}
          result (store/cultivation-lot st "nonexistent")]
      (is (nil? result)))))

(deftest cultivation-lot-registered-test
  (testing "registered cultivation lot returns true"
    (let [st {:cultivation-lots {"lot-001" {:field-id "field-42"}}}
          result (store/cultivation-lot-registered? st "lot-001")]
      (is (true? result))))

  (testing "unregistered cultivation lot returns false"
    (let [st {:cultivation-lots {}}
          result (store/cultivation-lot-registered? st "lot-999")]
      (is (false? result)))))

;; ──────────────────────── Cultivation-Lot Status Checks ──────────────────────

(deftest cultivation-lot-already-logged-test
  (testing "logged cultivation lot is detected"
    (let [st {:cultivation-lots {"lot-001" {:logged? true}}}
          result (store/cultivation-lot-already-logged? st "lot-001")]
      (is (true? result))))

  (testing "unlogged cultivation lot returns false"
    (let [st {:cultivation-lots {"lot-001" {:logged? false}}}
          result (store/cultivation-lot-already-logged? st "lot-001")]
      (is (false? result))))

  (testing "nonexistent cultivation lot returns false"
    (let [st {:cultivation-lots {}}
          result (store/cultivation-lot-already-logged? st "lot-001")]
      (is (false? result)))))

;; ──────────────────────── Cultivation-Lot Logging ──────────────────────

(deftest log-cultivation-record-test
  (testing "logging a cultivation lot marks it as logged"
    (let [st {:cultivation-lots {}}
          lot-data {:crop-operation-type :spray/herbicide-nursery-broadcast}
          result (store/log-cultivation-record st "lot-001" lot-data)]
      (is (true? (get-in result [:cultivation-lots "lot-001" :logged?])))))

  (testing "logging preserves cultivation-lot data"
    (let [st {:cultivation-lots {}}
          lot-data {:crop-operation-type :spray/herbicide-nursery-broadcast :field-id "field-42"}
          result (store/log-cultivation-record st "lot-001" lot-data)]
      (is (= (:crop-operation-type (get-in result [:cultivation-lots "lot-001"])) :spray/herbicide-nursery-broadcast))
      (is (= (:field-id (get-in result [:cultivation-lots "lot-001"])) "field-42")))))

;; ──────────────────────── Cultivation-Lot Scheduling ──────────────────────

(deftest mark-scheduled-test
  (testing "marking a cultivation lot marks it as scheduled"
    (let [st {:cultivation-lots {"lot-001" {:field-id "field-42"}}}
          result (store/mark-scheduled st "lot-001")]
      (is (true? (get-in result [:cultivation-lots "lot-001" :scheduled?]))))))

;; ──────────────────────── Audit Trail ──────────────────────

(deftest audit-trail-test
  (testing "audit trail is initially empty"
    (let [st {:facts []}
          result (store/audit-trail st)]
      (is (empty? result))))

  (testing "appended facts appear in audit trail"
    (let [st {:facts []}
          fact1 {:t :test-fact :detail "test 1"}
          fact2 {:t :test-fact :detail "test 2"}
          st' (store/append-fact st fact1)
          st'' (store/append-fact st' fact2)
          result (store/audit-trail st'')]
      (is (= (count result) 2))
      (is (= (first result) fact1))
      (is (= (second result) fact2)))))

(deftest append-fact-test
  (testing "appending a fact increases ledger length"
    (let [st {:facts []}
          fact {:t :governor-hold :op :log-cultivation-record}
          result (store/append-fact st fact)]
      (is (= (count (:facts result)) 1))
      (is (= (first (:facts result)) fact)))))
