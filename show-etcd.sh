#!/usr/bin/env bash
set -xuo pipefail

seq 1 5 |
while read n; do
  sudo nixos-container run n$n -- systemctl status etcd
done
