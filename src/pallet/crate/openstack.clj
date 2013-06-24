(ns pallet.crate.openstack
  (:require
    [clojure.string :as string]
    [pallet.actions
     :refer [exec-checked-script exec-script package-manager package packages
             plan-when remote-directory remote-file service]]
    [pallet.crate :refer [defplan]]
    [pallet.crate.mysql :as mysql]
    [pallet.script.lib :as lib]
    )
  )

(defplan init-packages []
  (package-manager :update)
  (packages :apt ["ubuntu-cloud-keyring" "python-software-properties"
                  "software-properties-common" "python-keyring"])
  (remote-file "/etc/apt/sources.list.d/openstack-grizzly.list"
               :content "deb http://ubuntu-cloud.archive.canonical.com/ubuntu precise-updates/grizzly main")
  (package-manager :update)
  (package-manager :upgrade)
  (exec-script "apt-get dist-upgrade"))

(def interface-snip
  "
auto %1$s
iface %1$s inet static
address %2$s
netmask %3$s")

(defn interface-str [[iface & {:keys [address netmask gateway dns-nameservers] :as opts}]]
  (let [s (format interface-snip iface address netmask)
        s (if gateway
            (str s "\ngateway " gateway)
            s)]
    (if dns-nameservers
      (str s "\ndns-nameservers "
           (if (string? dns-nameservers)
             dns-nameservers
             (string/join " " dns-nameservers)))
      s)))

(defn restart-network-interfaces [& {:keys [if-flag]}]
  (plan-when (lib/flag? (keyword if-flag)) 
    (exec-checked-script "interfaces: up->down->up" "ifdown -a; ifup -a")))

(defplan networking [interfaces]
  (remote-file "/etc/network/interfaces"
               :template "etc/network/interfaces"
               :values {:interfaces
                        (string/join \newline (map interface-str interfaces))}
               :flag-on-changed "restart-network")
  (restart-network-interfaces :if-flag "restart-network"))

(def ^:dynamic *mysql-root-pass* nil)
(def ^:dynamic *internal-ip* nil)
(def ^:dynamic *external-ip* nil)
(def ^:dynamic *admin-pass* nil)

(defplan mysql-install []
  (mysql/mysql-server *mysql-root-pass*)
  (package "python-mysqldb")
  (remote-file "/etc/mysql/my.cnf"
               :local-file "etc/mysql/my.cnf"
               :flag-on-changed "restart-mysql")
  (service "mysql"
          :action :restart
          :if-flag "restart-mysql"))

(defplan export-creds []
  (let [export (format
                 "export OS_TENANT_NAME=admin
                 export OS_USERNAME=admin
                 export OS_PASSWORD=%s 
                 export OS_AUTH_URL=\"http://%s:5000/v2.0/\""
                 *admin-pass*
                 *external-ip*)]
    (exec-script ~export)))

(defplan keystone-install [user password]
  (package "keystone")
  (mysql/create-user user password "root" *mysql-root-pass*)
  (mysql/create-database "keystone" user *mysql-root-pass*)
  (let [cmd "sed -i 's|^connection = .*$| connection = mysql://%s:%s@%s/keystone|g' /etc/keystone/keystone.conf"
        cmd (format cmd user password *internal-ip*)]
    (exec-script ~cmd))
  (service "keystone" :action :restart)
  (exec-script "keystone-manage db_sync")
  (remote-file "/tmp/keystone_basic.sh"
               :template "scripts/keystone_basic.sh"
               :values {:internal-ip *internal-ip*}
               :owner "root"
               :group "root"
               :mode "0755")
  (remote-file "/tmp/keystone_endpoint_basic.sh"
               :template "scripts/keystone_endpoint_basic.sh"
               :values {:internal-ip *internal-ip*
                        :external-ip *external-ip*
                        :keystone-user user
                        :keystone-password password}
               :owner "root"
               :group "root"
               :mode "0755")
  (exec-script "sh /tmp/keystone_basic.sh")
  (exec-script "sh /tmp/keystone_endpoint_basic.sh")
  (exec-script "keystone user-list"))

(defplan glance-install []
  (package "glance")
  (mysql/create-user user password "root" *mysql-root-pass*)
  (mysql/create-database "glance" user *mysql-root-pass*)

  )

(defplan install [& {:keys [interfaces admin-pass mysql-root-pass] :as opts}]
  (letfn [(iface-address [iface interfaces]
            (-> (filter (comp #{iface} first) interfaces)
                first second :address))]
    (binding [*mysql-root-pass* mysql-root-pass
              *external-ip* (iface-address "eth0" interfaces)
              *internal-ip* (iface-address "eth1" interfaces)
              *admin-pass* admin-pass]
      (init-packages)
      (networking interfaces) 
      (mysql-install mysql-root-pass) 
      (packages :apt ["rabbitmq-server" "ntp" "vlan" "bridge-utils"]) 
      (exec-script "sed -i 's/#net.ipv4.ip_forward=1/net.ipv4.ip_forward=1/' /etc/sysctl.conf") 
      (exec-script "sysctl net.ipv4.ip_forward=1")
      (keystone-install "keystone" admin-pass)
      )) 
  )
