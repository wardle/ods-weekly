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
