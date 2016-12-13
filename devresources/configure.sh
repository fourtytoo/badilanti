#!/bin/sh

HOST=localhost
PORT=10555

[ $# -eq 2 ] || {
    echo "usage: $0 path val"
    echo "examples: $0 '[:boards \"gulp\" :username]' '\"guest\"'" 
    echo "          $0 '[:boards \"gulp\" :password]' '\"bar\"'" 
    exit 1
}

dir=$(dirname "$0")
token=$(${dir}/authenticate.sh | tail -1)
path=$1
value=$2

exec curl -i -X POST -d "{:path $path, :value $value}" -H "Content-type: application/edn" -H "Authorization: Bearer $token" http://$HOST:$PORT/api/configure
