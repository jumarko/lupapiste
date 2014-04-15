(ns lupapalvelu.user-api
  (:require [taoensso.timbre :as timbre :refer [trace debug info infof warn warnf error fatal]]
            [clojure.set :as set]
            [noir.response :as resp]
            [noir.session :as session]
            [noir.core :refer [defpage]]
            [slingshot.slingshot :refer [throw+]]
            [monger.operators :refer :all]
            [sade.util :refer [future*]]
            [sade.env :as env]
            [sade.strings :as ss]
            [sade.util :as util]
            [lupapalvelu.core :refer :all]
            [lupapalvelu.action :refer [defquery defcommand defraw] :as action]
            [lupapalvelu.mongo :as mongo]
            [lupapalvelu.activation :as activation]
            [lupapalvelu.security :as security]
            [lupapalvelu.vetuma :as vetuma]
            [lupapalvelu.mime :as mime]
            [lupapalvelu.user :refer [with-user-by-email] :as user]
            [lupapalvelu.token :as token]
            [lupapalvelu.notifications :as notifications]
            [lupapalvelu.attachment :as attachment]))

;;
;; ==============================================================================
;; Getting user and users:
;; ==============================================================================
;;

(defquery user
  {:authenticated true :verified true}
  [{user :user}]
  (ok :user user))

(defquery users
  {:roles [:admin :authorityAdmin]}
  [{{:keys [role organizations]} :user data :data}]
  (ok :users (map user/non-private (-> data
                                     (select-keys [:id :role :organization :organizations :email :username :firstName :lastName :enabled :allowDirectMarketing])
                                     (as-> data (if (= role :authorityAdmin)
                                                  (assoc data :organizations {$in [organizations]})
                                                  data))
                                     (user/find-users)))))

(env/in-dev
  (defquery user-by-email
    {:parameters [email] :roles [:admin]}
    [_]
    (ok :user (user/get-user-by-email email))))

(defcommand users-for-datatables
  {:roles [:admin :authorityAdmin]}
  [{caller :user {params :params} :data}]
  (ok :data (user/users-for-datatables caller params)))

;;
;; ==============================================================================
;; Creating users:
;; ==============================================================================
;;

;; Emails
(def ^:private base-email-conf
  {:recipients-fn notifications/from-data
   :model-fn      (fn [{{token :token} :data} _]
                    {:link-fi (str (env/value :host) "/app/fi/welcome#!/setpw/" token)
                     :link-sv (str (env/value :host) "/app/sv/welcome#!/setpw/" token)})})

(notifications/defemail :invite-authority (assoc base-email-conf :subject-key "authority-invite.title"))
(notifications/defemail :reset-password   (assoc base-email-conf :subject-key "reset.email.title"))

(defn- notify-new-authority [new-user created-by]
  (let [token (token/make-token :authority-invitation (merge new-user {:caller-email (:email created-by)}))]
    (notifications/notify! :invite-authority {:data {:email (:email new-user) :token token}})))

(defn- validate-create-new-user! [caller user-data]
  (when-let [missing (util/missing-keys user-data [:email :role])]
    (fail! :error.missing-parameters :parameters missing))

  (let [password         (:password user-data)
        user-role        (keyword (:role user-data))
        caller-role      (keyword (:role caller))
        admin?           (= caller-role :admin)
        authorityAdmin?  (= caller-role :authorityAdmin)]

    (when (not (#{:authority :authorityAdmin :applicant :dummy} user-role))
      (fail! :error.invalid-role :desc "new user has unsupported role" :user-role user-role))

    (when (and (= user-role :applicant) caller)
      (fail! :error.unauthorized :desc "applicants are born via registration"))

    (when (and (= user-role :authorityAdmin) (not admin?))
      (fail! :error.unauthorized :desc "only admin can create authorityAdmin users"))

    (when (and (= user-role :authority) (not authorityAdmin?))
      (fail! :error.unauthorized :desc "only authorityAdmin can create authority users" :user-role user-role :caller-role caller-role))

    (when (and (= user-role :authorityAdmin) (not (:organization user-data)))
      (fail! :error.missing-parameters :desc "new authorityAdmin user must have organization" :parameters [:organization]))

    (when (and (= user-role :authority) (and (:organization user-data) (every? (partial not= (:organization user-data)) (:organizations caller))))
      (fail! :error.unauthorized :desc "authorityAdmin can create users into his/her own organization only, or statement givers without any organization at all"))

    (when (and (= user-role :dummy) (:organization user-data))
      (fail! :error.unauthorized :desc "dummy user may not have an organization" :missing :organization))

    (when (and password (not (security/valid-password? password)))
      (fail! :password-too-short :desc "password specified, but it's not valid"))

    (when (and (boolean (:enabled user-data)) (not admin?) (not authorityAdmin?))
      (fail! :error.unauthorized :desc "only admin and authorityAdmin can create enabled users"))

    (when (and (:apikey user-data) (not admin?))
      (fail! :error.unauthorized :desc "only admin can create create users with apikey")))

  true)

(defn- create-new-user-entity [user-data]
  (let [email (ss/lower-case (:email user-data))]
    (-> user-data
      (select-keys [:email :username :role :firstName :lastName :personId
                    :phone :city :street :zip :enabled :organization :allowDirectMarketing])
      (as-> user-data (merge {:firstName "" :lastName "" :username email} user-data))
      (assoc
        :email email
        :enabled (= "true" (str (:enabled user-data)))
        :organizations (if (:organization user-data) [(:organization user-data)] [])
        :private (merge {}
                   (when (:password user-data)
                     {:password (security/get-hash (:password user-data))})
                   (when (and (:apikey user-data) (not= "false" (:apikey user-data)))
                     {:apikey (if (and (env/dev-mode?) (not (#{"true" "false"} (:apikey user-data))))
                                (:apikey user-data)
                                (security/random-password))}))))))

;;
;; TODO: Ylimaaraisen "send-email"-parametrin sijaan siirra mailin lahetys pois
;;       activation/send-activation-mail-for -funktiosta kutsuviin funktioihin.
;;
(defn create-new-user
  "Insert new user to database, returns new user data without private information. If user
   exists and has role \"dummy\", overwrites users information. If users exists with any other
   role, throws exception."
  [caller user-data & {:keys [send-email] :or {send-email true}}]
  (validate-create-new-user! caller user-data)
  (let [user-entry  (create-new-user-entity user-data)
        old-user  (user/get-user-by-email (:email user-entry))
        new-user  (if old-user
                    (assoc user-entry :id (:id old-user))
                    (assoc user-entry :id (mongo/create-id)))
        email     (:email new-user)
        {old-id :id old-role :role}  old-user]
    (try
      (condp = old-role
        nil     (do
                  (info "creating new user" (dissoc new-user :private))
                  (mongo/insert :users new-user))
        "dummy" (do
                  (info "rewriting over dummy user:" old-id (dissoc new-user :private :id))
                  (mongo/update-by-id :users old-id (dissoc new-user :id)))
        (fail! :error.duplicate-email))

      (when (and send-email (not= "dummy" (name (:role new-user))))
        (activation/send-activation-mail-for new-user))

      (user/get-user-by-email email)

      (catch com.mongodb.MongoException$DuplicateKey e
        (if-let [field (second (re-find #"E11000 duplicate key error index: lupapiste\.users\.\$([^\s._]+)" (.getMessage e)))]
          (do
            (warnf "Duplicate key detected when inserting new user: field=%s" field)
            (fail! :duplicate-key :field field))
          (do
            (warn e "Inserting new user failed")
            (fail! :cant-insert)))))))

(defn- create-authority-user-with-organization [caller new-organization email firstName lastName]
  (let [new-user (create-new-user
                   caller
                   {:email email :role :authority :organization new-organization :enabled true
                    :firstName firstName :lastName lastName}
                   :send-email false)
        ]
    (infof "invitation for new authority user: email=%s, organization=%s" email new-organization)
    (notify-new-authority new-user caller)
    (ok :operation "invited")))

(defcommand create-user
  {:parameters [:email role]
   :input-validators [(partial action/non-blank-parameters [:email])
                      action/email-validator]
   :roles      [:admin :authorityAdmin]}
  [{user-data :data caller :user}]
  (let [user (create-new-user caller user-data :send-email false)]
    (infof "Added a new user: role=%s, email=%s, organizations=%s" (:role user) (:email user) (:organizations user))
    (if (= role "authority")
      (do
        (notify-new-authority user caller)
        (ok :id (:id user) :user user))
      (let [token-ttl (* 7 24 60 60 1000)
            token (token/make-token :password-reset {:email (:email user)} :ttl token-ttl)]
        (ok :id (:id user)
          :user user
          :linkFi (str (env/value :host) "/app/fi/welcome#!/setpw/" token)
          :linkSv (str (env/value :host) "/app/sv/welcome#!/setpw/" token))))))

(defn get-or-create-user-by-email [email]
  (let [email (ss/lower-case email)]
    (or
      (user/get-user-by-email email)
      (create-new-user (user/current-user) {:email email :role "dummy"}))))

;;
;; ==============================================================================
;; Updating user data:
;; ==============================================================================
;;

;;
;; General changes:
;;

(def ^:private user-data-editable-fields [:firstName :lastName :street :city :zip :phone
                                          :architect :degree :graduatingYear :fise
                                          :companyName :companyId :allowDirectMarketing])

(defn- validate-update-user! [caller user-data]
  (let [admin?          (= (-> caller :role keyword) :admin)
        caller-email    (:email caller)
        user-email      (:email user-data)]

    (if admin?
      (when (= user-email caller-email)    (fail! :error.unauthorized :desc "admin may not change his/her own data"))
      (when (not= user-email caller-email) (fail! :error.unauthorized :desc "can't edit others data")))

    true))

(defcommand update-user
  {:authenticated true}
  [{caller :user user-data :data}]
  (let [email     (ss/lower-case (or (:email user-data) (:email caller)))
        user-data (assoc user-data :email email)]
    (validate-update-user! caller user-data)
    (if (= 1 (mongo/update-n :users {:email email} {$set (select-keys user-data user-data-editable-fields)}))
      (do
        (when (= email (:email caller))
          (session/put! :user (user/get-user-by-email email)))
        (ok))
      (fail :not-found :email email))))

;;
;; Change organization data:
;;

(defn- valid-organization-operation? [{data :data}]
  (when-not (#{"add" "remove"} (:operation data))
    (fail :bad-request :desc (str "illegal organization operation: '" (:operation data) "'"))))

(defcommand update-user-organization
  {:parameters       [operation email firstName lastName]
   :input-validators [valid-organization-operation?
                      (partial action/non-blank-parameters [:email :firstName :lastName])
                      action/email-validator]
   :roles            [:authorityAdmin]}
  [{caller :user}]
  (let [email            (ss/lower-case email)
        new-organization (first (:organizations caller))
        update-count     (mongo/update-n :users {:email email, :role "authority"}
                           {({"add" $addToSet "remove" $pull} operation) {:organizations new-organization}})]
    (debug "update user" email)
    (if (pos? update-count)
      (ok :operation operation)
      (if (and (= operation "add") (not (user/get-user-by-email email)))
        (create-authority-user-with-organization caller new-organization email firstName lastName)
        (fail :error.user-not-found)))))

(defmethod token/handle-token :authority-invitation [{{:keys [email organization caller-email]} :data} {password :password}]
  (infof "invitation for new authority: email=%s: processing..." email)
  (let [caller (user/get-user-by-email caller-email)]
    (when-not caller (fail! :not-found :desc (format "can't process invitation token for email %s, authority admin (%s) no longer exists" email caller-email)))
    (user/change-password email password)
    (infof "invitation was accepted: email=%s, organization=%s" email organization)
    (ok)))

;;
;; Change and reset password:
;;

;; TODO: Remove this, change all password changes to use 'reset-password'.
;; Note: When this is removed, remove user/change-password too.
(defcommand change-passwd
  {:parameters [oldPassword newPassword]
   :authenticated true
   :verified true}
  [{{user-id :id :as user} :user}]
  (let [user-data (mongo/by-id :users user-id)]
    (if (security/check-password oldPassword (-> user-data :private :password))
      (do
        (debug "Password change: user-id:" user-id)
        (user/change-password (:email user) newPassword)
        (ok))
      (do
        (warn "Password change: failed: old password does not match, user-id:" user-id)
        (fail :mypage.old-password-does-not-match)))))

(defcommand reset-password
  {:parameters    [email]
   :input-validators [(partial action/non-blank-parameters [:email])
                      action/email-validator]
   :notified      true
   :authenticated false}
  [_]
  (let [email (ss/lower-case email)]
    (infof "Password reset request: email=%s" email)
    (let [user (mongo/select-one :users {:email email})]
      (if (and user (not= "dummy" (:role user)))
       (let [token-ttl (* 24 60 60 1000)
             token (token/make-token :password-reset {:email email} :ttl token-ttl)]
         (infof "password reset request: email=%s, token=%s" email token)
         (notifications/notify! :reset-password {:data {:email email :token token}})
         (ok))
       (do
         (warnf "password reset request: unknown email: email=%s" email)
         (fail :email-not-found))))))

(defmethod token/handle-token :password-reset [{data :data} {password :password}]
  (let [email (ss/lower-case (:email data))]
    (user/change-password email password)
    (infof "password reset performed: email=%s" email)
    (resp/status 200 (resp/json {:ok true}))))

;;
;; enable/disable:
;;

(defcommand set-user-enabled
  {:parameters    [email enabled]
   :input-validators [(partial action/non-blank-parameters [:email])
                      action/email-validator]
   :roles         [:admin]}
  [_]
  (let [email (ss/lower-case email)
       enabled (contains? #{true "true"} enabled)]
   (infof "%s user: email=%s" (if enabled "enable" "disable") email)
   (if (= 1 (mongo/update-n :users {:email email} {$set {:enabled enabled}}))
     (ok)
     (fail :not-found))))

;;
;; ==============================================================================
;; Login:
;; ==============================================================================
;;


(defcommand login
  {:parameters [username password]
   :verified false}
  [_]
  (if (user/throttle-login? username)
    (do
      (info "login throttled, username:" username)
      (fail :error.login-trottle))
    (if-let [user (user/get-user-with-password username password)]
      (do
        (info "login successful, username:" username)
        (user/clear-logins username)
        (session/put! :user user)
        (if-let [application-page (user/applicationpage-for (:role user))]
          (ok :user user :applicationpage application-page)
          (do
            (error "Unknown user role:" (:role user))
            (fail :error.login))))
      (do
        (info "login failed, username:" username)
        (user/login-failed username)
        (fail :error.login)))))

(defcommand impersonate-authority
  {:parameters [organizationId password]
   :roles [:admin]
   :input-validators [(partial action/non-blank-parameters [:organizationId])]
   :description "Changes admin session into authority session with access to given organization"}
  [{user :user}]
  (if (user/get-user-with-password (:username user) password)
    (let [imposter (assoc user :impersonating true :role "authority" :organizations [organizationId])]
      (session/put! :user imposter)
      (ok))
    (fail :error.login)))

;;
;; ==============================================================================
;; Registering:
;; ==============================================================================
;;

(defcommand register-user
  {:parameters [stamp email password street zip city phone]
   :input-validators [(partial action/non-blank-parameters [:email :password])
                      action/email-validator]
   :verified   true}
  [{data :data}]
  (let [vetuma-data (vetuma/get-user stamp)
        email (-> email ss/lower-case ss/trim)]
    (when-not vetuma-data (fail! :error.create-user))
    (when-not (ss/contains email "@") (fail! :error.email))
    (try
      (infof "Registering new user: %s - details from vetuma: %s" (dissoc data :password) vetuma-data)
      (if-let [user (create-new-user (user/current-user) (merge data vetuma-data {:email email :role "applicant"}))]
        (do
          (vetuma/consume-user stamp)
          (ok :id (:_id user)))
        (fail :error.create-user))
      (catch IllegalArgumentException e
        (fail (keyword (.getMessage e)))))))

;;
;; ==============================================================================
;; User attachments:
;; ==============================================================================
;;

(defquery user-attachments
  {:authenticated true}
  [{user :user}]
  (ok :attachments (:attachments user)))

(defpage [:post "/api/upload/user-attachment"] {[{:keys [tempfile filename content-type size]}] :files attachmentType :attachmentType}
  (let [user              (user/current-user)
        filename          (mime/sanitize-filename filename)
        attachment-type   (attachment/parse-attachment-type attachmentType)
        attachment-id     (mongo/create-id)
        file-info         {:attachment-type  attachment-type
                           :attachment-id    attachment-id
                           :file-name        filename
                           :content-type     content-type
                           :size             size
                           :created          (now)}]

    (info "upload/user-attachment" (:username user) ":" attachment-type "/" filename content-type size "id=" attachment-id)
    (when-not ((set attachment/attachment-types-osapuoli) (:type-id attachment-type)) (fail! :error.illegal-attachment-type))
    (when-not (mime/allowed-file? filename) (fail :error.illegal-file-type))

    (mongo/upload attachment-id filename content-type tempfile :user-id (:id user))
    (mongo/update-by-id :users (:id user) {$push {:attachments file-info}})
    (user/refresh-user!)

    (->> (assoc file-info :ok true)
      (resp/json)
      (resp/content-type "text/plain") ; IE is fucking stupid: must use content type text/plain, or else IE prompts to download response.
      (resp/status 200))))

(defraw download-user-attachment
  {:parameters [attachment-id]}
  [{user :user}]
  (when-not user (throw+ {:status 401 :body "forbidden"}))
  (if-let [attachment (mongo/download-find {:id attachment-id :metadata.user-id (:id user)})]
    {:status 200
     :body ((:content attachment))
     :headers {"Content-Type" (:content-type attachment)
               "Content-Length" (str (:content-length attachment))
               "Content-Disposition" (format "attachment;filename=\"%s\"" (ss/encode-filename (:file-name attachment)))}}
    {:status 404
     :body (str "can't file attachment: id=" attachment-id)}))

(defcommand remove-user-attachment
  {:parameters [attachment-id]
   :authenticated true}
  [{user :user}]
  (info "Removing user attachment: attachment-id:" attachment-id)
  (mongo/update-by-id :users (:id user) {$pull {:attachments {:attachment-id attachment-id}}})
  (user/refresh-user!)
  (mongo/delete-file {:id attachment-id :metadata.user-id (:id user)})
  (ok))

(defcommand copy-user-attachments-to-application
  {:parameters [id]
   :authenticated true
   :roles [:applicant]
   :states     [:draft :open :submitted :complement-needed]
   :pre-checks [(fn [command application] (not (-> command :user :architect)))]}
  [{application :application user :user}]
  (doseq [attachment (:attachments user)]
    (let [
          application-id id
          user-id (:id user)
          {:keys [attachment-type attachment-id file-name content-type size created]} attachment
          attachment (mongo/download-find {:id attachment-id :metadata.user-id user-id})
          attachment-id (str application-id "." user-id "." attachment-id)
          ]
      (when (zero? (mongo/count :applications {:_id application-id :attachments.id attachment-id}))
        (attachment/attach-file! {:application application
                       :attachment-id attachment-id
                       :attachment-type attachment-type
                       :content ((:content attachment))
                       :filename file-name
                       :content-type content-type
                       :file-size size
                       :created created
                       :user user
                       ;:attachment-target attachment-target
                       :locked false}))))
  (ok))

;;
;; ==============================================================================
;; Development utils:
;; ==============================================================================
;;

; FIXME: generalize
(env/in-dev

  (defquery activations {} [query]
    (ok :activations (activation/activations))))

