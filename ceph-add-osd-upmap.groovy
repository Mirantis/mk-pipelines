/**
 *
 * Add Ceph node to existing cluster
 *
 * Requred parameters:
 *  SALT_MASTER_URL             URL of Salt master
 *  SALT_MASTER_CREDENTIALS     Credentials to the Salt API
 *  HOST                        Host (minion id) to be added
 *  CLUSTER_FLAGS               Expected flags on the cluster during job run
 *
 */

timeout(time: 12, unit: 'HOURS') {
    build job: 'ceph-add-node', parameters: [
        [$class: 'BooleanParameterValue', name: 'OSD_ONLY', value: true],
        [$class: 'BooleanParameterValue', name: 'USE_UPMAP', value: true],
        [$class: 'StringParameterValue', name: 'HOST', value: HOST],
        [$class: 'StringParameterValue', name: 'CLUSTER_FLAGS', value: CLUSTER_FLAGS],
        [$class: 'StringParameterValue', name: 'SALT_MASTER_CREDENTIALS', value: SALT_MASTER_CREDENTIALS],
        [$class: 'StringParameterValue', name: 'SALT_MASTER_URL', value: SALT_MASTER_URL]
    ]
}
