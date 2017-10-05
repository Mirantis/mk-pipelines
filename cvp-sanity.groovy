/**
 *
 * Launch sanity validation of the cloud
 *
 * Expected parameters:
 *   SALT_MASTER_URL             URL of Salt master
 *   SALT_MASTER_CREDENTIALS     Credentials to the Salt API
 *
 *   INFRA_TEST_SET              If not false, run tests matched to pattern only
 *   INFRA_REPO                  CVP-sanity-checks repo
 *   PROXY                       Proxy to use for cloning repo or for pip
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
            validate.prepareVenv(INFRA_REPO, PROXY)
        }

        stage('Run Infra tests') {
            sh "mkdir -p ${artifacts_dir}"
            validate.runSanityTests(SALT_MASTER_URL, SALT_MASTER_CREDENTIALS, INFRA_TEST_SET, artifacts_dir)
        }
    } catch (Throwable e) {
        // If there was an error or exception thrown, the build failed
        currentBuild.result = "FAILURE"
        throw e
    } finally {
        archiveArtifacts artifacts: "${artifacts_dir}/*"
        junit "${artifacts_dir}/*.xml"
    }
}

