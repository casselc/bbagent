(ns bbagent.orientation
  "Model-facing capability orientation.

  A1's dogfood showed the model guessing eleven nonexistent Vars rather than
  using the discovery surface it already had.  This namespace turns the same
  authority description that feeds the TUI capability pane and bb4t's own
  `apropos`/`doc` into a short preamble for the model, so orientation is one
  more projection of a single authority description rather than parallel prose
  that would have to be maintained as capabilities change:

      RuntimeCatalog / ContextSpec
                |
        context description
                |
        +-------+--------+-----------------+
        |                |                 |
   TUI capability   apropos / doc    model preamble
        pane                            (here)

  Everything here is pure and derives from the context description.  Nothing
  here grants, requests, or describes authority the runtime did not project."
  (:require [clojure.string :as str]))

(def modes
  "Orientation variants.  :none is A0/A1 behaviour.

  :grounded is :generated plus a grounding constraint.  The A1.1 comparison
  showed that listing operations makes the model discover its surface but not
  refuse an impossible request: it guessed filenames, read them successfully,
  and then asserted what the project \"contains\".  Knowing which operations
  exist does not by itself stop an unsupported claim about the world.

  :derived is :grounded's successor.  :grounded works, and was measured, but
  it states the model's limits as prose -- \"you cannot enumerate a
  directory\" -- which becomes false the moment a capability to enumerate is
  granted.  :derived makes the same claims structurally instead: the list is
  closed, and the listed operations are the only means of observation.  Both
  stay true whatever the surface becomes."
  #{:none :minimal :generated :grounded :derived})

(defn mode
  "Normalizes an orientation selection.  nil means :none."
  [value]
  (let [selected (cond
                   (nil? value) :none
                   (keyword? value) value
                   (string? value) (keyword value)
                   :else ::invalid)]
    (when-not (contains? modes selected)
      (throw (ex-info "Unknown orientation mode"
                      {:bbagent/error :agent-invalid-action
                       :orientation/mode value})))
    selected))

(def minimal-instruction
  "The smallest possible orientation: name the discovery surface that already
   exists.  This adds no capability; `apropos` and `doc` are already granted."
  (str "Discover your operations before using them. "
       "(apropos \"\") lists every operation you can call. "
       "(doc some/operation) shows one operation's arguments, docstring, and "
       "effects. Only call operations that appear in that list. If a task "
       "needs an operation that is not listed, say so plainly instead of "
       "guessing at names."))

(def grounding-constraint
  "Constrains claims, not operations.  Listing authority tells the model what
   it may call; this tells it what it may assert.

   Frozen: this is the exact wording A1.1 measured at 3/3.  It names a
   specific absent capability, so it goes stale; `derived-constraint` is the
   replacement.  Kept unchanged so the measured result keeps covering the
   text that produced it."
  (str "Only state what your operations actually returned. You cannot "
       "enumerate a directory, so never say what a project contains or that "
       "a file is absent; a read failing does not mean a file does not "
       "exist. If a question requires enumeration, say that you cannot "
       "enumerate and ask for explicit paths."))

(def derived-constraint
  "Constrains claims without naming a capability, absent or present.

   Every sentence is either capability-independent or refers structurally to
   the generated operation list, so nothing here can be falsified by granting
   a capability.  When an enumerating operation is granted it joins that list,
   and the model may then state what a project contains -- because a listed
   operation returned it, which is exactly the rule this states."
  (str "Only state what your operations actually returned. The operations "
       "above are your only means of observing this project: if answering "
       "needs an observation that none of them provides, say so plainly and "
       "ask for what you need instead of inferring it. A call that fails or "
       "returns nothing is not evidence about the world; it does not "
       "establish that what you asked for is absent."))

(def ^:private max-listed-operations
  "The preamble is a short orientation, not a catalog dump."
  12)

(def ^:private max-doc-characters 120)

(defn- summarize-doc [doc]
  (let [text (some-> doc str str/trim (str/replace #"\s+" " "))]
    (cond
      (str/blank? text) nil
      (<= (count text) max-doc-characters) text
      :else (str (subs text 0 max-doc-characters) "..."))))

(defn- operation-line
  "One line per projected operation, using the runtime's own arglists and doc."
  [{:keys [sci/var arglists doc]}]
  (let [call (if-let [args (first arglists)]
               (str "(" var (when (seq args)
                              (str " " (str/join " " args))) ")")
               (str "(" var " ...)"))]
    (str "  " call (when-let [d (summarize-doc doc)] (str " - " d)))))

(defn operations
  "The projected operations, sorted, as inert data.  Same source as the TUI
   capability pane: the context description's surface projections."
  [context-description]
  (->> (get-in context-description [:context/surface :projections])
       (sort-by (comp str :sci/var))
       vec))

(defn- preamble
  "The shape both preamble variants share: a header, the projected
  operations, and a closing paragraph the variant supplies.

  Returns nil when the description projects no operations, so a context with
  no authority does not gain a paragraph claiming it has some."
  [context-description closing]
  (let [ops (operations context-description)
        profile (get-in context-description
                        [:context/effective :context/profile])]
    (when (seq ops)
      (let [listed (take max-listed-operations ops)
            omitted (- (count ops) (count listed))
            complete? (zero? omitted)]
        (str/join
         "\n"
         (concat
          [(str "Your bounded Clojure REPL projects "
                (if complete?
                  "exactly these operations"
                  (str "these " (count listed) " of its " (count ops)
                       " operations"))
                (when profile (str " under profile " profile))
                ":")]
          (map operation-line listed)
          (when-not complete?
            [(str "  ... and " omitted " more not shown here.")])
          [""
           (closing {:count (count ops) :complete? complete?})]))))))

(defn- generated-closing
  "Frozen: the exact closing A1.1 measured.  It enumerates absent authority,
   which is what `derived-closing` exists to stop doing."
  [{:keys [count complete?]}]
  (str (if complete?
         "That list is complete."
         (str "That list is partial; (apropos \"\") returns all " count "."))
       " You have no file listing, search, "
       "editing, shell, process, network, or host API operation. "
       minimal-instruction))

(defn- derived-closing
  "States closure rather than absence.

   \"Nothing outside this list\" carries the same practical meaning as \"you
   have no file listing\" for any surface, and unlike an enumeration of
   absent things it is true of every surface, including one that gains the
   capability the enumeration denied."
  [{:keys [count complete?]}]
  (str (if complete?
         (str "That list is complete: those " count
              " operations are your whole authority over this project, not a"
              " summary of it.")
         (str "That list is partial; (apropos \"\") returns all " count
              ", and they are your whole authority over this project, not a"
              " summary of it."))
       " Anything not in it is unavailable to you. "
       minimal-instruction))

(defn generated-preamble
  "Builds a bounded capability preamble from the context description."
  [context-description]
  (preamble context-description generated-closing))

(defn derived-preamble
  "Builds the same preamble with a closing that cannot go stale."
  [context-description]
  (preamble context-description derived-closing))

(defn compose
  "Composes the system prompt the model actually receives.

  The base prompt is always first, so orientation augments the application's
  prompt rather than replacing it.  Returns the base unchanged for :none."
  [base orientation-mode context-description]
  (let [selected (mode orientation-mode)
        addition (case selected
                   :none nil
                   :minimal minimal-instruction
                   :generated (generated-preamble context-description)
                   :grounded (some-> (generated-preamble context-description)
                                     (str "\n" grounding-constraint))
                   :derived (some-> (derived-preamble context-description)
                                    (str "\n" derived-constraint)))]
    (if (str/blank? (str addition))
      base
      (str base "\n\n" addition))))
