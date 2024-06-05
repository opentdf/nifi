#!/bin/bash

TRUSTSTORE_PASSWORD=password

certDir="$(pwd)/truststore"

echo "import certs from $certDir"

for filename in $certDir/*.crt; do
  echo "import $filename into truststore"
  filelocal=$(basename ${filename})
  docker run -v $(pwd)/truststore:/keys  \
      openjdk:latest keytool \
      -import -trustcacerts \
      -alias $filelocal \
      -file keys/$filelocal \
      -destkeystore keys/ca.jks \
      -noprompt \
      -deststorepass "$TRUSTSTORE_PASSWORD"
done

