/**
 *
 * Launch sanity validation of the cloud
 *
 * Expected parameters:
 *   SALT_MASTER_URL             URL of Salt master
 *   SALT_MASTER_CREDENTIALS     Credentials to the Salt API
 *
 *   SANITY_TESTS_SET            Leave empty for full run or choose a file (test), e.g. test_mtu.py
 *   SANITY_TESTS_REPO           CVP-sanity-checks repo to clone
 *   PROXY                       Proxy to use for cloning repo or for pip
 *
 */

validate = new com.mirantis.mcp.Validate()

def artifacts_dir = 'validation_artifacts/'
timeout(time: 12, unit: 'HOURS') {
    node() {
        try{
            stage('Initialization') {
                validate.prepareVenv(SANITY_TESTS_REPO, PROXY)
            }

            stage('Run Infra tests') {
                sh "mkdir -p ${artifacts_dir}"
                validate.runSanityTests(SALT_MASTER_URL, SALT_MASTER_CREDENTIALS, SANITY_TESTS_SET, artifacts_dir)
            }
            stage ('Publish results') {
                archiveArtifacts artifacts: "${artifacts_dir}/*"
                junit "${artifacts_dir}/*.xml"
            }
        } catch (Throwable e) {
            // If there was an error or exception thrown, the build failed
            currentBuild.result = "FAILURE"
            throw e
        }
    }
}
