# ods-weekly

[![Scc Count Badge](https://sloc.xyz/github/wardle/ods-weekly)](https://github.com/wardle/ods-weekly/)
[![Scc Cocomo Badge](https://sloc.xyz/github/wardle/ods-weekly?category=cocomo&avg-wage=100000)](https://github.com/wardle/ods-weekly/)
[![Clojars Project](https://img.shields.io/clojars/v/com.eldrix/ods-weekly.svg)](https://clojars.org/com.eldrix/ods-weekly)

This is a small clojure (and java) library and microservice designed to support the UK ODS weekly prescribing data.

It supplements the main ODS dataset, which you can easily make
use of using [clods](https://github.com/wardle/clods).

# Why is this needed?

The ODS dataset does not include a list of general practitioners for each GP surgery. Many applications need to make use of such data for handling clinical correspondence or determining a complete profile for a user.

For example, I combine information from user directories and other stores with ods-weekly data, so that I can, in software, make reasonable assumptions about where a clinician works. Sometimes, that might just be to provide an excellent user experience by tuning or sorting pick-lists to the geography in which we think the user might work, but permitting overrides when necessary. I aggregate data from disparate data as part of a unified graph API. 

# Status

This is now operational; it can import data from TRUD and provides an easily searchable index, and there is a simple API that can be used to
fetch the GPs working at a specific surgery, or find where a particular GP is working, based on their GNC or GMC identifier.

# Background

This supplements the ODS quarterly and monthly releases made available via [https://github.com/wardle/clods](https://github.com/wardle/clods).

It can create an immutable file-based database that can then be used within other applications to make use of NHS
prescribing data. These data document surgeries and branch surgeries in the UK, with lists of general practitioners.

Each practice has a list of general practitioners by GNC identifier. These data
also map between GNC identifier and GMC, as one GP may have more than one GNC
identifier if they have worked in multiple practices at the same time.

It is advisable to create a new service every week based on the most recent published data. 

# Creating an ods-weekly file-based database

You can directly run from source using the clojure command line tools.
If there is interest, I can provide a pre-built 'uberjar' containing 
command-line tools and a simple HTTP server. My recommendation is to run from
source, or embed as a library and use the clojure API.

## Usage from source code

1. Install clojure

e.g. on Mac:
```shell
brew install clojure/tools/clojure
```

See the [Clojure getting started guide](https://clojure.org/guides/getting_started) for more details on installation.

2. Clone the source code repository

```shell
git clone https://github.com/wardle/ods-weekly
cd ods-weekly
```

3. Check you have an NHS Digital TRUD API key. 

You can easily register and obtain an API key from [NHS Digital](https://isd.digital.nhs.uk/trud/users/guest/filters/0/api).

Once you have an API key, put it into a text file in a well known location in your computer's filesystem.

For example, trud-api-key.txt in the current directory.

4. Download and create an index:

You can specify the name of the index, or specify the directory in which an
automatically named index will be created. The default, if both are omitted,
is to create an automatically named index in the current directory.

```shell
clj -X:download :api-key trud-api-key.txt
```

To create a specifically named index:
```shell
clj -X:download :api-key trud-api-key.txt :db my-ods-weekly.db
```

To create an automatically named index:
```shell
clj -X:download :api-key trud-api-key.txt :dir /var/db/ods-weekly/
```
This latter operation will create a database index in the directory specified
with a name based on the release-date. This is ideal for use in a weekly 
automated cron job, for example.

The mandatory parameters are:

- :api-key  - path to a text file containing your TRUD API key

The optional parameters are:

- :dir - specify the directory in which your index will be installed
- :db  - specify the specify index to be created
- :cache-dir - specify a well known location to act as a TRUD download cache

For example:

```shell
clj -X:download :api-key trud-api-key.txt :dir /var/ods-weekly :cache-dir /var/cache/trud
```

This will download and create an index:

```shell
clj -X:download :api-key trud-api-key.txt
```

Result:

```
Item already in cache {:itemIdentifier 58, :archiveFileName nhs_odsweekly_2.1.0_20220203000001.zip, :releaseDate #object[java.time.LocalDate 0x2ad51c20 2022-02-03]}
Creating index: /Users/mark/Dev/ods-weekly/ods-weekly-2022-02-03.db
Importing  :egpcur :  Current GP Practitioners in England and Wales
Importing  :epraccur :  GP Practices in England and Wales
Importing  :ebranchs :  GP Branch Surgeries in England
Importing  :egmcmem :  A snapshot mapping, generated weekly, between General Medical Council (GMC) Reference Numbers and primary Prescriber Identifiers (otherwise known as GNC / GMP codes) for GPs.
```

Here you see that the tooling recognises that the release has already been downloaded and is in the local cache.
The zip file is unzipped, and the files processed and imported into a file-based database.

## Usage as a library

Simply include the [library as a dependency](https://clojars.org/com.eldrix/ods-weekly).

For example, in your clojure CLI/deps.edn file:

```clojure
com.eldrix/ods-weekly {:mvn/version "RELEASE"}
```

And then in your code (here is example usage from a REPL):
```clojure
(require '[com.eldrix.odsweekly.core :as ow])
(def conn (ow/open-index "ods-weekly-2022-02-10.db"))
```

Now you can get information about an organisation:
```clojure
(ow/get-by-organisation-code conn "W93036")
```

Result (truncated):
```clojure
{:nationalGrouping "W00",
 :highLevelHealthGeography "Q99",
 :leftParentDate "",
 :parent "7A6",
 :address3 "MONMOUTH",
 :telephone "01600 713811",
 :name "CASTLE GATE MEDICAL PRACTICE",
 :currentOrg "7A6"}
```

And let's fetch a list of GPs working at a different practice:

```clojure
(clojure.pprint/print-table [:gmcReferenceNumber :givenName :surname :gncPrescriberId] (ow/surgery-gps conn "W93029"))
```

Result (although these data are public, I have redacted some of this information):
```clojure
|   :gmcReferenceNumber | :givenName  | :surname |   :gncPrescriberId |
|-----------------------+-------------+----------+--------------------|
|               7*****7 |       L**** | ******** |           G******4 |
|               6*****2 |     ******* |     R*** |           G******9 |
|               4*****7 |       J**** |   ****** |           G******4 |
|               7*****0 |    S******* |   ****** |           G******9 |
|               4*****7 |        S*** |   ****** |           G******3 |
```

Further API documentation is [available](https://cljdoc.org/d/com.eldrix/ods-weekly).

## Development

Check for outdated dependencies
```shell
clj -M:outdated 
```

Build a library jar file

```shell
clj -T:build jar
```

Build and deploy a library jar file to clojars:

```shell
clj -T:build deploy
```