(ns bbagent.a3c-smoke
  "A3c evidence: the guest, and what the workload is inside it.

   A3b's gates prove the semantic layer and re-prove A3a's isolation from
   the model-facing path.  They are re-run unchanged here, against a
   different substrate, which is most of the point.  These phases prove the
   things that are new: the workload is not root and holds no capabilities,
   the hiding it used to be able to undo it can no longer undo, the
   toolchain comes from an image rather than a host directory, and a guest
   nobody approved does not run."
  (:require [bb4t.execution :as execution]
            [bbagent.bb4t :as app-runtime]
            [bbagent.executor :as executor]
            [bbagent.worker :as worker]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- verdict [ok?] (if ok? :ok :failed))

(defn- session! [{:keys [project-root image]}]
  (app-runtime/create project-root :agent/project-execute
                      {:executor {:image image :project-root project-root}}))

(defn- evaluate [app source]
  (let [result (app-runtime/evaluate app source)]
    (if (= :ok (:status result))
      (get-in result [:evaluation :value :value/data])
      {:status :evaluation-error :error (:error result)})))

(defn- run
  "One project/run, written the way a model would have to write it."
  [app argv]
  (evaluate app (str "(project/run " (pr-str {:argv argv :timeout-ms 120000})
                     ")")))

(defn- shell [script] ["/bin/sh" "-c" script])

(defn describe!
  "What guest this host will run, and what it says about itself."
  [{:keys [image project-root]}]
  (let [environment (executor/create {:image image :project-root project-root})
        {:keys [description coordinate]} (execution/describe environment)]
    {:guest/coordinate coordinate
     :guest/description description
     :guest/identity (executor/project-identity project-root)
     :guest/available? true}))

(defn privilege!
  "What the workload is, and what it cannot do about that."
  [{:keys [project-root] :as settings}]
  (let [app (session! settings)
        who (run app (shell (str "id -u; id -g; "
                                 "grep CapEff /proc/self/status; "
                                 "grep CapPrm /proc/self/status")))
        escape (run app (shell (str "umount /input 2>&1 | head -1; "
                                    "echo umount-done; "
                                    "mount -t tmpfs tmpfs /mnt 2>&1 | head -1; "
                                    "echo mount-done; "
                                    "ls -a /input | tr '\\n' ' '; echo")))
        setuid (run app (shell (str "find / -xdev -perm -4000 -type f 2>/dev/null "
                                    "| head -3; echo scan-done")))
        guest-dirs (run app (shell (str "for p in /etc /usr/bin /usr/sbin /root; do "
                                        "if touch $p/probe 2>/dev/null; then "
                                        "echo \"writable $p\"; rm -f $p/probe; "
                                        "else echo \"readonly $p\"; fi; done")))
        workspace (run app (shell (str "mkdir -p a/b && echo x > a/b/f && "
                                       "echo y > src/a.txt && rm -f src/b.txt && "
                                       "echo workspace-ok")))
        expected (executor/project-identity project-root)
        lines (str/split-lines (str (:stdout who)))]
    {:privilege/uid (first lines)
     :privilege/gid (second lines)
     :privilege/runs-as-project-owner
     (verdict (and (= (str (:uid expected)) (str/trim (str (first lines))))
                   (= (str (:gid expected)) (str/trim (str (second lines))))))
     :privilege/not-root (verdict (not= "0" (str/trim (str (first lines)))))
     :privilege/no-capabilities
     (verdict (and (str/includes? (:stdout who) "CapEff:\t0000000000000000")
                   (str/includes? (:stdout who) "CapPrm:\t0000000000000000")))
     :privilege/cannot-unmask-input
     (verdict (and (re-find #"(?i)not permitted|must be superuser|permission denied"
                            (str (:stdout escape)))
                   ;; and the export is still empty afterwards
                   (let [after (last (str/split-lines (str (:stdout escape))))]
                     (= #{"." ".."} (set (remove str/blank?
                                                 (str/split (str after) #"\s+")))))))
     :privilege/cannot-mount
     (verdict (re-find #"(?i)not permitted|must be superuser|permission denied"
                       (str (second (str/split (str (:stdout escape))
                                               #"umount-done\R" 2)))))
     :privilege/no-setuid-binaries
     (verdict (= "scan-done" (str/trim (str (:stdout setuid)))))
     :privilege/guest-directories-readonly
     (verdict (and (not (str/includes? (:stdout guest-dirs) "writable"))
                   (str/includes? (:stdout guest-dirs) "readonly /etc")))
     :privilege/workspace-still-writable
     (verdict (and (= :completed (:status workspace))
                   (= 0 (:exit workspace))
                   (str/includes? (:stdout workspace) "workspace-ok")))}))

(defn image!
  "The guest is the image, and the image is pinned."
  [{:keys [image project-root] :as settings}]
  (let [app (session! settings)
        actual (:executor/guest
                (:description
                 (execution/describe
                  (executor/create {:image image :project-root project-root}))))
        digest (:image/digest actual)
        wrong (try
                (executor/create {:image image
                                  :project-root project-root
                                  :image-digest "sha256:0000000000000000"})
                :created
                (catch Exception failure (ex-message failure)))
        pinned (try
                 (do (executor/create {:image image
                                       :project-root project-root
                                       :image-digest digest})
                     :created)
                 (catch Exception failure (ex-message failure)))
        toolchain (run app (shell (str "command -v bb; bb --version; "
                                       "ls /opt/bbagent-tools")))
        ;; The claim is not "one mount" -- smolvm uses virtiofs for its own
        ;; plumbing and the mask adds another entry over /input.  The claim
        ;; is that no host directory is mounted for the toolchain, because
        ;; the toolchain is in the image.
        mounts (run app (shell (str "grep -c ' /opt/bbagent-tools ' /proc/mounts;"
                                    " echo tools-done;"
                                    " grep -c ' /input .*virtiofs' /proc/mounts")))
        missing (try
                  (executor/create {:image "/nonexistent/guest.tar"
                                    :project-root project-root})
                  :created
                  (catch Exception failure (ex-message failure)))]
    {:image/digest digest
     :image/digest-recorded (verdict (string? digest))
     :image/pinned-digest-accepted (verdict (= :created pinned))
     :image/wrong-digest-refused
     (verdict (and (string? wrong) (str/includes? wrong "does not match")))
     :image/missing-archive-refused
     (verdict (and (string? missing) (str/includes? missing "not readable")))
     :image/toolchain-from-image
     (verdict (and (str/includes? (:stdout toolchain) "/opt/bbagent-tools/bb")
                   (str/includes? (:stdout toolchain) "babashka")))
     :image/prelude-contract worker/prelude-contract
     :image/no-host-tool-directory-mounted
     (verdict (= "0" (str/trim (str (first (str/split-lines
                                            (str (:stdout mounts))))))))
     :image/project-is-mounted
     (verdict (pos? (parse-long
                     (str/trim (str (last (str/split-lines
                                           (str (:stdout mounts))))))))) }))

(defn contract!
  "A host and a guest that disagree do not run anything."
  [{:keys [project-root image]}]
  (let [identity (executor/project-identity project-root)
        wrong (worker/execute!
               {:project-root project-root
                :image image
                :identity identity
                :argv ["/bin/sh" "-c" "echo should-not-run"]
                :limits {:worker/timeout-ms 60000}
                :contract "999"})
        right (worker/execute!
               {:project-root project-root
                :image image
                :identity identity
                :argv ["/bin/sh" "-c" "echo ran"]
                :limits {:worker/timeout-ms 60000}})
        rooted (try
                 (worker/execute! {:project-root project-root
                                   :image image
                                   :identity {:uid 0 :gid 0}
                                   :argv ["/bin/true"]})
                 :ran
                 (catch Exception failure (ex-message failure)))]
    {:contract/version worker/prelude-contract
     :contract/mismatch-refused
     (verdict (and (= :worker-failure (:status wrong))
                   (not (contains? wrong :exit))
                   (not (str/includes? (str (:stdout wrong)) "should-not-run"))))
     :contract/match-runs
     (verdict (and (= :completed (:status right))
                   (str/includes? (str (:stdout right)) "ran")))
     :contract/root-identity-refused
     (verdict (and (string? rooted)
                   (str/includes? rooted "non-root uid")))}))

(defn dogfood!
  "The whole thing, against the checkout this image was built from."
  [{:keys [project-root] :as settings}]
  (let [app (session! settings)
        _ (app-runtime/evaluate
           app (str "(defn check [] (project/run {:argv [\"bb\" "
                    "\"script/a3a-source-check.clj\"] :timeout-ms 240000}))"))
        check (evaluate app "(check)")
        vandal (run app (shell (str "rm -rf src/bbagent; "
                                    "echo destroyed > README.md; "
                                    "echo generated > WORKER-WAS-HERE.txt; "
                                    "ls src 2>&1 | head -2")))]
    {:dogfood/exit (:exit check)
     :dogfood/duration-ms (:duration-ms check)
     :dogfood/stdout (str/trim (str (:stdout check)))
     :dogfood/check-passed
     (verdict (and (= :completed (:status check))
                   (= 0 (:exit check))
                   (str/includes? (str (:stdout check)) "a3a-source-check OK")))
     :dogfood/unprivileged-toolchain-works
     (verdict (= 0 (:exit check)))
     :dogfood/vandal-believed-it-succeeded
     (verdict (= :completed (:status vandal)))
     :dogfood/host-project-intact
     (verdict (and (.exists (io/file project-root "src" "bbagent" "worker.clj"))
                   (not (.exists (io/file project-root
                                          "WORKER-WAS-HERE.txt")))))}))
