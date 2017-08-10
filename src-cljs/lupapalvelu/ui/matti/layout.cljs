(ns lupapalvelu.ui.matti.layout
  (:require [clojure.set :as set]
            [clojure.string :as s]
            [lupapalvelu.matti.shared :as shared]
            [lupapalvelu.ui.common :as common]
            [lupapalvelu.ui.components :as components]
            [lupapalvelu.ui.matti.docgen :as docgen]
            [lupapalvelu.ui.matti.path :as path]
            [lupapalvelu.ui.matti.service :as service]
            [lupapalvelu.ui.matti.state :as state]
            [lupapalvelu.ui.matti.phrases :as phrases]
            [rum.core :as rum]
            [sade.shared-util :as util]))


(defn schema-type [schema-type]
  (-> schema-type :schema keys first keyword))

(declare matti-list)

;; -------------------------------
;; Miscellaneous components
;; -------------------------------

(defn empty-label []
  [:label {:dangerouslySetInnerHTML {:__html "&nbsp;"}}])

(defn show-label? [{label? :label?} wrap-label?]
  (and wrap-label? (not (false? label?))))

(rum/defc matti-date-delta < rum/reactive
  [{:keys [state path schema] :as options}  & [wrap-label?]]
  (let [enabled-path (path/extend path :enabled)]
    [:div.matti-date-delta
     (when (show-label? schema wrap-label?)
       [:div.delta-label (path/loc path)])
     [:div.delta-editor
      (docgen/docgen-checkbox (assoc options
                                     :path enabled-path
                                     :schema (assoc schema :label false)))
      "+"
      (docgen/text-edit (assoc options :path (path/extend path :delta))
                        :input.grid-style-input
                        {:type "number"
                         :disabled (not (path/react enabled-path state))})
      (common/loc (str "matti-date-delta." (-> schema :unit name)))]]))


(rum/defc matti-multi-select < rum/reactive
  [{:keys [state path schema] :as options}  & [wrap-label?]]
  [:div.matti-multi-select
   (when (show-label? schema wrap-label?)
     [:h4.matti-label (path/loc path schema)])
   (let [state (path/state path state)
         items (->> (:items schema)
                    (map (fn [item]
                           (if (:value item)
                             item
                             (let [item (keyword item)]
                               {:value item
                                :text  (if-let [item-loc (:item-loc-prefix schema)]
                                         (path/loc [item-loc item])
                                         (path/loc path schema item))}))))
                    (sort-by :text))]

     (for [{:keys [value text]} items
           :let                 [item-id (path/id (path/extend path value))
                                 checked (util/includes-as-kw? (set (rum/react state)) value)]]
       [:div.matti-checkbox-wrapper
        {:key item-id}
        [:input {:type    "checkbox"
                 :id      item-id
                 :checked checked}]
        [:label.matti-checkbox-label
         {:for      item-id
          :on-click (fn [_]
                      (swap! state
                             (fn [xs]
                               ;; Sanitation on the client side.
                               (util/intersection-as-kw (if checked
                                                          (remove #(util/=as-kw value %) xs)
                                                          (cons value xs))
                                                        (map :value items))))
                      (path/meta-updated options))}
         text]]))])

(defn- resolve-reference-list
  "List of :value, :text maps"
  [{:keys [path schema]}]
  (let [{:keys [item-key item-loc-prefix term]}        schema
        {:keys [extra-path match-key] term-path :path} term
        match-key                                      (or match-key item-key)]
    (->> (path/value (:path schema) state/references)
         (remove :deleted) ; Safe for non-maps
         (map (fn [x]
                (let [v (if item-key (item-key x) x)]
                  {:value v
                   :text  (cond
                            (and match-key term-path) (-> (util/find-by-key match-key
                                                                            v
                                                                            (path/value term-path
                                                                                        state/references))
                                                          (get-in (cond->> [(keyword (common/get-current-language))]
                                                                    extra-path (concat extra-path))))
                            item-loc-prefix          (path/loc [item-loc-prefix v])
                            :else                    (path/loc path schema v))})))
         distinct)))

(rum/defc select-reference-list < rum/reactive
  [{:keys [state path schema ] :as options} & [wrap-label?]]
  (let [options   (assoc options
                         :schema (assoc schema
                                        :body [{:body (map #(hash-map :name %)
                                                           (distinct (path/react (:path schema)
                                                                                 state/references)))}]))
        component (docgen/docgen-select options)]
    (if (show-label? schema wrap-label?)
      (docgen/docgen-label-wrap options component)
      component)))

(rum/defc multi-select-reference-list < rum/reactive
  [{:keys [state path schema] :as options} & [wrap-label?]]
  (matti-multi-select (assoc-in options [:schema :items] (resolve-reference-list options))
                      wrap-label?))

(rum/defc last-saved < rum/reactive
  [{state :state}]
  [:span.saved-info
   (when-let [ts (path/react [:modified] state)]
     (common/loc :matti.last-saved (js/util.finnishDateAndTime ts)))])

(rum/defcs matti-phrase-text < rum/reactive
  (rum/local nil ::category)
  (rum/local "" ::selected)
  (rum/local "" ::replaced)
  [{category* ::category
    selected* ::selected
    replaced* ::replaced :as local-state}
   {:keys [state path schema] :as options} & [wrap-label?]]
  (letfn [(set-category [category]
            (when (util/not=as-kw category @category*)
              (reset! category* (name category))
              (reset! selected* "")))
          (update-text [text]
            (reset! (path/state path state) text)
            (path/meta-updated options))]
    (when-not @category*
      (set-category (:category schema)))
    (let [ref-id (str (path/id path) "-ref")]
      [:div.matti-grid-12
       (when (show-label? schema wrap-label?)
         [:h4.matti-label (path/loc path schema)])
       [:div.row
        [:div.col-3
         [:div.col--vertical
          [:label (common/loc :phrase.add)]
          (phrases/phrase-category-select @category* set-category)]]
        [:div.col-6
         [:div.col--vertical
          (empty-label)
          (components/autocomplete selected*
                                   {:items    (phrases/phrase-list-for-autocomplete (rum/react category*))
                                    :callback #(let [text-node (.-firstChild (rum/ref local-state ref-id) )
                                                     sel-start (.-selectionStart text-node)
                                                     sel-end   (.-selectionEnd text-node)
                                                     old-text  (or (path/value path state) "")]
                                                 (reset! replaced* (subs old-text sel-start sel-end))
                                                 (update-text (s/join (concat (take sel-start old-text)
                                                                              %
                                                                              (drop sel-end old-text)))))})]]
        [:div.col-3.col--right
         [:div.col--vertical
          (empty-label)
          [:div.inner-margins
           [:button.primary.outline
            {:on-click (fn []
                         (update-text "")
                         (reset! selected* ""))}
            (common/loc :matti.clear)]
           [:button.primary.outline
            {:disabled (let [phrase (rum/react selected*)]
                         (or (s/blank? phrase)
                             (not (re-find (re-pattern (goog.string/regExpEscape phrase))
                                           (path/react path state)))))
             :on-click (fn []
                         (update-text (s/replace-first (path/value path state)
                                                       @selected*
                                                       @replaced*))
                         (reset! selected* ""))}
            (common/loc :phrase.undo)]]]]]
       [:div.row
        [:div.col-12.col--full
         {:ref ref-id}
         (components/textarea-edit (path/state path state)
                                   {:callback update-text})]]])))

;; -------------------------------
;; Component instantiation
;; -------------------------------

(defmulti instantiate (fn [_ cell & _]
                      (schema-type cell)))

(defmethod instantiate :docgen
  [{:keys [state path]} {:keys [schema id]} & [wrap-label?]]
  (let [schema-name (:docgen schema)
        options     (shared/child-schema {:state  state
                                          :path   (path/extend path id)
                                          :schema (service/schema schema-name)}
                                         :schema
                                         schema)
        editing?    (path/meta? options :editing? )]
    (cond->> options
      editing?       docgen/docgen-component
      (not editing?) docgen/docgen-view
      wrap-label?    (docgen/docgen-label-wrap options ))))

(defmethod instantiate :default
  [{:keys [state path] :as options} {:keys [schema id] :as cell} & [wrap-label?]]
  (let [cell-type    (schema-type cell)
        schema-value (-> schema vals first)
        options      (shared/child-schema (assoc options
                                                 :state  state
                                                 :path   (path/extend path id (:id schema-value))
                                                 :schema schema-value)
                                          :schema
                                          schema)]
    ((case cell-type
       :list           matti-list
       :date-delta     matti-date-delta
       :reference-list (if (= :select (:type schema-value))
                         select-reference-list
                         multi-select-reference-list)
       :multi-select   matti-multi-select
       :phrase-text    matti-phrase-text) options wrap-label?)))

(defmethod instantiate :loc-text
  [_ {:keys [schema]} & wrap-label?]
  [:span
   (common/loc (name (:loc-text schema)))])

(defn- sub-options
  "Options for the subschema"
  [{:keys [state path]} subschema]
  (assoc subschema
         :state state
         :path (path/extend path (:id subschema))))

(defn matti-list [{:keys [schema state path] :as options} & [wrap-label?]]
  [:div.matti-list
   {:class (path/css (sub-options options schema))}
   (when (and wrap-label? (:title schema))
     [:h4.matti-label (common/loc (:title schema))])
   (map-indexed (fn [i item]
                  (when-let [component (instantiate (assoc options
                                                           :path (path/extend path
                                                                   (when-not (:id item)
                                                                     (str i))))
                                                    (shared/child-schema item
                                                                         :schema
                                                                         schema)
                                               wrap-label?)]
                    [:div.item {:key   (str "item-" i)
                                :class (path/css (sub-options options item)
                                                 (when (:align item)
                                                   (str "item--" (-> item :align name))))}
                     component]))
                (:items schema))])

(defn matti-grid [{:keys [grid state path] :as options}]
  [:div
   {:class (path/css (sub-options options grid)
                     (str "matti-grid-" (:columns grid)))}
   (map-indexed (fn [row-index row]
                  (let [row-index (get row :id row-index)]
                    [:div.row {:class (some->> row :css (map name) s/join )}
                    (map-indexed (fn [col-index {:keys [col align schema id] :as cell}]
                                   [:div {:class (path/css (sub-options options cell)
                                                           (str "col-" (or col 1))
                                                           (when align (str "col--" (name align))))}
                                    (when schema
                                      (instantiate (assoc options
                                                          :path (path/extend path
                                                                  (str row-index)
                                                                  (when-not id
                                                                    (str col-index))))
                                                   (shared/child-schema cell :schema grid) true))])
                                 (get row :row row))]))
                (:rows grid))])