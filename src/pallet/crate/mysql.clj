(ns pallet.crate.mysql
  (:require
    [pallet.actions
     :as actions
     :refer (package package-manager packages exec-script service)]
    [pallet.crate :refer (defplan)]
    [pallet.core.session :refer (session)]
    [pallet.stevedore :as stevedore]
    [pallet.template :as template]
    [clojure.string :as string]))

(def mysql-my-cnf "/etc/mysql/my.cnf")

(defplan mysql-client []
  (packages [ "libmysqlclient15-dev" ]))

(defn- mysql-script*
  "MYSQL script invocation"
  [username password sql-script]
  (stevedore/script
   ("{\n" mysql "-u" ~username ~(str "--password=" password)
    ~(str "<<EOF\n" (string/replace sql-script "`" "\\`") "\nEOF\n}"))))

(def ^{:private true} sql-create-user
  ;CREATE USER '%1$s'@localhost IDENTIFIED BY '%2$s';
  "GRANT USAGE ON *.* TO '%1$s'@localhost IDENTIFIED BY '%2$s'")

(defn log [& stuff]
  (doseq [s stuff]
    (spit "test.txt" s :append true)
    (spit "test.txt" \newline :append true)
    (spit "test.txt" \newline :append true)))

;(require '[pallet.actions.direct.package :refer (add-scope configure-package-manager)])
;(require '[pallet.core.session :refer [os-family packager]])
;(require '[pallet.action-plan :refer [checked-commands]])
;(require '[pallet.script.lib :as lib])

;(alter-var-root #'pallet.stevedore/*script-language*
 ;(constantly :pallet.stevedore.bash/bash))
;(alter-var-root #'pallet.script/*script-context*
 ;(constantly [:aptitude]))

;(prn (lib/debconf-set-selections "test"))

;(alter-var-root
  ;#'pallet.actions.direct.package/package-manager*
  ;(constantly (fn package-manager*
                ;[session action & options]
                ;(let [packager (packager session)]
                  ;;(pallet.script/with-script-context [:ubuntu :aptitude]
                    ;(log (pr-str @#'pallet.script/*script-context*)) 
                    ;(log (apply lib/debconf-set-selections options)) 
                    ;(checked-commands
                      ;(format "package-manager %s %s" (name action) (string/join " " options))
                      ;(case action
                        ;:update (stevedore/script (apply ~lib/update-package-list ~options))
                        ;:upgrade (stevedore/script (~lib/upgrade-all-packages))
                        ;:list-installed (stevedore/script (~lib/list-installed-packages))
                        ;:add-scope (add-scope (apply hash-map options))
                        ;:multiverse (add-scope (apply hash-map :scope "multiverse" options))
                        ;:universe (add-scope (apply hash-map :scope "universe" options))
                        ;:debconf (if (#{:aptitude :apt} packager)
                                   ;(stevedore/script
                                     ;(apply ~lib/debconf-set-selections ~options)))
                        ;:configure (configure-package-manager session packager options)
                        ;(throw (IllegalArgumentException.
                                 ;(str action
                                      ;" is not a valid action for package-manager action")))))
                    ;;)
                  ;))))

(defplan mysql-server
  "Install mysql server from packages"
  [root-password & {:keys [start-on-boot] :or {start-on-boot true}}]
  (package-manager
    :debconf
    (str "mysql-server mysql-server/root_password password " root-password)
    (str "mysql-server mysql-server/root_password_again password " root-password)
    (str "mysql-server mysql-server/start_on_boot boolean " start-on-boot)
    (str "mysql-server-5.5 mysql-server/root_password password " root-password)
    (str "mysql-server-5.5 mysql-server/root_password_again password " root-password)
    (str "mysql-server-5.5 mysql-server/start_on_boot boolean " start-on-boot)
    (str "mysql-server-5.5 mysql-server-5.5/root_password password " root-password)
    (str "mysql-server-5.5 mysql-server-5.5/root_password_again password " root-password)
    (str "mysql-server-5.5 mysql-server-5.5/start_on_boot boolean " start-on-boot)
    )
  (package "mysql-server")
  ;(when->
  ;(= :yum (session/packager session))
  ;(when->
  ;start-on-boot
  ;(service/service "mysqld" :action :enable))
  ;(service/service "mysqld" :action :start)
  ;(exec-script/exec-checked-script
  ;"Set Root Password"
  ;(chain-or
  ;("/usr/bin/mysqladmin" -u root password (quoted ~root-password))
  ;(echo "Root password already set"))))
  )

(template/deftemplate my-cnf-template [string]
  {{:path mysql-my-cnf 
    :owner "root" :mode "0440"}
   string})

;(action/def-bash-action mysql-conf
  ;"my.cnf configuration file for mysql"
  ;[config]
  ;(template/apply-templates #(my-cnf-template %) [config]))

(defplan mysql-script
  "Execute a mysql script"
  [username password sql-script]
  (actions/exec-checked-script
   "MYSQL command"
   ~(mysql-script* username password sql-script)))

(defplan create-database [name username root-password]
  (mysql-script
    username root-password
    (format "CREATE DATABASE IF NOT EXISTS `%s`" name)))

(defplan create-user [user password username root-password]
  {:pre [(<= (count user) 16)]}
  (mysql-script
    username root-password
    (format sql-create-user user password)))

(defplan grant [privileges level user username root-password]
  (mysql-script
    username root-password
    (format "GRANT %s ON %s TO %s" privileges level user)))
