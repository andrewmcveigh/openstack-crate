(ns pallet.crate.openstack
  (:require
    [pallet.api :as api]
    [pallet.crate.openstack.core :as core]
    [pallet.crate.openstack.cinder :as cinder]
    [pallet.crate.openstack.glance :as glance]
    [pallet.crate.openstack.horizon :as horizon]
    [pallet.crate.openstack.keystone :as keystone]
    [pallet.crate.openstack.nova :as nova]
    [pallet.crate.openstack.quantum :as quantum]))

(defn server-spec [& {:as settings}]
  (api/server-spec
    :extends [(core/server-spec settings)
              (keystone/server-spec settings)
              (glance/server-spec settings)
              (quantum/server-spec settings)
              (nova/server-spec settings)
              (cinder/server-spec settings)
              (horizon/server-spec settings)]))
