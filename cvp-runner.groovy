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
 *   TEST_IMAGE                      Docker image link or name to use for running container with test framework.
 *   DEBUG_MODE                      If you need to debug (keep container after test), please enabled this
 *
 */

common = new com.mirantis.mk.Common()
validate = new com.mirantis.mcp.Validate()
salt = new com.mirantis.mk.Salt()
def artifacts_dir = 'validation_artifacts/'
def remote_dir = '/root/qa_results/'
def container_workdir = '/var/lib'
def TARGET_NODE = "I@gerrit:client"
def reinstall_env = false
def container_name = "${env.JOB_NAME}"
def saltMaster
def settings

node() {
    try{
        stage('Initialization') {
            sh "rm -rf ${artifacts_dir}"
            if ( TESTS_SETTINGS != "" ) {
                for (var in TESTS_SETTINGS.tokenize(";")) {
                    key = var.tokenize("=")[0].trim()
                    value = var.tokenize("=")[1].trim()
                    if (key == 'TARGET_NODE') {
                        TARGET_NODE = value
                        common.infoMsg("Node for container is set to ${TARGET_NODE}")
                    }
                    if (key == 'REINSTALL_ENV') {
                        reinstall_env = value.toBoolean()
                    }
                }
            }
            if ( IMAGE == "" ) {
                common.infoMsg("Env for tests will be built on Jenkins slave")
                TARGET_NODE = ""
                validate.prepareVenv(TESTS_REPO, PROXY)
            } else {
                saltMaster = salt.connection(SALT_MASTER_URL, SALT_MASTER_CREDENTIALS)
                salt.cmdRun(saltMaster, TARGET_NODE, "rm -rf ${remote_dir}")
                salt.cmdRun(saltMaster, TARGET_NODE, "mkdir -p ${remote_dir}")
                validate.runContainer(saltMaster, TARGET_NODE, IMAGE, container_name)
                if ( TESTS_REPO != "") {
                    salt.cmdRun(saltMaster, TARGET_NODE, "docker exec ${container_name} rm -rf ${container_workdir}/cvp*")
                    salt.cmdRun(saltMaster, TARGET_NODE, "docker exec ${container_name} git clone ${TESTS_REPO} ${container_workdir}/${container_name}")
                    TESTS_SET = container_workdir + '/' + container_name + '/' + TESTS_SET
                    if ( reinstall_env ) {
                        common.infoMsg("Pip packages in container will be reinstalled based on requirements.txt from ${TESTS_REPO}")
                        salt.cmdRun(saltMaster, TARGET_NODE, "docker exec ${container_name} pip install --force-reinstall -r ${container_workdir}/${container_name}/requirements.txt")
                    }
                }
            }
        }

        stage('Run Tests') {
            sh "mkdir -p ${artifacts_dir}"
            validate.runPyTests(SALT_MASTER_URL, SALT_MASTER_CREDENTIALS, TESTS_SET, TESTS_SETTINGS.tokenize(";"), container_name, TARGET_NODE, remote_dir, artifacts_dir)
        }

        stage ('Publish results') {
            archiveArtifacts artifacts: "${artifacts_dir}/*"
            junit "${artifacts_dir}/*.xml"
            if (env.JOB_NAME.contains("cvp-spt")) {
                plot csvFileName: 'plot-8634d2fe-dc48-4713-99f9-b69a381483aa.csv',
                     group: 'SPT',
                     style: 'line',
                     title: 'SPT Glance results',
                     xmlSeries: [[
                     file: "${env.JOB_NAME}_report.xml",
                     nodeType: 'NODESET',
                     url: '',
                     xpath: '/testsuite/testcase[@name="test_speed_glance"]/properties/property']]
                plot csvFileName: 'plot-8634d2fe-dc48-4713-99f9-b69a381483bb.csv',
                     group: 'SPT',
                     style: 'line',
                     title: 'SPT HW2HW results',
                     xmlSeries: [[
                     file: "${env.JOB_NAME}_report.xml",
                     nodeType: 'NODESET',
                     url: '',
                     xpath: '/testsuite/testcase[@classname="cvp_spt.tests.test_hw2hw"]/properties/property']]
                plot csvFileName: 'plot-8634d2fe-dc48-4713-99f9-b69a381483bc.csv',
                     group: 'SPT',
                     style: 'line',
                     title: 'SPT VM2VM results',
                     xmlSeries: [[
                     file: "${env.JOB_NAME}_report.xml",
                     nodeType: 'NODESET',
                     url: '',
                     xpath: '/testsuite/testcase[@classname="cvp_spt.tests.test_vm2vm"]/properties/property']]
            }
        }
    } catch (Throwable e) {
        // If there was an error or exception thrown, the build failed
        currentBuild.result = "FAILURE"
        throw e
    } finally {
        if (DEBUG_MODE == 'false') {
            validate.runCleanup(saltMaster, TARGET_NODE, container_name)
            salt.cmdRun(saltMaster, TARGET_NODE, "rm -rf ${remote_dir}")
        }
    }
}
