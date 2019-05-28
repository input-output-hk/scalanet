#!/bin/bash

KEY_STORE=keystore
KEY_STORE_PWD=password
VALIDITY=365

rm -f ${KEY_STORE}.p12 ${KEY_STORE}.jks

echo "creating alice's key and certificate..."
keytool -genkeypair -alias alice -keyalg EC -dname 'C=UK,L=London,O=IOHK,OU=Atala,CN=scalanet' \
        -validity ${VALIDITY} -keypass ${KEY_STORE_PWD} -keystore ${KEY_STORE}.jks -storepass ${KEY_STORE_PWD}

echo "creating bob's key and certificate..."
keytool -genkeypair -alias bob -keyalg EC -dname 'C=UK,L=London,O=IOHK,OU=Atala,CN=scalanet' \
        -validity ${VALIDITY} -keypass ${KEY_STORE_PWD} -keystore ${KEY_STORE}.jks -storepass ${KEY_STORE_PWD}

keytool -importkeystore -srckeystore ${KEY_STORE}.jks -srcstorepass ${KEY_STORE_PWD} -destkeystore ${KEY_STORE}.p12 -deststoretype pkcs12 -deststorepass ${KEY_STORE_PWD}
