# ods-weekly

[![Scc Count Badge](https://sloc.xyz/github/wardle/ods-weekly)](https://github.com/wardle/ods-weekly/)
[![Scc Cocomo Badge](https://sloc.xyz/github/wardle/ods-weekly?category=cocomo&avg-wage=100000)](https://github.com/wardle/ods-weekly/)
[![Clojars Project](https://img.shields.io/clojars/v/com.eldrix/ods-weekly.svg)](https://clojars.org/com.eldrix/ods-weekly)

This is a small clojure (and java) library and microservice designed to support the UK ODS weekly prescribing data.

# Status

This is incomplete; it can import data from TRUD and provides an easily searchable index, but the API
provided is not finished.

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

```shell
clj -X:download :api-key trud-api-key.txt
```

The optional parameters are:

- :dir - specify the directory in which your index will be installed
- :cache-dir - specify a well known location to act as a TRUD download cache

For example:

```shell
clj -X:download :api-key trud-api-key.txt :dir /var/ods-weekly :cache-dir /var/cache/trud
```

This will download and create an index:

```shell
$ clj -X:download :api-key trud-api-key.txt

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

Documentation for the API is available.