/**
 *
 * Launch HA test for the cloud
 *
 * Expected parameters:
 *
 *   SALT_MASTER_URL             URL of Salt master
 *   SALT_MASTER_CREDENTIALS     Credentials that are used in this Jenkins for accessing Salt master (usually "salt")
 *   PROXY                       Proxy address (if any) for accessing the Internet. It will be used for cloning repos and installing pip dependencies
 *   TEST_IMAGE                  Docker image link to use for running container with testing tools.
 *   TOOLS_REPO                  URL of repo where testing tools, scenarios, configs are located
 *
 *   DEBUG_MODE                  If you need to debug (keep container after test), please enabled this
 *   MANUAL_CONFIRMATION         Ask for confirmation before doing something destructive (reboot/shutdown node)
 *   RETRY_CHECK_STATUS          Number of retries to check node status
 *   SKIP_LIST_PATH              Path to tempest skip list file in TOOLS_REPO
 *   TARGET_NODES                Nodes to test
 *   TEMPEST_REPO                Tempest repo to clone and use
 *   TEMPEST_TARGET_NODE         Node, where tests will be executed
 *   TEMPEST_TEST_PATTERN        Tests to run during HA scenarios
 *
 */

common = new com.mirantis.mk.Common()
salt = new com.mirantis.mk.Salt()
validate = new com.mirantis.mcp.Validate()

def saltMaster
def artifacts_dir = 'validation_artifacts/'
def remote_artifacts_dir = '/root/qa_results/'
def current_target_node = ''
def first_node = ''
def tempest_result = ''
timeout(time: 12, unit: 'HOURS') {
    node() {
        def num_retries = Integer.parseInt(RETRY_CHECK_STATUS)
        try {
            stage('Initialization') {
                saltMaster = salt.connection(SALT_MASTER_URL, SALT_MASTER_CREDENTIALS)
                validate.runBasicContainer(saltMaster, TEMPEST_TARGET_NODE, TEST_IMAGE)
                sh "rm -rf ${artifacts_dir}"
                salt.cmdRun(saltMaster, TEMPEST_TARGET_NODE, "rm -rf ${remote_artifacts_dir}")
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
                sleep 15
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
                first_node = current_target_node
                current_target_node = ''
                sleep 30
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
                salt.cmdRun(saltMaster, first_node, "service keepalived stop")
                current_target_node = validate.get_vip_node(saltMaster, TARGET_NODES)
                common.warningMsg("Shutdown current vip node ${current_target_node}")
                validate.shutdown_vm_node(saltMaster, current_target_node, 'hard_shutdown')
                sleep 10
                salt.cmdRun(saltMaster, first_node, "service keepalived start")
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
                    throw new Exception("Node ${current_target_node} cannot start")
                }
                current_target_node = ''
                sleep 30
            }
            stage('Check after hard shutdown') {
                tempest_result = validate.runCVPtempest(saltMaster, TEMPEST_TARGET_NODE, TEMPEST_TEST_PATTERN, SKIP_LIST_PATH, remote_artifacts_dir, "docker_tempest_after_hard_shutdown")
                validate.openstack_cleanup(saltMaster, TEMPEST_TARGET_NODE)
                if (tempest_result != "finished") {
                    currentBuild.result = "FAILURE"
                    throw new Exception("Tempest tests failed")
                }
                sleep 5
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
                sleep 30
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
                validate.addFiles(saltMaster, TEMPEST_TARGET_NODE, remote_artifacts_dir, artifacts_dir)
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
                if (current_target_node != '') {
                    common.warningMsg("Powering on node ${current_target_node}")
                    kvm = validate.locate_node_on_kvm(saltMaster, current_target_node)
                    salt.cmdRun(saltMaster, kvm, "virsh start ${current_target_node}")
                }
            }
        }
    }
}
