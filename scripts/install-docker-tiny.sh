#!/usr/bin/env bash
set -euo pipefail

if (( EUID != 0 )); then
  echo "Run this script with sudo on the VPS" >&2
  exit 1
fi

# Docker's Ubuntu apt repository instructions, limited to the tested VPS OS.
source /etc/os-release
if [[ "$ID" != ubuntu || "$VERSION_CODENAME" != noble || "$(dpkg --print-architecture)" != amd64 ]]; then
  echo "Expected Ubuntu 24.04 (noble) amd64" >&2
  exit 1
fi

if command -v docker >/dev/null && docker compose version >/dev/null 2>&1; then
  echo "Docker Engine and Compose are already installed"
  exit 0
fi

for package in docker.io docker-compose docker-compose-v2 docker-doc docker-buildx podman-docker containerd runc; do
  if dpkg-query -W -f='${Status}' "$package" 2>/dev/null | grep -q 'install ok installed'; then
    echo "Conflicting package $package is installed; review it before installing Docker Engine" >&2
    exit 1
  fi
done

backup_dir="/home/vpsadmin/insightops-agent/.runtime/firewall-before-docker"
install -d -m 0700 "$backup_dir"
iptables-save > "$backup_dir/iptables.v4"
ip6tables-save > "$backup_dir/iptables.v6"
ufw status verbose > "$backup_dir/ufw-status.txt" || true
chmod 600 "$backup_dir"/*

apt-get update
apt-get install -y ca-certificates curl
install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
chmod a+r /etc/apt/keyrings/docker.asc

cat > /etc/apt/sources.list.d/docker.sources <<'SOURCES'
Types: deb
URIs: https://download.docker.com/linux/ubuntu
Suites: noble
Components: stable
Architectures: amd64
Signed-By: /etc/apt/keyrings/docker.asc
SOURCES

apt-get update
apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
systemctl enable --now docker
docker version --format '{{.Server.Version}}'
docker compose version
