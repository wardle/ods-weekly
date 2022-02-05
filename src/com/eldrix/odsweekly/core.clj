(ns com.eldrix.odsweekly.core
  (:require [clojure.core.async :as a]
            [clojure.data.csv :as csv]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [com.eldrix.trud.core :as trud]
            [com.eldrix.trud.zip :as zf]
            [datalevin.core :as d])
  (:import (java.nio.file Path)))

(def nhs-ods-weekly-item-identifier 58)

(def n27-field-format
  "The standard ODS 27-field format headings"
  [:organisationCode
   :name
   :nationalGrouping
   :highLevelHealthGeography
   :address1
   :address2
   :address3
   :address4
   :address5
   :postcode
   :openDate
   :closeDate
   :statusCode
   :subtype
   :parent
   :joinParentDate
   :leftParentDate
   :telephone
   :nil
   :nil
   :nil
   :amendedRecord
   :nil
   :currentOrg
   :nil
   :nil
   :nil])

(def schema {:organisationCode         {:db/valueType :db.type/string
                                        :db/unique    :db.unique/identity}
             :name                     {:db/valueType :db.type/string}
             :nationalGrouping         {:db/valueType :db.type/string} ;; TODO: ?? reference type?
             :highLevelHealthGeography {:db/valueType :db.type/string} ;; TODO: ?? reference type?
             :address1                 {:db/valueType :db.type/string}
             :address2                 {:db/valueType :db.type/string}
             :address3                 {:db/valueType :db.type/string}
             :address4                 {:db/valueType :db.type/string}
             :address5                 {:db/valueType :db.type/string}
             :postcode                 {:db/valueType :db.type/string}
             :openDate                 {:db/valueType :db.type/string}
             :closeDate                {:db/valueType :db.type/string}
             :statusCode               {:db/valueType :db.type/string}
             :subtype                  {:db/valueType :db.type/string}
             :parent                   {:db/valueType :db.type/string} ;; TODO: change to reference
             :joinParentDate           {:db/valueType :db.type/string}
             :leftParentDate           {:db/valueType :db.type/string}
             :telephone                {:db/valueType :db.type/string}
             :amendedRecord            {:db/valueType :db.type/string}
             :currentOrg               {:db/valueType :db.type/string}})

(defn available-releases
  "Returns a sequence of releases from NHS Digital's TRUD service"
  [api-key]
  {:pre  [(string? api-key)]
   :post [(seq %)]}
  (trud/get-releases api-key nhs-ods-weekly-item-identifier))

(defn latest-release
  "Returns data about the latest release of the ODS weekly TRUD item."
  [api-key]
  {:pre  [(string? api-key)]
   :post [(map? %)]}
  (->> (available-releases api-key) (sort-by :releaseDate) last))

(defn download-latest-release
  "Downloads the latest release, unzipping recursively.
  Returns a java.nio.Path of the unzipped file."
  [{:keys [api-key cache-dir] :as config}]
  {:pre  [(string? api-key) (string? cache-dir)]
   :post [(instance? Path %)]}
  (let [latest (trud/get-latest config nhs-ods-weekly-item-identifier)]
    (zf/unzip-nested (:archiveFilePath latest))))

(def ods-weekly-files
  [{:type     :egpcur
    :filename "Data/egpcur-zip/egpcur.csv"
    :headings n27-field-format}
   {:type     :epraccur
    :filename "Data/epraccur-zip/epraccur.csv"
    :headings n27-field-format}
   {:type     :ebranchs
    :filename "Data/ebranchs-zip/ebranchs.csv"
    :headings n27-field-format}
   {:type     :egmcmem
    :filename "Data/egmcmem-zip/egmcmem.csv"
    :headings [:gmc-reference-number :given-name :surname :gnc-prescriber-id :date]}])

(defn read-csv-file [x headings]
  (with-open [rdr (io/reader x)]
    (let [data (csv/read-csv rdr)]
      (mapv #(zipmap headings %) data))))

(defn import-ods-weekly
  [conn ^Path path]
  (doseq [f ods-weekly-files]
    (println "Importing " (:type f))
    (let [path (.resolve path ^String (:filename f))
          data (read-csv-file (.toFile path) (:headings f))]
      (d/transact! conn data))))

(defn get-by-organisation-code
  "Return data on the 'organisation' specified. "
  [conn organisation-code]
  (d/q '[:find [(pull ?e [*]) ...]
         :in $ ?code
         :where
         [?e :organisationCode ?code]]
       (d/db conn)
       organisation-code))

(defn get-by-name
  [conn s]
  (d/q '[:find [(pull ?e [*]) ...]
         :in $ ?name
         :where
         [?e :name ?name]]
       (d/db conn)
       s))

(defn surgery-gps
  "Returns a sequence of general practitioners in the surgery specified."
  [conn surgery-identifier]
  (d/q '[:find [(pull ?e [*]) ...]
         :in $ ?surgery-id
         :where
         [?e :gnc-prescriber-id ?gnc-id]
         [?gp :organisationCode ?gnc-id]
         [?gp :parent ?surgery-id]]
       (d/db conn)
       surgery-identifier))

(defn gp-by-gmc-number
  "Return a sequence of GNC records for the GP with the given GMC number.
  The NHS Prescription Service use a number called the Doctor’s Index Number or
  DIN (allocated by the Health and Social Care Information Centre when a doctor
  first applies to practice in the UK), to derive a GP's first GNC code; this
  is prefixed with a ‘G’ and suffixed with a check digit. Thereafter however, if
  a GP begins to work at further practices (i.e. is working within more than one
  simultaneously), the NHS Prescription Service allocate further codes not based
  on the DIN. A GP can therefore have multiple GNC codes, one for each practice
  he works at simultaneously. If a GP completely leaves a practice before
  joining a new one, and there is no overlap, then the current code will be
  retained and just the links within the data updated."
  [conn gmc-number]
  {:pre [(string? gmc-number)]
   :post [(seq %)]}
  (d/q '[:find [(pull ?e [*]) ...]
         :in $ ?gmc
         :where
         [?e :gmc-reference-number ?gmc]]
       (d/db conn)
       gmc-number))

(comment
  (def trud-api-key (str/trim-newline (slurp "/Users/mark/Dev/trud/api-key.txt")))
  (def config {:api-key trud-api-key :cache-dir "/Users/mark/Dev/trud/cache"})
  (available-releases trud-api-key)

  (def data (read-csv-file "/var/folders/w_/s108lpdd1bn84sntjbghwz3w0000gn/T/trud10763576952452681518/Data/egpcur-zip/egpcur.csv"))
  (take 4 data)
  (download-latest-release config)
  (def conn (d/get-conn "ods-weekly.db" schema))
  (d/transact! conn data)

  (import-ods-weekly conn (java.nio.file.Paths/get "/var/folders/w_/s108lpdd1bn84sntjbghwz3w0000gn/T/trud10763576952452681518"
                                                   (make-array String 0)))

  (d/q '[:find [(pull ?e [*]) ...]
         :in $ ?name
         :where
         [?e :name ?name]]
       (d/db conn)
       "ALLISON JL")
  (d/q '[:find [(pull ?e [*]) ...]
         :in $ ?parent
         :where
         [?e :parent ?parent]]
       (d/db conn)
       "W93036")

  (map #(str "Dr. " (:given-name %) " " (:surname %)  ) (surgery-gps conn "W93036"))

  )