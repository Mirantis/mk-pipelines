/**

 * Launch system tests against new package.

 * Flow parameters:
 *   CREDENTIALS_ID
 *   SALT_OPTS
 *   STACK_DEPLOY_JOB

**/
def common = new com.mirantis.mk.Common()
def gerrit = new com.mirantis.mk.Gerrit()
timeout(time: 12, unit: 'HOURS') {
    node {
        def cred = common.getCredentials(CREDENTIALS_ID, 'key')
        def gerritChange = gerrit.getGerritChange(cred.username, GERRIT_HOST, GERRIT_CHANGE_NUMBER, CREDENTIALS_ID, true)

        stage('Trigger deploy job') {
            build(job: STACK_DEPLOY_JOB, parameters: [
                [$class: 'StringParameterValue', name: 'OPENSTACK_API_PROJECT', value: 'mcp-oscore'],
                [$class: 'StringParameterValue', name: 'STACK_TEST', value: ''],
                [$class: 'BooleanParameterValue', name: 'TEST_DOCKER_INSTALL', value: false]
            ])
        }
    }
}
