#!/bin/sh

HOST=localhost
PORT=10555

[ $# -eq 3 ] || {
    echo "usage: $0 board id file"
    exit 1
}

dir=$(dirname "$0")
token=$(${dir}/authenticate.sh | tail -1)
board=$1
id=$2
file=$3

exec curl -i -X POST -d "@$file" -H "Content-type: text/html" -H "Authorization: Bearer $token" "http://$HOST:$PORT/api/profile/$board/$id"
