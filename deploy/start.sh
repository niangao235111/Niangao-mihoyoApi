#!/usr/bin/env bash
set -euo pipefail
APP_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$APP_DIR"
if [[ ! -f config/application-production.yaml ]]; then
  echo 'Missing config/application-production.yaml; copy and edit the example first.' >&2
  exit 1
fi
if [[ -f config/backend.env ]]; then
  set -a
  source config/backend.env
  set +a
fi
exec java -Xms128m -Xmx512m -jar app.jar --spring.profiles.active=production
