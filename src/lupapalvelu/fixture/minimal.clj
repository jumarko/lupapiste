(ns lupapalvelu.fixture.minimal
  (:use lupapalvelu.fixture)
  (:require [lupapalvelu.mongo :as mongo]))

(def users
  [
   {:id "777777777777777777000016" ;; Veikko Viranomainen - tamperelainen Lupa-arkkitehti
    :email "veikko.viranomainen@tampere.fi"
    :role :authority
    :authority :tampere
    :personId "kunta 122"
    :firstName "Veikko"
    :lastName "Viranomainen"
    :phone "03121991"
    :username "veikko"
    :private {:password "$2a$10$s4OOPduvZeH5yQzsCFSKIuLF5AQqkSO5S1DJOgziMep.xJLYm3.xG"
              :salt "$2a$10$s4OOPduvZeH5yQzsCFSKIu"
              :apikey "5051ba0caa2480f374dcfeff"}}
   {:id "777777777777777777000023" ;; Sonja Sibbo - Sipoon lupa-arkkitehti
    :email "sonja.sibbo@sipoo.fi"
    :role :authority
    :authority :sipoo
    :personId "kunta123"
    :firstName "Sonja"
    :lastName "Sibbo"
    :phone "03121991"
    :username "sonja"
    :private {:password "$2a$10$s4OOPduvZeH5yQzsCFSKIuVKiwbKvNs90f80zc57FDiPnGjuMbuf2"
              :salt "$2a$10$s4OOPduvZeH5yQzsCFSKIu"
              :apikey "5056e6d3aa24a1c901e6b9dd"}},
   {:id "505718b0aa24a1c901e6ba24" ;; Admin
    :firstName "Judge"
    :lastName "Dread"
    :role :admin
    :private {:apikey "505718b0aa24a1c901e6ba24"}},
   {:lastName "Nieminen", ;; Mikkos neighbour
    :firstName "Teppo",
    :postalCode "33200",
    :city "Tampere",
    :username "teppo@example.com",
    :private {:salt "$2a$10$KKBZSYTFTEFlRrQPa.PYPe",
              :password "$2a$10$KKBZSYTFTEFlRrQPa.PYPe9wz4q1sRvjgEUCG7gt8YBXoYwCihIgG"},
    :street "Mutakatu 7",
    :phone "0505503171",
    :email "teppo@example.com",
    :personId "210281-0001",
    :role "applicant",
    :id "5073c0a1c2e6c470aef589a5",
    :streetAddress "Mutakatu 7",
    :postalPlace "Tampere"}
   {:id "777777777777777777000010", ;; Mikko Intonen
    :username "mikko@example.com",
    :role "applicant",
    :personId "210281-0002",
    :firstName "Mikko",
    :lastName "Intonen",
    :email "mikko@example.com",
    :streetAddress "Rambokuja 6"
    :postalCode "55550",
    :postalPlace "sipoo"
    :phone "0505503171"
    :private {:password "$2a$10$zwb/nvYQu4b1oZGpxz8.QOqHEBx3vXw9brc3NqDexgMbDuU2pwL9q"
              :salt "$2a$10$zwb/nvYQu4b1oZGpxz8.QO"
              :apikey "502cb9e58426c613c8b85abc"}}
   ])

(deffixture "minimal" {}
  (mongo/clear!)
  (dorun (map #(mongo/insert mongo/users %) users)))
