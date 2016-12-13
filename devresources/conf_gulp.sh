#!/bin/sh

HOST=localhost
PORT=10555

[ $# -eq 2 ] || {
    echo "usage: $0 username password"
    exit 1
}

dir=$(dirname "$0")
username=$1
password=$2

$dir/configure.sh '[:boards "gulp" :username]' "\"$username\""
echo
$dir/configure.sh '[:boards "gulp" :password]' "\"$password\""
