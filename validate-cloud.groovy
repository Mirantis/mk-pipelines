/**
 *
 * Launch validation of the cloud
 *
 * Expected parameters:
 *   SALT_MASTER_URL             URL of Salt master
 *   SALT_MASTER_CREDENTIALS     Credentials to the Salt API
 *
 *   TEST_IMAGE                  Docker image link
 *   TARGET_NODE                 Salt target for tempest node
 *   TEMPEST_TEST_SET            If not false, run tests matched to pattern only
 *   RUN_TEMPEST_TESTS           If not false, run Tempest tests
 *   RUN_RALLY_TESTS             If not false, run Rally tests
 *
 */

common = new com.mirantis.mk.Common()
salt = new com.mirantis.mk.Salt()
validate = new com.mirantis.mcp.Validate()

def saltMaster
def artifacts_dir = 'validation_artifacts/'

node() {
    try{
        stage('Initialization') {
            saltMaster = salt.connection(SALT_MASTER_URL, SALT_MASTER_CREDENTIALS)
        }

        stage('Configure') {
            validate.installDocker(saltMaster, TARGET_NODE)
            sh "mkdir -p ${artifacts_dir}"
            validate.runContainerConfiguration(saltMaster, TEST_IMAGE, TARGET_NODE, artifacts_dir)
        }

        stage('Run Tempest tests') {
            if (RUN_TEMPEST_TESTS.toBoolean() == true) {
                validate.runTempestTests(saltMaster, TARGET_NODE, artifacts_dir, TEMPEST_TEST_SET)
            } else {
                common.infoMsg("Skipping Tempest tests")
            }
        }

        stage('Run Rally tests') {
            if (RUN_RALLY_TESTS.toBoolean() == true) {
                validate.runRallyTests(saltMaster, TARGET_NODE, artifacts_dir)
            } else {
                common.infoMsg("Skipping Rally tests")
            }
        }
        stage('Collect results') {
            archiveArtifacts artifacts: "${artifacts_dir}/*"
        }
    } catch (Throwable e) {
        // If there was an error or exception thrown, the build failed
        currentBuild.result = "FAILURE"
        throw e
    } finally {
        validate.runCleanup(saltMaster, TARGET_NODE, artifacts_dir)
    }
}
