(ns lupapalvelu.application-itest
  (:use [lupapalvelu.itest-util]
        [midje.sweet]
        [clojure.pprint :only [pprint]]))

(defn- not-empty? [m] (not (empty? m)))

(fact "creating application without message"
  (let [resp            (command pena :create-application :permitType "buildingPermit" :x 444444 :y 6666666 :address "foo 42, bar" :municipality "753")
        application-id  (:id resp)
        resp            (query pena :application :id application-id)
        application     (:application resp)]
    application => (contains {:id application-id
                              :state "draft"
                              :location {:x 444444 :y 6666666}
                              :permitType "buildingPermit"})
    (count (:comments application)) => 0
    (first (:auth application)) => (contains
                                     {:firstName "Pena"
                                      :lastName "Panaani"
                                      :type "owner"
                                      :role "owner"})
    (:allowedAttahmentTypes application) => not-empty?))

(fact "creating application message"
  (let [resp            (command pena :create-application :permitType "buildingPermit" :x 444444 :y 6666666 :address "foo 42, bar" :municipality "753" :message "hello")
        application-id  (:id resp)
        resp            (query pena :application :id application-id)
        application     (:application resp)
        state           (:state application)
        comments        (:comments application)]
    state => "draft"
    (count comments) => 1
    (-> comments first :text) => "hello"))

(fact "Application in Sipoo has two possible authorities"
  (let [application-id (:id (command pena :create-application :permitType "buildingPermit" :x 444444 :y 6666666 :address "foo 42, bar" :municipality "Sipoo" :message "hello"))
        authorityInfo  (:authorityInfo (query sonja :authorities-in-applications-municipalities :id application-id))]
    (fact (count authorityInfo) => 1)
    (fact (count (get authorityInfo (keyword application-id))) => 2)))
