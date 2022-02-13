(ns com.eldrix.odsweekly.core
  "ods-weekly is an NHS dataset that supplements the standard ODS dataset.
  It principally links GP surgeries with general practitioners, with additional
  tables to support the mapping of GP identifiers to GMC reference numbers."
  (:require [clojure.data.csv :as csv]
            [clojure.spec.alpha :as s]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [com.eldrix.trud.core :as trud]
            [com.eldrix.trud.zip :as zf]
            [datalevin.core :as d]
            [clojure.pprint :as pprint])
  (:import (java.nio.file Path Paths)
           (java.time LocalDateTime)
           (java.time.format DateTimeFormatter)))

(def ^:private nhs-ods-weekly-item-identifier 58)
(def ^:private store-version 1)

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

(def ^:private
  schema {:organisationCode         {:db/valueType :db.type/string
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

(defn- available-releases
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

(defn- download-latest-release
  "Downloads the latest release, unzipping recursively.
  Returns TRUD data about the release, including an additional key:
  - :unzippedFilePath : a java.nio.Path of the unzipped file."
  [{:keys [api-key cache-dir] :as config}]
  {:pre [(string? api-key) (string? cache-dir)]}
  (let [latest (trud/get-latest config nhs-ods-weekly-item-identifier)]
    (assoc latest :unzippedFilePath (zf/unzip-nested (:archiveFilePath latest)))))

(def ^:private ods-weekly-files
  [{:type        :egpcur
    :description "Current GP Practitioners in England and Wales"
    :filename    "Data/egpcur-zip/egpcur.csv"
    :headings    n27-field-format}
   {:type        :epraccur
    :description "GP Practices in England and Wales"
    :filename    "Data/epraccur-zip/epraccur.csv"
    :headings    n27-field-format}
   {:type        :ebranchs
    :description "GP Branch Surgeries in England"
    :filename    "Data/ebranchs-zip/ebranchs.csv"
    :headings    n27-field-format}
   {:type        :egmcmem
    :description "A snapshot mapping, generated weekly, between General Medical Council (GMC) Reference Numbers and primary Prescriber Identifiers (otherwise known as GNC / GMP codes) for GPs."
    :filename    "Data/egmcmem-zip/egmcmem.csv"
    :headings    [:gmcReferenceNumber :givenName :surname :gncPrescriberId :date]}])

(defn- read-csv-file [x headings]
  (with-open [rdr (io/reader x)]
    (let [data (csv/read-csv rdr)]
      (mapv #(zipmap headings %) data))))

(defn- import-ods-weekly
  "Import ODS data to the database 'conn' from the path specified."
  [conn ^Path path]
  (doseq [f ods-weekly-files]
    (println "Importing " (:type f) ": " (:description f))
    (let [path (.resolve path ^String (:filename f))
          data (read-csv-file (.toFile path) (:headings f))]
      (d/transact! conn (map #(assoc % :uk.nhs.ods/type (:type f)) data)))))

(defn metadata
  "Returns the metadata from the index specified."
  [dir]
  (let [f (io/file dir)]
    (when (and (.exists f) (.isDirectory f))
      (d/with-conn [conn dir schema]
        (->> (d/q '[:find [(pull ?e [*]) ...]
                    :where
                    [?e :metadata/created ?]]
                  (d/db conn))
             (sort-by :metadata/created)
             last)))))

(defn create-index
  "Create an index with the latest distribution downloaded from TRUD.
  Parameters:
  - dir       : directory in which to create automatically named index
  - db        : specific path for index creation, if no 'dir' specified.
  - api-key   : TRUD api-key
  - cache-dir : TRUD cache directory

  Returns a map containing the release information, including key:
  - :indexDir - string representing location of created index

  By default, a new index will be created based on the release-date within the
  directory `dir`. If `db` is specified, the index will be created directly
  there instead. If `dir` is omitted, the current directory will be used."
  [& {:keys [db dir api-key cache-dir] :or {dir ""}}]
  (let [path (Paths/get (str (or db dir)) (make-array String 0))
        downloaded (download-latest-release {:api-key api-key :cache-dir cache-dir})
        f (-> (if db
                path
                (.resolve path (str "ods-weekly-" (:releaseDate downloaded) ".db")))
              (.toAbsolutePath) (.toString))
        existing (metadata f)]
    (when existing
      (throw (ex-info "Index already exists" {:indexDir f
                                              :metadata existing})))
    (let [conn (d/create-conn f schema)]
      (println "Creating index:" f)
      (import-ods-weekly conn (:unzippedFilePath downloaded))
      (d/transact! conn [{:metadata/version store-version
                          :metadata/created (LocalDateTime/now)
                          :metadata/release (:releaseDate downloaded)}])
      (println "Finished writing index: " f)
      (d/close conn))))

(defn download
  "Downloads the latest release to create a file-based database.
  A function designed to used as an exec-fn from deps.edn.
  Parameters:
  - :db        - specific name for file-based database
  - :dir       - directory in which a file-based database should be created
  - :api-key   - filename of file containing TRUD API key
  - :cache-dir - directory for TRUD cache.

  The only mandatory parameter is api-key.
  If ':db' and ':dir' are omitted, the current directory will be used.
  If ':cache-dir' is omitted, a system 'tmp' directory will be used."
  [{:keys [_db _dir api-key cache-dir] :as params}]
  (when (str/blank? (str api-key))
    (println "Error: Missing api-key. Usage: clj -X:download :api-key my-api-key.txt")
    (System/exit 1))
  (let [api-key' (str/trim-newline (slurp (str api-key)))
        cache-dir (str (or cache-dir (System/getProperty "java.io.tmpdir")))]
    (println "Creating index using cache directory: " cache-dir)
    (create-index (assoc params :api-key api-key' :cache-dir cache-dir))))

(defn status
  "Return the status of a specific ods-weekly index.
  A function designed to used as an exec-fn from deps.edn.
  Parameters:
  - :db - path to index."
  [{:keys [db]}]
  (when (str/blank? (str db))
    (println "Error: Missing dir. Usage: clj -X:status :db my-ods-weekly.db")
    (System/exit 1))
  (let [md (metadata (str db))]
    (pp/pprint {:version (:metadata/version md)
                :created (.format (DateTimeFormatter/ISO_LOCAL_DATE_TIME) (:metadata/created md))
                :release (.format (DateTimeFormatter/ISO_LOCAL_DATE) (:metadata/release md))})))

(defn open-index
  "Open an index.
  Parameters:
  - :db   - path to ods-weekly index
  The index must have been initialised and of the correct index version."
  [db]
  (let [metadata (metadata db)]
    (if-not (= store-version (:metadata/version metadata))
      (throw (ex-info "Incorrect index version" {:expected store-version
                                                 :got      metadata}))
      (d/create-conn db schema))))

(defn close-index [conn]
  (d/close conn))

(defn get-organisation-by-code
  "Return data on the 'organisation' specified. "
  [conn organisation-code]
  (d/q '[:find (pull ?e [*]) .
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
         [?e :gncPrescriberId ?gnc-id]
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
  {:pre  [(string? gmc-number)]
   :post [(seq %)]}
  (d/q '[:find ?gmc ?given-name ?surname ?gnc-id ?surgery-id
         :keys gmcReferenceNumber givenName surname gncPrescriberId surgeryId
         :in $ ?gmc
         :where
         [?e :gmcReferenceNumber ?gmc]
         [?e :gncPrescriberId ?gnc-id]
         [?e :givenName ?given-name]
         [?e :surname ?surname]
         [?org :organisationCode ?gnc-id]
         [?org :parent ?surgery-id]]
       (d/db conn)
       gmc-number))

(comment
  (def trud-api-key (str/trim-newline (slurp "/Users/mark/Dev/trud/api-key.txt")))
  (def config {:api-key trud-api-key :cache-dir "/Users/mark/Dev/trud/cache"})
  (available-releases trud-api-key)
  (latest-release trud-api-key)
  (def data (read-csv-file "/var/folders/w_/s108lpdd1bn84sntjbghwz3w0000gn/T/trud10763576952452681518/Data/egpcur-zip/egpcur.csv"
                           n27-field-format))
  (take 4 data)
  (download-latest-release config)
  (def conn (d/get-conn "ods-weekly-2022-02-10.db" schema))
  (d/transact! conn)
  (d/transact! conn [{:db/id  -1
                      :stupid 1
                      :idiot  2
                      :dummy  3}])

  (d/transact! conn [{:db/id            -1
                      :metadata/version store-version       ;; store some metadata
                      :metadata/created (LocalDateTime/now)
                      :metadata/release "ooo"}])
  (import-ods-weekly conn (Paths/get "/var/folders/w_/s108lpdd1bn84sntjbghwz3w0000gn/T/trud10763576952452681518"
                                     (make-array String 0)))
  (d/q '[:find [(pull ?e [*]) ...]
         :where
         [?e :metadata/created ?]]
       (d/db conn))
  (create-index :api-key trud-api-key :cache-dir "/Users/mark/Dev/trud/cache")
  (d/q '[:find [(pull ?e [*]) ...]
         :in $ ?name
         :where
         [?e :name ?name]]
       (d/db conn)
       "ALLISON JL")

  (d/q '[:find [(pull ?e [*]) ...]
         :in $ ?parent
         :where
         [? :uk.nhs.ods/type :egpcur]
         [?e :parent ?parent]]
       (d/db conn)
       "W93036")
  (d/q '[:find [(pull ?e [*]) ...]
         :in $ ?code
         :where
         [?e :organisationCode ?code]]
       (d/db conn)
       "W93036")
  (surgery-gps conn "W93036")
  (map #(str "Dr. " (:given-name %) " " (:surname %)) (surgery-gps conn "W93036"))

  (def conn (open-index "ods-weekly-2022-02-10.db"))
  (clojure.pprint/print-table [:gmcReferenceNumber :givenName :surname :gncPrescriberId] (surgery-gps conn "W93029"))

  )