#!/bin/sh

set -x

# Generate aes256 encrypted private key
openssl genrsa -aes256 -out auth_privkey.pem 2048

# Generate public key from previously created private key.
openssl rsa -pubout -in auth_privkey.pem -out auth_pubkey.pem
