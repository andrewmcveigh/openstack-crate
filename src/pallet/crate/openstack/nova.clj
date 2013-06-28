(ns pallet.crate.openstack.nova
  (:require
    [pallet.actions
     :refer [exec-script packages]]
    [pallet.api :as api]
    [pallet.crate :refer [defplan]]
    [pallet.crate.openstack.core :as core
     :refer [restart-services template-file]]
    [pallet.crate.mysql :as mysql]))

(defplan configure-kvm []
  (template-file "etc/libvirt/qemu.conf" nil "restart-kvm")
  (exec-script "virsh net-destroy default")
  (exec-script "virsh net-undefine default")
  (template-file "etc/libvirt/libvirtd.conf" nil "restart-kvm")
  (template-file "etc/init/libvirt-bin.conf" nil "restart-kvm")
  (template-file "etc/default/libvirt-bin" nil "restart-kvm")
  (restart-services :flag "restart-kvm" "dbus" "libvirt-bin"))

(defplan configure-nova [{{:keys [user password] :as nova} :nova
                          {:keys [quantum-user quantum-password]} :quantum
                          {:keys [internal-ip]} :interfaces
                          :keys [mysql-root-pass]}]
  (mysql/create-user user password "root" mysql-root-pass)
  (mysql/create-database "nova" "root" mysql-root-pass)
  (let [values (assoc nova
                      :quantum-user quantum-user
                      :quantum-password quantum-password
                      :internal-ip internal-ip)]
    (template-file "etc/nova/api-paste.ini" values "restart-nova")
    (template-file "etc/nova/nova.conf" values "restart-nova")
    (template-file "etc/nova/nova-compute.conf" nil "restart-nova"))
  (exec-script "nova-manage db sync")
  (restart-services :flag "restart-nova"
                    "nova-api" "nova-cert" "nova-compute" "nova-conductor"
                    "nova-consoleauth" "nova-novncproxy" "nova-scheduler"))

(defn server-spec [settings]
  (api/server-spec
    :phases {:install (api/plan-fn
                        (packages :aptitude ["kvm" "libvirt-bin" "pm-utils"])
                        (packages :aptitude
                                  ["nova-api" "nova-cert" "novnc"
                                   "nova-consoleauth" "nova-scheduler"
                                   "nova-novncproxy" "nova-doc"
                                   "nova-conductor" "nova-compute-kvm"]))
             :configure (api/plan-fn
                          (configure-kvm)
                          (configure-nova settings))}
    :extends [(core/server-spec settings)]))
