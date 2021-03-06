(ns lupapalvelu.ui.pate.service
  (:require [lupapalvelu.ui.common :as common]
            [lupapalvelu.ui.hub :as hub]
            [lupapalvelu.ui.pate.state :as state]
            [lupapalvelu.ui.authorization :as auth]
            [sade.shared-util :as util]))

(defn fetch-template-list []
  (common/query "verdict-templates"
                #(reset! state/template-list (:verdict-templates %))))

(defn- list-update-response [callback]
  (fn [response]
    (fetch-template-list)
    (callback response)
    (hub/send "pate::verdict-templates-changed")))

(defn update-changes-and-errors
  "Returns a success callback function that updates the state according
  to the command response (e.g., changes, removals and errors). container*
  is a 'top-level' atom that contains the info property (including
  modified and filled?)."
  [container* {:keys [state path]}]
  (fn [{:keys [modified changes errors removals filled] :as response}]
    (swap! state (fn [state]
                   (let [state (as-> state $
                                 (reduce (fn [acc [k v]]
                                           (assoc-in acc (map keyword k) v))
                                         $
                                         changes)
                                 (reduce (fn [acc k]
                                           (util/dissoc-in acc (map keyword k)))
                                         $
                                         removals))]
                     (reduce (fn [acc [k v]]
                               (assoc-in acc
                                         (cons :_errors
                                               (map keyword k))
                                         v))
                             (util/dissoc-in state
                                             (cons :_errors
                                                   (map keyword path)))
                             errors))))
    (swap! container* (fn [container]
                        (cond-> container
                          modified            (assoc-in [:info :modified] modified)
                          (not (nil? filled)) (assoc-in [:info :filled?] filled))))))

(defn fetch-categories [callback]
  (common/query "verdict-template-categories"
                #(do
                   (reset! state/categories (:categories %))
                   (callback @state/categories))))

(defn publish-template [template-id callback]
  (common/command {:command "publish-verdict-template"
                   :success (list-update-response callback)}
                  :template-id template-id))

(defn save-draft-value [template-id path value callback]
  (common/command {:command "save-verdict-template-draft-value"
                   :success callback}
                  :template-id template-id
                  :path path
                  :value value))

(defn fetch-template [template-id callback]
  (common/query "verdict-template"
                callback
                :template-id template-id))

(defn new-template [category callback]
  (common/command {:command "new-verdict-template"
                   :success (list-update-response callback)}
                  :category category))

(defn set-template-name [template-id name callback]
  (common/command {:command "set-verdict-template-name"
                   :success (list-update-response callback)}
                  :template-id template-id
                  :name name))

(defn toggle-delete-template [template-id delete callback]
  (common/command {:command "toggle-delete-verdict-template"
                   :success (list-update-response callback)}
                  :template-id template-id
                  :delete delete))

(defn copy-template [template-id callback]
  (common/command {:command "copy-verdict-template"
                   :success (list-update-response callback)}
                  :template-id template-id))

(defn settings [category callback]
  (common/query "verdict-template-settings"
                callback
                :category category))

(defn save-settings-value [category path value callback]
  (common/command {:command "save-verdict-template-settings-value"
                   :success callback}
                  :category category
                  :path path
                  :value value))

;; Generic refers to either review or plan.

(defn generics [generic-type category callback]
  (common/query (js/sprintf "verdict-template-%ss"
                            (name generic-type))
                callback
                :category category))

(defn new-generic [generic-type category callback]
  (common/command {:command (str "add-verdict-template-" (name generic-type))
                   :success callback}
                  :category category))

(defn update-generic [generic-type gen-id callback & updates]
  (apply (partial common/command {:command (str "update-verdict-template-"
                                                (name generic-type))
                                  :success (fn [{:keys [modified] :as response}]
                                             (when (= (-> response generic-type :category)
                                                      @state/current-category)
                                               (swap! state/settings
                                                      #(assoc % :modified modified)))
                                             (callback response))}
                  (keyword (str (name generic-type) "-id")) gen-id)
         updates))

;; Phrases

(defn fetch-organization-phrases []
  (common/query "organization-phrases"
                #(reset! state/phrases (get % :phrases []))))

(defn fetch-application-phrases [app-id]
  (common/query "application-phrases"
                #(reset! state/phrases (get % :phrases []))
                :id app-id))

(defn upsert-phrase [phrase-map callback]
  (apply common/command
         "upsert-phrase"
         (fn []
           (fetch-organization-phrases)
           (callback))
         (flatten (into [] phrase-map))))

(defn delete-phrase [phrase-id callback]
  (common/command {:command "delete-phrase"
                   :success (fn []
                              (fetch-organization-phrases)
                              (callback))}
                  :phrase-id phrase-id))

;; Application verdict templates

(defn fetch-application-verdict-templates [app-id]
  (common/query "application-verdict-templates"
                #(reset! state/template-list (:templates %))
                :id app-id))

(defn fetch-verdict-list [app-id]
  (common/query "pate-verdicts"
                #(reset! state/verdict-list (:verdicts %))
                :id app-id))

(defn new-verdict-draft [app-id template-id callback]
  (common/command {:command "new-pate-verdict-draft"
                   :success #(do (fetch-verdict-list app-id)
                                 (callback %))}
                  :id app-id
                  :template-id template-id))

(defn open-verdict [app-id verdict-id callback]
  (common/query "pate-verdict"
                callback
                :id app-id
                :verdict-id verdict-id))

(defn delete-verdict [app-id verdict-id callback]
  (common/command {:command "delete-pate-verdict"
                   :success #(do (fetch-verdict-list app-id)
                                 (js/lupapisteApp.services.attachmentsService.queryAll)
                                 (callback %))}
                  :id app-id
                  :verdict-id verdict-id))

(defn edit-verdict [app-id verdict-id path value callback]
  (common/command {:command "edit-pate-verdict"
                   :success callback}
                  :id app-id
                  :verdict-id verdict-id
                  :path path
                  :value value))

(defn publish-and-reopen-verdict [app-id verdict-id callback]
  (common/command {:command :publish-pate-verdict
                   :success (fn []
                              (fetch-verdict-list app-id)
                              (open-verdict app-id verdict-id callback)
                              (js/repository.load app-id))}
                  :id app-id
                  :verdict-id verdict-id))

;; Attachments

(defn delete-file [file-id]
  (common/command {:command :remove-uploaded-file}
                  :attachmentId file-id))

(defn bind-attachments [app-id filedatas callback]
  (common/command {:command :bind-attachments
                   :success callback}
                  :id app-id
                  :filedatas filedatas))

(defn bind-attachments-job [job-id version callback]
  (common/query :bind-attachments-job
                callback
                :jobId job-id
                :version version))

(defn- batch-job [status-fn {:keys [job]}]
  (status-fn (when job
               (reduce (fn [acc {:keys [fileId status]}]
                         (let [k (case (keyword status)
                                   :done :done
                                   :error :error
                                   :pending)]
                           (update acc k #(cons fileId %))))
                       {:pending []
                        :error   []
                        :done    []}
                       (some-> job :value vals))))
  (when (util/=as-kw (:status job) :running)
    (bind-attachments-job (:id job)
                          (:version job)
                          (partial batch-job status-fn))))

(defn bind-attachments-batch
  "Convenience function that combines the binding and querying of the
  results. Arguments:

  app-id: Application id

  filedatas: List of maps with :file-id (string), :type (kw-path). Any
  additional keys (e.g., :contents, :target) are copied as is.

  status-fn: Will be called with updated status - [file-id]
  map. Status can be :pending, :done or :error. The callback will not
  be called after every file has terminal status (:done or :error). In
  other words, when the :pending list is empty. Nil (no job) argument
  denotes error (e.g., timeout)."
  [app-id filedatas status-fn]
  (let [result* (atom {})]
    (bind-attachments app-id
                      (map (fn [{:keys [file-id type] :as filedata}]
                             (let [[type-group type-id] (util/split-kw-path type)]
                               (merge (dissoc filedata :file-id :type)
                                      {:fileId   file-id
                                       :type {:type-group type-group
                                              :type-id type-id}})))
                           filedatas)
                      (partial batch-job status-fn))))

;; Co-operation with the AttachmentsService

(defn attachments []
  (js->clj (js/lupapisteApp.services.attachmentsService.rawAttachments)
           :keywordize-keys true))

(defn refresh-attachments []
  (js/lupapisteApp.services.attachmentsService.queryAll)
  (js/lupapisteApp.services.attachmentsService.refreshAuthModels))
