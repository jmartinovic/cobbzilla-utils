#!/bin/bash
#
# Uses inotify to watch a directory for changes to apply to a djbdns data file.
# Prerequisite: sudo apt-get install inotify-tools
#
# Usage: djbdns_updater.sh <data-file> <service-path> <changes-dir>
#
# For example: djbdns_updater.sh /etc/tinydns/root/data /service/tinydns /tmp/tinydns_changes
#
# When a line of djbdns config is written to a file in /tmp/tinydns_changes this script will run
# If the data file already contains the line, nothing happens
# If the data file does not contain the line, it is added, the data.cdb file is rebuilt and djbdns is restarted
#
# This script will only run one instance of itself with the same arguments. If another process is detected
# watching the same data file, this process will exit
#

DATA_FILE=${1}
SERVICE=${2}
WATCH=${3}

already_running=$(ps auxwww | grep ${0} | grep -v $$ | wc -l | tr -d ' ')
if [ $already_running -gt 2 ] ; then
  echo "Already running, exiting."
  exit 1
fi

mkdir -p ${WATCH}

DATA_DIR=$(dirname ${DATA_FILE})
SCRATCH_DIR=${DATA_DIR}/scratch
mkdir ${SCRATCH_DIR}

inotifywait -e close_write -mr ${WATCH} --format "%f" | while read file ; do

  # only the first line of the written file matters
  line=$(cat ${WATCH}/${file} | head -n 1 | tr -d ' ')

  echo "found line=$line"
  if [ $(cat $DATA_FILE | grep "${line}" | wc -l | tr -d ' ') -gt 0 ] ; then
    echo "${DATA_FILE} already contains ${line}, not doing anything"

  else
    # Create a new data file as a tempfile
    temp=$(mktemp ${SCRATCH_DIR}/data.XXXXXXXX)
    cat ${DATA_FILE} > ${temp}
    echo "${line}" >> ${temp}

    # Backup the existing data file and move the temp file into position
    BACKUP=$(mktemp ${SCRATCH_DIR}/data-backup.$(date +%Y_%m_%d).XXXXXXXX)
    cp ${DATA_FILE} ${BACKUP}
    mv ${temp} ${DATA_FILE}

    # Try to run make and build the data.cdb file
    MAKE_RESULTS=$(cd ${DATA_DIR} && make 2>&1)
    if [ $? -ne 0 ] ; then
      echo "$0: Error running make (rolling back data file): ${MAKE_RESULTS}"
      cp ${BACKUP} ${DATA_FILE}

    else
      # Try to restart djbdns
      RESTART_RESULTS=$(svc -h ${SERVICE} 2>&1)
      if [ $? -ne 0 ] ; then
        echo "$0: Error restarting ${SERVICE}: ${RESTART_RESUTLS}"
        # restart failed, put old data file back in place and re-run make
        cp ${BACKUP} ${DATA_FILE}
        MAKE_RESULTS=$(cd ${DATA_DIR} && make 2>&1)
        if [ $? -ne 0 ] ; then
          echo "$0: Error running make while trying to rollback"
        fi
      fi
    fi

  fi
done
