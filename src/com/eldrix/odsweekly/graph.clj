(ns com.eldrix.odsweekly.graph
  "Provides a graph API across thr UK 'ods-weekly' dataset.
  This is designed to be composed with the main ODS distribution, via [[clods]](https://github.com/wardle/clods).
  As such, it focuses only on the information ods-weekly that is not found
  in the main ODS dataset. "
  (:require [clojure.tools.logging.readable :as log]
            [com.eldrix.odsweekly.core :as ow]
            [com.wsscode.pathom3.connect.indexes :as pci]
            [com.wsscode.pathom3.connect.operation :as pco]
            [com.wsscode.pathom3.interface.eql :as p.eql]))

(pco/defresolver surgery->gps
  "Resolve a vector of general practitioners for the given surgery.
  The principal output format is a HL7 FHIR R4 Practitioner structure, but
  with namespaced keys to permit easy onward graph traversal.
  See https://simplifier.net/guide/UKNamingSystems/Home/Identifiersystems/IndexofIdentifierNamingsystems"
  [{svc ::svc} {orgId :uk.nhs.ord/orgId}]
  {::pco/input  [{:uk.nhs.ord/orgId [ :uk.nhs.ord.orgId/extension]}]
   ::pco/output [{:uk.nhs.ord/generalPractitioners
                  [{:org.hl7.fhir.Practitioner/identifier
                    [:org.hl7.fhir.Identifier/system :org.hl7.fhir.Identifier/value]}
                   {:org.hl7.fhir.Practitioner/name
                    [:org.hl7.fhir.HumanName/prefix
                     :org.hl7.fhir.HumanName/given
                     :org.hl7.fhir.HumanName/family]}
                   :uk.org.hl7.fhir.Id/gmp-number
                   :uk.org.hl7.fhir.Id/gmc-number]}]}
  {:uk.nhs.ord/generalPractitioners
   (->> (ow/surgery-gps svc (:uk.nhs.ord.orgId/extension orgId))
        (mapv (fn [{:keys [organisationCode gmcReferenceNumber givenName surname]}]
                (cond-> {:uk.org.hl7.fhir.Id/gmp-number organisationCode
                         :org.hl7.fhir.Practitioner/identifier
                         (cond-> [{:org.hl7.fhir.Identifier/system "https://fhir.hl7.org.uk/Id/gmp-number"
                                   :org.hl7.fhir.Identifier/value  organisationCode}]
                           gmcReferenceNumber
                           (conj {:org.hl7.fhir.Identifier/system "https://fhir.hl7.org.uk/Id/gmc-number"
                                  :org.hl7.fhir.Identifier/value  gmcReferenceNumber}))}
                  gmcReferenceNumber
                  (assoc :uk.org.hl7.fhir.Id/gmc-number gmcReferenceNumber)
                  (or givenName surname)
                  (assoc :org.hl7.fhir.Practitioner/name
                         (cond-> {:org.hl7.fhir.HumanName/prefix ["Dr"]}
                           givenName (assoc :org.hl7.fhir.HumanName/given [givenName])
                           surname (assoc :org.hl7.fhir.HumanName/family surname)))))))})

(pco/defresolver gp-by-gmc->roles
  "Resolve roles for a general practitioner lookup by GMC number."
  [{svc ::svc} {gmc-number :uk.org.hl7.fhir.Id/gmc-number}]
  {::pco/input  [:uk.org.hl7.fhir.Id/gmc-number]
   ::pco/output [{:org.hl7.fhir.Practitioner/role
                  [:uk.org.hl7.fhir.Id/gmp-number
                   {:org.hl7.fhir.PractitionerRole/organization [:uk.nhs.fhir.Id/ods-organization]}
                   {:org.hl7.fhir.Practitioner/name [:org.hl7.fhir.HumanName/prefix
                                                     :org.hl7.fhir.HumanName/family
                                                     :org.hl7.fhir.HumanName/given]}]}]}
  (when-let [roles (seq (ow/gp-by-gmc-number svc gmc-number))]
    {:org.hl7.fhir.Practitioner/role
     (->> roles
          (map (fn [{:keys [gncPrescriberId parent surname givenName]}]
                 {:uk.org.hl7.fhir.Id/gmp-number              gncPrescriberId
                  :org.hl7.fhir.PractitionerRole/organization {:uk.nhs.fhir.Id/ods-organization parent}
                  :org.hl7.fhir.Practitioner/name             {:org.hl7.fhir.HumanName/prefix ["Dr"]
                                                               :org.hl7.fhir.HumanName/family surname
                                                               :org.hl7.fhir.HumanName/given  [givenName]}})))}))

(def all-resolvers
  "UK ods-weekly resolvers; expect a key :com.eldrix.odsweekly.graph/svc in environment."
  [surgery->gps
   gp-by-gmc->roles])

(comment
  (ow/download {:api-key "../trud/api-key.txt"})
  (def conn (ow/open-index "ods-weekly-2024-02-15.db"))
  (ow/surgery-gps conn "W93036")
  (ow/gp-by-gmc-number conn "7016404")
  (def registry (-> (pci/register all-resolvers)
                    (assoc ::svc conn)))
  (p.eql/process registry
                 [{[:uk.nhs.ord/orgId {:uk.nhs.ord.orgId/extension "W93036"}]
                   [:uk.nhs.ord/generalPractitioners]}])
  (p.eql/process registry
                 [{[:uk.org.hl7.fhir.Id/gmc-number "7016404"]
                   [:org.hl7.fhir.Practitioner/role]}]))

