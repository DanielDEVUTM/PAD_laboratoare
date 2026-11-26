#!/bin/sh
# Lets the machines of the private subnet reach the internet through this one.
#   nat.sh <private subnet cidr> <vpc cidr>
# Safe to run repeatedly. Runs after Docker, which blocks forwarded traffic by default.
set -eu
src=$1
vpc=$2
sysctl -q -w net.ipv4.ip_forward=1
iptables -t nat -C POSTROUTING -s "$src" ! -d "$vpc" -j MASQUERADE 2>/dev/null \
  || iptables -t nat -A POSTROUTING -s "$src" ! -d "$vpc" -j MASQUERADE
# DOCKER-USER is the chain Docker leaves for our own forwarding rules
iptables -C DOCKER-USER -s "$src" -j ACCEPT 2>/dev/null \
  || iptables -I DOCKER-USER -s "$src" -j ACCEPT
iptables -C DOCKER-USER -d "$src" -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT 2>/dev/null \
  || iptables -I DOCKER-USER -d "$src" -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT
