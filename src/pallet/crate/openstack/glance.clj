(ns pallet.crate.openstack.glance
  (:require
    [pallet.actions :refer [package]]
    [pallet.api :as api]
    [pallet.crate :refer [defplan]]
    [pallet.crate.openstack.core :as core
     :refer [restart-services template-file]]
    [pallet.crate.mysql :as mysql]))

(defplan configure [{:keys [user password] :as credentials} mysql-root-pass]
  (mysql/create-user user password "root" mysql-root-pass)
  (mysql/create-database "glance" "root" mysql-root-pass)
  (template-file "etc/glance/glance-api-paste.ini" credentials "restart-glance")
  (template-file
    "etc/glance/glance-registry-paste.ini" credentials "restart-glance")
  (template-file "etc/glance/glance-api.conf" credentials "restart-glance")
  (template-file "etc/glance/glance-registry.conf" credentials "restart-glance")
  (restart-services :flag "restart-glance" "glance-api" "glance-registry"))

(defn server-spec [{:keys [glance mysql-root-pass] :as settings}]
  (api/server-spec
    :phases
    {:install (api/plan-fn (package "glance"))
     :configure (api/plan-fn (configure glance mysql-root-pass))}
    :extends [(core/server-spec settings)]))
