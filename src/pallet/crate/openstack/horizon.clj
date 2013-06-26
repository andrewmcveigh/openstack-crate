(ns pallet.crate.openstack.horizon
  (:require
    [pallet.actions :refer [exec-script packages]]
    [pallet.api :as api]
    [pallet.crate.openstack.core :as core :refer [restart-services]]
    [pallet.crate :refer [defplan]]))

(defn server-spec [settings]
  (api/server-spec
    :phases
    {:install (api/plan-fn
                (packages :apt ["openstack-dashboard" "memcached"])
                (exec-script "dpkg --purge openstack-dashboard-ubuntu-theme")
                (restart-services "apache2" "memcached"))}
    :extend [(core/server-spec settings)]))
