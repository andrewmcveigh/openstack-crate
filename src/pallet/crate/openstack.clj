(ns pallet.crate.openstack
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
    [pallet.crate.openstack.cinder :as cinder]
    [pallet.crate.openstack.horizon :as horizon]
    [pallet.crate.openstack.nova :as nova]
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

(defplan glance-install [user password]
  (let [values {:user user :password password :internal-ip *internal-ip*}]
    (package "glance")
    (mysql/create-user user password "root" *mysql-root-pass*)
    (mysql/create-database "glance" user *mysql-root-pass*)
    (remote-file "/etc/glance/glance-api-paste.ini"
                 :template "etc/glance/glance-api-paste.ini"
                 :flag-on-changed "restart-glance"
                 :values values)
    (remote-file "/etc/glance/glance-registry-paste.ini"
                 :template "etc/glance/glance-registry-paste.ini"
                 :flag-on-changed "restart-glance"
                 :values values)
    (remote-file "/etc/glance/glance-api.conf"
                 :template "etc/glance/glance-api.conf"
                 :flag-on-changed "restart-glance"
                 :values values)
    (remote-file "/etc/glance/glance-registry.conf"
                 :template "etc/glance/glance-registry.conf"
                 :flag-on-changed "restart-glance"
                 :values values)
    (service "glance-api" :action :restart :if-flag "restart-glance")
    (service "glance-registry" :action :restart :if-flag "restart-glance")))

(defplan open-vswitch-install [interfaces & flags]
  (let [flags (set flags)
        eth1 "
# VM internet Access
auto eth1
iface eth1 inet manual
up ifconfig $IFACE 0.0.0.0 up
up ip link set $IFACE promisc on
down ip link set $IFACE promisc off
down ifconfig $IFACE down
        "]
    (packages :apt ["openvswitch-switch" "openvswitch-datapath-dkms"])
    (exec-script "ovs-vsctl add-br br-int")
    (exec-script "ovs-vsctl add-br br-ext")
    (when (:br-ext flags)
      (let [s (map interface-str
                   (concat (remove (comp #{"eth1"} first) interfaces)
                           (assoc-in (vec (filter (comp #{"eth1"} first)
                                                  interfaces))
                                     [0 0] "br-ext")))
            s (str (string/join \newline s) eth1)]
        (remote-file "/etc/network/interfaces"
                     :template "etc/network/interfaces"
                     :values {:interfaces s}
                     :flag-on-changed "restart-network")
        (restart-network-interfaces :if-flag "restart-network"))
      (exec-script "ovs-vsctl add-port br-ex eth1"))))

(defplan quantum-install [user password]
  (packages :apt ["quantum-server" "quantum-plugin-openvswitch"
                  "quantum-plugin-openvswitch-agent" "dnsmasq"
                  "quantum-dhcp-agent""quantum-l3-agent"])
  (mysql/create-user user password "root" *mysql-root-pass*)
  (mysql/create-database "quantum" user *mysql-root-pass*)
  (let [values {:user user :password password :internal-ip *internal-ip*}]
    (template-file "etc/quantum/api-paste.ini" values "restart-quantum")
    (template-file "etc/quantum/plugins/openvswitch/ovs_quantum_plugin.ini"
                   values
                   "restart-quantum")
    (template-file "etc/quantum/metadata_agent.ini" values "restart-quantum")
    (template-file "etc/quantum/quantum.conf" values "restart-quantum")
    (restart-services :flag "restart-quantum"
                      "quantum-dhcp-agent" "quantum-l3-agent"
                      "quantum-metadata-agent"
                      "quantum-plugin-openvswitch-agent" "quantum-server"
                      "dnsmasq")))


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

(defn server-spec []
  (api/server-spec
    :extends [(horizon/server-spec)
              (cinder/server-spec)
              (nova/server-spec)
              ] 
    )
  )
