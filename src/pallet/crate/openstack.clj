(ns pallet.crate.openstack
  (:require
    [pallet.actions :refer [exec-script packages]]
    [pallet.api :as api]
    [pallet.crate.openstack.core :as core]
    [pallet.crate.openstack.cinder :as cinder]
    [pallet.crate.openstack.glance :as glance]
    [pallet.crate.openstack.horizon :as horizon]
    [pallet.crate.openstack.keystone :as keystone]
    [pallet.crate.openstack.open-vswitch :as open-vswitch]
    [pallet.crate.openstack.nova :as nova]
    [pallet.crate.openstack.quantum :as quantum]))

(defn server-spec
  [& {:keys [cinder glance horizon keystone nova open-vswitch quantum
             interfaces admin-pass mysql-root-pass]
      :as options}]
  (let [iface-address (fn [iface interfaces]
                        (-> (filter (comp #{iface} first) interfaces)
                            first second :address))
        settings (-> options
                     (assoc-in [:interfaces :external-ip]
                               (iface-address "eth0" interfaces))
                     (assoc-in [:interfaces :internal-ip]
                               (iface-address "eth1" interfaces)))]
    (api/server-spec
      :extends [(core/server-spec settings)
                (keystone/server-spec settings)
                (horizon/server-spec settings)
                (cinder/server-spec settings)
                (nova/server-spec settings)
                (quantum/server-spec settings)
                ] 
      ))
  )
