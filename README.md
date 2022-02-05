# ods-weekly

This is a small clojure (and java) library and microservice designed to support the UK ODS weekly prescribing data.

This supplements the ODS quarterly and monthly releases made available via [https://github.com/wardle/clods](https://github.com/wardle/clods).

It can create an immutable file-based database that can then be used within other applications to make use of NHS
prescribing data. 

It is advisable to create a new service every week based on the most recent published data. 
