/**
 *
 * Launch CVP Tempest verification of the cloud
 *
 * Expected parameters:

 *   SALT_MASTER_URL             URL of Salt master
 *   SALT_MASTER_CREDENTIALS     Credentials that are used in this Jenkins for accessing Salt master (usually "salt")
 *   SERVICE_NODE                Node, where runtest formula and some other states will be executed
 *   VERBOSE                     Show salt output in Jenkins console
 *   DEBUG_MODE                  Remove or keep container after the test
 *   STOP_ON_ERROR               Stop pipeline if error during salt run occurs
 *   GENERATE_CONFIG             Run runtest formula / generate Tempest config
 *   SKIP_LIST_PATH              Path to skip list (not in use right now)
 *   TEST_IMAGE                  Docker image link to use for running container with testing tools.
 *   TARGET_NODE                 Node to run container with Tempest/Rally
 *   PREPARE_RESOURCES           Prepare Openstack resources before test run
 *   TEMPEST_TEST_PATTERN        Tests to run
 *   TEMPEST_ENDPOINT_TYPE       Type of OS endpoint to use during test run (not in use right now)
 *   concurrency                 Number of threads to use for Tempest test run
 *   remote_artifacts_dir        Folder to use for artifacts on remote node
 *   report_prefix               Some prefix to put to report name
 *
 */


common = new com.mirantis.mk.Common()
salt = new com.mirantis.mk.Salt()
validate = new com.mirantis.mcp.Validate()

def saltMaster
extraYamlContext = env.getProperty('EXTRA_PARAMS')
if (extraYamlContext) {
    common.mergeEnv(env, extraYamlContext) }
def SALT_MASTER_CREDENTIALS=(env.SALT_MASTER_CREDENTIALS) ?: 'salt'
def VERBOSE = (env.VERBOSE) ? env.VERBOSE.toBoolean() : true
def DEBUG_MODE = (env.DEBUG_MODE) ?: false
def STOP_ON_ERROR = (env.STOP_ON_ERROR) ? env.STOP_ON_ERROR.toBoolean() : false
def GENERATE_CONFIG = (env.GENERATE_CONFIG) ?: true
def remote_artifacts_dir = (env.remote_artifacts_dir) ?: '/root/test/'
def report_prefix = (env.report_prefix) ?: ''
def args = ''
node() {
    try{
        stage('Initialization') {
            deleteDir()
            saltMaster = salt.connection(SALT_MASTER_URL, SALT_MASTER_CREDENTIALS)
            container_name = "${env.JOB_NAME}"
            cluster_name=salt.getPillar(saltMaster, 'I@salt:master', '_param:cluster_name')['return'][0].values()[0]
            os_version=salt.getPillar(saltMaster, 'I@salt:master', '_param:openstack_version')['return'][0].values()[0]
            if (os_version == '') {
                throw new Exception("Openstack is not found on this env. Exiting")
            }
            TEST_IMAGE = (env.TEST_IMAGE) ?: "docker-prod-virtual.docker.mirantis.net/mirantis/cicd/ci-tempest:${os_version}"
            runtest_node = salt.runSaltProcessStep(saltMaster, 'I@runtest:*', 'test.ping')['return'][0]
            if (runtest_node.values()[0]) {
                // Let's use Service node that was defined in reclass. If several nodes are defined
                // we will use the first from salt output
                common.infoMsg("Service node ${runtest_node.keySet()[0]} is defined in reclass")
                SERVICE_NODE = runtest_node.keySet()[0]
            }
            else {
                throw new Exception("Runtest config is not found in reclass. Please create runtest.yml and include it " +
                                    "into reclass. Check documentation for more details")
            }
            common.infoMsg('Refreshing pillars on service node')
            salt.runSaltProcessStep(saltMaster, SERVICE_NODE, 'saltutil.refresh_pillar', [], null, VERBOSE)
            tempest_node=salt.getPillar(saltMaster, SERVICE_NODE, '_param:tempest_test_target')['return'][0].values()[0] ?: 'I@gerrit:client'
        }
        stage('Preparing resources') {
            if ( PREPARE_RESOURCES.toBoolean() ) {
                common.infoMsg('Running salt.minion state on service node')
                salt.enforceState(saltMaster, SERVICE_NODE, ['salt.minion'], VERBOSE, STOP_ON_ERROR, null, false, 300, 2, true, [], 60)
                common.infoMsg('Running keystone.client on service node')
                salt.enforceState(saltMaster, SERVICE_NODE, 'keystone.client', VERBOSE, STOP_ON_ERROR)
                common.infoMsg('Running glance.client on service node')
                salt.enforceState(saltMaster, SERVICE_NODE, 'glance.client', VERBOSE, STOP_ON_ERROR)
                common.infoMsg('Running nova.client on service node')
                salt.enforceState(saltMaster, SERVICE_NODE, 'nova.client', VERBOSE, STOP_ON_ERROR)
            }
            else {
                common.infoMsg('Skipping resources preparation')
            }
        }
        stage('Generate config') {
            if ( GENERATE_CONFIG.toBoolean() ) {
                salt.runSaltProcessStep(saltMaster, SERVICE_NODE, 'file.remove', ["${remote_artifacts_dir}"])
                salt.runSaltProcessStep(saltMaster, SERVICE_NODE, 'file.mkdir', ["${remote_artifacts_dir}"])
                fullnodename = salt.getMinions(saltMaster, SERVICE_NODE).get(0)
                TARGET_NODE = (env.TARGET_NODE) ?: tempest_node
                if (TARGET_NODE != tempest_node) {
                    common.infoMsg("TARGET_NODE is defined in Jenkins")
                    def params_to_update = ['tempest_test_target': "${TARGET_NODE}"]
                    common.infoMsg("Overriding default ${tempest_node} value of tempest_test_target parameter")
                    result = salt.runSaltCommand(saltMaster, 'local', ['expression': SERVICE_NODE, 'type': 'compound'], 'reclass.node_update',
                                                 null, null, ['name': fullnodename, 'parameters': ['tempest_test_target': "${TARGET_NODE}"]])
                    salt.checkResult(result)
                }
                common.infoMsg("TARGET_NODE is ${TARGET_NODE}")
                salt.runSaltProcessStep(saltMaster, TARGET_NODE, 'file.remove', ["${remote_artifacts_dir}"])
                salt.runSaltProcessStep(saltMaster, TARGET_NODE, 'file.mkdir', ["${remote_artifacts_dir}"])
                salt.enforceState(saltMaster, SERVICE_NODE, 'runtest', VERBOSE, STOP_ON_ERROR)
                // we need to refresh pillars on target node after runtest state
                salt.runSaltProcessStep(saltMaster, TARGET_NODE, 'saltutil.refresh_pillar', [], null, VERBOSE)
                if (TARGET_NODE != tempest_node) {
                    common.infoMsg("Reverting tempest_test_target parameter")
                    result = salt.runSaltCommand(saltMaster, 'local', ['expression': SERVICE_NODE, 'type': 'compound'], 'reclass.node_update',
                                                 null, null, ['name': fullnodename, 'parameters': ['tempest_test_target': "${tempest_node}"]])
                }
                SKIP_LIST_PATH = (env.SKIP_LIST_PATH) ?: salt.getPillar(saltMaster, SERVICE_NODE, '_param:tempest_skip_list_path')['return'][0].values()[0]
                runtest_tempest_cfg_dir = salt.getPillar(saltMaster, SERVICE_NODE, '_param:runtest_tempest_cfg_dir')['return'][0].values()[0] ?: '/root/test/'
                if (SKIP_LIST_PATH) {
                    salt.cmdRun(saltMaster, SERVICE_NODE, "salt-cp ${TARGET_NODE} ${SKIP_LIST_PATH} ${runtest_tempest_cfg_dir}/skip.list")
                    args += ' --blacklist-file /root/tempest/skip.list '
                }
            }
            else {
                common.infoMsg('Skipping Tempest config generation')
                salt.cmdRun(saltMaster, TARGET_NODE, "rm -rf ${remote_artifacts_dir}/reports")
            }
        }

        stage('Run Tempest tests') {
            mounts = ['/root/test/tempest_generated.conf': '/etc/tempest/tempest.conf']
            validate.runContainer(master: saltMaster, target: TARGET_NODE, dockerImageLink: TEST_IMAGE,
                                  mounts: mounts, name: container_name)
            report_prefix += 'tempest_'
            if (env.concurrency) {
                args += ' -w ' + env.concurrency
            }
            if (TEMPEST_TEST_PATTERN == 'set=smoke') {
                args += ' -s '
                report_prefix += 'smoke'
            }
            else {
                if (TEMPEST_TEST_PATTERN != 'set=full') {
                    args += " -r ${TEMPEST_TEST_PATTERN} "
                    report_prefix += 'full'
                }
            }
            salt.cmdRun(saltMaster, TARGET_NODE, "docker exec -e ARGS=\'${args}\' ${container_name} /bin/bash -c 'run-tempest'")
        }
        stage('Collect results') {
            report_prefix += "_report_${env.BUILD_NUMBER}"
            // will be removed after changing runtest-formula logic
            salt.cmdRun(saltMaster, TARGET_NODE, "mkdir -p ${remote_artifacts_dir}/reports; mv ${remote_artifacts_dir}/report_* ${remote_artifacts_dir}/reports")
            validate.addFiles(saltMaster, TARGET_NODE, "${remote_artifacts_dir}/reports", '')
            sh "mv report_*.xml ${report_prefix}.xml"
            sh "mv report_*.log ${report_prefix}.log"
            archiveArtifacts artifacts: "${report_prefix}.*"
            junit "${report_prefix}.xml"
        }
    } catch (Throwable e) {
        // If there was an error or exception thrown, the build failed
        currentBuild.result = "FAILURE"
        throw e
    } finally {
        if (DEBUG_MODE == 'false') {
            validate.runCleanup(saltMaster, TARGET_NODE, container_name)
        }
    }
}
