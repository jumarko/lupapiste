(ns lupapalvelu.application-test
  (:require [midje.sweet :refer :all]
            [midje.util :refer [testable-privates]]
            [monger.operators :refer [$set $push]]
            [swiss.arrows :refer :all]
            [sade.core :refer [now]]
            [lupapalvelu.test-util :refer :all]
            [lupapalvelu.action :refer [update-application]]
            [lupapalvelu.application :refer :all]
            [lupapalvelu.permit :as permit]
            [lupapalvelu.operations :as operations]
            [lupapalvelu.domain :as domain]
            [lupapalvelu.mongo :as mongo]
            [lupapalvelu.application-api]
            [lupapalvelu.document.schemas :as schemas]
            [lupapalvelu.organization :as org]))


(fact "update-document"
  (update-application {:application ..application.. :data {:id ..id..}} ..changes..) => nil
  (provided
    ..application.. =contains=> {:id ..id..}
    (mongo/update-by-query :applications {:_id ..id..} ..changes..) => 1))

(testable-privates lupapalvelu.application-api add-operation-allowed?)
(testable-privates lupapalvelu.application required-link-permits)

(facts "mark-indicators-seen-updates"
  (let [timestamp 123
        expected-seen-bys (-<>> ["comments" "statements" "verdicts"
                                 "authority-notices" "info-links"]
                               (map (partial format "_%s-seen-by.pena"))
                               (zipmap <> (repeat timestamp)))
        expected-attachment (assoc expected-seen-bys :_attachment_indicator_reset timestamp)
        expected-docs (assoc expected-attachment "documents.0.meta._indicator_reset.timestamp" timestamp)]
    (mark-indicators-seen-updates {} {:id "pena"} timestamp) => expected-seen-bys
    (mark-indicators-seen-updates {:documents []} {:id "pena", :role "authority"} timestamp) => expected-attachment
    (mark-indicators-seen-updates {:documents [{}]} {:id "pena", :role "authority"} timestamp) => expected-docs))

(defn find-by-schema? [docs schema-name]
  (domain/get-document-by-name {:documents docs} schema-name))

(defn has-schema? [schema] (fn [docs] (find-by-schema? docs schema)))

(facts filter-repeating-party-docs
  (filter-party-docs 1 ["a" "b" "c"] true) => (just "a")
  (provided
    (schemas/get-schema 1 "a") => {:info {:type :party :repeating true}}
    (schemas/get-schema 1 "b") => {:info {:type :party :repeating false}}
    (schemas/get-schema 1 "c") => {:info {:type :foo :repeating true}}))

(facts required-link-permits
  (fact "Muutoslupa"
    (required-link-permits {:permitSubtype "muutoslupa"}) => 1)
  (fact "Aloitusilmoitus"
    (required-link-permits {:primaryOperation {:name "aloitusoikeus"}}) => 1)
  (fact "Poikkeamis"
    (required-link-permits {:primaryOperation {:name "poikkeamis"}}) => 0)
  (fact "ya-jatkoaika, primary"
    (required-link-permits {:primaryOperation {:name "ya-jatkoaika"}}) => 1)
  (fact "ya-jatkoaika, secondary"
    (required-link-permits {:secondaryOperations [{:name "ya-jatkoaika"}]}) => 1)
  (fact "ya-jatkoaika x 2"
    (required-link-permits {:secondaryOperations [{:name "ya-jatkoaika"} {:name "ya-jatkoaika"}]}) => 2)
  (fact "muutoslupa+ya-jatkoaika"
    (required-link-permits {:permitSubtype "muutoslupa" :secondaryOperations [{:name "ya-jatkoaika"}]}) => 2)
  (fact "muutoslupa+aloitusilmoitus+ya-jatkoaika"
    (required-link-permits {:permitSubtype "muutoslupa" :primaryOperation {:name "aloitusoikeus"} :secondaryOperations [{:name "ya-jatkoaika"}]}) => 3))

(facts "Add operation allowed"
       (let [not-allowed-for #{;; R-operations, adding not allowed
                               :raktyo-aloit-loppuunsaat :jatkoaika :aloitusoikeus :suunnittelijan-nimeaminen
                               :tyonjohtajan-nimeaminen :tyonjohtajan-nimeaminen-v2 :aiemmalla-luvalla-hakeminen
                               ;; KT-operations, adding not allowed
                               :rajankaynti
                               ;; YL-operations, adding not allowed
                               :pima
                               ;; YM-operations, adding not allowed
                               :muistomerkin-rauhoittaminen :jatteen-keraystoiminta :lannan-varastointi
                               :kaytostapoistetun-oljy-tai-kemikaalisailion-jattaminen-maaperaan
                               :koeluontoinen-toiminta :maa-ainesten-kotitarveotto :ilmoitus-poikkeuksellisesta-tilanteesta}
        error {:ok false :text "error.add-operation-not-allowed"}]

    (doseq [operation lupapalvelu.operations/operations]
      (let [[op {permit-type :permit-type}] operation
            application {:primaryOperation {:name (name op)} :permitSubtype nil}
            operation-allowed (add-operation-allowed? {:application application})]
        (fact {:midje/description (name op)}
          (if (or (not (contains? #{"R" "KT" "P" "YM"} permit-type)) (not-allowed-for op))
            (fact "Add operation not allowed" operation-allowed => error)
            (fact "Add operation allowed" operation-allowed => nil?)))))

    (fact "Add operation not allowed for :muutoslupa"
      (add-operation-allowed? {:application {:primaryOperation {:name "kerrostalo-rivitalo"} :permitSubtype :muutoslupa}}) => error)))

(fact "validate-has-subtypes"
  (validate-has-subtypes {:application {:permitType "P"}}) => nil
  (validate-has-subtypes {:application {:primaryOperation {:name "tyonjohtajan-nimeaminen-v2"}}}) => nil
  (validate-has-subtypes {:application {:permitType "R"}}) => {:ok false :text "error.permit-has-no-subtypes"}
  (validate-has-subtypes nil) => {:ok false :text "error.permit-has-no-subtypes"})

(facts "State transitions"
  (let [pena {:username "pena", :firstName "Pena" :lastName "Panaani"}]
    (fact "update"
      (state-transition-update :open 1 {:created 0 :permitType "R" :organization "123-R"} pena) => {$set {:state :open, :opened 1, :modified 1}, $push {:history {:state :open, :ts 1, :user pena}}}
      (state-transition-update :open 1 {:opened nil :permitType "R" :organization "123-R"} pena) => {$set {:state :open, :opened 1, :modified 1}, $push {:history {:state :open, :ts 1, :user pena}}}
      (state-transition-update :submitted 2 {:created 0 :opened 1 :permitType "R" :organization "123-R"} pena) => {$set {:state :submitted, :submitted 2, :modified 2}, $push {:history {:state :submitted, :ts 2, :user pena}}}
      (state-transition-update :verdictGiven 3 {:created 0 :opened 1 :submitted 2 :permitType "R" :organization "123-R"} pena) => {$set {:state :verdictGiven, :modified 3}, $push {:history {:state :verdictGiven, :ts 3, :user pena}}})
    (fact "re-update"
      (state-transition-update :open 4 {:opened 3 :permitType "R" :organization "123-R"} pena) => {$set {:state :open, :modified 4}, $push {:history {:state :open, :ts 4, :user pena}}}
      (state-transition-update :submitted 5 {:submitted 4 :permitType "R" :organization "123-R"} pena) => {$set {:state :submitted, :modified 5}, $push {:history {:state :submitted, :ts 5, :user pena}}}
      (state-transition-update :constructionStarted 6 {:started 5 :permitType "R" :organization "123-R"} pena) => {$set {:state :constructionStarted, :modified 6}, $push {:history {:state :constructionStarted, :ts 6, :user pena}}}))
  (against-background
    (org/get-organization "123-R") => "123-R"
    (org/krysp-integration? "123-R" "R") => false))

(facts "State transition wih warranty"
  (let [pena {:username "pena", :firstName "Pena" :lastName "Panaani"}]
    (fact "should set warranty period"
      (state-transition-update :closed 1 {:created 0 :permitType "YA" :organization "123-YA"} pena) =>  {$set {:state :closed, :closed 1, :modified 1, :warrantyStart 1, :warrantyEnd 63072000001},
                                                                                                         $push {:history {:state :closed, :ts 1, :user pena}}})
    (fact "should not set warranty period when krysp in use"
      (state-transition-update :closed 1 {:created 0 :permitType "YA" :organization "456-YA"} pena) =>  {$set {:state :closed, :closed 1, :modified 1},
                                                                                                             $push {:history {:state :closed, :ts 1, :user pena}}}))
  (against-background
    (org/get-organization "123-YA") => "123-YA"
    (org/krysp-integration? "123-YA" "YA") => false
    (org/get-organization "456-YA") => "456-YA"
    (org/krysp-integration? "456-YA" "YA") => true))

(fact "Valid permit types pre-checker"
      (let [error {:ok false :text "error.unsupported-permit-type"}
            m1 {:R [] :P :all}
            m2 {:R ["tyonjohtaja-hakemus"] :P :all}
            m3 {:R ["tyonjohtaja-hakemus" :empty]}]
        (permit/valid-permit-types m1 {:application {:permitType "R"}}) => nil
        (permit/valid-permit-types m1 {:application {:permitType "P"}}) => nil
        (permit/valid-permit-types m1 {:application {:permitType "R" :permitSubtype "tyonjohtaja-hakemus"}}) => error
        (permit/valid-permit-types m1 {:application {:permitType "P" :permitSubtype "foo"}}) => nil
        (permit/valid-permit-types m2 {:application {:permitType "R"}}) => error
        (permit/valid-permit-types m2 {:application {:permitType "P"}}) => nil
        (permit/valid-permit-types m2 {:application {:permitType "R" :permitSubtype "tyonjohtaja-hakemus"}}) => nil
        (permit/valid-permit-types m2 {:application {:permitType "R" :permitSubtype "foobar"}}) => error
        (permit/valid-permit-types m2 {:application {:permitType "P" :permitSubtype "foo"}}) => nil
        (permit/valid-permit-types m3 {:application {:permitType "R"}}) => nil
        (permit/valid-permit-types m3 {:application {:permitType "P"}}) => error
        (permit/valid-permit-types m3 {:application {:permitType "R" :permitSubtype "tyonjohtaja-hakemus"}}) => nil
        (permit/valid-permit-types m3 {:application {:permitType "R" :permitSubtype "foobar"}}) => error
        (permit/valid-permit-types m3 {:application {:permitType "P" :permitSubtype "foo"}}) => error))

(facts "Previous app state"
  (let [user {:username "pena"}
        now (now)
        state-seq [:one :two :three :four :five]]

    (dotimes [i (count state-seq)]
      (let [prev-state (get-previous-app-state
                         {:history (map
                                     #(history-entry % now user)
                                     (take (+ i 1) state-seq))})]
        (if (= i 0)
          (fact "no previous state" prev-state => nil)
          (fact {:midje/description prev-state}
            prev-state => (nth state-seq (- i 1))))))

    (fact "no previous state if no history"
      (get-previous-app-state nil) => nil
      (get-previous-app-state []) => nil)))

(facts "Get previous state (history)"
  (let [state-seq [:one nil :two :three nil nil]
        now (now)
        history {:history
                 (map-indexed
                   (fn [i state] (history-entry state (+ now i) {:username "Pena"}))
                   state-seq)}]
    (fact "only entries with :state are regarded"
      (get-previous-app-state history) => :two)))

(facts "Primary operation prechecks"
       (let [app {:application {:primaryOperation {:name "foobar"}}}
             err {:ok false :text "error.unsupported-primary-operation"}]
         (fact "Allow"
               ((allow-primary-operations #{:hii :foobar}) app) => nil?
               ((allow-primary-operations #{:foobar}) app) => nil?
               ((allow-primary-operations #{:hii}) app) => err
               ((allow-primary-operations #{}) app) => err)
         (fact "Reject"
               ((reject-primary-operations #{:hii :foobar}) app) => err
               ((reject-primary-operations #{:foobar}) app) => err
               ((reject-primary-operations #{:hii}) app) => nil?
               ((reject-primary-operations #{}) app) => nil?)))
