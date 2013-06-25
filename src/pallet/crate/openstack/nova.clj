(ns pallet.crate.openstack.nova
  (:require
    [clojure.string :as string]
    [pallet.actions
     :refer [exec-checked-script exec-script package-manager package packages
             plan-when remote-directory remote-file service]]
    [pallet.crate :refer [defplan]]
    [pallet.crate.openstack.core
     :refer [*mysql-root-pass* *internal-ip* *external-ip* *admin-pass*
             restart-services template-file]]
    [pallet.crate.mysql :as mysql]
    [pallet.script.lib :as lib]
    )
  )

(defplan kvm []
  (packages :apt ["kvm" "libvirt-bin" "pm-utils"])
  (template-file "etc/libvirt/qemu.conf" nil "restart-kvm")
  (exec-script "virsh net-destroy default")
  (exec-script "virsh net-undefine default")
  (template-file "etc/libvirt/libvirtd.conf" nil "restart-kvm")
  (template-file "etc/init/libvirt-bin.conf" nil "restart-kvm")
  (template-file "etc/default/libvirt-bin" nil "restart-kvm")
  (restart-services :flag "restart-kvm" "dbus" "libvirt-bin"))

(defplan nova [user password]
  (packages :apt ["nova-api" "nova-cert" "novnc" "nova-consoleauth"
                  "nova-scheduler" "nova-novncproxy" "nova-doc"
                  "nova-conductor" "nova-compute-kvm"])
  (mysql/create-user user password "root" *mysql-root-pass*)
  (mysql/create-database "nova" user *mysql-root-pass*)
  (let [values {:user user :password password :internal-ip *internal-ip*}]
    (template-file "etc/nova/api-paste.ini" values "restart-nova")
    (template-file "etc/nova/nova.conf" values "restart-nova")
    (template-file "etc/nova/nova-compute.conf" nil "restart-nova"))
  (exec-script "nova-manage db sync")
  (restart-services :flag "restart-nova"
                    "nova-api" "nova-cert" "nova-compute" "nova-conductor"
                    "nova-consoleauth" "nova-novncproxy" "nova-scheduler"))
