(ns lupapalvelu.pdf.html-template-itest
  (:require [midje.sweet :refer :all]
            [clojure.java.io :as io]
            [schema.core :as sc]
            [pdfboxing.text :as pdfbox]
            [sade.core :refer [now]]
            [sade.files :as files]
            [sade.schemas :as ssc]
            [sade.util :as util]
            [lupapalvelu.itest-util :refer :all]
            [lupapalvelu.test-util :refer [replace-in-schema]]
            [lupapalvelu.fixture.core :as fixture]
            [lupapalvelu.document.schemas :as schemas]
            [lupapalvelu.inspection-summary :refer [InspectionSummary]]
            [lupapalvelu.mongo :as mongo]
            [lupapalvelu.pdf.html-template :refer :all]))

(def local-db-name (str "test_pdf_html_template_" (now)))

(mongo/with-db local-db-name
  (fixture/apply-fixture "minimal"))

(mongo/with-db local-db-name
  (facts "inspection summary pdf generation at muuntaja"
    (let [app  {:primaryOperation     {:id "..op-id..", :name "pientalo-laaj", :description nil}
                :municipality         "300"
                :state                "verdictGiven"
                :documents            [{:id "..doc-id..",
                                        :schema-info {:op {:id "..op-id..", :name "pientalo-laaj", :description nil},
                                                      :name "rakennuksen-laajentaminen",
                                                      :accordion-fields schemas/buildingid-accordion-paths
                                                      :version 1},
                                        :data {:valtakunnallinenNumero {:value "bld_123456" }}}]
                :attachments          [{:id "..attachment-id..",
                                        :groupType "operation",
                                        :type {:type-id :tarkastusasiakirja, :type-group :katselmukset_ja_tarkastukset},
                                        :op [{:id "..op-id..", :name "pientalo-laaj"}],
                                        :contents nil,
                                        :target {:id "..target-id-3..", :type "inspection-summary-item"},
                                        :versions [{:filename "liite.pdf",
                                                    :originalFileId "..file-id..",
                                                    :contentType "application/pdf",
                                                    :fileId "..file-id.." }],
                                        :latestVersion  {:filename "liite.pdf",
                                                         :originalFileId "..file-id..",
                                                         :contentType "application/pdf",
                                                         :fileId "..file-id.."}}]
                :inspection-summaries [{:name "Some inspection summary"
                                        :id "..summary-id..",
                                        :op {:id "..op-id.."
                                             :name "pientalo-laaj"
                                             :description "" },
                                        :targets [{:finished false,
                                                   :id "..target-id-1..",
                                                   :target-name "First inspection target"},
                                                  {:finished true,
                                                   :id "..target-id-2..",
                                                   :target-name "Second target",
                                                   :finished-date 1488528634011,
                                                   :finished-by {:id "..user-id.."
                                                                 :username  "pena"
                                                                 :firstName "Pena"
                                                                 :lastName "Banaani"
                                                                 :role "authority"}},
                                                  {:finished false,
                                                   :id "..target-id-3..",
                                                   :target-name "Last target"}]}]}
          file-id (mongo/create-id)]

      (fact "inpection summary test data matches schema"
        (sc/check [(replace-in-schema InspectionSummary ssc/ObjectIdStr sc/Str)] (:inspection-sumamries app)) => nil)

      (fact "pdf creation succeeded"
        (create-inspection-summary-pdf app "en" "..summary-id.." :file-id file-id) => ok?)

      (facts "pdf contents"
        (files/with-temp-file file
          (io/copy ((:content (mongo/download  file-id))) file)

          (let [contents (pdfbox/extract file)]

            (fact "no missing translations"
              (re-find #"\?\?\?" contents) => nil)

            (fact "municipality"
              (or (re-find #"Kuortane" contents) contents) => "Kuortane")

            (fact "inspection summary name"
              (or (re-find #"Some inspection summary" contents) contents) => "Some inspection summary")

            (fact "building id"
              (or (re-find #"bld_123456" contents) contents) => "bld_123456")

            (fact "target name header"
              (or (re-find #"Inspection target" contents) contents) => "Inspection target")

            (fact "target name value"
              (or (re-find #"First inspection target" contents) contents) => "First inspection target")

            (fact "attachment file name"
              (or (re-find #"liite.pdf" contents) contents) => "liite.pdf")))))))
