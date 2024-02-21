(ns com.eldrix.odsweekly.core
  "ods-weekly is an NHS dataset that supplements the standard ODS dataset.
  It principally links GP surgeries with general practitioners, with additional
  tables to support the mapping of GP identifiers to GMC reference numbers."
  (:require [clojure.data.csv :as csv]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [com.eldrix.trud.core :as trud]
            [com.eldrix.zipf :as zf]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs])
  (:import (java.nio.file Path Paths)
           (java.time LocalDateTime)))

(def ^:private nhs-ods-weekly-item-identifier 58)
(def ^:private store-version 1)

(def ^:private n27-field-format
  "The standard ODS 27-field format headings"
  [:organisationCode :name :nationalGrouping :highLevelHealthGeography
   :address1 :address2 :address3 :address4 :address5 :postcode
   :openDate :closeDate :statusCode :subtype :parent :joinParentDate :leftParentDate
   :telephone :nil :nil :nil :amendedRecord :nil :currentOrg :nil :nil :nil])

(def ^:private n27-sql-fields
  "(organisationCode text primary key, name text not null, nationalGrouping text, highLevelHealthGeography text,
    address1 text, address2 text, address3 text, address4 text, address5 text, postcode text, openDate text, closeDate text,
    statusCode text, subtype text, parent text, joinParentDate text, leftParentDate text, telephone text, amendedRecord text, currentOrg text)")

(defn ^:private create-n27
  [table-name]
  (str "create table if not exists " table-name " " n27-sql-fields))

(defn ^:private insert-n27
  [table-name]
  {:sql     (str "insert into " table-name " (organisationCode, name, nationalGrouping, highLevelHealthGeography,
              address1, address2, address3, address4, address5, postcode, openDate, closeDate,
              statusCode, subtype, parent, joinParentDate, leftParentDate, telephone, amendedRecord, currentOrg)
              values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
              on conflict (organisationCode) do update set
              name=excluded.name, nationalGrouping=excluded.nationalGrouping,
              highLevelHealthGeography=excluded.highLevelHealthGeography,
              address1=excluded.address1, address2=excluded.address2, address3=excluded.address3,
              address4=excluded.address4, address5=excluded.address5, postcode=excluded.postcode,
              openDate=excluded.openDate, closeDate=excluded.closeDate, statusCode=excluded.statusCode,
              subtype=excluded.subtype, parent=excluded.parent, joinParentDate=excluded.joinParentDate,
              telephone=excluded.telephone,amendedRecord=excluded.amendedRecord, currentOrg=excluded.currentOrg")
   :data-fn (juxt :organisationCode :name :nationalGrouping :highLevelHealthGeography :address1 :address2 :address3 :address4 :address5 :postcode
                  :openDate :closeDate :statusCode :subtype :parent :joinParentDate :leftParentDate :telephone :amendedRecord :currentOrg)})

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
    (assoc latest :unzippedFilePath (trud/unzip-nested (:archiveFilePath latest)))))

(def ^:private ods-weekly-files
  [{:type        :egpcur
    :description "Current GP Practitioners in England and Wales"
    :filename    "Data/egpcur-zip/egpcur.csv"
    :headings    n27-field-format
    :create      (create-n27 "egpcur")
    :insert      (insert-n27 "egpcur")}
   {:type        :epraccur
    :description "GP Practices in England and Wales"
    :filename    "Data/epraccur-zip/epraccur.csv"
    :headings    n27-field-format
    :create      (create-n27 "epraccur")
    :insert      (insert-n27 "epraccur")}
   {:type        :ebranchs
    :description "GP Branch Surgeries in England"
    :filename    "Data/ebranchs-zip/ebranchs.csv"
    :headings    n27-field-format
    :create      (create-n27 "ebranchs")
    :insert      (insert-n27 "ebranchs")}
   {:type        :egmcmem
    :description "Map between General Medical Council (GMC) Reference Numbers and primary Prescriber Identifiers (otherwise known as GNC / GMP codes) for GPs"
    :filename    "Data/egmcmem-zip/egmcmem.csv"
    :headings    [:gmcReferenceNumber :givenName :surname :gncPrescriberId :date]
    :create      "create table if not exists egmcmem (gmcReferenceNumber text primary key, givenName text, surname text, gncPrescriberId text unique, date text)"
    :insert      {:sql     "insert into egmcmem (gmcReferenceNumber, givenName, surname, gncPrescriberId, date) values (?,?,?,?,?)
                            on conflict (gmcReferenceNumber) do update set
                            givenName=excluded.givenName, surname=excluded.surname,
                            gncPrescriberId=excluded.gncPrescriberId, date=excluded.date"
                  :data-fn (juxt :gmcReferenceNumber :givenName :surname :gncPrescriberId :date)}}])

(defn- get-user-version
  [conn]
  (:user_version (jdbc/execute-one! conn ["SELECT * from pragma_user_version"] {:builder-fn rs/as-unqualified-maps})))

(defn- set-user-version!
  [conn v]
  (jdbc/execute-one! conn [(str "PRAGMA user_version(" v ")")]))

(defn open-sqlite
  "Open a SQLite database from the file `f`. This can be anything coercible by
  `clojure.java.io/file`"
  [f]
  (let [f' (io/as-file f)
        exists (.exists f')
        conn (jdbc/get-connection (str "jdbc:sqlite:" (.getCanonicalPath f')))
        version (get-user-version conn)]
    (when (and exists (not= version store-version))
      (throw (ex-info "Incompatible index version" {:expected store-version, :found version})))
    conn))

(defn- create-tables
  [conn]
  (jdbc/execute-one! conn ["create table if not exists metadata (created text, version integer, release text)"])
  (run! #(jdbc/execute-one! conn [%]) (map :create ods-weekly-files)))

(defn- read-csv-file [x headings]
  (with-open [rdr (io/reader x)]
    (let [data (csv/read-csv rdr)]
      (mapv #(zipmap headings %) data))))

(defn- import-ods-weekly
  "Import ODS data to the database 'conn' from the path specified."
  [conn ^Path path]
  (doseq [{:keys [type filename description headings insert]} ods-weekly-files]
    (println "Importing " (format "%-10s" type) ": " description)
    (let [path (.resolve path ^String filename)
          data (read-csv-file (.toFile path) headings)
          {:keys [sql data-fn]} insert]
      (jdbc/with-transaction [txn conn]
        (jdbc/execute-batch! txn sql (map data-fn data) {}))))) ;; import in one transaction

(defn- metadata [f]
  (with-open [conn (open-sqlite f)]
    (jdbc/execute-one! conn ["select * from metadata order by created desc"])))

(defn- write-metadata!
  [conn release-date]
  (jdbc/execute-one! conn ["insert into metadata (created,version,release) values (?,?,?)" (LocalDateTime/now) store-version release-date]))

(defn- create-index
  "Create an index with the latest distribution downloaded from TRUD.
  Parameters:
  - dir       : directory in which to create automatically named index
  - db        : specific path for index creation, if no 'dir' specified.
  - api-key   : TRUD api-key
  - cache-dir : TRUD cache directory

  Returns information about the release, including keys:
  - :indexFilename : filename of the newly created index

  By default, a new index will be created based on the release-date within the
  directory `dir`. If `db` is specified, the index will be created directly
  there instead. If `dir` is omitted, the current directory will be used."
  [& {:keys [db dir api-key cache-dir] :or {dir ""}}]
  (let [path (Paths/get (str (or db dir)) (make-array String 0))
        downloaded (download-latest-release {:api-key api-key :cache-dir cache-dir})
        f (-> (if db
                path
                (.resolve path (str "ods-weekly-" (:releaseDate downloaded) ".db")))
              (.toAbsolutePath) (.toFile))
        exists? (.exists f)]
    (with-open [conn (open-sqlite f)]
      (if exists? (println "Updating existing index:" (str f)) (println "Creating index:" (str f)))
      (set-user-version! conn store-version)
      (create-tables conn)
      (import-ods-weekly conn (:unzippedFilePath downloaded))
      (write-metadata! conn (:releaseDate downloaded))
      (println "Finished writing index: " (str f))
      (assoc downloaded :index f))))

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
    (println "Using cache directory  : " cache-dir)
    (create-index (assoc params :api-key api-key' :cache-dir cache-dir))))

(defn status
  "Return the status of a specific ods-weekly index.
  A function designed to used as an exec-fn from deps.edn.
  Parameters:
  - :db - path to index."
  [{:keys [db]}]
  (when (str/blank? (str db))
    (println "Error: Missing dir. Usage: clj -X:status :db '\"my-ods-weekly.db\"'")
    (System/exit 1))
  (let [md (metadata (str db))]
    (pp/pprint md)))

(defn open-index
  "Open an index.
  Parameters:
  - :db   - path to ods-weekly index
  The index must have been initialised and of the correct index version."
  [db]
  (open-sqlite db))

(defn close-index [conn]
  (.close conn))

(defn get-organisation-by-code
  "Return data on the 'organisation' specified. "
  [conn organisation-code]
  (or (jdbc/execute-one! conn
                         ["select * from epraccur where organisationCode=?" organisation-code]
                         {:builder-fn rs/as-unqualified-maps})
      (jdbc/execute-one! conn
                         ["select * from ebranchs where organisationCode=?" organisation-code]
                         {:builder-fn rs/as-unqualified-maps})
      (jdbc/execute-one! conn
                         ["select * from egpcur where organisationCode=?" organisation-code]
                         {:builder-fn rs/as-unqualified-maps})))

(defn get-by-name
  [conn s]
  (or (jdbc/execute-one! conn
                         ["select * from epraccur where name like ?" s]
                         {:builder-fn rs/as-unqualified-maps})
      (jdbc/execute-one! conn
                         ["select * from ebranchs where name like ?" s]
                         {:builder-fn rs/as-unqualified-maps})
      (jdbc/execute-one! conn
                         ["select * from egpcur where name like ?" s]
                         {:builder-fn rs/as-unqualified-maps})))

(defn surgery-gps
  "Returns a sequence of general practitioners in the surgery specified."
  [conn surgery-identifier]
  (jdbc/execute! conn
                 ["select * from egpcur left join egmcmem on gncPrescriberId=organisationCode where parent=?" surgery-identifier]
                 {:builder-fn rs/as-unqualified-maps}))

(defn gp-by-gmc-number
  "Return a sequence of GNC records for the GP with the given GMC number.
  The NHS Prescription Service use a number called the Doctor’s Index Number or
  DIN (allocated by the Health and Social Care Information Centre when a doctor
  first applies to practice in the UK), to derive a GP's first GNC code; this
  is prefixed with a ‘G’ and suffixed with a check digit. Thereafter however, if
  a GP begins to work at further practices (i.e. is working within more than one
  simultaneously), the NHS Prescription Service allocate further codes not based
  on the DIN. A GP can therefore have multiple GNC codes, one for each practice
  they work at simultaneously. If a GP completely leaves a practice before
  joining a new one, and there is no overlap, then the current code will be
  retained and just the links within the data updated."
  [conn gmc-number]
  (jdbc/execute! conn
                 ["select * from egpcur,egmcmem where gncPrescriberId=organisationCode and gmcReferenceNumber=?" (str gmc-number)]
                 {:builder-fn rs/as-unqualified-maps}))

(comment
  (def trud-api-key (str/trim-newline (slurp "/Users/mark/Dev/trud/api-key.txt")))
  (def config {:api-key trud-api-key :cache-dir "/Users/mark/Dev/trud/cache"})
  (available-releases trud-api-key)
  (latest-release trud-api-key)
  (def latest (download-latest-release config))
  (keys latest)
  (def path (com.eldrix.zipf/unzip-nested (:archiveFilePath latest)))
  path
  (.resolve path "Data/egpcur-zip/egpcur.csv")
  (def data (read-csv-file (.toFile (.resolve ^Path path "Data/egpcur-zip/egpcur.csv")) n27-field-format))
  (take 4 data)

  (def conn (open-index "ods-weekly-2024-02-15.db"))
  (clojure.pprint/print-table [:parent :organisationCode :name :gmcReferenceNumber :givenName :surname :gncPrescriberId] (surgery-gps conn "W93029")))

