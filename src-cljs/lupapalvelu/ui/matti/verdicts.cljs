(ns lupapalvelu.ui.matti.verdicts
  (:require [lupapalvelu.matti.shared :as shared]
            [lupapalvelu.ui.common :as common]
            [lupapalvelu.ui.components :as components]
            [lupapalvelu.ui.hub :as hub]
            [lupapalvelu.ui.matti.layout :as layout]
            [lupapalvelu.ui.matti.path :as path]
            [lupapalvelu.ui.matti.phrases :as phrases]
            [lupapalvelu.ui.matti.sections :as sections]
            [lupapalvelu.ui.matti.service :as service]
            [lupapalvelu.ui.matti.state :as state]
            [clojure.set :as set]
            [rum.core :as rum]
            [sade.shared_util :as util]))

(defonce args (atom {}))

(defn update-changes-and-errors [{:keys [state path]}]
  (fn [{:keys [modified changes errors] :as response}]
    (swap! state (fn [state]
                   (let [state (reduce (fn [acc [k v]]
                                         (assoc-in acc (map keyword k) v))
                                       state
                                       changes)]
                     (reduce (fn [acc [k v]]
                               (assoc-in acc
                                         (cons :_errors (map keyword k))
                                         v))
                             (assoc-in state
                                       (cons :_errors (map keyword path))
                                       nil)
                             errors))))
    (when modified
      (swap! state/current-verdict #(assoc % :modified modified)))))

(defn updater [{:keys [state path] :as options}]
  (service/edit-verdict @state/application-id
                        (path/value [:info :id] state/current-verdict)
                        path
                        (path/value path state)
                        (update-changes-and-errors options)))

(defn reset-verdict [{:keys [verdict settings]}]
  (reset! state/current-verdict
          (when verdict
            {:state (:data verdict)
             :info (dissoc verdict :data)
             :_meta {:updated updater}}))
  (reset! state/settings settings)
  (reset! state/current-view (if verdict ::verdict ::list)))

(defn update-application-id []
  (let [app-id (lupapisteApp.services.contextService.applicationId)]
    (when (common/reset-if-needed! state/application-id app-id)
      (if app-id
        (do (service/fetch-application-verdict-templates app-id)
            (service/fetch-application-phrases app-id)
            (service/fetch-verdict-list app-id))
        (do (reset! state/template-list [])
            (reset! state/phrases [])
            (reset! state/verdict-list nil))))))

(defn with-back-button [component]
  [:div
   [:button.secondary
    {:on-click #(reset-verdict nil)}
    [:i.lupicon-chevron-left]
    [:span (common/loc "back")]]
   component])

(rum/defc verdict-section-header < rum/reactive
  [options]
  [:div.matti-grid-1.section-header
   [:div.row.row--tight
    [:div.col-1.col--right
     [:div.verdict-buttons
      [:button.primary.outline
       {:on-click #(path/flip-meta options :editing?)}
       (common/loc (if (path/react-meta options :editing?)
                     :close
                     :edit))]]]]])

(defmethod sections/section-header :verdict
  [options _]
  (verdict-section-header options))


(defn verdict
  [{:keys [schema state] :as options}]
  [:div.matti-verdict
   [:div.matti-grid-2
    [:div.row.row--tight
     [:div.col-2.col--right
      (layout/last-saved options)]]]
   (for [sec (:sections schema)
         :let [section-id (-> sec :id keyword)]]
     (when-not (some-> @state :removed-sections section-id  )
       (sections/section (path/schema-options options sec) :verdict)))])


(rum/defcs new-verdict < rum/reactive
  (rum/local nil ::template)
  [{template* ::template}]
  (let [items (map #(set/rename-keys % {:id :value :name :text})
                   (rum/react state/template-list))]
    (when-not (rum/react template*)
      (common/reset-if-needed! template*
                               (:value (or (util/find-by-key :default? true items)
                                           (first items)))))
    [:div.matti-grid-6
     [:div.row
      (layout/vertical {:label :matti-verdict-template
                        :align :full}
                       (components/dropdown template*
                            {:items items}))
      (layout/vertical [:button.positive
                        {:on-click #(service/new-verdict-draft @state/application-id
                                                               @template*
                                                               reset-verdict)}
                        [:i.lupicon-circle-plus]
                        [:span (common/loc :application.verdict.add)]])]]))


(rum/defc verdict-list < rum/reactive
  [verdicts app-id]
  [:div
   [:h2 (common/loc "application.tabVerdict")]
   [:ol
    (map (fn [{:keys [id published modified]}]
           [:li {:key id}
            [:a {:on-click #(service/open-verdict app-id id reset-verdict)}
             (js/sprintf "Published: %s, Modified: %s"
                         (js/util.finnishDate published)
                         (js/util.finnishDateAndTime modified))]
            [:i.lupicon-remove.primary {:on-click #(service/delete-verdict app-id id reset-verdict)}
             ]])
         verdicts)]
   (new-verdict)])

(rum/defc verdicts < rum/reactive
  {:will-mount   (fn [state]
                   (update-application-id)
                   (assoc state
                          ::hub-id (hub/subscribe :contextService::enter
                                                  update-application-id)))
   :will-unmount (fn [state]
                   (hub/unsubscribe (::hub-id state))
                   (dissoc state ::hub-id))}
  []
  (when (and (rum/react state/application-id)
             (rum/react state/schemas)
             (rum/react state/phrases)
             (rum/react state/verdict-list))
    [:div
     (case (rum/react state/current-view)
       ::list (verdict-list @state/verdict-list @state/application-id)
       ::verdict (let [{dictionary :dictionary :as schema} (get shared/verdict-schemas
                                                                (shared/permit-type->category (lupapisteApp.models.application.permitType)))]
                   (with-back-button (verdict (assoc (state/select-keys state/current-verdict
                                                                       [:state :info :_meta])
                                                     :schema (dissoc schema :dictionary)
                                                     :dictionary dictionary
                                                     :references state/references)))))
     #_(components/debug-atom state/current-verdict "state/current-verdict")
     #_(components/debug-atom state/references "state/references")]))

(defn mount-component []
  (when (common/feature? :matti)
    (rum/mount (verdicts)
               (.getElementById js/document (:dom-id @args)))))

(defn ^:export start [domId _]
  (when (common/feature? :matti)
    (swap! args assoc
           :dom-id (name domId))
    (service/fetch-schemas)
    (reset-verdict nil)
    (mount-component)))
