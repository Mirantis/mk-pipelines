/**
 *
 * Service test pipeline
 *
 * Expected parameters:
 *   SALT_MASTER_URL                 URL of Salt master
 *   SALT_MASTER_CREDENTIALS         Credentials to the Salt API
 * Test settings:
 *   IMAGE_LINK                      Link to docker image with Rally and Tempest
 *   TEST_TEMPEST_PATTERN            If not false, run tests matched to pattern only
 *   TEST_TARGET                     Salt target for tempest node
 *   CLEANUP_REPORTS                 Cleanup reports from rally,tempest container, remove all containers started the IMAGE_LINK
 *   SET                             Predefined set for tempest tests
 *   CONCURRENCY                     How many processes to use to run Tempest tests
 *   DO_CLEANUP_RESOURCES            If "true": runs clean-up script for removing Rally and Tempest resources
 */


common = new com.mirantis.mk.Common()
salt = new com.mirantis.mk.Salt()
test = new com.mirantis.mk.Test()
def python = new com.mirantis.mk.Python()

def pepperEnv = "pepperEnv"
timeout(time: 12, unit: 'HOURS') {
    node("python") {
        try {

            stage('Setup virtualenv for Pepper') {
                python.setupPepperVirtualenv(pepperEnv, SALT_MASTER_URL, SALT_MASTER_CREDENTIALS)
            }

            //
            // Test
            //

            stage('Run OpenStack Tempest tests') {
                test.runTempestTests(pepperEnv, IMAGE_LINK, TEST_TARGET, TEST_TEMPEST_PATTERN, "/home/rally/rally_reports/",
                        "/home/rally/keystonercv3", SET, CONCURRENCY, "mcp.conf", "mcp_skip.list", "/root/keystonercv3",
                        "/root/rally_reports", DO_CLEANUP_RESOURCES)
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
            if (CLEANUP_REPORTS.toBoolean()) {
                stage('Cleanup reports') {
                    test.removeReports(pepperEnv, TEST_TARGET, "rally_reports", 'rally_reports.tar')
                }
            }
        }
    }
}
