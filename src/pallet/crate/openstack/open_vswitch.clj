(ns pallet.crate.openstack.open-vswitch
  (:require
    [clojure.string :as string]
    [pallet.actions
     :refer [exec-script packages remote-file]]
    [pallet.crate :refer [defplan]]
    [pallet.crate.openstack.core
     :refer [restart-network-interfaces template-file]]))

(defplan install [{:keys [interfaces] :as settings} & flags]
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
