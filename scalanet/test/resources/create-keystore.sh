#!/bin/bash +x

KEY_STORE=keystore
KEY_STORE_PWD=password
VALIDITY=365

TRUST_STORE=truststore
TRUST_STORE_PWD=password

rm -f ${KEY_STORE}.p12 ${KEY_STORE}.jks ${TRUST_STORE}.jks ${TRUST_STORE}.p12


echo "creating root key and certificate..."
keytool -genkeypair -alias root -keyalg EC -dname 'C=UK,L=London,O=IOHK,OU=Atala,CN=cf-root' \
        -ext BC=ca:true -validity ${VALIDITY} -keypass ${TRUST_STORE_PWD} -keystore ${TRUST_STORE}.jks -storepass ${TRUST_STORE_PWD}

echo "creating CA key and certificate..."
keytool -genkeypair -alias ca -keyalg EC -dname 'C=UK,L=London,O=IOHK,OU=Atala,CN=cf-ca' \
        -ext BC=ca:true -validity ${VALIDITY} -keypass ${TRUST_STORE_PWD} -keystore ${TRUST_STORE}.jks -storepass ${TRUST_STORE_PWD}
keytool -keystore ${TRUST_STORE}.jks -storepass ${TRUST_STORE_PWD} -certreq -alias ca | \
  keytool -keystore ${TRUST_STORE}.jks -storepass ${TRUST_STORE_PWD} -alias root -gencert -validity ${VALIDITY} -ext BC=0 -rfc | \
  keytool -alias ca -importcert -keystore ${TRUST_STORE}.jks -storepass ${TRUST_STORE_PWD}

echo "creating alice's key and certificate..."
keytool -genkeypair -alias alice -keyalg EC -dname 'C=UK,L=London,O=IOHK,OU=Atala,CN=cf-alice' \
        -validity ${VALIDITY} -keypass ${KEY_STORE_PWD} -keystore ${KEY_STORE}.jks -storepass ${KEY_STORE_PWD}
keytool -keystore ${KEY_STORE}.jks -storepass ${KEY_STORE_PWD} -certreq -alias alice | \
  keytool -keystore ${TRUST_STORE}.jks -storepass ${TRUST_STORE_PWD} -alias ca -gencert -ext KU=dig -validity ${VALIDITY} -rfc > alice.pem
keytool -alias alice -importcert -keystore ${KEY_STORE}.jks -storepass ${KEY_STORE_PWD} -trustcacerts -file alice.pem

echo "creating bob's key and certificate..."
keytool -genkeypair -alias bob -keyalg EC -dname 'C=UK,L=London,O=IOHK,OU=Atala,CN=cf-bob' \
        -validity ${VALIDITY} -keypass ${KEY_STORE_PWD} -keystore ${KEY_STORE}.jks -storepass ${KEY_STORE_PWD}
keytool -keystore ${KEY_STORE}.jks -storepass ${KEY_STORE_PWD} -certreq -alias bob | \
  keytool -keystore ${TRUST_STORE}.jks -storepass ${TRUST_STORE_PWD} -alias ca -gencert -ext KU=dig -validity ${VALIDITY} -rfc > bob.pem
keytool -alias bob -importcert -keystore ${KEY_STORE}.jks -storepass ${KEY_STORE_PWD} -trustcacerts -file bob.pem

echo "exporting keys into PKCS#12 format"
keytool -v -importkeystore -srckeystore ${KEY_STORE}.jks -srcstorepass ${KEY_STORE_PWD} \
   -destkeystore ${KEY_STORE}.p12 -deststorepass ${KEY_STORE_PWD} -deststoretype PKCS12
keytool -v -importkeystore -srckeystore ${TRUST_STORE}.jks -srcstorepass ${TRUST_STORE_PWD} \
   -destkeystore ${TRUST_STORE}.p12 -deststorepass ${TRUST_STORE_PWD} -deststoretype PKCS12

