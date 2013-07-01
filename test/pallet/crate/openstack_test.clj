(ns pallet.crate.openstack-test
  (:require
    [clojure.test :refer :all]
    [pallet.crate :as crate]
    [pallet.crate.openstack :as openstack]
    [pallet.core.session :as session :refer [session with-session]]
    [pallet.node :as node]
    [pallet.compute.node-list :refer [make-node]]
    ))

(deftest assoc-ip-addresses-test []
  (let [m (openstack/assoc-ip-addresses
            {:cinder {:user "cinder" :password "cinder"}
             :glance {:user "glance" :password "glance"}
             :keystone {:user "keystone" :password "keystone"}
             :nova {:user "nova" :password "nova"}
             :quantum {:user "quantum" :password "quantum"}
             :interfaces
             {"eth0" {:address "10.10.100.51"
                      :netmask "255.255.255.0"}
              "eth1" {:address "192.168.78.151"
                      :netmask "255.255.255.0"
                      :gateway "192.168.78.254"
                      :dns-nameservers ["192.168.78.217" "8.8.8.8"]}}
             :admin-pass "admin"
             :mysql-root-pass "mysql"})]
    (is (= "192.168.78.151" (-> m :interfaces :internal-ip)))
    (is (= "10.10.100.51" (-> m :interfaces :external-ip)))))

(comment

  (run-tests)

  )
(require 'pallet.actions)
(require 'pallet.template)

(with-session {:server {:node (make-node "node-1" "openstack" "192.168.78.151" :ubuntu
                                         :private-ip "10.10.100.51"
                                         :os-version "12.04")
                        :image {:os-family :ubuntu}
                        }}
  (pallet.template/interpolate-template
    "etc/cinder/api-paste.ini"
    {:user "tsetes" :password "tshsths"}
    (session)
    )
  )
