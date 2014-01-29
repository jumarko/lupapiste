(ns lupapalvelu.document.rakennuslupa_canonical
  (:require [clojure.java.io :as io]
            [clojure.xml :as xml]
            [clojure.string :as s]
            [lupapalvelu.core :refer [now]]
            [sade.strings :refer :all]
            [sade.common-reader :as cr]
            [sade.util :refer :all]
            [lupapalvelu.i18n :refer [with-lang loc]]
            [lupapalvelu.document.canonical-common :refer :all]
            [lupapalvelu.document.tools :as tools]
            [lupapalvelu.document.schemas :as schemas]))

;; Macro to get values from
(defmacro value [m & path] `(-> ~m ~@path :value))

(defn- get-huoneisto-data [huoneistot]
  (for [huoneisto (vals huoneistot)
        :let [tyyppi (:huoneistonTyyppi huoneisto)
              varusteet (:varusteet huoneisto)
              huoneistonumero (-> huoneisto :huoneistoTunnus :huoneistonumero :value)
              huoneistoPorras (-> huoneisto :huoneistoTunnus :porras :value)
              jakokirjain (-> huoneisto :huoneistoTunnus :jakokirjain :value)]
        :when (seq huoneisto)]
    (merge {:muutostapa (-> huoneisto :muutostapa :value)
            :huoneluku (-> tyyppi :huoneluku :value)
            :keittionTyyppi (-> huoneisto :keittionTyyppi :value)
            :huoneistoala (-> tyyppi :huoneistoala :value)
            :varusteet {:WCKytkin (true? (-> varusteet :WCKytkin :value))
                        :ammeTaiSuihkuKytkin (true? (-> varusteet :ammeTaiSuihkuKytkin :value))
                        :saunaKytkin (true? (-> varusteet :saunaKytkin :value))
                        :parvekeTaiTerassiKytkin (true? (-> varusteet  :parvekeTaiTerassiKytkin :value))
                        :lamminvesiKytkin (true? (-> varusteet :lamminvesiKytkin :value))}
            :huoneistonTyyppi (-> tyyppi :huoneistoTyyppi :value)}
           (when (numeric? huoneistonumero)
             {:huoneistotunnus
              (merge {:huoneistonumero (format "%03d" (read-string (remove-leading-zeros huoneistonumero)))}
                     (when (not-empty huoneistoPorras) {:porras (clojure.string/upper-case huoneistoPorras)})
                     (when (not-empty jakokirjain) {:jakokirjain (lower-case jakokirjain)}))}))))

(defn- get-rakennuksen-omistaja [omistaja]
  {:Omistaja (merge (get-osapuoli-data omistaja :rakennuksenomistaja))})

(defn- get-rakennus [toimenpide application {id :id created :created}]
  (let [{kuvaus   :toimenpiteenKuvaus
         kaytto   :kaytto
         mitat    :mitat
         rakenne  :rakenne
         lammitys :lammitys
         luokitus :luokitus
         huoneistot :huoneistot} toimenpide
        kantava-rakennus-aine-map (muu-select-map :muuRakennusaine (-> rakenne :muuRakennusaine :value)
                                                  :rakennusaine (-> rakenne :kantavaRakennusaine :value))
        lammonlahde-map (muu-select-map
                          :muu (-> lammitys :muu-lammonlahde :value)
                          :polttoaine (if (= "kiviihiili koksi tms" (-> lammitys :lammonlahde :value))
                                          (str (-> lammitys :lammonlahde :value) ".")
                                          (-> lammitys :lammonlahde :value)))
        julkisivu-map (muu-select-map :muuMateriaali (-> rakenne :muuMateriaali :value)
                                      :julkisivumateriaali (-> rakenne :julkisivu :value))
        lammitystapa (-> lammitys :lammitystapa :value)
        huoneistot {:huoneisto (get-huoneisto-data huoneistot)}]
    {:yksilointitieto id
     :alkuHetki (to-xml-datetime  created)
     :sijaintitieto {:Sijainti {:tyhja empty-tag}}
     :rakentajatyyppi (-> kaytto :rakentajaTyyppi :value)
     :omistajatieto (for [m (vals (:rakennuksenOmistajat toimenpide))] (get-rakennuksen-omistaja m))
     :rakennuksenTiedot (merge {:kayttotarkoitus (-> kaytto :kayttotarkoitus :value)
                                :tilavuus (-> mitat :tilavuus :value)
                                :kokonaisala (-> mitat :kokonaisala :value)
                                :kellarinpinta-ala (-> mitat :kellarinpinta-ala :value)
                                ;:BIM empty-tag
                                :kerrosluku (-> mitat :kerrosluku :value)
                                :kerrosala (-> mitat :kerrosala :value)
                                :rakentamistapa (-> rakenne :rakentamistapa :value)
                                :verkostoliittymat {:sahkoKytkin (true? (-> toimenpide :verkostoliittymat :sahkoKytkin :value))
                                                    :maakaasuKytkin (true? (-> toimenpide :verkostoliittymat :maakaasuKytkin :value))
                                                    :viemariKytkin (true? (-> toimenpide :verkostoliittymat :viemariKytkin :value))
                                                    :vesijohtoKytkin (true? (-> toimenpide :verkostoliittymat :vesijohtoKytkin :value))
                                                    :kaapeliKytkin (true? (-> toimenpide :verkostoliittymat :kaapeliKytkin :value))}
                                :energialuokka (-> luokitus :energialuokka :value)
                                :energiatehokkuusluku (-> luokitus :energiatehokkuusluku :value)
                                :energiatehokkuusluvunYksikko (-> luokitus :energiatehokkuusluvunYksikko :value)
                                :paloluokka (-> luokitus :paloluokka :value)
                                :lammitystapa (cond (= lammitystapa "suorasahk\u00f6") "suora s\u00e4hk\u00f6"
                                                    (= lammitystapa "eiLammitysta") "ei l\u00e4mmityst\u00e4"
                                                :default lammitystapa)
                                :varusteet {:sahkoKytkin (true? (-> toimenpide :varusteet :sahkoKytkin :value))
                                            :kaasuKytkin (true? (-> toimenpide :varusteet :kaasuKytkin :value))
                                            :viemariKytkin (true? (-> toimenpide :varusteet :sahkoKytkin :value))
                                            :vesijohtoKytkin (true? (-> toimenpide :varusteet :vesijohtoKytkin :value))
                                            :lamminvesiKytkin (true? (-> toimenpide :varusteet :lamminvesiKytkin :value))
                                            :aurinkopaneeliKytkin (true? (-> toimenpide :varusteet :aurinkopaneeliKytkin :value))
                                            :hissiKytkin (true? (-> toimenpide :varusteet :hissiKytkin :value))
                                            :koneellinenilmastointiKytkin (true? (-> toimenpide :varusteet :koneellinenilmastointiKytkin :value))
                                            :saunoja (-> toimenpide :varusteet :saunoja :value)
                                            :vaestonsuoja (-> toimenpide :varusteet :vaestonsuoja :value)}}
                               (cond (-> toimenpide :manuaalinen_rakennusnro :value)
                                 {:rakennustunnus {:rakennusnro (-> toimenpide :manuaalinen_rakennusnro :value)
                                                     :jarjestysnumero nil
                                                    :kiinttun (:propertyId application)}}
                                     (-> toimenpide :rakennusnro :value)
                                       {:rakennustunnus {:rakennusnro (-> toimenpide :rakennusnro :value)
                                                     :jarjestysnumero nil
                                                     :kiinttun (:propertyId application)}}
                                     :default
                                       {:rakennustunnus {:jarjestysnumero nil
                                                         :kiinttun (:propertyId application)}})
                               (when kantava-rakennus-aine-map {:kantavaRakennusaine kantava-rakennus-aine-map})
                               (when lammonlahde-map {:lammonlahde lammonlahde-map})
                               (when julkisivu-map {:julkisivu julkisivu-map})
                               (when huoneistot (if (not-empty (:huoneisto huoneistot))
                                                  {:asuinhuoneistot huoneistot})))}))

(defn- get-rakennus-data [toimenpide application doc]
  {:Rakennus (get-rakennus toimenpide application doc)})

(defn- get-toimenpiteen-kuvaus [doc]
  ;Uses fi as default since krysp uses finnish in enumeration values
  {:kuvaus (with-lang "fi" (loc (str "operations." (-> doc :schema-info :op :name))))})

(defn get-uusi-toimenpide [doc application]
  (let [toimenpide (:data doc)]
    {:Toimenpide {:uusi (get-toimenpiteen-kuvaus doc)
                  :rakennustieto (get-rakennus-data toimenpide application doc)}
     :created (:created doc)}))

(defn fix-typo-in-kayttotarkotuksen-muutos [v]
  (if (= v lupapalvelu.document.schemas/kayttotarkotuksen-muutos)
    "rakennuksen p\u00e4\u00e4asiallinen k\u00e4ytt\u00f6tarkoitusmuutos"
    v))

(defn- get-rakennuksen-muuttaminen-toimenpide [rakennuksen-muuttaminen-doc application]
  (let [toimenpide (:data rakennuksen-muuttaminen-doc)]
    {:Toimenpide {:muuMuutosTyo (conj (get-toimenpiteen-kuvaus rakennuksen-muuttaminen-doc)
                                      {:perusparannusKytkin (true? (-> rakennuksen-muuttaminen-doc :data :perusparannuskytkin :value))}
                                      {:muutostyonLaji (fix-typo-in-kayttotarkotuksen-muutos (-> rakennuksen-muuttaminen-doc :data :muutostyolaji :value))})
                  :rakennustieto (get-rakennus-data toimenpide application rakennuksen-muuttaminen-doc)}
     :created (:created rakennuksen-muuttaminen-doc)}))

(defn- get-rakennuksen-laajentaminen-toimenpide [laajentaminen-doc application]
  (let [toimenpide (:data laajentaminen-doc)
        mitat (-> toimenpide :laajennuksen-tiedot :mitat )]
    {:Toimenpide {:laajennus (conj (get-toimenpiteen-kuvaus laajentaminen-doc)
                                   {:perusparannusKytkin (true? (-> laajentaminen-doc :data :laajennuksen-tiedot :perusparannuskytkin :value))}
                                   {:laajennuksentiedot {:tilavuus (-> mitat :tilavuus :value)
                                                         :kerrosala (-> mitat :kerrosala :value)
                                                         :kokonaisala (-> mitat :kokonaisala :value)
                                                         :huoneistoala (for [huoneistoala (vals (:huoneistoala mitat))]
                                                                         {:pintaAla (-> huoneistoala :pintaAla :value)
                                                                          :kayttotarkoitusKoodi (-> huoneistoala :kayttotarkoitusKoodi :value)})}})
                  :rakennustieto (get-rakennus-data toimenpide application laajentaminen-doc)}
     :created (:created laajentaminen-doc)}))

(defn- get-purku-toimenpide [purku-doc application]
  (let [toimenpide (:data purku-doc)]
    {:Toimenpide {:purkaminen (conj (get-toimenpiteen-kuvaus purku-doc)
                                   {:purkamisenSyy (-> toimenpide :poistumanSyy :value)}
                                   {:poistumaPvm (to-xml-date-from-string (-> toimenpide :poistumanAjankohta :value))})
                  :rakennustieto (get-rakennus-data toimenpide application purku-doc)}
     :created (:created purku-doc)}))

(defn get-kaupunkikuvatoimenpide [kaupunkikuvatoimenpide-doc application]
  (let [toimenpide (:data kaupunkikuvatoimenpide-doc)]
    {:Toimenpide {:kaupunkikuvaToimenpide (get-toimenpiteen-kuvaus kaupunkikuvatoimenpide-doc)
                  :rakennelmatieto {:Rakennelma {:yksilointitieto (:id kaupunkikuvatoimenpide-doc)
                                                 :alkuHetki (to-xml-datetime (:created kaupunkikuvatoimenpide-doc))
                                                 :sijaintitieto {:Sijainti {:tyhja empty-tag}}
                                                 :kokonaisala (-> toimenpide :kokonaisala :value)
                                                 :kuvaus {:kuvaus (-> toimenpide :kuvaus :value)}
                                                 :tunnus {:jarjestysnumero nil}
                                                 :kiinttun (:propertyId application)}}}
     :created (:created kaupunkikuvatoimenpide-doc)}))


(defn- get-toimenpide-with-count [toimenpide n]
  (clojure.walk/postwalk #(if (and (map? %) (contains? % :jarjestysnumero))
                            (assoc % :jarjestysnumero n)
                            %) toimenpide))



(defn- get-operations [documents application]
  ;funkito
  (let [toimenpiteet (filter not-empty (concat (map #(get-uusi-toimenpide % application) (:uusiRakennus documents))
                                               (map #(get-rakennuksen-muuttaminen-toimenpide % application) (:rakennuksen-muuttaminen documents))
                                               (map #(get-rakennuksen-laajentaminen-toimenpide % application) (:rakennuksen-laajentaminen documents))
                                               (map #(get-purku-toimenpide % application) (:purku documents))
                                               (map #(get-kaupunkikuvatoimenpide % application) (:kaupunkikuvatoimenpide documents))))
        toimenpiteet (map get-toimenpide-with-count toimenpiteet (range 1 9999))]
    (not-empty (sort-by :created toimenpiteet))))



(defn- get-lisatiedot [documents lang]
  (let [lisatiedot (:data (first (:lisatiedot documents)))
        aloitusoikeus (:data (first (:aloitusoikeus documents)))]
    {:Lisatiedot {:suoramarkkinointikieltoKytkin (true? (-> lisatiedot :suoramarkkinointikielto :value))
                  :asioimiskieli (if (= lang "sv")
                                   "ruotsi"
                                   "suomi")}}))

(defn- get-asian-tiedot [documents]
  (let [maisematyo_documents (:maisematyo documents)
        hankkeen-kuvaus-doc (or (:hankkeen-kuvaus documents) (:hankkeen-kuvaus-minimum documents) (:aloitusoikeus documents))
        asian-tiedot (:data (first hankkeen-kuvaus-doc))
        maisematyo_kuvaukset (for [maisematyo_doc maisematyo_documents]
                               (str "\n\n" (:kuvaus (get-toimenpiteen-kuvaus maisematyo_doc))
                                 ":" (-> maisematyo_doc :data :kuvaus :value)))
        r {:Asiantiedot {:rakennusvalvontaasianKuvaus (str (-> asian-tiedot :kuvaus :value)
                                                        (apply str maisematyo_kuvaukset))}}]
    (if (:poikkeamat asian-tiedot)
      (assoc-in r [:Asiantiedot :vahainenPoikkeaminen] (or (-> asian-tiedot :poikkeamat :value) empty-tag))
      r)))

(defn- get-kayttotapaus [documents toimenpiteet]
  (if (and (contains? documents :maisematyo) (empty? toimenpiteet))
      "Uusi maisematy\u00f6hakemus"
      "Uusi hakemus"))
(defn- assoc-in-no-nil [m ks v]
  (if v
    (assoc-in m ks v)
    m))

(defn application-to-canonical
  "Transforms application mongodb-document to canonical model."
  [application lang]
  (let [link-permit-data (first (:linkPermitData application))
        documents (documents-by-type-without-blanks application)
        toimenpiteet (get-operations documents application)
        operation-name (-> application :operations first :name)
        canonical {:Rakennusvalvonta
                   {:toimituksenTiedot (toimituksen-tiedot application lang)
                    :rakennusvalvontaAsiatieto
                    {:RakennusvalvontaAsia
                     {:kasittelynTilatieto (get-state application)
                      :luvanTunnisteTiedot (lupatunnus (:id application))
                      :osapuolettieto (osapuolet documents)
                      :kayttotapaus (if (= "muutoslupa" (:permitSubtype application))
                                      "Rakentamisen aikainen muutos"
                                      (condp = operation-name
                                        "tyonjohtajan-nimeaminen" "Uuden ty\u00f6njohtajan nime\u00e4minen"
                                        "suunnittelijan-nimeaminen" "Uuden suunnittelijan nime\u00e4minen"
                                        "jatkoaika" "Jatkoaikahakemus"
                                        "aloitusoikeus" "Uusi aloitusoikeus"
                                        (get-kayttotapaus documents toimenpiteet)))
                      :asianTiedot (get-asian-tiedot documents)
                      :lisatiedot (get-lisatiedot documents lang)}}}}
        canonical (if link-permit-data
                    (assoc-in canonical [:Rakennusvalvonta :rakennusvalvontaAsiatieto :RakennusvalvontaAsia :viitelupatieto]
                      (get-viitelupatieto link-permit-data))
                    canonical)
        canonical (if-not (or (= operation-name "tyonjohtajan-nimeaminen")
                            (= operation-name "suunnittelijan-nimeaminen")
                            (= operation-name "jatkoaika"))
                    (-> canonical
                      (assoc-in [:Rakennusvalvonta :rakennusvalvontaAsiatieto :RakennusvalvontaAsia :rakennuspaikkatieto]
                        (get-bulding-places (:rakennuspaikka documents) application))
                      (assoc-in-no-nil [:Rakennusvalvonta :rakennusvalvontaAsiatieto :RakennusvalvontaAsia :toimenpidetieto]
                        toimenpiteet)
                      (assoc-in [:Rakennusvalvonta :rakennusvalvontaAsiatieto :RakennusvalvontaAsia :lausuntotieto]
                        (get-statements (:statements application))))
                    canonical)]
    canonical))

(defn katselmusnimi-to-type [nimi tyyppi]
  (if (= :tarkastus tyyppi)
    "muu tarkastus"
    (condp = nimi
    "Aloitusilmoitus" "ei tiedossa"
    "muu katselmus" "muu katselmus"
    "aloituskokous" "aloituskokous"
    "rakennuksen paikan merkitseminen" "rakennuksen paikan merkitseminen"
    "rakennuksen paikan tarkastaminen" "rakennuksen paikan tarkastaminen"
    "pohjakatselmus" "pohjakatselmus"
    "rakennekatselmus" "rakennekatselmus"
    "l\u00e4mp\u00f6-, vesi- ja ilmanvaihtolaitteiden katselmus" "l\u00e4mp\u00f6-, vesi- ja ilmanvaihtolaitteiden katselmus"
    "osittainen loppukatselmus" "osittainen loppukatselmus"
    "loppukatselmus" ; XXX tarkoituksella ei defaulttia?
    "ei tiedossa"))
  )

(defn katselmus-kayttotapaus [nimi tyyppi]
  (if (= nimi "Aloitusilmoitus")
    "Aloitusilmoitus"
    (if (= tyyppi :katselmus)
      "Uusi katselmus"
      "Uusi tarkastus")))


;; TODO: Voisiko tahan tehda YA-canonicalin tyyppisen config-systeemin? Ei tarvitsisi nain montaa parametria.
;; TODO: Yhdistele taman namespacen canonical-funktiota.
;                                                   paatokselta (refaktoroidaan building selector)
;                                                            kirjautunut kayttaja
;                                                                 lisaa skeemaan (muu-muu)
;                                                                                          lopullinen-tila
(defn katselmus-canonical [application lang task-id pitoPvm buildings user katselmuksen-nimi tyyppi osittainen pitaja lupaehtona huomautukset lasnaolijat poikkeamat]
  (let [documents (documents-by-type-without-blanks application)
        katselmus (cr/strip-nils
                    (merge
                      {:pitoPvm (if (number? pitoPvm) (to-xml-date pitoPvm) (to-xml-date-from-string pitoPvm))
                       :katselmuksenLaji (katselmusnimi-to-type katselmuksen-nimi tyyppi)
                       :vaadittuLupaehtonaKytkin (true? lupaehtona)
                       :tarkastuksenTaiKatselmuksenNimi katselmuksen-nimi
                       :osittainen osittainen
                       :lasnaolijat lasnaolijat
                       :pitaja pitaja
                       :poikkeamat poikkeamat}
                      (when task-id {:muuTunnustieto {:MuuTunnus {:tunnus task-id :sovellus "Lupapiste"}}}) ; v 2.1.3
                      (when (seq buildings)
                        {:rakennustunnus (select-keys (first buildings) [:jarjestysnumero :kiinttun :rakennusnro]) ; v2.1.2
                         :katselmuksenRakennustieto (map #(when % {:KatselmuksenRakennus (select-keys % [:jarjestysnumero :kiinttun :rakennusnro])}) buildings)}) ; v2.1.3
                      (when huomautukset {:huomautukset {:huomautus {:kuvaus huomautukset}}})))
        canonical {:Rakennusvalvonta
                   {:toimituksenTiedot (toimituksen-tiedot application lang)
                    :rakennusvalvontaAsiatieto
                    {:RakennusvalvontaAsia
                     {:kasittelynTilatieto (get-state application)
                      :luvanTunnisteTiedot (lupatunnus (:id application))
                      :osapuolettieto {:Osapuolet {:osapuolitieto {:Osapuoli {:kuntaRooliKoodi "Ilmoituksen tekij\u00e4"
                                                                              :henkilo {:nimi {:etunimi (:firstName user)
                                                                                               :sukunimi (:lastName user)}
                                                                                        :osoite {:osoitenimi {:teksti (:street user)}
                                                                                                 :postitoimipaikannimi (:city user)
                                                                                                 :postinumero (:zip user)}
                                                                                         :sahkopostiosoite (:email user)
                                                                                         :puhelin (:phone user)}}}}}
                      :katselmustieto {:Katselmus katselmus}
                      :lisatiedot (get-lisatiedot (:lisatiedot documents) lang)
                      :kayttotapaus (katselmus-kayttotapaus katselmuksen-nimi tyyppi)
                      }}}}]
    canonical))

(defn unsent-attachments-to-canonical [application lang]
  (let [documents (documents-by-type-without-blanks application)
        hakija-info (-> (filter #(= (-> % :Osapuoli :VRKrooliKoodi) "hakija") (get-parties documents))
                      first
                      (assoc-in [:Osapuoli :kuntaRooliKoodi] "ei tiedossa"))
        canonical {:Rakennusvalvonta
                   {:toimituksenTiedot (toimituksen-tiedot application lang)
                    :rakennusvalvontaAsiatieto
                    {:RakennusvalvontaAsia
                     {:kasittelynTilatieto (get-state application)
                      :luvanTunnisteTiedot (lupatunnus (:id application))
                      :osapuolettieto {:Osapuolet {:osapuolitieto hakija-info}}
                      :lisatiedot (get-lisatiedot (:lisatiedot documents) lang)
                      :kayttotapaus "Liitetiedoston lis\u00e4ys"
                      }}}}]
    canonical))
