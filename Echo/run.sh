#!/bin/bash
source ../teachnet.path

java -jar $tn_path \
--cp . \
--config EchoTreeconfig.txt \
--compile

java -jar $tn_path \
--cp . \
--config Echoconfig.txt \
--compile

java -jar $tn_path \
--cp . \
--config EchoRingconfig.txt \
--compile

java -jar $tn_path \
--cp . \
--config EchoCubeconfig.txt \
--compile

javac -cp $tn_path Echo.java
#java -cp .:../teachnet.jar teachnet/view/TeachnetFrame

# Windows Users use 
# java -cp .;..\teachnet.jar teachnet/view/TeachnetFrame
