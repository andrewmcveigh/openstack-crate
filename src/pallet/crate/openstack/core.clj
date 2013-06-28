(ns pallet.crate.openstack.core
  (:require
    [clojure.java.io :as io]
    [clojure.string :as string]
    [pallet.actions
     :refer [exec-checked-script exec-script package package-manager packages
             plan-when remote-file service]]
    [pallet.api :as api]
    [pallet.crate :refer [defplan target]]
    [pallet.crate.automated-admin-user :refer [automated-admin-user]]
    [pallet.crate.mysql :as mysql]
    [pallet.node :as node]
    [pallet.script.lib :as lib]))

(defn private-ip []
  (node/private-ip (target)))

(defn primary-ip []
  (node/primary-ip (target)))

(defn local-file [path & substitutions]
  (let [filename (apply format path substitutions)
        r (io/reader (io/resource filename))
        tmp (io/as-file (str "/tmp/" filename))]
    (.mkdirs (io/as-file (.getParent tmp)))
    (io/copy r tmp)
    (.getCanonicalPath tmp)))

(defn template-file [resource values & [flag]]
  (apply remote-file
         (str \/ resource)
         `(~@(if values
               [:template resource :values values]
               [:local-file (local-file resource)])
           ~@(when flag [:flag-on-changed flag]))))

(defn restart-services [& [flag? flag & more :as services]]
  (if (= :flag flag?)
    (doseq [svc more]
      (service svc :action :restart :if-flag flag))
    (doseq [svc services]
      (service svc :action :restart))))

(defplan debconf-grub []
  (package-manager
    :debconf 
    "grub-pc grub-pc/kopt_extracted boolean false"
    "grub-pc grub2/kfreebsd_cmdline string"
    "grub-pc grub2/device_map_regenerated note"
    "grub-pc grub-pc/install_devices multiselect /dev/sda " 
    "grub-pc grub-pc/postrm_purge_boot_grub boolean false"
    "grub-pc grub-pc/install_devices_failed_upgrade boolean true"
    "grub-pc grub2/linux_cmdline string"
    "grub-pc grub-pc/install_devices_empty boolean false"
    "grub-pc grub2/kfreebsd_cmdline_default string quiet"
    "grub-pc grub-pc/install_devices_failed boolean false"
    "grub-pc grub-pc/install_devices_disks_changed multiselect /dev/sda " 
    "grub-pc grub2/linux_cmdline_default string"
    "grub-pc grub-pc/chainload_from_menu.lst boolean true"
    "grub-pc grub-pc/hidden_timeout boolean false"
    "grub-pc grub-pc/mixed_legacy_and_grub2 boolean true"
    "grub-pc grub-pc/timeout string 2"))

(defplan bootstrap []
  ;(exec-script "export DEBIAN_FRONTEND=noninteractive")
  ;(package-manager :update)
  (packages :aptitude ["ubuntu-cloud-keyring" "python-software-properties"
                       "software-properties-common" "python-keyring"])
  (remote-file "/etc/apt/sources.list"
               :local-file (local-file "etc/apt/sources.list")
               :owner "root"
               :group "root"
               :mode "0644")
  (remote-file
    "/etc/apt/sources.list.d/openstack-grizzly.list"
    :content (str "deb http://ubuntu-cloud.archive.canonical.com/ubuntu "
                  "precise-updates/grizzly main"))
  (package-manager :update)
  (debconf-grub)
  (package-manager :upgrade)
  (debconf-grub)
  (exec-script "apt-get -y dist-upgrade"))

(def interface-snip
  "
auto %1$s
iface %1$s inet static
address %2$s
netmask %3$s")

(defn interface-str [[iface {:keys [address netmask gateway dns-nameservers]}]]
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

(defplan restart-network-interfaces [ifaces & {:keys [if-flag]}]
  (plan-when (lib/flag? (keyword if-flag))
    (doseq [iface ifaces]
      (exec-checked-script "interfaces: up->down->up"
                           (format "ifdown %1$s; ifup %1$s" iface)))))

(defplan configure-networking [interfaces]
  (remote-file "/etc/network/interfaces"
               :template "etc/network/interfaces"
               :values {:interfaces
                        (string/join \newline (map interface-str interfaces))}
               :flag-on-changed "restart-network")
  (restart-network-interfaces (filterv string? (map first interfaces))
                              :if-flag "restart-network"))

(defplan mysql-install [mysql-root-pass]
  (mysql/mysql-server mysql-root-pass)
  (package "python-mysqldb"))

(defplan mysql-configure []
  (remote-file "/etc/mysql/my.cnf"
               :local-file (local-file "etc/mysql/my.cnf")
               :flag-on-changed "restart-mysql")
  (service "mysql" :action :restart :if-flag "restart-mysql"))

(defn server-spec [{:keys [interfaces mysql-root-pass] :as settings}]
  (api/server-spec
    :phases
    {:bootstrap (api/plan-fn
                  (bootstrap)
                  (automated-admin-user))  
     :install
     (api/plan-fn
       (package-manager :update)
       (mysql-install mysql-root-pass)
       (packages :aptitude ["rabbitmq-server" "ntp" "vlan" "bridge-utils"]))
     :configure
     (api/plan-fn
       (configure-networking interfaces)
       (exec-script "sed -i 's/#net.ipv4.ip_forward=1/net.ipv4.ip_forward=1/' /etc/sysctl.conf")
       (exec-script "sysctl net.ipv4.ip_forward=1")
       (mysql-configure))}))
