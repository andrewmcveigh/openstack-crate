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
  "GRANT USAGE ON *.* TO '%1$s'@'%%' IDENTIFIED BY '%2$s'")

(defn log [& stuff]
  (doseq [s stuff]
    (spit "test.txt" s :append true)
    (spit "test.txt" \newline :append true)
    (spit "test.txt" \newline :append true)))

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
    (str "mysql-server-5.5 mysql-server-5.5/start_on_boot boolean " start-on-boot))
  (package "mysql-server"))

(template/deftemplate my-cnf-template [string]
  {{:path mysql-my-cnf 
    :owner "root" :mode "0440"}
   string})

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
