(ns pallet.crate.openstack.core
  (:require
    [pallet.actions :refer [remote-file service]]))

(def ^:dynamic *mysql-root-pass* nil)
(def ^:dynamic *internal-ip* nil)
(def ^:dynamic *external-ip* nil)
(def ^:dynamic *admin-pass* nil)

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
