(ns lupapalvelu.rest-docstore-api-itest
  (:require [midje.sweet :refer :all]
            [lupapalvelu.itest-util :refer :all]
            [lupapalvelu.organization :as org]))

(apply-remote-minimal)

(def organizations-address (str (server-address) "/rest/docstore/organizations"))

(def organization-address (str (server-address) "/rest/docstore/organization"))

(def docstore-user-basic-auth ["docstore" "basicauth"])

(defn- api-call [address params]
  (http-get address (merge params {:throw-exceptions false})))

(defn- docstore-api-call [address query-params]
  (decode-response
   (api-call address (merge {:basic-auth docstore-user-basic-auth}
                            (when-not (empty? query-params)
                              {:query-params query-params})))))

(facts "REST interface for organization docstore information -"

  (fact "not available as anonymous user"
    (api-call organizations-address {}) => http401?
    (api-call organization-address {:query-params {:id "753-R"}}) => http401?)

  (fact "Docstore user can access"
    (docstore-api-call organizations-address {}) => http200?
    (docstore-api-call organization-address {:id "753-R"}) => http200?)
  (fact "Queries return correct information"
    (-> (docstore-api-call organization-address {:id "753-R"}) :body :data)
    => (assoc org/default-docstore-info :id "753-R")
    (-> (docstore-api-call organizations-address {}) :body :data)
    => [(assoc org/default-docstore-info :id "753-R")])

  (fact "Docstore user cannot access other REST endpoints"
        (api-call (str (server-address) "/rest/submitted-applications")
                  {:basic-auth docstore-user-basic-auth})
        => http401?))
