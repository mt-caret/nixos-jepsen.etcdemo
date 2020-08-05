# nixos-jepsen.etcdemo

Working code for the
[Jepsen Tutorial](https://github.com/jepsen-io/jepsen/blob/2142ec75ca0a241b0e10fe0c6147067b1142a4ac/doc/tutorial/index.md),
slightly tweaked to work on NixOS containers.

## running nixos-jepsen.etcdemo

First, import [etcd-cluster.nix](./etcd-cluster.nix) from your NixOS
configuration, run `sudo nixos-rebuild switch`, and then execute the following
command to run the register test:

```shell
nix-shell -p leiningen --run "lein run test --time-limit 30 --concurrency 100 -w register -q"
```
