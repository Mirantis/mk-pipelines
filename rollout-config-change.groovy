
/**
 * Rollout changes to the node(s) configuration
 *
 * Expected parameters:
 *   TST_SALT_MASTER_CREDENTIALS  Credentials to the Salt API (QA environment).
 *   TST_SALT_MASTER_URL          Full Salt API address [https://10.10.10.1:8000].
 *   PRD_SALT_MASTER_CREDENTIALS  Credentials to the Salt API (PRD environment).
 *   PRD_SALT_MASTER_URL          Full Salt API address [https://10.10.10.1:8000].
 * Model parameters:
 *   MODEL_REPO_CREDENTIALS       Credentials to the Model.
 *   MODEL_REPO_URL               Full model repo address.
 *   MODEL_REPO_SOURCE_BRANCH     Source branch to merge from.
 *   MODEL_REPO_TARGET_BRANCH     Target branch to merge fo.
 * Change settings:
 *   TARGET_SERVERS               Salt compound target to match nodes to be updated [*, G@osfamily:debian].
 *   TARGET_STATES                States to be applied, empty string means running highstate [linux, linux,openssh, salt.minion.grains].
 *   TARGET_SUBSET_TEST           Number of nodes to test config changes, empty string means all targetted nodes.
 *   TARGET_SUBSET_LIVE           Number of selected noded to live apply selected config changes.
 *   TARGET_BATCH_LIVE            Batch size for the complete live config changes on all nodes, empty string means apply to all targetted nodes.
 * Test settings:
 *   TEST_SERVICE                 Comma separated list of services to test
 *   TEST_K8S_API_SERVER          Kubernetes API address
 *   TEST_K8S_CONFORMANCE_IMAGE   Path to docker image with conformance e2e tests
 *   TEST_DOCKER_INSTALL          Install docker on the target if true
 *   TEST_TEMPEST_IMAGE           Tempest image link
 *   TEST_TEMPEST_PATTERN         If not false, run tests matched to pattern only
 *   TEST_TEMPEST_TARGET          Salt target for tempest node
 *
**/

def common = new com.mirantis.mk.Common()
def salt = new com.mirantis.mk.Salt()

node() {
    try {

        stage('Run config change on test env') {
            build job: "deploy-update-service-config", parameters: [
              [$class: 'StringParameterValue', name: 'SALT_MASTER_URL', value: TST_SALT_MASTER_URL],
              [$class: 'StringParameterValue', name: 'SALT_MASTER_CREDENTIALS', value: TST_SALT_MASTER_CREDENTIALS],
              [$class: 'StringParameterValue', name: 'TARGET_BATCH_LIVE', value: TARGET_BATCH_LIVE],
              [$class: 'StringParameterValue', name: 'TARGET_SERVERS', value: TARGET_SERVERS],
              [$class: 'StringParameterValue', name: 'TARGET_STATES', value: TARGET_STATES],
              [$class: 'StringParameterValue', name: 'TARGET_SUBSET_LIVE', value: TARGET_SUBSET_LIVE],
              [$class: 'StringParameterValue', name: 'TARGET_SUBSET_TEST', value: TARGET_SUBSET_TEST],
            ]
        }

        stage('Test config change on test env') {
            build job: "deploy-test-service", parameters: [
              [$class: 'StringParameterValue', name: 'SALT_MASTER_URL', value: TST_SALT_MASTER_URL],
              [$class: 'StringParameterValue', name: 'SALT_MASTER_CREDENTIALS', value: TST_SALT_MASTER_CREDENTIALS],
              [$class: 'StringParameterValue', name: 'TEST_SERVICE', value: TEST_SERVICE],
              [$class: 'StringParameterValue', name: 'TEST_K8S_API_SERVER', value: TEST_K8S_API_SERVER],
              [$class: 'StringParameterValue', name: 'TEST_K8S_CONFORMANCE_IMAGE', value: TEST_K8S_CONFORMANCE_IMAGE],
            ]
        }

        stage('Promote config change in repo') {
            build job: "gerrit-merge-branch", parameters: [
              [$class: 'StringParameterValue', name: 'MODEL_REPO_URL', value: MODEL_REPO_URL],
              [$class: 'StringParameterValue', name: 'MODEL_REPO_CREDENTIALS', value: MODEL_REPO_CREDENTIALS],
              [$class: 'StringParameterValue', name: 'MODEL_REPO_SOURCE_BRANCH', value: MODEL_REPO_SOURCE_BRANCH],
              [$class: 'StringParameterValue', name: 'MODEL_REPO_TARGET_BRANCH', value: MODEL_REPO_TARGET_BRANCH],
            ]
        }

        stage('Run config change on production env') {
            build job: "deploy-update-service-config", parameters: [
              [$class: 'StringParameterValue', name: 'SALT_MASTER_URL', value: PRD_SALT_MASTER_URL],
              [$class: 'StringParameterValue', name: 'SALT_MASTER_CREDENTIALS', value: PRD_SALT_MASTER_CREDENTIALS],
              [$class: 'StringParameterValue', name: 'TARGET_BATCH_LIVE', value: TARGET_BATCH_LIVE],
              [$class: 'StringParameterValue', name: 'TARGET_SERVERS', value: TARGET_SERVERS],
              [$class: 'StringParameterValue', name: 'TARGET_STATES', value: TARGET_STATES],
              [$class: 'StringParameterValue', name: 'TARGET_SUBSET_LIVE', value: TARGET_SUBSET_LIVE],
              [$class: 'StringParameterValue', name: 'TARGET_SUBSET_TEST', value: TARGET_SUBSET_TEST],
            ]
        }

        stage('Test config change on prod env') {
            build job: "deploy-test-service", parameters: [
              [$class: 'StringParameterValue', name: 'SALT_MASTER_URL', value: PRD_SALT_MASTER_URL],
              [$class: 'StringParameterValue', name: 'SALT_MASTER_CREDENTIALS', value: PRD_SALT_MASTER_CREDENTIALS],
              [$class: 'StringParameterValue', name: 'TEST_SERVICE', value: TEST_SERVICE],
              [$class: 'StringParameterValue', name: 'TEST_K8S_API_SERVER', value: TEST_K8S_API_SERVER],
              [$class: 'StringParameterValue', name: 'TEST_K8S_CONFORMANCE_IMAGE', value: TEST_K8S_CONFORMANCE_IMAGE],
            ]
        }

    } catch (Throwable e) {
        currentBuild.result = 'FAILURE'
        throw e
    }
}
