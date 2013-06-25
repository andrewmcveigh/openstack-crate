(ns pallet.crate.openstack.horizon
  (:require
    [pallet.actions :refer [exec-script packages]]
    [pallet.api :as api]
    [pallet.crate.openstack.core :refer [restart-services]]
    [pallet.crate :refer [defplan]]))

(defplan install []
  (packages :apt ["openstack-dashboard" "memcached"])
  (exec-script "dpkg --purge openstack-dashboard-ubuntu-theme")
  (restart-services "apache2" "memcached"))

(defn server-spec []
  (api/server-spec
    :phases {:install install}))
