(ns lupapalvelu.verdict
  (:require [taoensso.timbre :as timbre :refer [debug debugf info infof warn warnf error errorf]]
            [clojure.java.io :as io]
            [monger.operators :refer :all]
            [pandect.core :as pandect]
            [plumbing.core :as pc]
            [net.cgrand.enlive-html :as enlive]
            [swiss.arrows :refer :all]
            [schema.core :refer [defschema] :as sc]
            [sade.common-reader :as cr]
            [sade.core :refer :all]
            [sade.env :as env]
            [sade.files :as files]
            [sade.http :as http]
            [sade.schemas :as ssc]
            [sade.strings :as ss]
            [sade.util :refer [fn-> fn->>] :as util]
            [sade.xml :as xml]
            [lupapalvelu.action :refer [update-application application->command] :as action]
            [lupapalvelu.application :as application]
            [lupapalvelu.application-meta-fields :as meta-fields]
            [lupapalvelu.appeal-common :as appeal-common]
            [lupapalvelu.authorization :as auth]
            [lupapalvelu.document.transformations :as doc-transformations]
            [lupapalvelu.document.schemas :as schemas]
            [lupapalvelu.document.tools :as tools]
            [lupapalvelu.domain :as domain]
            [lupapalvelu.i18n :as i18n]
            [lupapalvelu.mime :as mime]
            [lupapalvelu.mongo :as mongo]
            [lupapalvelu.notifications :as notifications]
            [lupapalvelu.attachment :as attachment]
            [lupapalvelu.operations :as operations]
            [lupapalvelu.organization :as organization]
            [lupapalvelu.permit :as permit]
            [lupapalvelu.states :as states]
            [lupapalvelu.state-machine :as sm]
            [lupapalvelu.tasks :as tasks]
            [lupapalvelu.tiedonohjaus :as t]
            [lupapalvelu.user :as usr]
            [lupapalvelu.verdict-review-util :as verdict-review-util]
            [lupapalvelu.xml.krysp.reader :as krysp-reader]
            [lupapalvelu.xml.krysp.building-reader :as building-reader]
            [lupapalvelu.xml.krysp.application-from-krysp :as krysp-fetch]
            [lupapalvelu.organization :as org]
            [lupapalvelu.inspection-summary :as inspection-summary])
  (:import [java.net URL]
           [java.nio.charset StandardCharsets]))

(def Timestamp sc/Num) ;; Some timestamps are casted as double during mongo export

(defschema Katselmus
  {:katselmuksenLaji (sc/if ss/numeric? ssc/NatString (apply sc/enum tasks/task-types)) ;; TODO: cleanup numeric values
   (sc/optional-key :tarkastuksenTaiKatselmuksenNimi) sc/Str})

(defschema Maarays
  "Schema for additional requirements for verdict."
  {(sc/optional-key :sisalto)       sc/Str
   (sc/optional-key :maaraysPvm)    ssc/Timestamp
   (sc/optional-key :maaraysaika)   Timestamp
   (sc/optional-key :toteutusHetki) ssc/Timestamp})

(defschema Status
  "Schema for verdict status"
  (sc/if integer? ssc/Nat ssc/NatString))

(defschema Liite
  {:kuvaus sc/Str
   :tyyppi sc/Str})

(defschema Poytakirja
  "Schema for verdict record."
  {(sc/optional-key :paatoksentekija) (sc/maybe sc/Str)
   (sc/optional-key :paatoskoodi)     (sc/maybe sc/Str) ;; (apply sc/enum verdict-codes), data contains invalid values: nil "Peruutettu" "14" "annettu lausunto (ent. selitys)" "1" "lausunto/p\u00e4\u00e4tu00f6s (muu kuin rlk)" "11"
   (sc/optional-key :status)          (sc/maybe Status)
   (sc/optional-key :urlHash)         sc/Str
   (sc/optional-key :paatos)          (sc/maybe sc/Str)
   (sc/optional-key :paatospvm)       (sc/maybe Timestamp)
   (sc/optional-key :pykala)          (sc/maybe sc/Str)
   (sc/optional-key :liite)           Liite})

(defschema Paatos
  "Schema for single verdict fetched from backing system."
  {:id                               ssc/ObjectIdStr
   :poytakirjat                      [Poytakirja]
   (sc/optional-key :lupamaaraykset) {(sc/optional-key :maaraykset)                     [Maarays]
                                      (sc/optional-key :vaaditutKatselmukset)           [Katselmus]
                                      (sc/optional-key :vaaditutErityissuunnitelmat)    [sc/Str]
                                      (sc/optional-key :vaaditutTyonjohtajat)           sc/Str
                                      (sc/optional-key :vaadittuTyonjohtajatieto)       [sc/Str]
                                      (sc/optional-key :muutMaaraykset)                 [(sc/maybe sc/Str)]
                                      (sc/optional-key :autopaikkojaEnintaan)           ssc/Nat
                                      (sc/optional-key :autopaikkojaVahintaan)          ssc/Nat
                                      (sc/optional-key :autopaikkojaRakennettava)       ssc/Nat
                                      (sc/optional-key :autopaikkojaRakennettu)         ssc/Nat
                                      (sc/optional-key :autopaikkojaKiinteistolla)      ssc/Nat
                                      (sc/optional-key :autopaikkojaUlkopuolella)       ssc/Nat
                                      (sc/optional-key :takuuaikaPaivat)                ssc/NatString
                                      (sc/optional-key :kerrosala)                      sc/Str
                                      (sc/optional-key :kokonaisala)                    sc/Str
                                      (sc/optional-key :rakennusoikeudellinenKerrosala) sc/Str}
   (sc/optional-key :paivamaarat)    {(sc/optional-key :anto)                           (sc/maybe Timestamp)
                                      (sc/optional-key :lainvoimainen)                  (sc/maybe Timestamp)
                                      (sc/optional-key :aloitettava)                    Timestamp
                                      (sc/optional-key :voimassaHetki)                  Timestamp
                                      (sc/optional-key :viimeinenValitus)               Timestamp
                                      (sc/optional-key :raukeamis)                      Timestamp
                                      (sc/optional-key :paatosdokumentinPvm)            Timestamp
                                      (sc/optional-key :julkipano)                      Timestamp}})

(defschema Signature
  {:created ssc/Timestamp
   :user    usr/SummaryUser})

(defschema Verdict
  "Schema for verdict wrapper for verdicts with same kuntalupatunnus."
  {:id                           ssc/ObjectIdStr
   :kuntalupatunnus              (sc/maybe sc/Str)
   :timestamp                    (sc/maybe ssc/Timestamp)
   (sc/optional-key :source)     (sc/enum "ah")
   (sc/optional-key :draft)      sc/Bool
   (sc/optional-key :sopimus)    (sc/maybe sc/Bool)
   :paatokset                    [Paatos]
   (sc/optional-key :signatures) [Signature]
   (sc/optional-key :metadata)   (sc/eq nil)})

(defn- backend-id->verdict [backend-id]
  {:id              (mongo/create-id)
   :kuntalupatunnus backend-id
   :timestamp       nil
   :paatokset       []
   :draft           true})

(defn verdict-tab-action? [{action-name :action}]
  (boolean (#{:publish-verdict :check-for-verdict :process-ah-verdict :fetch-verdicts} (keyword action-name))))

(defn verdict-attachment-type
  ([application] (verdict-attachment-type application "paatosote"))
  ([{permit-type :permitType :as application} type]
   (if (#{:P :R} (keyword permit-type))
     {:type-group "paatoksenteko" :type-id type}
     {:type-group "muut" :type-id type})))

(defn- attachment-type-from-krysp-type [type]
  (case (ss/lower-case type)
    "paatosote" "paatosote"
    "lupaehto" "muu"
    "paatos"))

(defn- content-disposition-filename
  "Extracts the filename from the Content-Disposition header of the
  given respones. Decodes string according to the Server information."
  [{headers :headers}]
  (when-let [raw-filename (some->> (get headers "content-disposition")
                                    (re-find #".*filename=\"?([^\"]+)")
                                    last)]
    (case (some-> (get headers "server") ss/trim ss/lower-case)
      "microsoft-iis/7.5" (-> raw-filename
                              (.getBytes StandardCharsets/ISO_8859_1)
                              (String. StandardCharsets/UTF_8))
      raw-filename)))

(defn- get-poytakirja
  "At least outlier verdicts (KT) poytakirja can have multiple
  attachments. On the other hand, traditional (e.g., R) verdict
  poytakirja can only have one attachment."
  [application user timestamp verdict-id pk]
  (verdict-review-util/get-poytakirja application user timestamp {:type "verdict" :id verdict-id} pk))

(defn- verdict-attachments [application user timestamp verdict]
  {:pre [application]}
  (when (:paatokset verdict)
    (let [verdict-id (mongo/create-id)]
      (-> (assoc verdict :id verdict-id, :timestamp timestamp)
          (update :paatokset
                  (fn->> (map #(update % :poytakirjat (partial map (partial get-poytakirja application user timestamp verdict-id))))
                         (map #(assoc % :id (mongo/create-id)))
                         (filter seq)))))))

(defn- get-verdicts-with-attachments [application user timestamp xml reader & reader-args]
  (->> (apply krysp-reader/->verdicts xml (:permitType application) reader reader-args)
       (map (partial verdict-attachments application user timestamp))
       (filter seq)))

(defn- get-task-updates [application created verdicts app-xml]
  (when (not-any? (comp #{"verdict"} :type :source) (:tasks application))
    {$set {:tasks (-> (assoc application
                             :verdicts verdicts
                             :buildings (building-reader/->buildings-summary app-xml))
                      (tasks/verdicts->tasks created))}}))

(defn find-verdicts-from-xml
  "Returns a monger update map"
  [{:keys [application user created organization] :as command} app-xml]
  {:pre [(every? command [:application :user :created]) app-xml]}
  (let [organization (if organization @organization (org/get-organization (:organization application)))]
    (when-let [verdicts-with-attachments (seq (get-verdicts-with-attachments application user created app-xml permit/read-verdict-xml organization))]
      (inspection-summary/process-verdict-given application)
      (util/deep-merge
        {$set {:verdicts verdicts-with-attachments, :modified created}}
        (get-task-updates application created verdicts-with-attachments app-xml)
        (permit/read-verdict-extras-xml application app-xml)
        (when-not (states/post-verdict-states (keyword (:state application)))
          (application/state-transition-update (sm/verdict-given-state application) created application user))))))

(defn find-tj-suunnittelija-verdicts-from-xml
  [{:keys [application user created] :as command} doc app-xml osapuoli-type target-kuntaRoolikoodi]
  {:pre [(every? command [:application :user :created]) app-xml]}
  (when-let [verdicts-with-attachments (seq (get-verdicts-with-attachments application user created app-xml permit/read-tj-suunnittelija-verdict-xml doc osapuoli-type target-kuntaRoolikoodi))]
    (util/deep-merge
     (application/state-transition-update (sm/verdict-given-state application) created application user)
     {$set {:verdicts verdicts-with-attachments}})))

(defn- get-tj-suunnittelija-doc-name
  "Returns name of first party document of operation"
  [operation-name]
  (let [operation (get operations/operations (keyword operation-name))
        schemas (cons (:schema operation) (:required operation))]
    (some
      #(when
         (= :party
           (keyword
             (get-in (schemas/get-schema {:name %}) [:info :type])))
         %)
      schemas)))

;; Trimble writes verdict for tyonjohtaja/suunnittelija applications to their link permits.
(defn fetch-tj-suunnittelija-verdict [{{:keys [municipality permitType] :as application} :application :as command}]
  (let [application-op-name (-> application :primaryOperation :name)
        organization (organization/resolve-organization municipality permitType)
        krysp-version (get-in organization [:krysp (keyword permitType) :version])]
    (when (and
            (#{"tyonjohtajan-nimeaminen-v2" "tyonjohtajan-nimeaminen" "suunnittelijan-nimeaminen"} application-op-name)
            (util/version-is-greater-or-equal krysp-version {:major 2 :minor 1 :micro 8}))
      (let [application (meta-fields/enrich-with-link-permit-data application)
            link-permit (first (application/get-link-permit-apps application))
            link-permit-xml (krysp-fetch/get-application-xml-by-application-id link-permit)
            osapuoli-type (cond
                            (or (= "tyonjohtajan-nimeaminen" application-op-name) (= "tyonjohtajan-nimeaminen-v2" application-op-name)) "tyonjohtaja"
                            (= "suunnittelijan-nimeaminen" application-op-name) "suunnittelija")
            doc-name (get-tj-suunnittelija-doc-name application-op-name)
            doc (tools/unwrapped (domain/get-document-by-name application doc-name))
            target-kuntaRoolikoodi (get-in doc [:data :kuntaRoolikoodi])]
        (when (and link-permit-xml osapuoli-type doc target-kuntaRoolikoodi)
          (or
            (krysp-reader/tj-suunnittelija-verdicts-validator doc link-permit-xml osapuoli-type target-kuntaRoolikoodi)
            (let [updates (find-tj-suunnittelija-verdicts-from-xml command doc link-permit-xml osapuoli-type target-kuntaRoolikoodi)]
              (action/update-application command updates)
              (ok :verdicts (get-in updates [$set :verdicts])))))))))

(defn special-foreman-designer-verdict?
  "Some verdict providers handle foreman and designer verdicts a bit
  differently. These 'special' verdicts contain reference permit id in
  MuuTunnus. xml should be without namespaces"
  [application xml]
  (let [app-id (:id application)
        op-name (-> application :primaryOperation :name)
        link-permit-id (-> application :linkPermitData first :id)]
    (and (#{"tyonjohtajan-nimeaminen-v2" "tyonjohtajan-nimeaminen" "suunnittelijan-nimeaminen"} op-name)
         (not-empty (enlive/select xml [:luvanTunnisteTiedot :MuuTunnus :tunnus (enlive/text-pred #(= link-permit-id %))])))))

(defn verdict-xml-with-foreman-designer-verdicts
  "Normalizes special foreman/designer verdict by creating a proper
  paatostieto. Takes data from foreman/designer's party details. The
  resulting paatostieto element overrides old one. Returns the xml
  with paatostieto.
  Note: This must only be called with special verdict xml (see above)"
  [application xml]
  (let [op-name      (-> application :primaryOperation :name)
        tag          (if (ss/starts-with op-name "tyonjohtajan-") :Tyonjohtaja :Suunnittelija)
        [party]      (enlive/select xml [tag])
        attachment   (-> party (enlive/select [:liitetieto :Liite]) first enlive/unwrap)
        date         (xml/get-text party [:paatosPvm])
        decision     (xml/get-text party [:paatostyyppi])
        verdict-xml  [{:tag :Paatos
                       :content [{:tag :poytakirja
                                  :content [{:tag :paatoskoodi :content [decision]}
                                            {:tag :paatoksentekija :content [""]}
                                            {:tag :paatospvm :content [date]}
                                            {:tag :liite :content attachment}]}]}]
        paatostieto  {:tag :paatostieto :content verdict-xml}
        placeholders #{:paatostieto :muistiotieto :lisatiedot
                       :liitetieto  :kayttotapaus :asianTiedot
                       :hankkeenVaativuus}
        [rakval]     (enlive/select xml [:RakennusvalvontaAsia])
        place        (some #(placeholders (:tag %)) (:content rakval))]
    (case place
      :paatostieto (enlive/at xml [:RakennusvalvontaAsia :paatostieto] (enlive/content verdict-xml))
      nil          (enlive/at xml [:RakennusvalvontaAsia] (enlive/append paatostieto))
      (enlive/at xml [:RakennusvalvontaAsia place] (enlive/before paatostieto)))))

(defn- normalize-special-verdict
  "Normalizes special foreman/designer verdicts by
  creating a traditional paatostieto element from the proper special
  verdict party.
    application: Application that requests verdict.
    app-xml:     Verdict xml message
  Returns either normalized app-xml (without namespaces) or app-xml if
  the verdict is not special."
  [application app-xml]
  (let [xml (cr/strip-xml-namespaces app-xml)]
    (if (special-foreman-designer-verdict? (meta-fields/enrich-with-link-permit-data application) xml)
      (verdict-xml-with-foreman-designer-verdicts application xml)
      app-xml)))

(defn- save-verdicts-from-xml
  "Saves verdict's from valid app-xml to application. Returns (ok) with updated verdicts and tasks"
  [{:keys [application] :as command} app-xml]
  (appeal-common/delete-all command)
  (let [updates (find-verdicts-from-xml command app-xml)]
    (when updates
      (let [doc-updates (doc-transformations/get-state-transition-updates command (sm/verdict-given-state application))]
        (update-application command (:mongo-query doc-updates) (util/deep-merge (:mongo-updates doc-updates) updates))
        (t/mark-app-and-attachments-final! (:id application) (:created command))))
    (ok :verdicts (get-in updates [$set :verdicts])
        :tasks (get-in updates [$set :tasks])
        :state (get-in updates [$set :state] (:state application)))))

(defn- backend-id-mongo-updates
  [{verdicts :verdicts} backend-ids]
  (some->> backend-ids
           (remove (set (map :kuntalupatunnus verdicts)))
           (map backend-id->verdict)
           (assoc-in {} [$push :verdicts $each])))

(defn validate-section-requirement
  "Validator that fails if the organization requires section (pykala)
  in verdicts and app-xml is missing one (muutoslupa permits are
  excluded from validation) Note: besides organization, the
  requirement is also operation-specific. The requirement is fulfilled
  if _any_ paatostieto element contains at least one non-blank
  pykala."
  [{:keys [primaryOperation permitSubtype]} app-xml {section :section}]
  (let [{:keys [enabled operations]} section]
    (when (and enabled
               (util/not=as-kw permitSubtype :muutoslupa)
               (contains? (set operations) (:name primaryOperation))
               (not (some-<> app-xml
                             cr/strip-xml-namespaces
                             (xml/select [:paatostieto :pykala])
                             not-empty
                             (some (util/fn-> :content first ss/not-blank?) <>))))
      (fail :info.section-required-in-verdict))))

(defn do-check-for-verdict [{:keys [application organization] :as command}]
  {:pre [(every? command [:application :user :created])]}
  (when-let [app-xml (or (krysp-fetch/get-application-xml-by-application-id application)
                         ;; LPK-1538 If fetching with application-id fails try to fetch application with first to find backend-id
                         (krysp-fetch/get-application-xml-by-backend-id application (some :kuntalupatunnus (:verdicts application))))]
    (let [app-xml          (normalize-special-verdict application app-xml)
          organization     (if organization @organization (org/get-organization (:organization application)))
          validation-error (or (permit/validate-verdict-xml (:permitType application) app-xml organization)
                               (validate-section-requirement application
                                                             app-xml
                                                             organization))]
      (if-not validation-error
        (save-verdicts-from-xml command app-xml)
        (let [extras-updates     (permit/read-verdict-extras-xml application app-xml)
              backend-id-updates (->> (seq (krysp-reader/->backend-ids app-xml))
                                      (backend-id-mongo-updates application))]
          (some->> (util/deep-merge extras-updates backend-id-updates) (update-application command))
          validation-error)))))

(defn- verdict-task?
  "True if given task is 'rooted' via source chain to the verdict.
   tasks: tasks of the application
   verdict-id: Id of the target verdict
   task: task to be analyzed."
  [tasks verdict-id {{source-type :type source-id :id} :source :as task}]
  (case (keyword source-type)
    :verdict (= verdict-id source-id)
    :task (verdict-task? tasks verdict-id (some #(when (= (:id %) source-id) %) tasks))
    false))

(defn deletable-verdict-task-ids
  "Task ids that a) can be deleted and b) belong to the
  verdict with the given id."
  [{:keys [tasks attachments]} verdict-id]
  (->> tasks
       (filter #(and (not= (-> % :state keyword) :sent)
                     (verdict-task? tasks verdict-id %)))
       (map :id)))

(defn task-ids->attachments
  "All the attachments that belong to the tasks with the given ids."
  [application task-ids]
  (->> task-ids
       (map (partial tasks/task-attachments application))
       flatten))

(defn get-state-updates [user created {current-state :state :as application} app-xml]
  (let [new-state (->> (krysp-reader/application-state app-xml)
                       krysp-reader/krysp-state->application-state)]
    (cond
      (nil? new-state) nil
      (sm/can-proceed? application new-state)  (application/state-transition-update new-state created application user)
      (not= new-state (keyword current-state)) (errorf "Invalid state transition. Failed to update application %s state from '%s' to '%s'."
                                                       (:id application) current-state (name new-state)))))

(defmethod attachment/edit-allowed-by-target :verdict [{user :user application :application}]
  (when-not (auth/application-authority? application user)
    (fail :error.unauthorized)))

;; Notifications

(defn state-change-email-model
  "Generic state change email. :state-text is set per application state.
  When state changes and if notify is invoked as post-fn from command,
  result must contain new state in :state key."
  [command conf recipient]
  (assoc
   (notifications/create-app-model command conf recipient)
   :state-text #(i18n/localize % "email.state-description" (get-in command [:application :state]))))

(def state-change {:subject-key    "state-change"
                   :template       "application-state-change.md"
                   :application-fn (fn [{id :id}] (domain/get-application-no-access-checking id))
                   :tab-fn         (fn [command] (cond (verdict-tab-action? command) "verdict"))
                   :model-fn       state-change-email-model})

(notifications/defemail :application-state-change state-change)

(notifications/defemail :undo-cancellation
                        {:subject-key    "undo-cancellation"
                         :application-fn (fn [{id :id}] (domain/get-application-no-access-checking id))
                         :model-fn       (fn [command conf recipient]
                                           (assoc (notifications/create-app-model command conf recipient)
                                             :state-text #(i18n/localize % "email.state-description.undoCancellation")))})
