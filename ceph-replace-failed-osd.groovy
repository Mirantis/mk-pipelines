/**
 *
 * Replace failed disk with a new disk
 *
 * Requred parameters:
 *  SALT_MASTER_URL                     URL of Salt master
 *  SALT_MASTER_CREDENTIALS             Credentials to the Salt API
 *
 *  HOST                                Host (minion id) to be removed
 *  ADMIN_HOST                          Host (minion id) with admin keyring and /etc/crushmap file present
 *  OSD                                 Failed OSD ids to be replaced (comma-separated list - 1,2,3)
 *  CLUSTER_FLAGS                       Comma separated list of tags to apply to cluster
 *
 */

timeout(time: 12, unit: 'HOURS') {
    node("python") {
      stage ('remove OSD') {
        build job: 'ceph-remove-osd', parameters: [[$class: 'StringParameterValue', name: 'OSD', value: OSD],[$class: 'StringParameterValue', name: 'HOST', value: HOST],[$class: 'StringParameterValue', name: 'SALT_MASTER_CREDENTIALS', value: SALT_MASTER_CREDENTIALS], [$class: 'StringParameterValue', name: 'SALT_MASTER_URL', value: SALT_MASTER_URL], [$class: 'StringParameterValue', name: 'CLUSTER_FLAGS', value: CLUSTER_FLAGS], [$class: 'StringParameterValue', name: 'ADMIN_HOST', value: ADMIN_HOST]]
      }

      stage ('replace failed disk') {
        input("Replace failed disk and click proceed")
      }

      stage ('add new osd') {
        build job: 'ceph-add-osd-upmap', parameters: [[$class: 'StringParameterValue', name: 'HOST', value: HOST], [$class: 'StringParameterValue', name: 'SALT_MASTER_CREDENTIALS', value: SALT_MASTER_CREDENTIALS], [$class: 'StringParameterValue', name: 'SALT_MASTER_URL', value: SALT_MASTER_URL]]
      }
    }
}
