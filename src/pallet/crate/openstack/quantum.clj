(ns pallet.crate.openstack.quantum
  (:require
    [clojure.string :as string]
    [pallet.actions :refer [exec-script packages remote-file]]
    [pallet.api :as api]
    [pallet.crate :refer [defplan]]
    [pallet.crate.openstack.core :as core
     :refer [interface-str restart-network-interfaces restart-services template-file]]
    [pallet.crate.mysql :as mysql]))

(defplan open-vswitch [{{:strs [eth1] :as interfaces} :settings} & flags]
  (let [flags (set flags)
        br-ext eth1
        eth1 (into core/iface-sorted-map
                   {:iface "inet manual"
                    :up ["ifconfig $IFACE 0.0.0.0 up"
                         "ip link set $IFACE promisc on"]
                    :down ["ip link set $IFACE promisc off"
                           "ifconfig $IFACE 0.0.0.0 down"]})]
    (exec-script "ovs-vsctl add-br br-int")
    (exec-script "ovs-vsctl add-br br-ext")
    (when (:br-ext flags)
      (core/remote-manage-network-interfaces
        #(network-map->str
           (assoc (core/parse-network-str %)
                  "eth1" eth1
                  "br-ext" br-ext)))
      (restart-network-interfaces :if-flag "restart-network")
      (exec-script "ovs-vsctl add-port br-ex eth1"))))

(defplan configure [{{:keys [user password] :as quantum} :quantum
                     :keys [mysql-root-pass]}]
  (mysql/create-user user password "root" mysql-root-pass)
  (mysql/create-database "quantum" "root" mysql-root-pass)
  (template-file "etc/quantum/api-paste.ini" quantum "restart-quantum")
  (template-file "etc/quantum/plugins/openvswitch/ovs_quantum_plugin.ini"
                 quantum
                 "restart-quantum")
  (template-file "etc/quantum/metadata_agent.ini" quantum "restart-quantum")
  (template-file "etc/quantum/quantum.conf" quantum "restart-quantum")
  (restart-services :flag "restart-quantum"
                    "quantum-dhcp-agent" "quantum-l3-agent"
                    "quantum-metadata-agent"
                    "quantum-plugin-openvswitch-agent" "quantum-server"
                    "dnsmasq"))

(defn server-spec [settings & flags]
  (api/server-spec
    :phases {:install
             (api/plan-fn
               (packages :aptitude ["openvswitch-switch"
                                    "openvswitch-datapath-dkms"])
               (packages :aptitude
                         ["quantum-server" "quantum-plugin-openvswitch"
                          "quantum-plugin-openvswitch-agent" "dnsmasq"
                          "quantum-dhcp-agent""quantum-l3-agent"]))
             :configure (api/plan-fn
                          (apply open-vswitch settings flags)
                          (configure settings))}
    :extends [(core/server-spec settings)]))
