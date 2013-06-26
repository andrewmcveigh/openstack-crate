(ns pallet.crate.openstack.cinder
  (:require
    [pallet.actions :refer [exec-script packages remote-file]]
    [pallet.api :as api]
    [pallet.crate :refer [defplan]]
    [pallet.crate.openstack.core :as core
     :refer [restart-services template-file]]
    [pallet.crate.mysql :as mysql]))

(defplan install [{{:keys [user password] :as cinder} :cinder
                   {:keys [internal-ip external-ip]} :interfaces
                   :keys [mysql-root-pass]}]
  (packages :apt ["cinder-api" "cinder-scheduler" "cinder-volume" "iscsitarget"
                  "open-iscsi" "iscsitarget-dkms"])
  (remote-file "/etc/default/iscsitarget"
               :content "ISCSITARGET_ENABLE=true"
               :flag-on-changed "restart-iscsi")
  (restart-services :flag "restart-iscsi" "iscsitarget" "open-iscsi")
  (mysql/create-user user password "root" mysql-root-pass)
  (mysql/create-database "cinder" user mysql-root-pass)
  (let [values (assoc cinder :internal-ip internal-ip :external-ip external-ip)]
    (template-file "etc/cinder/api-paste.conf" values "restart-cinder")
    (template-file "etc/cinder/cinder.conf" values "restart-cinder")
    (exec-script "cinder-manage db sync")
    (exec-script "dd if=/dev/zero of=cinder-volumes bs=1 count=0 seek=2G")
    (exec-script "losetup /dev/loop2 cinder-volumes")
    (exec-script "echo \"n\np\n1\n\n\nt\n8e\nw\n\" | fdisk /dev/loop2")
    (exec-script "pvcreate /dev/loop2")
    (exec-script "vgcreate cinder-volumes /dev/loop2"))
  (restart-services :flag "restart-cinder"
                    "cinder-api" "cinder-scheduler" "cinder-volume"))

(defn server-spec [settings]
  (api/server-spec
    :phases {:install (api/plan-fn (install settings))}
    :extends [(core/server-spec settings)]))
