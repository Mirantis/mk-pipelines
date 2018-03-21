/**
 *
 * Launch validation of the cloud
 *
 * Expected parameters:
 *   SALT_MASTER_URL             URL of Salt master
 *   SALT_MASTER_CREDENTIALS     Credentials that are used in this Jenkins for accessing Salt master (usually "salt")
 *   PROXY                       Proxy address (if any) for accessing the Internet. It will be used for cloning repos and installing pip dependencies
 *   TEST_IMAGE                  Docker image link to use for running container with testing tools.
 *   TOOLS_REPO                  URL of repo where testing tools, scenarios, configs are located
 *
 *   TARGET_NODE                 Node to run container with Rally
 *   DEBUG_MODE                  If you need to debug (keep container after test), please enabled this
 *   RALLY_SCENARIO_FILE         Path to Rally scenario file in container
 *
 */

common = new com.mirantis.mk.Common()
salt = new com.mirantis.mk.Salt()
validate = new com.mirantis.mcp.Validate()

def artifacts_dir = 'validation_artifacts/'
def remote_artifacts_dir = '/root/qa_results/'
def saltMaster

node() {
    try{
        stage('Initialization') {
            saltMaster = salt.connection(SALT_MASTER_URL, SALT_MASTER_CREDENTIALS)
            sh "rm -rf ${artifacts_dir}"
            salt.cmdRun(saltMaster, TARGET_NODE, "rm -rf ${remote_artifacts_dir}")
            salt.cmdRun(saltMaster, TARGET_NODE, "mkdir -p ${remote_artifacts_dir}")
            validate.runBasicContainer(saltMaster, TARGET_NODE, TEST_IMAGE)
            validate.configureContainer(saltMaster, TARGET_NODE, PROXY, TOOLS_REPO, "")
        }

        stage('Run Rally tests') {
            sh "mkdir -p ${artifacts_dir}"
            validate.runCVPrally(saltMaster, TARGET_NODE, RALLY_SCENARIO_FILE, remote_artifacts_dir)
        }

        stage('Collect results') {
            validate.addFiles(saltMaster, TARGET_NODE, remote_artifacts_dir, artifacts_dir)
            archiveArtifacts artifacts: "${artifacts_dir}/*"
            junit "${artifacts_dir}/*.xml"
        }
    } catch (Throwable e) {
        // If there was an error or exception thrown, the build failed
        currentBuild.result = "FAILURE"
        throw e
    } finally {
        if (DEBUG_MODE == 'false') {
            validate.runCleanup(saltMaster, TARGET_NODE)
            salt.cmdRun(saltMaster, TARGET_NODE, "rm -rf ${remote_artifacts_dir}")
        }
    }
}

