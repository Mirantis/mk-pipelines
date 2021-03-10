/**
 *
 * Remove Ceph osds from node
 *
 * Requred parameters:
 *  SALT_MASTER_URL             URL of Salt master
 *  SALT_MASTER_CREDENTIALS     Credentials to the Salt API
 *  HOST                        Host (minion id) to be added
 *  WAIT_FOR_HEALTHY            Wait for cluster rebalance after a osd was removed
 *  CLUSTER_FLAGS               Expected flags on the cluster during job run
 *  FAST_WIPE                   Clean only partition table insted of full wipe
 *  CLEAN_ORPHANS               Clean ceph partition which are no longer part of the cluster
 *  OSD                         Coma separated list of OSDs to remove while keep the rest intact
 *
 */

timeout(time: 12, unit: 'HOURS') {
    build job: 'ceph-remove-node', parameters: [
        [$class: 'BooleanParameterValue', name: 'CLEAN_ORPHANS', value: CLEAN_ORPHANS],
        [$class: 'BooleanParameterValue', name: 'FAST_WIPE', value: FAST_WIPE],
        [$class: 'BooleanParameterValue', name: 'WAIT_FOR_HEALTHY', value: WAIT_FOR_HEALTHY],
        [$class: 'StringParameterValue', name: 'HOST', value: HOST],
        [$class: 'StringParameterValue', name: 'OSD', value: OSD],
        [$class: 'StringParameterValue', name: 'CLUSTER_FLAGS', value: CLUSTER_FLAGS],
        [$class: 'StringParameterValue', name: 'SALT_MASTER_CREDENTIALS', value: SALT_MASTER_CREDENTIALS],
        [$class: 'StringParameterValue', name: 'SALT_MASTER_URL', value: SALT_MASTER_URL]
    ]
}
