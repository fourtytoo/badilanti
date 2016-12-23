#!/bin/sh

HOST=localhost
PORT=10555

[ $# -eq 2 ] || {
    echo "usage: $0 board id"
    exit 1
}

dir=$(dirname "$0")
token=$(${dir}/authenticate.sh | tail -1)
board=$1
id=$2

exec curl -i -X DELETE -H "Authorization: Bearer $token" "http://$HOST:$PORT/api/profile/$board/$id"
