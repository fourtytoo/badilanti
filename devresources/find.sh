#!/bin/sh

HOST=localhost
PORT=10555

[ $# -eq 2 ] || {
    echo "usage: $0 query"
    exit 1
}

dir=$(dirname "$0")
token=$(${dir}/authenticate.sh | tail -1)
query=$1

curl -i -X GET -H "Authorization: Bearer $token" http://$HOST:$PORT/api/find\?query="$query"
