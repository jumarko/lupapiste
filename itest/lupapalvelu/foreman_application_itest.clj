(ns lupapalvelu.foreman-application-itest
  (:require [midje.sweet :refer :all]
            [lupapalvelu.itest-util :refer :all]
            [lupapalvelu.factlet :refer :all]
            [lupapalvelu.domain :as domain]))

(apply-remote-minimal)

(facts* "Foreman application"
  (let [apikey                       mikko
        {application-id :id}         (create-app apikey :operation "kerrostalo-rivitalo") => truthy
        application                  (query-application apikey application-id)
        _                            (generate-documents application apikey)
        {foreman-application-id :id} (command apikey :create-foreman-application :id application-id :taskId "" :foremanRole "ei tiedossa") => truthy

        foreman-application          (query-application apikey foreman-application-id)
        foreman-link-permit-data     (first (foreman-application :linkPermitData))
        foreman-doc                  (domain/get-document-by-name foreman-application "tyonjohtaja-v2")
        application                  (query-application apikey application-id)
        link-to-application          (first (application :appsLinkingToUs))
        foreman-applications         (query apikey :foreman-applications :id application-id) => truthy]

    (fact "Update ilmoitusHakemusValitsin to 'ilmoitus'"
      (command apikey :update-doc :id foreman-application-id :doc (:id foreman-doc) :updates [["ilmoitusHakemusValitsin" "ilmoitus"]]) => ok?)

    (fact "Foreman application contains link to application"
      (:id foreman-link-permit-data) => application-id)

    (fact "Original application contains link to foreman application"
      (:id link-to-application) => foreman-application-id)

    (fact "All linked Foreman applications are returned in query"
      (let [applications (:applications foreman-applications)]
        (count applications) => 1
        (:id (first applications)) => foreman-application-id))

    (fact "Document data is copied to foreman application"
      (fact "Hankkeen kuvaus"
        (let [foreman-hankkeen-kuvaus (domain/get-document-by-name foreman-application "hankkeen-kuvaus-minimum")
              app-hankkeen-kuvaus     (domain/get-document-by-name application "hankkeen-kuvaus")]

          (get-in app-hankkeen-kuvaus [:data :kuvaus :value]) => (get-in foreman-hankkeen-kuvaus [:data :kuvaus :value])))

      (fact "Tyonjohtaja doc has value from the command"
        (get-in foreman-doc [:data :kuntaRoolikoodi :value]) => "ei tiedossa")

      (fact "Hakija docs are equal, expect the userId"
        (let [hakija-doc-data         (:henkilo (:data (domain/get-document-by-name application "hakija-r")))
              foreman-hakija-doc-data (:henkilo (:data (domain/get-document-by-name foreman-application "hakija-r")))]

          hakija-doc-data => map?
          foreman-hakija-doc-data => map?

          (dissoc hakija-doc-data :userId) => (dissoc foreman-hakija-doc-data :userId))))

    (fact "Can't submit foreman app before original link-permit-app is submitted"
      (:submittable (query-application apikey foreman-application-id)) => false)

    (fact "Submit link-permit app"
      (command apikey :submit-application :id application-id :confirm false) => ok?)

    (facts "Submit foreman-app"
      (fact "gives error first and warns about foreman notice"
        (command apikey :submit-application :id foreman-application-id :confirm false) => (partial expected-failure? :error.foreman.notice-not-submittable))
      (fact "By confirming the foreman notice application can be submitted"
        (command apikey :submit-application :id foreman-application-id :confirm true) => ok?))

    (fact "Link foreman application to task"
      (let [apikey                       mikko
            application (create-and-submit-application apikey)
            _ (command sonja :check-for-verdict :id (:id application))
            application (query-application apikey (:id application))
            {foreman-application-id :id} (command apikey :create-foreman-application :id (:id application) :taskId "" :foremanRole "")
            tasks (:tasks application)
            foreman-task (first (filter #(= (get-in % [:schema-info :name]) "task-vaadittu-tyonjohtaja") tasks))]
        (command apikey :link-foreman-task :id (:id application) :taskId (:id foreman-task) :foremanAppId foreman-application-id) => ok?
        (let [app (query-application apikey (:id application))
              updated-tasks (:tasks app)
              updated-foreman-task (first (filter #(= (get-in % [:schema-info :name]) "task-vaadittu-tyonjohtaja") updated-tasks))]
          (get-in updated-foreman-task [:data :asiointitunnus :value]) => foreman-application-id)))

    (facts "approve foreman"
      (fact "Can't approve foreman application before actual application"
        (command sonja :approve-application :id foreman-application-id :lang "fi") => (partial expected-failure? "error.link-permit-app-not-in-post-sent-state"))

      (fact "After approving actual application, foreman can be approved. No need for verdict"
        (command sonja :approve-application :id application-id :lang "fi") => ok?
        (command sonja :approve-application :id foreman-application-id :lang "fi") => ok?)

      (fact "when foreman application is of type 'ilmoitus', after approval its state is closed"
        (:state (query-application apikey foreman-application-id)) => "closed"))

    (facts "updating other foreman projects to current foreman application"
      (let [{application1-id :id}         (create-app apikey :operation "kerrostalo-rivitalo") => truthy
            {foreman-application1-id :id} (command apikey :create-foreman-application :id application1-id :taskId "" :foremanRole "") => truthy

            {application2-id :id}         (create-app apikey :operation "kerrostalo-rivitalo") => truthy
            {foreman-application2-id :id} (command apikey :create-foreman-application :id application2-id :taskId "" :foremanRole "") => truthy

            foreman-application1          (query-application apikey foreman-application1-id)
            foreman-application2          (query-application apikey foreman-application2-id)

            foreman-doc1                  (domain/get-document-by-name foreman-application1 "tyonjohtaja-v2")
            foreman-doc2                  (domain/get-document-by-name foreman-application2 "tyonjohtaja-v2")]

        (fact "other project is updated into current foreman application"
          (command apikey :set-current-user-to-document :id foreman-application1-id :documentId (:id foreman-doc1) :userId mikko-id :path "" :collection "documents" => truthy)
          (command apikey :set-current-user-to-document :id foreman-application2-id :documentId (:id foreman-doc2) :userId mikko-id :path "" :collection "documents" => truthy)
          (command apikey :update-foreman-other-applications :id foreman-application2-id :foremanHetu "")

          (let [updated-application (query-application apikey foreman-application2-id)
                updated-foreman-doc (domain/get-document-by-name updated-application "tyonjohtaja-v2")
                project-id (get-in updated-foreman-doc [:data :muutHankkeet :0 :luvanNumero :value])]
            (fact "first project is in other projects document"
              project-id => application1-id)))))

    (facts "foreman history"
      (apply-remote-minimal) ; clean mikko before history tests
      (let [{application-id :id} (create-app apikey :operation "kerrostalo-rivitalo")
            foreman-app-id1      (create-foreman-application application-id apikey mikko-id "KVV-ty\u00F6njohtaja" "B"); -> should be visible
            foreman-app-id2      (create-foreman-application application-id apikey mikko-id "KVV-ty\u00F6njohtaja" "A") ; -> should be visible
            foreman-app-id3      (create-foreman-application application-id apikey mikko-id "vastaava ty\u00F6njohtaja" "B") ; -> should be visible
            foreman-app-id4      (create-foreman-application application-id apikey mikko-id "vastaava ty\u00F6njohtaja" "B") ; -> should *NOT* be visible
            foreman-app-id5      (create-foreman-application application-id apikey mikko-id "vastaava ty\u00F6njohtaja" "A") ; -> should be visible

            base-foreman-app-id  (create-foreman-application application-id apikey mikko-id "vastaava ty\u00F6njohtaja" "B") => truthy] ;for calling history

        (facts "reduced"
          (fact "reduced history should contain reduced history"
            (let [reduced-history (query apikey :reduced-foreman-history :id base-foreman-app-id) => ok?
                  history-ids (map :foremanAppId (:projects reduced-history))]
              history-ids => (just [foreman-app-id1 foreman-app-id2 foreman-app-id3 foreman-app-id5] :in-any-order)
              (some #{foreman-app-id4} history-ids) => nil?))

          (fact "reduced history should depend on the base application"
            (let [reduced-history (query apikey :reduced-foreman-history :id foreman-app-id1) => ok?
                  history-ids (map :foremanAppId (:projects reduced-history))]
              history-ids =>     (just [foreman-app-id2 foreman-app-id3 foreman-app-id5] :in-any-order)
              history-ids =not=> (has some #{foreman-app-id4 base-foreman-app-id})))

          (fact "Unknown foreman app id"
            (query apikey :reduced-foreman-history :id "foobar") => fail?))

          (fact "Should be queriable only with a foreman application"
            (let [resp (query apikey :foreman-history :id application-id) => fail?]
              (:text resp) => "error.not-foreman-app"))

        (facts "unreduced"
          (fact "unreduced history should not reduce history"
            (let [unreduced-history (query apikey :foreman-history :id base-foreman-app-id) => ok?
                  history-ids       (map :foremanAppId (:projects unreduced-history))]
              history-ids => (just [foreman-app-id1 foreman-app-id2 foreman-app-id3 foreman-app-id4 foreman-app-id5] :in-any-order)))

          (fact "Unknown foreman app id"
            (query apikey :foreman-history :id "foobar") => fail?)

          (fact "Should be queriable only with a foreman application"
            (let [resp (query apikey :reduced-foreman-history :id application-id) => fail?]
              (:text resp) => "error.not-foreman-app")))))))
