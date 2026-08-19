(ns bbagent.tui.spike
  "A1 TUI runtime spike: start, draw, key input, resize, clean exit."
  (:require [charm.program :as program]
            [charm.message :as msg]
            [charm.style.core :as style])
  (:gen-class))

(defn init [] {:keys-seen [] :size nil :resizes 0})

(defn update-state [state m]
  (cond
    (msg/window-size? m)
    [(-> state (assoc :size [(:width m) (:height m)])
                (update :resizes inc)) nil]

    (msg/key-press? m)
    (let [k (:key m)]
      (if (or (= k "q") (and (:ctrl m) (= k "c")))
        [(assoc state :bye true) program/quit-cmd]
        [(update state :keys-seen conj (str k)) nil]))

    :else [state nil]))

(defn view [state]
  (str (style/render (style/style {:foreground :cyan :bold true}) "SPIKE-READY") "\r\n"
       "size=" (pr-str (:size state)) "\r\n"
       "resizes=" (:resizes state) "\r\n"
       "keys=" (pr-str (:keys-seen state)) "\r\n"
       "press q to quit\r\n"))

(defn -main [& _]
  (program/run {:init init :update update-state :view view
                :alt-screen false :hide-cursor true :fps 30})
  (println "SPIKE-EXIT-CLEAN")
  (flush)
  (System/exit 0))
