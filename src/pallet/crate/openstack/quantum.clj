(ns pallet.crate.openstack.quantum
  (:require
    [clojure.string :as string]
    [pallet.actions :refer [exec-script packages remote-file]]
    [pallet.api :as api]
    [pallet.crate :refer [defplan]]
    [pallet.crate.openstack.core :as core
     :refer [interface-str restart-network-interfaces restart-services template-file]]
    [pallet.crate.mysql :as mysql]))

(defplan open-vswitch [{:keys [interfaces] :as settings} & flags]
  (let [flags (set flags)
        eth1 "
# VM internet Access
auto eth1
iface eth1 inet manual
up ifconfig $IFACE 0.0.0.0 up
up ip link set $IFACE promisc on
down ip link set $IFACE promisc off
down ifconfig $IFACE down
        "]
    (packages :apt ["openvswitch-switch" "openvswitch-datapath-dkms"])
    (exec-script "ovs-vsctl add-br br-int")
    (exec-script "ovs-vsctl add-br br-ext")
    (when (:br-ext flags)
      (let [s (map interface-str
                   (concat (remove (comp #{"eth1"} first) interfaces)
                           (assoc-in (vec (filter (comp #{"eth1"} first)
                                                  interfaces))
                                     [0 0] "br-ext")))
            values {:interfaces (str (string/join \newline s) eth1)}]
        (template-file "etc/network/interfaces" values "restart-network")
        (restart-network-interfaces :if-flag "restart-network"))
      (exec-script "ovs-vsctl add-port br-ex eth1"))))

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

(defn server-spec [settings & flags]
  (api/server-spec
    :phases {:install (api/plan-fn
                        (apply open-vswitch settings flags)
                        (install settings))}
    :extend [(core/server-spec settings)]))
