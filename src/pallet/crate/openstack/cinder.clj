(ns pallet.crate.openstack.cinder
  (:require
    [clojure.string :as string]
    [pallet.actions
     :refer [exec-checked-script exec-script package-manager package packages
             plan-when remote-directory remote-file service]]
    [pallet.api :as api]
    [pallet.crate :refer [defplan]]
    [pallet.crate.openstack.core
     :refer [*mysql-root-pass* *internal-ip* *external-ip* *admin-pass*
             restart-services template-file]]
    [pallet.crate.mysql :as mysql]))

(defplan install [{[user password] :cinder :as credentials}]
  (packages :apt ["cinder-api" "cinder-scheduler" "cinder-volume" "iscsitarget"
                  "open-iscsi" "iscsitarget-dkms"])
  (remote-file "/etc/default/iscsitarget"
               :content "ISCSITARGET_ENABLE=true"
               :flag-on-changed "restart-iscsi")
  (restart-services :flag "restart-iscsi" "iscsitarget" "open-iscsi")
  (mysql/create-user user password "root" *mysql-root-pass*)
  (mysql/create-database "cinder" user *mysql-root-pass*)
  (let [values {:user user :password password
                :internal-ip *internal-ip* :external-ip *external-ip*}]
    (template-file "etc/cinder/api-paste.conf" values "restart-cinder")
    (template-file "etc/cinder/cinder.conf" values "restart-cinder")
    (exec-script "cinder-manage db sync")
    (exec-script "dd if=/dev/zero of=cinder-volumes bs=1 count=0 seek=2G")
    (exec-script "losetup /dev/loop2 cinder-volumes")
    (exec-script "echo \"n
p
1
ENTER
ENTER
t
8e
w
\" | fdisk /dev/loop2")
    (exec-script "pvcreate /dev/loop2")
    (exec-script "vgcreate cinder-volumes /dev/loop2"))
  (restart-services :flag "restart-cinder"
                    "cinder-api" "cinder-scheduler" "cinder-volume"))

(defn server-spec []
  (api/server-spec
    :phases {:install install}))
