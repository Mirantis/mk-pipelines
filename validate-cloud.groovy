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
 *   TEMPEST_CONFIG_REPO         Git repository with configuration files for Tempest
 *   TEMPEST_CONFIG_BRANCH       Git branch which will be used during the checkout
 *   TEMPEST_REPO                Git repository with Tempest
 *   TEMPEST_VERSION             Version of Tempest (tag, branch or commit)
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
 *   RALLY_CONFIG_REPO           Git repository with files for Rally
 *   RALLY_CONFIG_BRANCH         Git branch which will be used during the checkout
 *   RALLY_SCENARIOS             Path to file or directory with rally scenarios
 *   RALLY_TASK_ARGS_FILE        Path to file with rally tests arguments
 *   REPORT_DIR                  Path for reports outside docker image
 *   TEST_K8S_API_SERVER         Kubernetes API address
 *   TEST_K8S_CONFORMANCE_IMAGE  Path to docker image with conformance e2e tests
 *   TEST_K8S_NODE               Kubernetes node to run tests from
 *   GENERATE_REPORT             If not false, run report generation command
 *   ACCUMULATE_RESULTS          If true, results from the previous build will be used
 *
 */

common = new com.mirantis.mk.Common()
test = new com.mirantis.mk.Test()
validate = new com.mirantis.mcp.Validate()
def python = new com.mirantis.mk.Python()

def pepperEnv = "pepperEnv"
def artifacts_dir = 'validation_artifacts/'
timeout(time: 12, unit: 'HOURS') {
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
            }

            stage('Run Tempest tests') {
                if (RUN_TEMPEST_TESTS.toBoolean() == true) {
                    validate.runTempestTests(pepperEnv, TARGET_NODE, TEST_IMAGE, artifacts_dir, TEMPEST_CONFIG_REPO, TEMPEST_CONFIG_BRANCH, TEMPEST_REPO, TEMPEST_VERSION, TEMPEST_TEST_SET)
                } else {
                    common.infoMsg("Skipping Tempest tests")
                }
            }

            stage('Run Rally tests') {
                if (RUN_RALLY_TESTS.toBoolean() == true) {
                    def report_dir = '/root/qa_results'
                    try {
                         if(REPORT_DIR != ""){
                             report_dir = REPORT_DIR
                         }
                    } catch (MissingPropertyException e) {
                    }
                    def rally_variables = ["floating_network=${FLOATING_NETWORK}",
                                           "rally_image=${RALLY_IMAGE}",
                                           "rally_flavor=${RALLY_FLAVOR}",
                                           "availability_zone=${AVAILABILITY_ZONE}"]
                    validate.runRallyTests(pepperEnv, TARGET_NODE, TEST_IMAGE, artifacts_dir, RALLY_CONFIG_REPO, RALLY_CONFIG_BRANCH, RALLY_SCENARIOS, RALLY_TASK_ARGS_FILE, rally_variables, report_dir)
                } else {
                    common.infoMsg("Skipping Rally tests")
                }
            }

            stage('Run SPT tests') {
                if (RUN_SPT_TESTS.toBoolean() == true) {
                    def spt_variables = ["spt_ssh_user=${SPT_SSH_USER}",
                                         "spt_floating_network=${FLOATING_NETWORK}",
                                         "spt_image=${SPT_IMAGE}",
                                         "spt_user=${SPT_IMAGE_USER}",
                                         "spt_flavor=${SPT_FLAVOR}",
                                         "spt_availability_zone=${AVAILABILITY_ZONE}"]
                    validate.runSptTests(pepperEnv, TARGET_NODE, TEST_IMAGE, artifacts_dir, spt_variables)
                } else {
                    common.infoMsg("Skipping SPT tests")
                }
            }

            stage('Run k8s bootstrap tests') {
                if (RUN_K8S_TESTS.toBoolean() == true) {
                    def image = 'tomkukral/k8s-scripts'
                    def output_file = 'k8s-bootstrap-tests.txt'
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
                    common.infoMsg("Generating html test report ...")
                    validate.generateTestReport(pepperEnv, TARGET_NODE, TEST_IMAGE, artifacts_dir)
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
        }
    }
}
