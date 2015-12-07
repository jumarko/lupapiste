(ns lupapalvelu.user-api-itest
  (:require [clojure.java.io :as io]
            [monger.operators :refer :all]
            [midje.sweet :refer :all]
            [ring.util.codec :as codec]
            [lupapalvelu.itest-util :refer :all]
            [lupapalvelu.factlet :refer [fact* facts*]]
            [lupapalvelu.mongo :as mongo]
            [lupapalvelu.user :as user]
            [lupapalvelu.user-api :as user-api]))

;;
;; ==============================================================================
;; Getting user and users:
;; ==============================================================================
;;
(apply-remote-minimal)

(fact "user query"
  (let [response (query pena :user)]
    response => ok?
    (get-in response [:user :email]) => "pena@example.com"))

(facts "Getting users"

  (fact "applicants are not allowed to call this"
    (query pena :users) =not=> ok?)

  ; It's not nice to test the number of users, but... well, this is relly easy:
  (fact (-> (query admin :users :role "admin") :users count) => 1)
  (fact (-> (query admin :users :organization "753-R") :users count) => 5)
  (fact (-> (query admin :users :role "authority" :organization "753-R") :users count) => 4))

(facts users-for-datatables
 (fact (datatables admin :users-for-datatables :params {:iDisplayLength 5 :iDisplayStart 0 :sEcho "123" :enabled "true" :organizations ["753-R"]})
   => (contains {:ok true
                 :data (contains {:rows (comp (partial = 5) count)
                                  :total 5
                                  :display 5
                                  :echo "123"})}))
 (fact (datatables admin :users-for-datatables :params {:iDisplayLength 5 :iDisplayStart 0 :sEcho "123" :enabled "true" :organizations ["753-R"] :filter-search "Suur"})
   => (contains {:ok true
                 :data (contains {:rows (comp (partial = 1) count)
                                  :total 5
                                  :display 1
                                  :echo "123"})})))
;;
;; ==============================================================================
;; Creating users:
;; ==============================================================================
;;

(facts create-user
  (apply-remote-minimal)
  (fact (command pena :create-user :email "x" :role "dummy" :password "foobarbozbiz") => fail?)
  (fact (command admin :create-user :email "x@example.com" :role "authorityAdmin" :enabled true
          :organization "753-R" :password "foobarbozbiz") => ok?)
  ; Check that user was created
  (fact (-> (query admin :users :email "x@example.com") :users first) => (contains {:role "authorityAdmin" :email "x@example.com" :enabled true :orgAuthz {:753-R ["authorityAdmin"]}}))

  ; Inbox zero
  (last-email)

  (fact "authority without organization, i.e., a statement giver, can be created"
    (command sipoo :create-user :email "foo@example.com" :role "authority" :enabled true) => ok?)
  (fact "newly created authority receives mail"
    (let [email (last-email)]
      (:to email) => "foo@example.com"
      (:subject email) => "Lupapiste.fi: Kutsu Lupapiste-palvelun viranomaisk\u00e4ytt\u00e4j\u00e4ksi"
      (get-in email [:body :plain]) => (contains "/app/fi/welcome#!/setpw/"))))

;;
;; ==============================================================================
;; Updating user data:
;; ==============================================================================
;;

(facts "Veikko updates his name"
  (fact (command veikko :update-user :firstName "f" :lastName "l") => ok?)
  (fact (-> (query veikko :user) :user) => (contains {:firstName "f" :lastName "l"})))

(facts "save-application-filter"
  (apply-remote-minimal)

  (fact "fails with invalid filter type"
    (command ronja :save-application-filter :title "titteli" :filter {} :sort {:field "applicant" :asc true} :filterId "beefcace" :filterType "a") => fail?)

  (fact (command ronja :save-application-filter :title "titteli" :filter {} :sort {:field "applicant" :asc true} :filterId "beefcace" :filterType "application") => ok?)
  (fact "Filter is saved"
    (let [{user :user} (query admin :user-by-email :email "ronja.sibbo@sipoo.fi")]
      (get-in user [:applicationFilters 0 :title]) => "titteli"
      (fact "as default"
        (->> user :defaultFilter :id) => "beefcace"))))

(facts update-default-application-filter
  (apply-remote-minimal)

  (fact (command sonja :update-default-application-filter :filterId "foobar" :filterType "application") => ok?)

  (fact (->> (query admin :user-by-email :email "sonja.sibbo@sipoo.fi") :user :defaultFilter :id) => "foobar")

  (fact "Overwrite default filter"
    (command sonja :update-default-application-filter :filterId "barfoo" :filterType "application") => ok?)

  (fact "Filter overwritten"
      (->> (query admin :user-by-email :email "sonja.sibbo@sipoo.fi") :user :defaultFilter :id) => "barfoo"))

(facts update-user-organization
  (apply-remote-minimal)

  (fact (command naantali :create-user :email "foo@example.com" :role "authority" :enabled "true" :organization "529-R") => ok?)

  (fact (->> (query admin :user-by-email :email "foo@example.com") :user :orgAuthz keys (map name)) => ["529-R"])
  (fact (command sipoo :update-user-organization :email "foo@example.com" :firstName "bar" :lastName "har" :roles ["authority"]) => ok?)

  (fact (->> (query admin :user-by-email :email "foo@example.com") :user :orgAuthz keys (map name)) => (just ["529-R" "753-R"] :in-any-order))
  (fact (command sipoo :update-user-organization :email "foo@example.com" :firstName "bar" :lastName "har" :roles ["authority"]) => ok?)

  (fact (command sipoo :update-user-organization :email "foo@example.com" :firstName "bar" :lastName "har" :roles []) => (contains {:ok false, :parameters ["roles"], :text "error.vector-parameters-with-items-missing-required-keys"}))

  (fact (command sipoo :update-user-organization :email (email-for-key teppo) :firstName "Teppo" :lastName "Example" :roles ["authority"]) => fail?)

  (fact (command sipoo :update-user-organization :email "tonja.sibbo@sipoo.fi" :firstName "bar" :roles ["authority"]) => (contains {:ok false :parameters ["lastName"], :text "error.missing-parameters"}))

  (fact "invite new user Tonja to Sipoo"

    (command sipoo :update-user-organization :email "tonja.sibbo@sipoo.fi" :firstName "bar" :lastName "har" :operation "add"  :roles ["authority"]) => ok?

    (let [email (last-email)]
      (:to email) => (contains "tonja.sibbo@sipoo.fi")
      (:subject email) => "Lupapiste.fi: Kutsu Lupapiste-palvelun viranomaisk\u00e4ytt\u00e4j\u00e4ksi"
      (get-in email [:body :plain]) => (contains "/app/fi/welcome#!/setpw/")))

  (fact "add existing authority to new organization"

    (command naantali :update-user-organization :email "tonja.sibbo@sipoo.fi" :firstName "bar" :lastName "har" :operation "add"  :roles ["authority"]) => ok?
    (let [email (last-email)]
      (:to email) => (contains "tonja.sibbo@sipoo.fi")
      (:subject email) => "Lupapiste.fi: Ilmoitus k\u00e4ytt\u00e4j\u00e4tilin liitt\u00e4misest\u00e4 organisaation viranomaisk\u00e4ytt\u00e4j\u00e4ksi"
      (get-in email [:body :plain]) => (contains "Naantalin rakennusvalvonta"))))

(facts remove-user-organization
  (apply-remote-minimal)

  (fact (command naantali :create-user :email "foo@example.com" :role "authority" :enabled "true" :organization "529-R") => ok?)
  (fact (command sipoo :update-user-organization :email "foo@example.com" :firstName "bar" :lastName "har" :operation "add" :roles ["authority"]) => ok?)
  (fact (->> (query admin :user-by-email :email "foo@example.com") :user :orgAuthz keys (map name)) => (just ["529-R" "753-R"] :in-any-order))
  (fact (command sipoo :remove-user-organization :email "foo@example.com") => ok?)
  (fact (->> (query admin :user-by-email :email "foo@example.com") :user :orgAuthz keys (map name)) => ["529-R"]))

(fact update-user-roles
  (apply-remote-minimal)
  (fact "Meta: check current roles" (-> (query admin :user-by-email :email "sonja.sibbo@sipoo.fi") :user :orgAuthz :753-R) => ["authority" "approver"])
  (fact (command sipoo :update-user-roles :email "sonja.sibbo@sipoo.fi" :roles ["authority" "foobar"]) => fail?)
  (fact (-> (query admin :user-by-email :email "sonja.sibbo@sipoo.fi") :user :orgAuthz :753-R) => ["authority" "approver"])

  (fact "Sipoo does not have permanent achive, can not set TOS roles but reader is OK"
    (command sipoo :update-user-roles :email "sonja.sibbo@sipoo.fi" :roles ["authority" "tos-editor" "tos-publisher" "reader"]) => ok?
    (-> (query admin :user-by-email :email "sonja.sibbo@sipoo.fi") :user :orgAuthz :753-R) => ["authority" "reader"])

  (fact "Jarvenpaa has permanent achive, can set TOS roles"
    (fact "Meta: check current roles" (-> (query admin :user-by-email :email "rakennustarkastaja@jarvenpaa.fi") :user :orgAuthz :186-R) => ["authority"])
    (command jarvenpaa :update-user-roles :email "rakennustarkastaja@jarvenpaa.fi" :roles ["authority" "tos-editor" "tos-publisher"]) => ok?
    (-> (query admin :user-by-email :email "rakennustarkastaja@jarvenpaa.fi") :user :orgAuthz :186-R) => ["authority" "tos-editor" "tos-publisher"]))

(fact "changing user info"
  (apply-remote-minimal)
  (fact (query teppo :user) => (every-checker ok? (contains
                                                    {:user
                                                     (just
                                                       {:city "Tampere"
                                                        :email "teppo@example.com"
                                                        :enabled true
                                                        :firstName "Teppo"
                                                        :id "5073c0a1c2e6c470aef589a5"
                                                        :lastName "Nieminen"
                                                        :phone "0505503171"
                                                        :role "applicant"
                                                        :street "Mutakatu 7"
                                                        :username "teppo@example.com"
                                                        :zip "33560"})})))
  (fact
    (let [data {:firstName "Seppo"
                :lastName "Sieninen"
                :street "Sutakatu 7"
                :city "Sampere"
                :zip "33200"
                :phone "0505503171"
                :architect true
                :allowDirectMarketing true
                :degree "kirvesmies"
                :graduatingYear "2000"
                :fise "f"
                :fiseKelpoisuus "tavanomainen p\u00e4\u00e4suunnittelu (uudisrakentaminen)"
                :companyName "cn"
                :companyId "1060155-5"}]
      (apply command teppo :update-user (flatten (seq data))) => ok?
      (query teppo :user) => (contains {:user (contains data)}))))

;;
;; historical tests, dragons be here...
;;

(defn upload-user-attachment [apikey attachment-type expect-to-succeed]
  (let [filename    "dev-resources/test-attachment.txt"
        uploadfile  (io/file filename)
        uri         (str (server-address) "/api/upload/user-attachment")
        resp        (http-post uri
                      {:headers {"authorization" (str "apikey=" apikey)}
                       :multipart [{:name "attachmentType"  :content attachment-type}
                                   {:name "files[]"         :content uploadfile}]})
        body        (:body (decode-response resp))]
    (if expect-to-succeed
      (facts "successful"
        resp => http200?
        body => ok?)
      (facts "should fail"
        (:status resp) =not=> 200
        body => fail?))
    body))

(facts* "uploading user attachment"
  (apply-remote-minimal)

  ;
  ; Initially pena does not have attachments?
  ;

  (fact "Initially pena does not have attachments?"
        (:attachments (query pena "user-attachments")) => nil?)

  ;
  ; Pena uploads a tutkintotodistus:
  ;

  (fact "Applicant Pena can upload user attachment"
    (query pena :add-user-attachment-allowed) => ok?)

  (fact "Auhtority Sonja can't upload user attachment"
    (query sonja :add-user-attachment-allowed) => unauthorized?)

  (let [attachment-id (:attachment-id (upload-user-attachment pena "osapuolet.tutkintotodistus" true))]

    ; Now Pena has attachment
    (get (:attachments (query pena "user-attachments")) 0) => (contains {:attachment-id attachment-id :attachment-type {:type-group "osapuolet", :type-id "tutkintotodistus"}})

    ; Attachment is in GridFS

    (let [resp (raw pena "download-user-attachment" :attachment-id attachment-id) => http200?]
      (:body resp) => "This is test file for file upload in itest.")

    (fact "Sonja can not get attachment"
      (raw sonja "download-user-attachment" :attachment-id attachment-id) => http401?)

    (fact "Sonja can not delete attachment"
      (command sonja "remove-user-attachment" :attachment-id attachment-id)
      (get (:attachments (query pena "user-attachments")) 0) =not=> nil?)

    ; Pena can delete attachment

    (command pena "remove-user-attachment" :attachment-id attachment-id) => ok?
    (:attachments (query pena "user-attachments")) => empty?))

;;
;; ==============================================================================
;; Admin impersonates an authority
;; ==============================================================================
;;
(facts* "impersonating"
 (let [store        (atom {})
       params       {:cookie-store (->cookie-store store)
                     :follow-redirects false
                     :throw-exceptions false}
       login        (http-post
                      (str (server-address) "/api/login")
                      (assoc params :form-params {:username "admin" :password "admin"})) => http200?
       csrf-token   (-> (get @store "anti-csrf-token") .getValue codec/url-decode) => truthy
       params       (assoc params :headers {"x-anti-forgery-token" csrf-token})
       sipoo-rakval (-> "sipoo" find-user-from-minimal :orgAuthz keys first name)
       impersonate  (fn [password]
                      (-> (http-post
                            (str (server-address) "/api/command/impersonate-authority")
                            (assoc params
                              :form-params (merge {:organizationId sipoo-rakval :role "authority"} (when password {:password password}))
                              :content-type :json))
                        decode-response :body))
       role         (fn [] (-> (http-get (str (server-address) "/api/query/user") params) decode-response :body :user :role))
       actions      (fn [] (-> (http-get (str (server-address) "/api/query/allowed-actions") params) decode-response :body :actions))]

   (fact "impersonation action is available"
     (:impersonate-authority (actions)) => ok?)

   (fact "admin can not query property owners"
     (-> (http-get (str (server-address) "/api/query/owners?propertyId=0") params) decode-response :body) => unauthorized?)

   (fact "fails without password"
     (impersonate nil) => fail?)

   (fact "fails with wrong password"
     (impersonate "nil") => fail?)

   (fact "role remains admin"
     (role) => "admin")

   (let [application (create-and-submit-application pena :propertyId sipoo-property-id) => truthy
         application-id (:id application)
         query-as-admin (http-get (str (server-address) "/api/query/application?id=" application-id) params) => http200?]

     (fact "sonja sees the application"
       (query-application sonja application-id) => truthy)

     (fact "but admin doesn't"
       (-> query-as-admin decode-response :body) => unauthorized?)

     (fact "succeeds with correct password"
       (impersonate "admin") => ok?)

     (fact "but not again (as we're now impersonating)"
       (impersonate "admin") => fail?)

     (fact "instead, application is visible"
       (let [query-as-imposter (http-get (str (server-address) "/api/query/application?id=" application-id) params) => http200?
             body (-> query-as-imposter decode-response :body) => ok?
             application (:application body)]
         (:id application) => application-id)))

   (fact "role has changed to authority"
     (role) => "authority")

   (fact "every available action is a query or raw, i.e. not a command
          (or any other mutating action type we might have in the future)"
     (let [action-names (keys (filter (fn [[name ok]] (ok? ok)) (actions)))]
       ; Make sure we have required all the actions
       (require 'lupapalvelu.server)
       (map #(:type (% @lupapalvelu.action/actions)) action-names) => (partial every? #{:query :raw})))

   (fact "still can not query property owners"
     (-> (http-get (str (server-address) "/api/query/owners?propertyId=12312312341234") params) decode-response :body) => unauthorized?)))

(facts* "reset password email"
  (last-email) ; Inbox zero

  (let [params {:form-params {:email (email-for "pena")}
                :content-type :json
                :follow-redirects false
                :throw-exceptions false}
        resp   (http-post (str (server-address) "/api/reset-password") params) => http200?
        email  (last-email)]
    (-> resp decode-response :body) => ok?
    (:to email) => (email-for "pena")
    (:subject email) => "Lupapiste.fi: Salasanan vaihto"
    (get-in email [:body :plain]) => (contains "/app/fi/welcome#!/setpw/")))
