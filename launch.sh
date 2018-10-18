#!/bin/sh

set -x
trap "echo exited $0" HUP INT QUIT TERM KILL

export APP_ROOT='/app'

# service uses the default.yml config file but can be overridden below
# CONFIG_FILE='sstk.yml'

mkdir -p config && cp ${CONFIG_FILE} ${APP_ROOT}/config/default.yml

export cmd=java\ -jar\ "${APP_ROOT}"/kbrowse-*-SNAPSHOT-standalone.jar\ server
$cmd