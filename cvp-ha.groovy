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
 *
 */

common = new com.mirantis.mk.Common()
salt = new com.mirantis.mk.Salt()
validate = new com.mirantis.mcp.Validate()

def saltMaster
def artifacts_dir = 'validation_artifacts/'
def remote_artifacts_dir = '/root/qa_results/'
def current_target_node = ''
def tempest_result = ''
node() {
    def num_retries = Integer.parseInt(RETRY_CHECK_STATUS)
    try {
        stage('Initialization') {
            saltMaster = salt.connection(SALT_MASTER_URL, SALT_MASTER_CREDENTIALS)
            validate.runBasicContainer(saltMaster, TEMPEST_TARGET_NODE, TEST_IMAGE)
            sh "rm -rf ${artifacts_dir}"
            salt.cmdRun(saltMaster, TEMPEST_TARGET_NODE, "mkdir -p ${remote_artifacts_dir}")
            validate.configureContainer(saltMaster, TEMPEST_TARGET_NODE, PROXY, TOOLS_REPO, TEMPEST_REPO)
        }

        stage('Initial env check') {
            sh "mkdir -p ${artifacts_dir}"
            tempest_result = validate.runCVPtempest(saltMaster, TEMPEST_TARGET_NODE, TEMPEST_TEST_PATTERN, SKIP_LIST_PATH, remote_artifacts_dir, "docker_tempest_initial")
            validate.openstack_cleanup(saltMaster, TEMPEST_TARGET_NODE)
            if (tempest_result != "finished") {
                currentBuild.result = "FAILURE"
                throw new Exception("Tempest tests failed")
            }
        }

        stage('Soft Shutdown') {
            if (MANUAL_CONFIRMATION.toBoolean() == true) {
                stage('Ask for manual confirmation') {
                    input message: "Are you sure you want to shutdown current vip node?"
                }
            }
            current_target_node = validate.get_vip_node(saltMaster, TARGET_NODES)
            common.warningMsg("Shutdown current vip node ${current_target_node}")
            validate.shutdown_vm_node(saltMaster, current_target_node, 'soft_shutdown')
        }
        stage('Check during shutdown') {
            tempest_result = validate.runCVPtempest(saltMaster, TEMPEST_TARGET_NODE, TEMPEST_TEST_PATTERN, SKIP_LIST_PATH, remote_artifacts_dir, "docker_tempest_during_shutdown")
            validate.openstack_cleanup(saltMaster, TEMPEST_TARGET_NODE)
            if (tempest_result != "finished") {
                currentBuild.result = "FAILURE"
                throw new Exception("Tempest tests failed")
            }
        }
        stage('Power on') {
            common.infoMsg('Powering on node')
            kvm = validate.locate_node_on_kvm(saltMaster, current_target_node)
            salt.cmdRun(saltMaster, kvm, "virsh start ${current_target_node}")
            common.infoMsg("Checking that node is UP")
            status = salt.minionsReachable(saltMaster, 'I@salt:master', current_target_node, null, 10, num_retries)
            if (status == null) {
                throw new Exception("Node ${current_target_node} cannot start")
            }
        }
        stage('Check after shutdown') {
            tempest_result = validate.runCVPtempest(saltMaster, TEMPEST_TARGET_NODE, TEMPEST_TEST_PATTERN, SKIP_LIST_PATH, remote_artifacts_dir, "docker_tempest_after_shutdown")
            validate.openstack_cleanup(saltMaster, TEMPEST_TARGET_NODE)
            if (tempest_result != "finished") {
                currentBuild.result = "FAILURE"
                throw new Exception("Tempest tests failed")
            }
            sleep 15
        }

        stage('Hard Shutdown') {
            if (MANUAL_CONFIRMATION.toBoolean() == true) {
                stage('Ask for manual confirmation') {
                    input message: "Are you sure you want to hard shutdown current vip node?"
                }
            }
            salt.cmdRun(saltMaster, current_target_node, "service keepalived stop")
            current_target_node = validate.get_vip_node(saltMaster, TARGET_NODES)
            common.warningMsg("Shutdown current vip node ${current_target_node}")
            validate.shutdown_vm_node(saltMaster, current_target_node, 'hard_shutdown')
        }
        stage('Check during hard shutdown') {
            tempest_result = validate.runCVPtempest(saltMaster, TEMPEST_TARGET_NODE, TEMPEST_TEST_PATTERN, SKIP_LIST_PATH, remote_artifacts_dir, "docker_tempest_during_hard_shutdown")
            validate.openstack_cleanup(saltMaster, TEMPEST_TARGET_NODE)
            if (tempest_result != "finished") {
                currentBuild.result = "FAILURE"
                throw new Exception("Tempest tests failed")
            }
        }
        stage('Power on') {
            common.infoMsg('Powering on node')
            kvm = validate.locate_node_on_kvm(saltMaster, current_target_node)
            salt.cmdRun(saltMaster, kvm, "virsh start ${current_target_node}")
            common.infoMsg("Checking that node is UP")
            status = salt.minionsReachable(saltMaster, 'I@salt:master', current_target_node, null, 10, num_retries)
            if (status == null) {
                throw new Exception("Command execution failed")
            }
            salt.cmdRun(saltMaster, TARGET_NODES, "service keepalived start")
        }
        stage('Check after hard shutdown') {
            tempest_result = validate.runCVPtempest(saltMaster, TEMPEST_TARGET_NODE, TEMPEST_TEST_PATTERN, SKIP_LIST_PATH, remote_artifacts_dir, "docker_tempest_after_hard_shutdown")
            validate.openstack_cleanup(saltMaster, TEMPEST_TARGET_NODE)
            if (tempest_result != "finished") {
                currentBuild.result = "FAILURE"
                throw new Exception("Tempest tests failed")
            }
            sleep 15
        }

        stage('Reboot') {
            if (MANUAL_CONFIRMATION.toBoolean() == true) {
                stage('Ask for manual confirmation') {
                    input message: "Are you sure you want to reboot current vip node?"
                }
            }
            current_target_node = validate.get_vip_node(saltMaster, TARGET_NODES)
            common.warningMsg("Rebooting current vip node ${current_target_node}")
            validate.shutdown_vm_node(saltMaster, current_target_node, 'reboot')
            sleep 5
        }
        stage('Check during reboot') {
            tempest_result = validate.runCVPtempest(saltMaster, TEMPEST_TARGET_NODE, TEMPEST_TEST_PATTERN, SKIP_LIST_PATH, remote_artifacts_dir, "docker_tempest_during_reboot")
            validate.openstack_cleanup(saltMaster, TEMPEST_TARGET_NODE)
            if (tempest_result != "finished") {
                currentBuild.result = "FAILURE"
                throw new Exception("Tempest tests failed")
            }
        }
        stage('Check after reboot') {
            common.warningMsg("Checking that node is UP")
            status = salt.minionsReachable(saltMaster, 'I@salt:master', current_target_node, null, 10, num_retries)
            if (status == null) {
                throw new Exception("Node ${current_target_node} cannot start")
            }
            tempest_result = validate.runCVPtempest(saltMaster, TEMPEST_TARGET_NODE, TEMPEST_TEST_PATTERN, SKIP_LIST_PATH, remote_artifacts_dir, "docker_tempest_after")
            validate.openstack_cleanup(saltMaster, TEMPEST_TARGET_NODE)
            if (tempest_result != "finished") {
                currentBuild.result = "FAILURE"
                throw new Exception("Tempest tests failed")
            }
        }

        stage('Collect results') {
            val.addFiles(saltMaster, TEMPEST_TARGET_NODE, remote_artifacts_dir, artifacts_dir)
            archiveArtifacts artifacts: "${artifacts_dir}/*"
            if (DEBUG_MODE == 'false') {
                validate.runCleanup(saltMaster, TEMPEST_TARGET_NODE)
                salt.cmdRun(saltMaster, TEMPEST_TARGET_NODE, "rm -rf ${remote_artifacts_dir}")
            }
        }
    } finally {
        if (DEBUG_MODE == 'false') {
            salt.cmdRun(saltMaster, TEMPEST_TARGET_NODE, "rm -rf ${remote_artifacts_dir}")
            validate.runCleanup(saltMaster, TEMPEST_TARGET_NODE)
        }
    }
}
