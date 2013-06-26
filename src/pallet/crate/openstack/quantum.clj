(ns pallet.crate.openstack.quantum
  (:require
    [pallet.actions :refer [packages]]
    [pallet.api :as api]
    [pallet.crate :refer [defplan]]
    [pallet.crate.openstack.core :refer [restart-services template-file]]
    [pallet.crate.mysql :as mysql]))

(defplan install [{{:keys [internal-ip external-ip]} :interfaces
                   {:keys [user password] :as quantum} :quantum 
                   :keys [mysql-root-pass]}]
  (packages :apt ["quantum-server" "quantum-plugin-openvswitch"
                  "quantum-plugin-openvswitch-agent" "dnsmasq"
                  "quantum-dhcp-agent""quantum-l3-agent"])
  (mysql/create-user user password "root" mysql-root-pass)
  (mysql/create-database "quantum" user mysql-root-pass)
  (let [values (assoc quantum :internal-ip internal-ip)]
    (template-file "etc/quantum/api-paste.ini" values "restart-quantum")
    (template-file "etc/quantum/plugins/openvswitch/ovs_quantum_plugin.ini"
                   values
                   "restart-quantum")
    (template-file "etc/quantum/metadata_agent.ini" values "restart-quantum")
    (template-file "etc/quantum/quantum.conf" values "restart-quantum")
    (restart-services :flag "restart-quantum"
                      "quantum-dhcp-agent" "quantum-l3-agent"
                      "quantum-metadata-agent"
                      "quantum-plugin-openvswitch-agent" "quantum-server"
                      "dnsmasq")))

(defn server-spec [settings]
  (api/server-spec
    :phases {:install (install settings)}))
