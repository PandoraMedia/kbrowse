#!/bin/bash

set -x
set -e

ROOT=$(pwd)

export LEIN_ROOT=true

mkdir -p ${ROOT}/docker-files
rm -rf ${ROOT}/docker-files/*
cd ${ROOT}/docker-files/

curl -L -o master.zip https://github.com/PandoraMedia/kbrowse/archive/master.zip
unzip master.zip
cd kbrowse-master
./lein uberjar

chown -R --reference=${ROOT}/build.sh ${ROOT}/docker-files
