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
 *   RUN_K8S_TESTS               If not false, run Kubernetes tests
 *   RUN_SPT_TESTS               If not false, run SPT tests
 *   SPT_SSH_USER                The name of the user which should be used for ssh to nodes
 *   SPT_IMAGE                   The name of the image for SPT tests
 *   SPT_IMAGE_USER              The name of the user for SPT image
 *   SPT_FLAVOR                  The name of the flavor for SPT image
 *   AVAILABILITY_ZONE           The name of availability zone
 *   FLOATING_NETWORK            The name of the external(floating) network
 *   RALLY_IMAGE                 The name of the image for Rally tests
 *   RALLY_FLAVOR                The name of the flavor for Rally image
 *   TEST_K8S_API_SERVER         Kubernetes API address
 *   TEST_K8S_CONFORMANCE_IMAGE  Path to docker image with conformance e2e tests
 *   TEST_K8S_NODE               Kubernetes node to run tests from
 *   GENERATE_REPORT             If not false, run report generation command
 *   ACCUMULATE_RESULTS          If true, results from the previous build will be used
 *
 */

common = new com.mirantis.mk.Common()
salt = new com.mirantis.mk.Salt()
test = new com.mirantis.mk.Test()
validate = new com.mirantis.mcp.Validate()
def python = new com.mirantis.mk.Python()

def pepperEnv = "pepperEnv"
def artifacts_dir = 'validation_artifacts/'

node() {
    try{
        stage('Setup virtualenv for Pepper') {
            python.setupPepperVirtualenv(pepperEnv, SALT_MASTER_URL, SALT_MASTER_CREDENTIALS)
        }

        stage('Configure') {
            validate.installDocker(pepperEnv, TARGET_NODE)
            if (ACCUMULATE_RESULTS.toBoolean() == false) {
                sh "rm -r ${artifacts_dir}"
            }
            sh "mkdir -p ${artifacts_dir}"
            def ext_variables = "-e spt_ssh_user=${SPT_SSH_USER} " +
                    "-e spt_floating_network=${FLOATING_NETWORK} " +
                    "-e spt_image=${SPT_IMAGE} -e spt_user=${SPT_IMAGE_USER} " +
                    "-e spt_flavor=${SPT_FLAVOR} -e spt_availability_zone=${AVAILABILITY_ZONE} " +
                    "-e floating_network=${FLOATING_NETWORK} -e rally_image=${RALLY_IMAGE} " +
                    "-e rally_flavor=${RALLY_FLAVOR} -e availability_zone=${AVAILABILITY_ZONE} "
            validate.runContainerConfiguration(pepperEnv, TEST_IMAGE, TARGET_NODE, artifacts_dir, ext_variables)
        }

        stage('Run Tempest tests') {
            if (RUN_TEMPEST_TESTS.toBoolean() == true) {
                validate.runTempestTests(pepperEnv, TARGET_NODE, artifacts_dir, TEMPEST_TEST_SET)
            } else {
                common.infoMsg("Skipping Tempest tests")
            }
        }

        stage('Run Rally tests') {
            if (RUN_RALLY_TESTS.toBoolean() == true) {
                validate.runRallyTests(pepperEnv, TARGET_NODE, artifacts_dir)
            } else {
                common.infoMsg("Skipping Rally tests")
            }
        }

        stage('Run SPT tests') {
            if (RUN_SPT_TESTS.toBoolean() == true) {
                validate.runSptTests(pepperEnv, TARGET_NODE, artifacts_dir)
            } else {
                common.infoMsg("Skipping SPT tests")
            }
        }

        stage('Run k8s bootstrap tests') {
            if (RUN_K8S_TESTS.toBoolean() == true) {
                def image = 'tomkukral/k8s-scripts'
                def output_file = 'k8s-bootstrap-tests.txt'
                def containerName = 'conformance_tests'
                def outfile = "/tmp/" + image.replaceAll('/', '-') + '.output'
                test.runConformanceTests(pepperEnv, TEST_K8S_NODE, TEST_K8S_API_SERVER, image)

                def file_content = validate.getFileContent(pepperEnv, TEST_K8S_NODE, outfile)
                writeFile file: "${artifacts_dir}${output_file}", text: file_content
            } else {
                common.infoMsg("Skipping k8s bootstrap tests")
            }
        }

        stage('Run k8s conformance e2e tests') {
            if (RUN_K8S_TESTS.toBoolean() == true) {
                def image = TEST_K8S_CONFORMANCE_IMAGE
                def output_file = 'report-k8s-e2e-tests.txt'
                def containerName = 'conformance_tests'
                def outfile = "/tmp/" + image.replaceAll('/', '-') + '.output'
                test.runConformanceTests(pepperEnv, TEST_K8S_NODE, TEST_K8S_API_SERVER, image)

                def file_content = validate.getFileContent(pepperEnv, TEST_K8S_NODE, outfile)
                writeFile file: "${artifacts_dir}${output_file}", text: file_content
            } else {
                common.infoMsg("Skipping k8s conformance e2e tests")
            }
        }
        stage('Generate report') {
            if (GENERATE_REPORT.toBoolean() == true) {
                print("Generating html test report ...")
                validate.generateTestReport(pepperEnv, TARGET_NODE, artifacts_dir)
            } else {
                common.infoMsg("Skipping report generation")
            }
        }
        stage('Collect results') {
            archiveArtifacts artifacts: "${artifacts_dir}/*"
        }
    } catch (Throwable e) {
        // If there was an error or exception thrown, the build failed
        currentBuild.result = "FAILURE"
        currentBuild.description = currentBuild.description ? e.message + " " + currentBuild.description : e.message
        throw e
    } finally {
        validate.runCleanup(pepperEnv, TARGET_NODE, artifacts_dir)
    }
}
