Install and configure openstack.

### Dependency Information

```clj
:dependencies [[com.andrewmcveigh/openstack-crate "0.8.0-SNAPSHOT"]]
```

## Server Spec

The openstack crate defines the `server-spec` function, that takes settings
arguments returning a server-spec for installing openstack. You can use this in
a `group-spec` or `server-spec`.

```clj
(group-spec "openstack-all-services-node"
  :extends [(pallet.crate.openstack/server-spec
              ... settings ...)])
```

The default server-spec uses the `:bootstrap` and `:install` phase, so remember
to add the `:bootstrap` and `:install` phases when lifting or converging a node
including this crate.

## Settings

The openstack crate uses the following settings:

`:cinder`
a credentials map for cinder `{:user "cinder-user" :password "pass"}`

`:glance`
a credentials map for glance `{:user "glance-user" :password "pass"}`

`:keystone`
a credentials map for keystone `{:user "keystone-user" :password "pass"}`

`:nova`
a credentials map for nova `{:user "nova-user" :password "pass"}`

`:quantum`
a credentials map for quantum `{:user "quantum-user" :password "pass"}`

`:interfaces`
a interface to settings map:
```clj
{"eth0" {:address "192.168.50.101"
         :netmask "255.255.255.0"
         :gateway "192.168.50.1"}
 "eth1" ... }
```

`:admin-pass`
the overall admin password

`:mysql-root-pass`
what to set the mysql root user password

## License

Licensed under [EPL](http://www.eclipse.org/legal/epl-v10.html)

Copyright 2013 Andrew Mcveigh.
