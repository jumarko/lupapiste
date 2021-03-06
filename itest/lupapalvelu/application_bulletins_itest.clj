 (ns lupapalvelu.application-bulletins-itest
   (:require [midje.sweet :refer :all]
             [lupapalvelu.itest-util :refer :all]
             [lupapalvelu.application-bulletins-itest-util :refer :all]
             [lupapalvelu.factlet :refer :all]
             [sade.util :as util]
             [sade.strings :as ss]
             [sade.core :refer [now]]))

(apply-remote-minimal)

(let [r-app (create-and-submit-application mikko :operation "kerrostalo-rivitalo"
                                          :propertyId sipoo-property-id
                                          :x 406898.625 :y 6684125.375
                                          :address "Hitantine 108")
      op-description "Kuvausteksti123"
      app-id (:id r-app)]
  (fact "bulletin-op-description"
    (command sonja :update-app-bulletin-op-description :id (:id r-app) :description op-description) => ok?
    (-> (get-by-id :applications app-id) :body :data) => (contains  {:bulletinOpDescription op-description}))
  (fact "check for backend verdict"
    (command sonja :check-for-verdict :id app-id) => ok?
    (-> (query-application mikko app-id) :verdicts count) => pos?)
  (fact "Bulletin is created automatically"
    (let [bulletins (:data (datatables mikko :application-bulletins :municipality sonja-muni :searchText "" :state "" :page 1 :sort ""))]
      (count bulletins) => 1
      (-> bulletins first :bulletinState) => "verdictGiven"
      ;TODO ensure bulletin dates looks correct
      (fact "description is set"
        (-> bulletins first :bulletinOpDescription) => op-description)))

  (fact "foreman verdict"
    (let [foreman-app-id (create-foreman-application app-id mikko pena-id "vastaava ty\u00F6njohtaja" "A")
          _ (finalize-foreman-app mikko sonja foreman-app-id true)
          bulletins (:data (datatables mikko :application-bulletins :municipality sonja-muni :searchText "" :state "" :page 1 :sort ""))]
      (fact "bulletin was not generated"
        (util/find-by-key :application-id foreman-app-id bulletins) => empty?)))

  (fact "Fetch bulletin app-description from backend"
    (command sipoo :update-organization-bulletin-scope
             :permitType "R"
             :municipality "753"
             :descriptionsFromBackendSystem true) => ok?
    (command sonja :check-for-verdict :id app-id) => ok?)
    (let [bulletins (:data (datatables mikko :application-bulletins :municipality sonja-muni :searchText "" :state "" :page 1 :sort ""))]
      (fact "description is set according to the backend verdict"
        (-> bulletins first :bulletinOpDescription) => "Uudisrakennuksen ja talousrakennuksen 15 m2 rakentaminen. Yhden huoneiston erillistalo.")))