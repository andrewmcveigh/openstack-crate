(ns pallet.crate.openstack
  (:require
    [pallet.api :as api]
    [pallet.crate.openstack.core :as core]
    [pallet.crate.openstack.cinder :as cinder]
    [pallet.crate.openstack.glance :as glance]
    [pallet.crate.openstack.horizon :as horizon]
    [pallet.crate.openstack.keystone :as keystone]
    [pallet.crate.openstack.nova :as nova]
    [pallet.crate.openstack.quantum :as quantum]))

(defn server-spec
  "The openstack crate uses the following settings:

 - :cinder
a credentials map for cinder {:user \"cinder-user\" :password \"pass\"}

 - :glance
a credentials map for glance {:user \"glance-user\" :password \"pass\"}

 - :keystone
a credentials map for keystone {:user \"keystone-user\" :password \"pass\"}

 - :nova
a credentials map for nova {:user \"nova-user\" :password \"pass\"}

 - :quantum
a credentials map for quantum {:user \"quantum-user\" :password \"pass\"}

## Credentials

A credentials map can also contain the address of the service. If not
specified, the target-node's primary-ip/private-ip will be used.
E.G.,

 - :quantum {:primary-ip \"192.168.50.101\"
             :private-ip \"10.10.51.101\"
             :user \"quantum-user\"
             :password \"quantum-password\"}

 - :interfaces
a interface to settings map:

{\"eth0\" {:address \"192.168.50.101\"
           :netmask \"255.255.255.0\"
           :gateway \"192.168.50.1\"}
 \"eth1\" ... }

 - :admin-pass
the overall admin password

 - :mysql-root-pass
what to set the mysql root user password"
  [& {:as settings}]
  (api/server-spec
    :extends [(core/server-spec settings)
              (keystone/server-spec settings)
              (glance/server-spec settings)
              (quantum/server-spec settings)
              (nova/server-spec settings)
              (cinder/server-spec settings)
              (horizon/server-spec settings)]))
