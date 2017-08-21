(ns lupapalvelu.xml.asianhallinta.verdict
  (:require [sade.core :refer [ok fail fail!] :as core]
            [sade.common-reader :as cr]
            [sade.strings :as ss]
            [sade.util :as util]
            [sade.xml :as xml]
            [taoensso.timbre :refer [error]]
            [me.raynes.fs :as fs]
            [monger.operators :refer :all]
            [lupapalvelu.attachment :as attachment]
            [lupapalvelu.domain :as domain]
            [lupapalvelu.organization :as org]
            [lupapalvelu.action :as action]
            [lupapalvelu.application :as application]
            [lupapalvelu.mongo :as mongo]
            [lupapalvelu.user :as user]
            [lupapalvelu.notifications :as notifications]
            [lupapalvelu.state-machine :as sm]
            [lupapalvelu.xml.asianhallinta.reader :as ah-reader]))


(defn- build-verdict [{:keys [AsianPaatos]} timestamp]
  {:id              (mongo/create-id)
   :kuntalupatunnus (:AsianTunnus AsianPaatos)
   :timestamp timestamp
   :source    "ah"
   :paatokset [{:paatostunnus (:PaatoksenTunnus AsianPaatos)
                :paivamaarat  {:anto (cr/to-timestamp (:PaatoksenPvm AsianPaatos))}
                :poytakirjat  [{:paatoksentekija (:PaatoksenTekija AsianPaatos)
                                :paatospvm       (cr/to-timestamp (:PaatoksenPvm AsianPaatos))
                                :pykala          (:Pykala AsianPaatos)
                                :paatoskoodi     (or (:PaatosKoodi AsianPaatos) (:PaatoksenTunnus AsianPaatos)) ; PaatosKoodi is not required
                                :id              (mongo/create-id)}]}]})

(defn- insert-attachment! [application attachment unzipped-path verdict-id poytakirja-id timestamp]
  (let [filename      (fs/base-name (:LinkkiLiitteeseen attachment))
        file          (fs/file (ss/join "/" [unzipped-path filename]))
        file-size     (.length file)
        orgs          (org/resolve-organizations
                        (:municipality application)
                        (:permitType application))
        batchrun-user (user/batchrun-user (map :id orgs))
        target        {:type "verdict" :id verdict-id :poytakirjaId poytakirja-id}
        attachment-id (mongo/create-id)]
    (attachment/upload-and-attach! {:application application :user batchrun-user}
                                   {:attachment-id attachment-id
                                    :attachment-type {:type-group "muut" :type-id "paatos"}
                                    :target target
                                    :required false
                                    :locked true
                                    :created timestamp
                                    :state :ok}
                                   {:filename filename
                                    :size file-size
                                    :content file})))

(defn- check-ftp-user-has-right-to-modify-app! [ftp-user {application-id :id municipality :municipality permit-type :permitType}]
  (when-not (-> (org/resolve-organization-scope municipality permit-type)
                (get-in [:caseManagement :ftpUser])
                (= ftp-user))
    (ah-reader/error-and-fail!
     (str "FTP user " ftp-user " is not allowed to make changes to application " application-id)
     :error.integration.asianhallinta.unauthorized)))

(defn- check-application-is-in-correct-state! [{application-id :id current-state :state :as application}]
  (when-not (#{:constructionStarted :sent (sm/verdict-given-state application)} (keyword current-state))
    (ah-reader/error-and-fail!
     (str "Application " application-id " in wrong state (" current-state ") for asianhallinta verdict")
     :error.integration.asianhallinta.wrong-state)))


(defn process-ah-verdict [parsed-xml unzipped-path ftp-user system-user]
  (let [xml-edn   (xml/xml->edn parsed-xml)
        timestamp (core/now)
        application-id (get-in xml-edn [:AsianPaatos :HakemusTunnus])
        attachments (-> (get-in xml-edn [:AsianPaatos :Liitteet])
                        (util/ensure-sequential :Liite)
                        :Liite)]
    (when-not application-id
      (ah-reader/error-and-fail!
        (str "ah-verdict - Application id is nil for user " ftp-user)
        :error.integration.asianhallinta.no-application-id))
    (let [application (domain/get-application-no-access-checking application-id)
          verdict-given-state (sm/verdict-given-state application)]

      (check-ftp-user-has-right-to-modify-app! ftp-user application)
      (check-application-is-in-correct-state! application)

      ;; -> build update clause
      ;; -> update-application
      (let [new-verdict   (build-verdict xml-edn timestamp)
            command       (assoc (action/application->command application) :action :process-ah-verdict)
            poytakirja-id (get-in new-verdict [:paatokset 0 :poytakirjat 0 :id])
            update-clause (util/deep-merge
                            {$push {:verdicts new-verdict}, $set  {:modified timestamp}}
                            (when (= :sent (keyword (:state application)))
                              (application/state-transition-update verdict-given-state timestamp application system-user)))]

        (action/update-application command update-clause)
        (doseq [attachment attachments]
          (insert-attachment!
            application
            attachment
            unzipped-path
            (:id new-verdict)
            poytakirja-id
            timestamp))
        (notifications/notify! :application-state-change command)
        (ok)))))

(defmethod ah-reader/handle-asianhallinta-message :AsianPaatos
  [parsed-xml unzipped-path ftp-user system-user]
  (process-ah-verdict parsed-xml unzipped-path ftp-user system-user))
