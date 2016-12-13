#!/bin/sh

HOST=localhost
PORT=10555

username=${1:-guest}
password=${2:-guest}

exec curl -s -i -X POST -d "{\"username\":\"$username\", \"password\":\"$password\"}" -H "Content-type: application/json" http://$HOST:$PORT/authenticate
