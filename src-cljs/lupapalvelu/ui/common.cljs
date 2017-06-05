(ns lupapalvelu.ui.common)

(defn get-current-language []
  (.getCurrentLanguage js/loc))

(defn loc [& args]
  (apply js/loc (map name args)))

(defn query [query-name success-fn & kvs]
  (-> (js/ajax.query (clj->js query-name) (-> (apply hash-map kvs) clj->js))
      (.success (fn [js-result]
                  (success-fn (js->clj js-result :keywordize-keys true))))
      .call))


(defmulti command (fn [a & _]
                    (cond
                      (map? a)    :map
                      (string? a) :string)))

(defmethod command :map
  [{:keys [command  show-saved-indicator? success]} & kvs]
  (-> (js/ajax.command (clj->js command) (-> (apply hash-map kvs) clj->js))
      (.success (fn [js-result]
                  (when show-saved-indicator?
                    (js/util.showSavedIndicator js-result))
                  (when success
                    (success (js->clj js-result :keywordize-keys true)))))
      .call))

(defmethod command :string
  [command-name success-fn & kvs]
  (command {:command               command-name
            :show-saved-indicator? true
            :success               success-fn }))


(defn reset-if-needed!
  "Resets atom with value if needed. True if reset."
  [atom* value]
  (when (not= @atom* value)
    (do (reset! atom* value)
        true)))

(defn event->state [state]
  #(reset-if-needed! state (.. % -target -value)))
