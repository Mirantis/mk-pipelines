/**
 *
 * Service test pipeline
 *
 * Expected parameters:
 *   SALT_MASTER_URL                 URL of Salt master
 *   SALT_MASTER_CREDENTIALS         Credentials to the Salt API
 * Test settings:
 *   IMAGE_LINK                      Link to docker image with Rally
 *   RALLY_SCENARIO                  Rally test scenario
 *   TEST_TARGET                     Salt target for Rally node
 *   CLEANUP_REPORTS_AND_CONTAINER   Cleanup reports from rally,tempest container, remove all containers started the IMAGE_LINK
 *   DO_CLEANUP_RESOURCES            If "true": runs clean-up script for removing Rally and Tempest resources
 */


common = new com.mirantis.mk.Common()
salt = new com.mirantis.mk.Salt()
test = new com.mirantis.mk.Test()
def python = new com.mirantis.mk.Python()

def pepperEnv = "pepperEnv"

node("python") {
    try {

        //
        // Prepare connection
        //
        stage('Setup virtualenv for Pepper') {
            python.setupPepperVirtualenv(pepperEnv, SALT_MASTER_URL, SALT_MASTER_CREDENTIALS)
        }

        //
        // Test
        //

        stage('Run OpenStack Rally scenario') {
            test.runRallyScenarios(pepperEnv, IMAGE_LINK, TEST_TARGET, RALLY_SCENARIO, "/home/rally/rally_reports/",
                    DO_CLEANUP_RESOURCES)
        }
        stage('Copy test reports') {
            test.copyTempestResults(pepperEnv, TEST_TARGET)
        }
        stage('Archiving test artifacts') {
            test.archiveRallyArtifacts(pepperEnv, TEST_TARGET)
        }
    } catch (Throwable e) {
        currentBuild.result = 'FAILURE'
        throw e
    } finally {
        if (CLEANUP_REPORTS_AND_CONTAINER.toBoolean()) {
            stage('Cleanup reports and container') {
                test.removeReports(pepperEnv, TEST_TARGET, "rally_reports", 'rally_reports.tar')
                test.removeDockerContainer(pepperEnv, TEST_TARGET, IMAGE_LINK)
            }
        }
    }
}
