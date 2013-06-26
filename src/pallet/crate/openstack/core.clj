(ns pallet.crate.openstack.core
  (:require
    [clojure.string :as string]
    [pallet.actions
     :refer [exec-checked-script exec-script package package-manager packages
             plan-when remote-file service]]
    [pallet.api :as api]
    [pallet.crate :refer [defplan]]
    [pallet.crate.mysql :as mysql]
    [pallet.script.lib :as lib]))

(defn template-file [resource values & [flag]]
  (apply remote-file
         (str \/ resource) 
         `((if values
             ~@[:template resource :values values]
             ~@[:local-file resource]) 
         (when flag ~@[:flag-on-changed flag]))))

(defn restart-services [& [flag? flag & more :as services]]
  (if (= :flag flag?)
    (doseq [svc more]
      (service svc :action :restart :if-flag flag))
    (doseq [svc services]
      (service svc :action :restart))))

(defplan init-packages []
  (package-manager :update)
  (packages :apt ["ubuntu-cloud-keyring" "python-software-properties"
                  "software-properties-common" "python-keyring"])
  (remote-file
    "/etc/apt/sources.list.d/openstack-grizzly.list"
    :content (str "deb http://ubuntu-cloud.archive.canonical.com/ubuntu"
                  "precise-updates/grizzly main"))
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

(defplan mysql-install [mysql-root-pass]
  (mysql/mysql-server mysql-root-pass)
  (package "python-mysqldb")
  (remote-file "/etc/mysql/my.cnf"
               :local-file "etc/mysql/my.cnf"
               :flag-on-changed "restart-mysql")
  (service "mysql"
          :action :restart
          :if-flag "restart-mysql"))


(defn server-spec [{:keys [interfaces mysql-root-pass] :as settings}]
  (api/server-spec
    :phases
    {:install
     (api/plan-fn
       (init-packages)
       (networking interfaces)
       (mysql-install mysql-root-pass)
       (packages :apt ["rabbitmq-server" "ntp" "vlan" "bridge-utils"])
       (exec-script "sed -i 's/#net.ipv4.ip_forward=1/net.ipv4.ip_forward=1/' /etc/sysctl.conf")
       (exec-script "sysctl net.ipv4.ip_forward=1"))}))
