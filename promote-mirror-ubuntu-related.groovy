/**
 *
 * Promote Ubuntu-related mirrors in same time.
 * Promote ubuntu|maas|maas-ephermal should be always together.
 *
 * Expected parameters:
 *   MCP_VERSION
 *   SNAPSHOT_NAME - Snapshot name to set
 *   SNAPSHOT_ID   - Set name for specified snapshot ID
 */

common = new com.mirantis.mk.Common()

timeout(time: 1, unit: 'HOURS') {
    node() {
        stage("Promote") {
            catchError {
                for (String jobname : ['mirror-snapshot-name-maas-xenial', 'mirror-snapshot-name-ubuntu', 'mirror-snapshot-name-maas-ephemeral-v3']) {
                    build job: jobname, parameters: [
                        [$class: 'StringParameterValue', name: 'SNAPSHOT_NAME', value: SNAPSHOT_NAME],
                        [$class: 'StringParameterValue', name: 'SNAPSHOT_ID', value: SNAPSHOT_ID],
                    ]
                }
            }
        }
    }
}
