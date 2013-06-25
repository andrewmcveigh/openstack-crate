(ns pallet.crate.openstack.nova
  (:require
    [clojure.string :as string]
    [pallet.actions
     :refer [exec-checked-script exec-script package-manager package packages
             plan-when remote-directory remote-file service]]
    [pallet.crate :refer [defplan]]
    [pallet.crate.openstack.core :refer [restart-services template-file]]
    [pallet.script.lib :as lib]
    )
  )

(defplan kvm []
  (packages :apt ["kvm" "libvirt-bin" "pm-utils"])
  (template-file "etc/libvirt/qemu.conf" nil "restart-kvm")
  (exec-script "virsh net-destroy default")
  (exec-script "virsh net-undefine default")
  (template-file "etc/libvirt/libvirtd.conf" nil "restart-kvm")
  (restart-services :flag "restart-kvm" "dbus" "libvirt-bin")
  )
