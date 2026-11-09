#!/bin/sh
set -eu

: "${REDIS_USER_SERVICE_PASSWORD:?Redis user-service password is required}"
: "${REDIS_GATEWAY_PASSWORD:?Redis gateway password is required}"

umask 077
acl_file=/tmp/users.acl

cat > "$acl_file" <<EOF
user default on nopass resetkeys resetchannels -@all +ping
user user-service on >${REDIS_USER_SERVICE_PASSWORD} resetchannels ~RT:* ~BL:* ~LF:* ~LK:* -@all +get +set +del +exists +incr +incrby +expire +pexpire +ttl +pttl +eval +evalsha +ping +hello +client|setname +client|setinfo +info
user gateway on >${REDIS_GATEWAY_PASSWORD} resetchannels %R~BL:* -@all +exists +ping +hello +client|setname +client|setinfo +info
EOF

chown redis:redis "$acl_file"

exec /usr/local/bin/docker-entrypoint.sh \
  redis-server --aclfile "$acl_file"