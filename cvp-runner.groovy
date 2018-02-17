/**
 *
 * Launch pytest frameworks in Jenkins
 *
 * Expected parameters:
 *   SALT_MASTER_URL                 URL of Salt master
 *   SALT_MASTER_CREDENTIALS         Credentials to the Salt API
 *
 *   TESTS_SET                       Leave empty for full run or choose a file (test)
 *   TESTS_REPO                      Repo to clone
 *   TESTS_SETTINGS                  Additional environment varibales to apply
 *   PROXY                           Proxy to use for cloning repo or for pip
 *
 */

validate = new com.mirantis.mcp.Validate()

def artifacts_dir = 'validation_artifacts/'

node() {
    try{
        stage('Initialization') {
            validate.prepareVenv(TESTS_REPO, PROXY)
        }

        stage('Run Tests') {
            sh "mkdir -p ${artifacts_dir}"
            validate.runTests(SALT_MASTER_URL, SALT_MASTER_CREDENTIALS, TESTS_SET, artifacts_dir, TESTS_SETTINGS)
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
