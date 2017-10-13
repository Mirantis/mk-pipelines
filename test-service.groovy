/**
 *
 * Service test pipeline
 *
 * Expected parameters:
 *   SALT_MASTER_URL              URL of Salt master
 *   SALT_MASTER_CREDENTIALS      Credentials to the Salt API
 * Test settings:
 *   TEST_SERVICE                 Comma separated list of services to test
 *   TEST_K8S_API_SERVER          Kubernetes API address
 *   TEST_K8S_CONFORMANCE_IMAGE   Path to docker image with conformance e2e tests
 *   TEST_DOCKER_INSTALL          Install docker on the target if true
 *   TEST_TEMPEST_IMAGE           Tempest image link
 *   TEST_TEMPEST_PATTERN         If not false, run tests matched to pattern only
 *   TEST_TEMPEST_TARGET          Salt target for tempest node
 *
 */

common = new com.mirantis.mk.Common()
git = new com.mirantis.mk.Git()
salt = new com.mirantis.mk.Salt()
test = new com.mirantis.mk.Test()
def python = new com.mirantis.mk.Python()

def pepperEnv = "pepperEnv"

node("python") {
    try {

        stage('Setup virtualenv for Pepper') {
            python.setupPepperVirtualenv(pepperEnv, SALT_MASTER_URL, SALT_MASTER_CREDENTIALS)
        }

        //
        // Test
        //
        def artifacts_dir = '_artifacts/'

        if (common.checkContains('TEST_SERVICE', 'k8s')) {
            stage('Run k8s bootstrap tests') {
                def image = 'tomkukral/k8s-scripts'
                def output_file = image.replaceAll('/', '-') + '.output'

                // run image
                test.runConformanceTests(pepperEnv, 'ctl01*', TEST_K8S_API_SERVER, image)

                // collect output
                sh "mkdir -p ${artifacts_dir}"
                file_content = salt.getFileContent(pepperEnv, 'ctl01*', '/tmp/' + output_file)
                writeFile file: "${artifacts_dir}${output_file}", text: file_content
                sh "cat ${artifacts_dir}${output_file}"

                // collect artifacts
                archiveArtifacts artifacts: "${artifacts_dir}${output_file}"
            }

            stage('Run k8s conformance e2e tests') {
                def image = K8S_CONFORMANCE_IMAGE
                def output_file = image.replaceAll('/', '-') + '.output'

                // run image
                test.runConformanceTests(pepperEnv, 'ctl01*', TEST_K8S_API_SERVER, image)

                // collect output
                sh "mkdir -p ${artifacts_dir}"
                file_content = salt.getFileContent(pepperEnv, 'ctl01*', '/tmp/' + output_file)
                writeFile file: "${artifacts_dir}${output_file}", text: file_content
                sh "cat ${artifacts_dir}${output_file}"

                // collect artifacts
                archiveArtifacts artifacts: "${artifacts_dir}${output_file}"
            }
        }

        if (common.checkContains('TEST_SERVICE', 'openstack')) {
            if (common.checkContains('TEST_DOCKER_INSTALL', 'true')) {
                test.install_docker(pepperEnv, TEST_TEMPEST_TARGET)
            }

            stage('Run OpenStack tests') {
                test.runTempestTests(pepperEnv, TEST_TEMPEST_IMAGE, TEST_TEMPEST_TARGET, TEST_TEMPEST_PATTERN)
            }

            writeFile(file: 'report.xml', text: salt.getFileContent(pepperEnv, TEST_TEMPEST_TARGET, '/root/report.xml'))
            junit(keepLongStdio: true, testResults: 'report.xml', healthScaleFactor:  Double.parseDouble(TEST_JUNIT_RATIO))
            def testResults = test.collectJUnitResults(currentBuild.rawBuild.getAction(hudson.tasks.test.AbstractTestResultAction.class))
            if(testResults){
                currentBuild.desc = String.format("result: %s", testResults["failed"] / testResults["total"])
            }
        }
    } catch (Throwable e) {
        currentBuild.result = 'FAILURE'
        throw e
    }
}
