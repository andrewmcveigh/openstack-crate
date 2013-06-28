(ns pallet.crate.openstack.keystone
  (:require
    [clojure.string :as string]
    [pallet.actions
     :refer [exec-script package remote-file service]]
    [pallet.api :as api]
    [pallet.crate :refer [defplan]]
    [pallet.crate.mysql :as mysql]
    [pallet.crate.openstack.core :as core]))

(defplan configure [{{:keys [internal-ip external-ip]} :interfaces
                     {:keys [user password] :as keystone} :keystone
                     :keys [mysql-root-pass]}]
  (mysql/create-user user password "root" mysql-root-pass)
  (mysql/create-database "keystone" "root" mysql-root-pass)
  (let [cmd "sed -i 's|^connection = .*$|connection = mysql://%s:%s@%s/keystone|g' /etc/keystone/keystone.conf"
        cmd (format cmd user password internal-ip)
        values (assoc keystone
                      :internal-ip internal-ip
                      :external-ip external-ip)]
    (exec-script ~cmd)
    (service "keystone" :action :restart)
    (exec-script "keystone-manage db_sync")
    (remote-file "/tmp/keystone_basic.sh"
                 :template "scripts/keystone_basic.sh"
                 :values values
                 :owner "root"
                 :group "root"
                 :mode "0755")
    (remote-file "/tmp/keystone_endpoint_basic.sh"
                 :template "scripts/keystone_endpoint_basic.sh"
                 :values values
                 :owner "root"
                 :group "root"
                 :mode "0755")
    (exec-script "sh /tmp/keystone_basic.sh")
    (exec-script "sh /tmp/keystone_endpoint_basic.sh")
    (exec-script "keystone user-list")))

(defplan export-creds [admin-pass external-ip]
  (let [export (format
                 "export OS_TENANT_NAME=admin
                  export OS_USERNAME=admin
                  export OS_PASSWORD=%s
                  export OS_AUTH_URL=\"http://%s:5000/v2.0/\""
                 admin-pass
                 external-ip)]
    (exec-script ~export)))

(defn server-spec [{{external-ip :external-ip} :interfaces
                    admin-pass :admin-pass :as settings}]
  (api/server-spec
    :phases
    {:install (api/plan-fn (package "keystone"))
     :configure (api/plan-fn
                  (configure settings)
                  (export-creds admin-pass external-ip))}
    :extends [(core/server-spec settings)]))
