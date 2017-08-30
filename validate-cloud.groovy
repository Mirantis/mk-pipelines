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
 *   SPT_FLOATING_NETWORK        The name of the external(floating) network
 *   SPT_IMAGE                   The name of the image for SPT tests
 *   SPT_USER                    The name of the user for SPT image
 *   SPT_FLAVOR                  The name of the flavor for SPT image
 *   SPT_AVAILABILITY_ZONE       The name of availability zone
 *   TEST_K8S_API_SERVER         Kubernetes API address
 *   TEST_K8S_CONFORMANCE_IMAGE  Path to docker image with conformance e2e tests
 *
 */

common = new com.mirantis.mk.Common()
salt = new com.mirantis.mk.Salt()
test = new com.mirantis.mk.Test()
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
            def spt_variables = "-e spt_ssh_user=${SPT_SSH_USER} " +
                    "-e spt_floating_network=${SPT_FLOATING_NETWORK} " +
                    "-e spt_image=${SPT_IMAGE} -e spt_user=${SPT_USER} " +
                    "-e spt_flavor=${SPT_FLAVOR} -e spt_availability_zone=${SPT_AVAILABILITY_ZONE} "
            validate.runContainerConfiguration(saltMaster, TEST_IMAGE, TARGET_NODE, artifacts_dir, spt_variables)
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

        stage('Run SPT tests') {
            if (RUN_SPT_TESTS.toBoolean() == true) {
                validate.runSptTests(saltMaster, TARGET_NODE, artifacts_dir)
            } else {
                common.infoMsg("Skipping SPT tests")
            }
        }

        stage('Run k8s bootstrap tests') {
            if (RUN_K8S_TESTS.toBoolean() == true) {
                def image = 'tomkukral/k8s-scripts'
                def output_file = image.replaceAll('/', '-') + '.output'

                // run image
                test.runConformanceTests(saltMaster, TEST_K8S_API_SERVER, image)

                // collect output
                def file_content = salt.getFileContent(saltMaster, 'ctl01*', '/tmp/' + output_file)
                writeFile file: "${artifacts_dir}${output_file}", text: file_content
            } else {
                common.infoMsg("Skipping k8s bootstrap tests")
            }
        }

        stage('Run k8s conformance e2e tests') {
            if (RUN_K8S_TESTS.toBoolean() == true) {
                def image = TEST_K8S_CONFORMANCE_IMAGE
                def output_file = image.replaceAll('/', '-') + '.output'

                // run image
                test.runConformanceTests(saltMaster, TEST_K8S_API_SERVER, image)

                // collect output
                def file_content = salt.getFileContent(saltMaster, 'ctl01*', '/tmp/' + output_file)
                writeFile file: "${artifacts_dir}${output_file}", text: file_content
            } else {
                common.infoMsg("Skipping k8s conformance e2e tests")
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
        validate.runCleanup(saltMaster, TARGET_NODE, artifacts_dir)
    }
}
