(ns bbagent.storage
  "S0b backend selection and root store factory.

  Normalizes the operator-facing backend value and opens the matching
  root-level bbagent.store implementation.  Callers never name concrete
  storage namespaces; the durable contract is the store protocol."
  (:require [bbagent.errors :as errors]
            [bbagent.journal :as journal]
            [bbagent.sqlite-store :as sqlite-store]))

(def ^:private backends #{:file :sqlite})

(defn backend
  "Normalizes a backend selection to :file or :sqlite.  Accepts the
   keywords or the strings \"file\"/\"sqlite\"; nil defaults to :file.
   Anything else is rejected with :journal-storage-failure."
  [value]
  (let [selected (cond
                   (nil? value) :file
                   (keyword? value) value
                   (string? value) (keyword value)
                   :else ::invalid)]
    (when-not (contains? backends selected)
      (throw (errors/error :journal-storage-failure
                           "Unknown store backend"
                           {:store/backend value})))
    selected))

(defn open!
  "Opens the root-level durable store for the selected backend at
   state-root.  The SQLite backend lives at state-root/bbagent.sqlite3;
   the file backend keeps per-session journals under state-root/sessions."
  [state-root backend-value]
  (case (backend backend-value)
    :file (journal/file-store state-root)
    :sqlite (sqlite-store/sqlite-store state-root)))
