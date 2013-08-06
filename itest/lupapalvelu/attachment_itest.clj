(ns lupapalvelu.attachment-itest
  (:use [lupapalvelu.attachment]
        [lupapalvelu.itest-util]
        [midje.sweet])
  (:require [clj-http.client :as c]))

(defn- get-attachment-by-id [application-id attachment-id]
  (let [application     (:application (query pena :application :id application-id))]
    (some #(when (= (:id %) attachment-id) %) (:attachments application))))

(defn- approve-attachment [application-id attachment-id]
  (command veikko :approve-attachment :id application-id :attachmentId attachment-id) => ok?
  (get-attachment-by-id application-id attachment-id) => (in-state? "ok"))

(defn- reject-attachment [application-id attachment-id]
  (command veikko :reject-attachment :id application-id :attachmentId attachment-id) => ok?
  (get-attachment-by-id application-id attachment-id) => (in-state? "requires_user_action"))

(facts "attachments"
  (let [{application-id :id :as response} (create-app pena :municipality veikko-muni)]

    response => ok?

    (comment-application application-id pena)

    (let [resp (command veikko
                 :create-attachments
                 :id application-id
                 :attachmentTypes [{:type-group "tg" :type-id "tid-1"}
                                   {:type-group "tg" :type-id "tid-2"}])
          attachment-ids (:attachmentIds resp)]

      (fact "Veikko can create an attachment"
        (success resp) => true)

      (fact "Two attachments were created in one call"
        (fact (count attachment-ids) => 2))

      (fact "attachment has been saved to application"
        (get-attachment-by-id application-id (first attachment-ids)) => (contains
                                                                          {:type {:type-group "tg" :type-id "tid-1"}
                                                                           :state "requires_user_action"
                                                                           :versions []})
        (get-attachment-by-id application-id (second attachment-ids)) => (contains
                                                                           {:type {:type-group "tg" :type-id "tid-2"}
                                                                            :state "requires_user_action"
                                                                            :versions []}))

      (fact "uploading files"
        (let [application (:application (query pena :application :id application-id))
              _           (upload-attachment-to-all-placeholders pena application)
              application (:application (query pena :application :id application-id))]

          (fact "download all"
            (raw pena "download-all-attachments" :id application-id) => http200?)

          (fact "pdf export"
            (raw pena "pdf-export" :id application-id) => http200?)

          (doseq [attachment-id (get-attachment-ids application)
                  :let [file-id  (attachment-latest-file-id application attachment-id)]]

            (fact "view-attachment anonymously should not be possible"
              (raw nil "view-attachment" :attachment-id file-id) => http401?)

            (fact "view-attachment as pena should be possible"
              (raw pena "view-attachment" :attachment-id file-id) => http200?)

            (fact "download-attachment anonymously should not be possible"
              (raw nil "download-attachment" :attachment-id file-id) => http401?)

            (fact "download-attachment as pena should be possible"
              (raw pena  "download-attachment" :attachment-id file-id) => http200?))))

      (fact "Veikko can approve attachment"
        (approve-attachment application-id (first attachment-ids)))

      (fact "Veikko can reject attachment"
        (reject-attachment application-id (first attachment-ids)))

      (fact "Pena submits the application"
        (success (command pena :submit-application :id application-id)) => true
        (-> (query veikko :application :id application-id) :application :state) => "submitted")

      (fact "Veikko can still approve attachment"
        (approve-attachment application-id (first attachment-ids)))

      (fact "Veikko can still reject attachment"
        (reject-attachment application-id (first attachment-ids)))

      (fact "pdf does not work with YA-lupa"
        (let [{application-id :id :as response} (create-YA-app pena :municipality veikko-muni)]
          response => ok?
          (action-not-allowed pena application-id :pdf-export)
          (raw pena "pdf-export" :id application-id) => http404?)))))
