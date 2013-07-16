(ns pallet.crate.openstack.glance
  (:require
    [pallet.actions :refer [exec-script package]]
    [pallet.api :as api]
    [pallet.crate :refer [defplan]]
    [pallet.crate.openstack.core :as core
     :refer [restart-services template-file]]
    [pallet.crate.mysql :as mysql]))

(defplan configure [{{:keys [user password]} :glance
                     :keys [mysql-root-pass]
                     :as settings}]
  (mysql/create-user user password "root" mysql-root-pass)
  (mysql/create-database "glance" "root" mysql-root-pass)
  (mysql/grant "ALL" "glance.*" (format "'%s'@'%%'" user) "root" mysql-root-pass)
  (template-file "etc/glance/glance-api-paste.ini" settings "restart-glance")
  (template-file
    "etc/glance/glance-registry-paste.ini" settings "restart-glance")
  (template-file "etc/glance/glance-api.conf" settings "restart-glance")
  (template-file "etc/glance/glance-registry.conf" settings "restart-glance")
  (exec-script "glance-manage db_sync")
  (restart-services :flag "restart-glance" "glance-api" "glance-registry"))

(defn server-spec [settings]
  (api/server-spec
    :phases
    {:install (api/plan-fn (package "glance"))
     :configure (api/plan-fn (configure settings))}
    :extends [(core/server-spec settings)]))
