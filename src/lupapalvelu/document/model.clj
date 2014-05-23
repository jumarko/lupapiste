(ns lupapalvelu.document.model
  (:require [taoensso.timbre :as timbre :refer [trace debug info warn error fatal]]
            [clojure.walk :refer [keywordize-keys]]
            [clojure.set :refer [union difference]]
            [clj-time.format :as timeformat]
            [lupapalvelu.mongo :as mongo]
            [lupapalvelu.document.vrk]
            [lupapalvelu.document.schemas :as schemas]
            [lupapalvelu.document.tools :as tools]
            [sade.env :as env]
            [sade.util :as util]
            [sade.strings :as ss]
            [lupapalvelu.domain :as domain]
            [lupapalvelu.document.validator :as validator]
            [lupapalvelu.document.subtype :as subtype]))

;;
;; Validation:
;;

;; if you changes these values, change it in docgen.js, too
(def default-max-len 255)
(def dd-mm-yyyy (timeformat/formatter "dd.MM.YYYY"))

(def ^:private latin1 (java.nio.charset.Charset/forName "ISO-8859-1"))

(defn- latin1-encoder
  "Creates a new ISO-8859-1 CharsetEncoder instance, which is not thread safe."
  [] (.newEncoder latin1))

;;
;; Field validation
;;

(defmulti validate-field (fn [_ elem _] (keyword (:type elem))))

(defmethod validate-field :group [_ _ v]
  (if (not (map? v)) [:err "illegal-value:not-a-map"]))

(defmethod validate-field :string [_ {:keys [max-len min-len] :as elem} v]
  (cond
    (not= (type v) String) [:err "illegal-value:not-a-string"]
    (not (.canEncode (latin1-encoder) v)) [:warn "illegal-value:not-latin1-string"]
    (> (.length v) (or max-len default-max-len)) [:err "illegal-value:too-long"]
    (< (.length v) (or min-len 0)) [:warn "illegal-value:too-short"]
    :else (subtype/subtype-validation elem v)))

(defmethod validate-field :text [_ elem v]
  (cond
    (not= (type v) String) [:err "illegal-value:not-a-string"]
    (> (.length v) (or (:max-len elem) default-max-len)) [:err "illegal-value:too-long"]
    (< (.length v) (or (:min-len elem) 0)) [:warn "illegal-value:too-short"]))

(defn- validate-hetu-date [hetu]
  (let [dateparsts (rest (re-find #"^(\d{2})(\d{2})(\d{2})([aA+-]).*" hetu))
        yy (last (butlast dateparsts))
        yyyy (str (case (last dateparsts) "+" "18" "-" "19" "20") yy)
        basic-date (str yyyy (second dateparsts) (first dateparsts))]
    (try
      (timeformat/parse (timeformat/formatters :basic-date) basic-date)
      nil
      (catch Exception e
        [:err "illegal-hetu"]))))

(defn- validate-hetu-checksum [hetu]
  (let [number   (Long/parseLong (str (subs hetu 0 6) (subs hetu 7 10)))
        n (mod number 31)
        checksum  (nth ["0" "1" "2" "3" "4" "5" "6" "7" "8" "9" "A" "B" "C" "D" "E" "F" "H" "J" "K" "L" "M" "N" "P" "R" "S" "T" "U" "V" "W" "X" "Y"] n)
        old-checksum (subs hetu 10 11)]
    (when (not= checksum old-checksum) [:err "illegal-hetu"])))

(defmethod validate-field :hetu [_ _ v]
  (cond
    (ss/blank? v) nil
    (re-matches #"^(0[1-9]|[12]\d|3[01])(0[1-9]|1[0-2])([5-9]\d\+|\d\d-|\d\dA)\d{3}[\dA-Y]$" v) (or (validate-hetu-date v) (validate-hetu-checksum v))
    :else [:err "illegal-hetu"]))

(defmethod validate-field :checkbox [_ _ v]
  (if (not= (type v) Boolean) [:err "illegal-value:not-a-boolean"]))

(defmethod validate-field :date [_ _ v]
  (try
    (or (ss/blank? v) (timeformat/parse dd-mm-yyyy v))
    nil
    (catch Exception e [:warn "illegal-value:date"])))

(defmethod validate-field :time [_ _ v]
  (when-not (ss/blank? v)
    (if-let [matches (seq (rest (re-matches util/time-pattern v)))]
      (let [h (util/->int (first matches))
            m (util/->int (second matches))]
        (when-not (and (<= 0 h 23) (<= 0 m 59)) [:warn "illegal-value:time"]))
      [:warn "illegal-value:time"])))

(defmethod validate-field :select [_ {:keys [body other-key]} v]
  (let [accepted-values (set (map :name body))
        accepted-values (if other-key (conj accepted-values "other") accepted-values)]
    (when-not (or (ss/blank? v) (contains? accepted-values v))
      [:warn "illegal-value:select"])))

;; FIXME https://support.solita.fi/browse/LUPA-1453
;; implement validator, the same as :select?
(defmethod validate-field :radioGroup [_ elem v] nil)

(defmethod validate-field :buildingSelector [_ elem v] (subtype/subtype-validation {:subtype :rakennusnumero} v))
(defmethod validate-field :newBuildingSelector [_ elem v] (subtype/subtype-validation {:subtype :number} v))

;;
;; TODO: Improve validation functionality so that it could take application as a parameter.
;;
(defmethod validate-field :personSelector [application elem v]
  (when-not (and (not (ss/blank? v)) (domain/invite-accepted-by-user application v))
    [:err "application-does-not-have-given-auth"]))

(defmethod validate-field nil [_ _ _]
  [:err "illegal-key"])

(defmethod validate-field :default [_ elem _]
  (warn "Unknown schema type: elem=[%s]" elem)
  [:err "unknown-type"])

;;
;; Neue api:
;;

(defn find-by-name [schema-body [k & ks]]
  (when-let [elem (some #(when (= (:name %) (name k)) %) schema-body)]
    (if (nil? ks)
      elem
      (if (:repeating elem)
        (when (ss/numeric? (name (first ks)))
          (if (seq (rest ks))
            (find-by-name (:body elem) (rest ks))
            elem))
        (find-by-name (:body elem) ks)))))

(defn- ->validation-result [path element result]
  (when result
    {:path    (vec (map keyword path))
     :element element
     :result  result}))

(defn- validate-fields [application schema-body k data path]
  (let [current-path (if k (conj path (name k)) path)]
    (if (contains? data :value)
      (let [element (keywordize-keys (find-by-name schema-body current-path))
            result  (validate-field application element (:value data))]
        (->validation-result current-path element result))
      (filter
        (comp not nil?)
        (map (fn [[k2 v2]]
               (validate-fields application schema-body k2 v2 current-path)) data)))))

(defn- sub-schema-by-name [sub-schemas name]
  (some (fn [schema] (when (= (:name schema) name) schema)) sub-schemas))

(defn- one-of-many-options [sub-schemas]
  (map :name (:body (sub-schema-by-name sub-schemas schemas/select-one-of-key))))

(defn- one-of-many-selection [sub-schemas path data]
  (when-let [one-of (seq (one-of-many-options sub-schemas))]
    (or (get-in data (conj path :_selected :value)) (first one-of))))

(defn- validate-required-fields [schema-body path data validation-errors]
  (let [check (fn [{:keys [name required body repeating] :as element}]
                (let [kw (keyword name)
                      current-path (conj path kw)
                      validation-error (when (and required (ss/blank? (get-in data (conj current-path :value))))
                                         (->validation-result current-path element [:tip "illegal-value:required"]))
                      current-validation-errors (if validation-error (conj validation-errors validation-error) validation-errors)]
                  (concat current-validation-errors
                    (if body
                      (if repeating
                        (map (fn [k] (validate-required-fields body (conj current-path k) data [])) (keys (get-in data current-path)))
                        (validate-required-fields body current-path data []))
                      []))))

        selected (one-of-many-selection schema-body path data)
        sub-schemas-to-validate (-> (set (map :name schema-body))
                                  (difference (set (one-of-many-options schema-body)) #{schemas/select-one-of-key})
                                  (union (when selected #{selected})))]

      (map #(check (sub-schema-by-name schema-body %)) sub-schemas-to-validate)))

(defn get-document-schema [{schema-info :schema-info}]
  (schemas/get-schema schema-info))

(defn validate
  "Validates document against schema and document level rules. Returns list of validation errors.
   If schema is not given, uses schema defined in document."
  ([application document]
    (validate application document nil))
  ([application document schema]
    (let [data (:data document)
          schema (or schema (get-document-schema document))
          schema-body (:body schema)]
      (when data
        (flatten
          (concat
            (validate-fields application schema-body nil data [])
            (validate-required-fields schema-body [] data [])
            (validator/validate document)))))))

(defn valid-document?
  "Checks weather document is valid."
  [document] (empty? (validate document)))

(defn has-errors?
  [results]
  (->>
    results
    (map :result)
    (map first)
    (some (partial = :err))
    true?))

;;
;; Updates
;;

(def ^:dynamic *timestamp* nil)
(defn current-timestamp
  "Returns the current timestamp to be used in document modifications."
  [] *timestamp*)

(defmacro with-timestamp [timestamp & body]
  `(binding [*timestamp* ~timestamp]
     ~@body))

(declare apply-updates)

(defn map2updates
  "Creates model-updates from map into path."
  [path m]
  (map (fn [[p v]] [(into path p) v]) (tools/path-vals m)))

(defn apply-update
  "Updates a document returning the modified document.
   Value defaults to \"\", e.g. unsetting the value.
   To be used within with-timestamp.
   Example: (apply-update document [:mitat :koko] 12)"
  ([document path]
    (apply-update document path ""))
  ([document path value]
    (if (map? value)
      (apply-updates document (map2updates path value))
      (let [data-path (vec (flatten [:data path]))]
        (-> document
          (assoc-in (conj data-path :value) value)
          (assoc-in (conj data-path :modified) (current-timestamp)))))))

(defn apply-updates
  "Updates a document returning the modified document.
   To be used within with-timestamp.
   Example: (apply-updates document [[:mitat :koko] 12])"
  [document updates]
  (reduce (fn [document [path value]] (apply-update document path value)) document updates))

;;
;; Approvals
;;

(defn ->approved [status user]
  "Approval meta data model. To be used within with-timestamp."
  {:value status
   :user (select-keys user [:id :firstName :lastName])
   :timestamp (current-timestamp)})


(defn apply-approval
  "Merges approval meta data into a map.
   To be used within with-timestamp or with a given timestamp."
  ([document path status user]
    (assoc-in document (filter (comp not nil?) (flatten [:meta path :_approved])) (->approved status user)))
  ([document path status user timestamp]
    (with-timestamp timestamp (apply-approval document path status user))))

(defn approvable?
  ([document] (approvable? document nil nil))
  ([document path] (approvable? document nil path))
  ([document schema path]
    (if (seq path)
      (let [schema      (or schema (get-document-schema document))
            schema-body (:body schema)
            str-path    (map #(if (keyword? %) (name %) %) path)
            element     (keywordize-keys (find-by-name schema-body str-path))]
        (true? (:approvable element)))
      (true? (get-in document [:schema-info :approvable])))))

(defn modifications-since-approvals
  ([{:keys [schema-info data meta]}]
    (let [schema (and schema-info (schemas/get-schema (:version schema-info) (:name schema-info)))]
      (modifications-since-approvals (:body schema) [] data meta (get-in schema [:info :approvable]) (get-in meta [:_approved :timestamp] 0))))
  ([schema-body path data meta approvable-parent timestamp]
    (letfn [(max-timestamp [p] (max timestamp (get-in meta (concat p [:_approved :timestamp]) 0)))
            (count-mods
              [{:keys [name approvable repeating body type] :as element}]
              (let [current-path (conj path (keyword name))
                    current-approvable (or approvable-parent approvable)]
                (if (= :group type)
                  (if repeating
                    (reduce + 0 (map (fn [k] (modifications-since-approvals body (conj current-path k) data meta current-approvable (max-timestamp (conj current-path k)))) (keys (get-in data current-path))))
                    (modifications-since-approvals body current-path data meta current-approvable (max-timestamp current-path)))
                  (if (and current-approvable (> (get-in data (conj current-path :modified) 0) (max-timestamp current-path))) 1 0))))]
      (reduce + 0 (map count-mods schema-body)))))

;;
;; Create
;;

(defn new-document
  "Creates an empty document out of schema"
  [schema created]
  {:id           (mongo/create-id)
   :created      created
   :schema-info  (:info schema)
   :data         {}})

;;
;; Convert data
;;
(defn convert-document-data
  "Walks document data starting from initial-path.
   If predicate matches, value is outputted using emitter function.
   Predicate takes two parameters: element schema definition and the value map.
   Emitter takes one parameter, the value map."
  [pred emitter {data :data :as document} initial-path]
  (when data
    (letfn [(doc-walk [schema-body path]
              (into {}
                (map
                  (fn [{:keys [name type body repeating] :as element}]
                    (let [k (keyword name)
                          current-path (conj path k)
                          v (get-in data current-path)]
                      (if (pred element v)
                        [k (emitter v)]
                        (when v
                          (if (not= (keyword type) :group)
                            [k v]
                            [k (if repeating
                                 (into {} (map (fn [k2] [k2 (doc-walk body (conj current-path k2))]) (keys v)))
                                 (doc-walk body current-path))])))))
                  schema-body)))]
      (let [path (vec initial-path)
            schema (get-document-schema document)
            schema-body (:body (if (seq path) (find-by-name (:body schema) path) schema))]
        (assoc-in document (concat [:data] path) (doc-walk schema-body path))))))

(defn strip-blacklisted-data
  "Strips values from document data if blacklist in schema includes given blacklist-item."
  [document blacklist-item & [initial-path]]
  (let [bl-kw (keyword blacklist-item)
        strip-if (fn [{bl :blacklist} _] ((set (map keyword bl)) bl-kw))]
    (convert-document-data strip-if (constantly nil) document initial-path)))

(defn strip-turvakielto-data [{data :data :as document}]
  (reduce
    (fn [doc [path v]]
      (let [turvakielto-value (:value v)
            ; Strip data starting from one level up.
            ; Fragile, but currently schemas are modeled this way!
            strip-from (butlast path)]
        (if turvakielto-value
          (strip-blacklisted-data doc schemas/turvakielto strip-from)
          doc)))
    document
    (tools/deep-find data (keyword schemas/turvakielto))))

(defn mask-person-ids
  "Replaces last characters of person IDs with asterisks (e.g., 010188-123A -> 010188-****)"
  [document & [initial-path]]
  (let [mask-if (fn [{type :type} {hetu :value}] (and (= (keyword type) :hetu) hetu (> (count hetu) 7)))
        do-mask (fn [{hetu :value :as v}] (assoc v :value (str (subs hetu 0 7) "****")))]
    (convert-document-data mask-if do-mask document initial-path)))

(defn has-hetu?
  ([schema]
    (has-hetu? schema [:henkilo]))
  ([schema-body base-path]
    (let [full-path (apply conj base-path [:henkilotiedot :hetu])]
      (boolean (find-by-name schema-body full-path)))))

(defn ->henkilo [{:keys [id firstName lastName email phone street zip city personId
                         companyName companyId
                         fise degree graduatingYear]} & {:keys [with-hetu with-empty-defaults]}]
  (letfn [(wrap [v] (if (and with-empty-defaults (nil? v)) "" v))]
    (->
      {:userId                        (wrap id)
       :henkilotiedot {:etunimi       (wrap firstName)
                       :sukunimi      (wrap lastName)
                       :hetu          (wrap (when with-hetu personId))}
       :yhteystiedot {:email          (wrap email)
                      :puhelin        (wrap phone)}
       :osoite {:katu                 (wrap street)
                :postinumero          (wrap zip)
                :postitoimipaikannimi (wrap city)}
       :yritys {:yritysnimi           (wrap companyName)
                :liikeJaYhteisoTunnus (wrap companyId)}
       :patevyys {:koulutus           (wrap degree)
                  :valmistumisvuosi   (wrap graduatingYear)
                  :fise               (wrap fise)}}
      util/strip-nils
      util/strip-empty-maps
      tools/wrapped)))

