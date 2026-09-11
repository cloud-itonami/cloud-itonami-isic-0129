(ns perennial.store
  "Store abstraction for perennial-crop cultivation lots. Current
  implementation operates on plain data (`{:cultivation-lots
  {cultivation-lot-id lot-map} :facts [...]}`); production should migrate
  this seam to Datomic/kotoba-server (the same seam point all cloud-itonami
  actors use) while keeping the same pure-function surface.

  A cultivation lot is the minimal unit of work: one perennial-crop field
  or plot GROWN AND OWNED BY THE OPERATOR (never a client farm -- that is
  what distinguishes ISIC 0129 from ISIC 0161, support activities for crop
  production, which never owns the crop it services). Representative
  cultivation-lot keys:
    - :crop-operation-type keyword crop-operation-type id (see
      `perennial.facts/crop-operation-types`)
    - :jurisdiction keyword jurisdiction id (see `perennial.facts/jurisdictions`)
    - :field-id the operator's own field/plot identifier
    - :field-area-hectares cultivated field area
    - :evidence-checklist evidence items present for the cultivation lot
    - :applicator-license-expiry-date epoch-ms of the applicator's license
      expiry (nil for mechanical harvest/maintenance operation types)
    - :sprayer-last-calibration-date epoch-ms of last sprayer/applicator
      equipment calibration (nil for mechanical operation types)
    - :days-until-harvest days between the application and this lot's
      expected harvest date (nil for mechanical operation types)
    - :hours-until-reentry planned gap before workers re-enter the treated
      field (nil for mechanical operation types)
    - :wind-speed-kmh actual wind speed at time of application (nil for
      mechanical operation types)
    - :buffer-zone-actual-m actual distance maintained to the nearest
      sensitive site (nil for mechanical operation types)
    - :crop-health-concern-raised? / :crop-health-concern-resolved? open
      pest/disease concern flag
    - :logged? true once a `:log-cultivation-record` proposal commits
    - :scheduled? true once a `:schedule-field-operation` proposal commits

  The ledger (`:facts`) is a separate append-only vector of audit facts,
  kept alongside `:cultivation-lots` in the same store value.")

(defn cultivation-lot
  "Retrieve a cultivation lot by id, or nil if it does not exist / is not
  yet registered."
  [st cultivation-lot-id]
  (get-in st [:cultivation-lots cultivation-lot-id]))

(defn cultivation-lot-registered?
  "True only if the cultivation lot exists in the store -- registration
  is the HARD invariant that must be independently verified before ANY
  of this actor's four proposal ops can be made against it."
  [st cultivation-lot-id]
  (some? (cultivation-lot st cultivation-lot-id)))

(defn cultivation-lot-already-logged?
  "True only if the cultivation lot exists and has already been marked
  logged."
  [st cultivation-lot-id]
  (true? (:logged? (cultivation-lot st cultivation-lot-id))))

(defn log-cultivation-record
  "Register/update `lot-data` under `cultivation-lot-id` and mark it
  logged (one-way flag). Used once a `:log-cultivation-record` proposal
  commits."
  [st cultivation-lot-id lot-data]
  (assoc-in st [:cultivation-lots cultivation-lot-id] (assoc lot-data :logged? true)))

(defn mark-scheduled
  "Mark an existing cultivation lot as scheduled (one-way flag). Used
  once a `:schedule-field-operation` proposal commits."
  [st cultivation-lot-id]
  (assoc-in st [:cultivation-lots cultivation-lot-id :scheduled?] true))

(defn audit-trail
  "Return the append-only audit ledger (empty vector if none yet)."
  [st]
  (get st :facts []))

(defn append-fact
  "Append `fact` to the store's audit ledger."
  [st fact]
  (update st :facts (fnil conj []) fact))
