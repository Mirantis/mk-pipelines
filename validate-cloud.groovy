/**
 *
 * Launch validation of the cloud with Rally
 *
 * Expected parameters:
 *
 *   JOB_TIMEOUT                 Job timeout in hours
 *   SALT_MASTER_URL             URL of Salt master
 *   SALT_MASTER_CREDENTIALS     Credentials to the Salt API
 *   VALIDATE_PARAMS             Validate job YAML params (see below)
 *
 *   Rally - map with parameters for starting Rally tests
 *
 *   AVAILABILITY_ZONE           The name of availability zone
 *   FLOATING_NETWORK            The name of the external(floating) network
 *   K8S_RALLY                   Use Kubernetes Rally plugin for testing K8S cluster
 *   STACKLIGHT_RALLY            Use Stacklight Rally plugin for testing Stacklight
 *   RALLY_IMAGE                 The name of the image for Rally tests
 *   RALLY_FLAVOR                The name of the flavor for Rally image
 *   RALLY_PLUGINS_REPO          Git repository with Rally plugins
 *   RALLY_PLUGINS_BRANCH        Git branch which will be used during the checkout
 *   RALLY_CONFIG_REPO           Git repository with files for Rally
 *   RALLY_CONFIG_BRANCH         Git branch which will be used during the checkout
 *   RALLY_SCENARIOS             Path to file or directory with rally scenarios
 *   RALLY_SL_SCENARIOS          Path to file or directory with stacklight rally scenarios
 *   RALLY_TASK_ARGS_FILE        Path to file with rally tests arguments
 *   RALLY_DB_CONN_STRING        Rally-compliant DB connection string for long-term storing
 *                               results to external DB
 *   RALLY_TAGS                  List of tags for marking Rally tasks. Can be used when
 *                               generating Rally trends based on particular group of tasks
 *   RALLY_TRENDS                If enabled, generate Rally trends report. Requires external DB
 *                               connection string to be set. If RALLY_TAGS was set, trends will
 *                               be generated based on finished tasks with these tags, otherwise
 *                               on all the finished tasks available in DB
 *   SKIP_LIST                   List of the Rally scenarios which should be skipped
 *
 *   PARALLEL_PERFORMANCE        If enabled, run Rally tests separately in parallel for each sub directory found
 *                               inside RALLY_SCENARIOS and RALLY_SL_SCENARIOS (if STACKLIGHT_RALLY is enabled)
 *   GENERATE_REPORT             Set this to false if you are running longevity tests on a cicd node with less than
 *                               21GB memory. Rally consumes lots of memory when generating reports sourcing week
 *                               amounts of data (BUG PROD-30433)
 */

common = new com.mirantis.mk.Common()
validate = new com.mirantis.mcp.Validate()
salt = new com.mirantis.mk.Salt()
salt_testing = new com.mirantis.mk.SaltModelTesting()

def VALIDATE_PARAMS = readYaml(text: env.getProperty('VALIDATE_PARAMS')) ?: [:]
if (! VALIDATE_PARAMS) {
    throw new Exception("VALIDATE_PARAMS yaml is empty.")
}
def TEST_IMAGE = env.getProperty('TEST_IMAGE') ?: 'xrally-openstack:1.4.0'
def JOB_TIMEOUT = env.getProperty('JOB_TIMEOUT').toInteger() ?: 12
def SLAVE_NODE = env.getProperty('SLAVE_NODE') ?: 'docker'
def rally = VALIDATE_PARAMS.get('rally') ?: [:]
def scenariosRepo = rally.get('RALLY_CONFIG_REPO') ?: 'https://review.gerrithub.io/Mirantis/scale-scenarios'
def scenariosBranch = rally.get('RALLY_CONFIG_BRANCH') ?: 'master'
def pluginsRepo = rally.get('RALLY_PLUGINS_REPO') ?: 'https://github.com/Mirantis/rally-plugins'
def pluginsBranch = rally.get('RALLY_PLUGINS_BRANCH') ?: 'master'
def tags = rally.get('RALLY_TAGS') ?: []
def generateReport = rally.get('GENERATE_REPORT', true).toBoolean()

// contrainer working dir vars
def rallyWorkdir = '/home/rally'
def rallyPluginsDir = "${rallyWorkdir}/rally-plugins"
def rallyScenariosDir = "${rallyWorkdir}/rally-scenarios"
def rallyResultsDir = "${rallyWorkdir}/test_results"
def rallySecrets = "${rallyWorkdir}/secrets"

// env vars
def env_vars = []
def platform = [
    type: 'unknown',
    stacklight: [enabled: false, grafanaPass: ''],
]
def cmp_count

// test results vars
def testResult
def tasksParallel = [:]
def parallelResults = [:]
def configRun = [:]

timeout(time: JOB_TIMEOUT, unit: 'HOURS') {
    node (SLAVE_NODE) {

        // local dir vars
        def workDir = "${env.WORKSPACE}/rally"
        def pluginsDir = "${workDir}/rally-plugins"
        def scenariosDir = "${workDir}/rally-scenarios"
        def secrets = "${workDir}/secrets"
        def artifacts = "${workDir}/validation_artifacts"

        stage('Configure env') {

            def master = salt.connection(SALT_MASTER_URL, SALT_MASTER_CREDENTIALS)

            // create local directories
            sh "rm -rf ${workDir} || true"
            sh "mkdir -p ${artifacts} ${secrets}"
            writeFile file: "${workDir}/entrypoint.sh", text: '''#!/bin/bash
set -xe
exec "$@"
'''
            sh "chmod 755 ${workDir}/entrypoint.sh"

            // clone repo with Rally plugins and checkout refs/branch
            checkout([
                $class           : 'GitSCM',
                branches         : [[name: 'FETCH_HEAD']],
                extensions       : [[$class: 'RelativeTargetDirectory', relativeTargetDir: pluginsDir]],
                userRemoteConfigs: [[url: pluginsRepo, refspec: pluginsBranch]],
            ])

            // clone scenarios repo and switch branch / fetch refspecs
            checkout([
                $class           : 'GitSCM',
                branches         : [[name: 'FETCH_HEAD']],
                extensions       : [[$class: 'RelativeTargetDirectory', relativeTargetDir: scenariosDir]],
                userRemoteConfigs: [[url: scenariosRepo, refspec: scenariosBranch]],
            ])

            // get number of computes in the cluster
            platform['cluster_name'] = salt.getPillar(
                master, 'I@salt:master', '_param:cluster_name'
            )['return'][0].values()[0]
            def rcs_str_node = salt.getPillar(
                master, 'I@salt:master', 'reclass:storage:node'
            )['return'][0].values()[0]

            // set up Openstack env variables
            if (rally.get('K8S_RALLY').toBoolean() == false) {

                platform['type'] = 'openstack'
                platform['cmp_count'] = rcs_str_node.openstack_compute_rack01['repeat']['count']
                def rally_variables = [
                    "floating_network=${rally.FLOATING_NETWORK}",
                    "rally_image=${rally.RALLY_IMAGE}",
                    "rally_flavor=${rally.RALLY_FLAVOR}",
                    "availability_zone=${rally.AVAILABILITY_ZONE}",
                ]

                env_vars = validate._get_keystone_creds_v3(master)
                if (!env_vars) {
                    env_vars = validate._get_keystone_creds_v2(master)
                }
                env_vars = env_vars + rally_variables

            } else {
            // set up Kubernetes env variables get required secrets
                platform['type'] = 'k8s'
                platform['cmp_count'] = rcs_str_node.kubernetes_compute_rack01['repeat']['count']

                def kubernetes = salt.getPillar(
                    master, 'I@kubernetes:master and *01*', 'kubernetes:master'
                )['return'][0].values()[0]

                env_vars = [
                    "KUBERNETES_HOST=http://${kubernetes.apiserver.vip_address}" +
                    ":${kubernetes.apiserver.insecure_port}",
                    "KUBERNETES_CERT_AUTH=${rallySecrets}/k8s-ca.crt",
                    "KUBERNETES_CLIENT_KEY=${rallySecrets}/k8s-client.key",
                    "KUBERNETES_CLIENT_CERT=${rallySecrets}/k8s-client.crt",
                ]

                // get K8S certificates to manage cluster
                def k8s_ca = salt.getFileContent(
                    master, 'I@kubernetes:master and *01*', '/etc/kubernetes/ssl/ca-kubernetes.crt'
                )
                def k8s_client_key = salt.getFileContent(
                    master, 'I@kubernetes:master and *01*', '/etc/kubernetes/ssl/kubelet-client.key'
                )
                def k8s_client_crt = salt.getFileContent(
                    master, 'I@kubernetes:master and *01*', '/etc/kubernetes/ssl/kubelet-client.crt'
                )
                writeFile file: "${secrets}/k8s-ca.crt", text: k8s_ca
                writeFile file: "${secrets}/k8s-client.key", text: k8s_client_key
                writeFile file: "${secrets}/k8s-client.crt", text: k8s_client_crt

            }

            // get Stacklight data
            if (rally.STACKLIGHT_RALLY.toBoolean() == true) {
                platform['stacklight']['enabled'] = true

                def grafana = salt.getPillar(
                    master, 'I@grafana:client', 'grafana:client:server'
                )['return'][0].values()[0]

                platform['stacklight']['grafanaPass'] = grafana['password']
            }

            if (! rally.PARALLEL_PERFORMANCE.toBoolean()) {

                // Define map with docker commands
                def commands = validate.runRallyTests(
                    platform, rally.RALLY_SCENARIOS,
                    rally.RALLY_SL_SCENARIOS, rally.RALLY_TASK_ARGS_FILE,
                    rally.RALLY_DB_CONN_STRING, tags,
                    rally.RALLY_TRENDS.toBoolean(), rally.SKIP_LIST, generateReport
                )
                def commands_list = commands.collectEntries{ [ (it.key) : { sh("${it.value}") } ] }

                configRun = [
                    'image': TEST_IMAGE,
                    'baseRepoPreConfig': false,
                    'dockerMaxCpus': 2,
                    'dockerHostname': 'localhost',
                    'dockerExtraOpts': [
                        "--network=host",
                        "--entrypoint=/entrypoint.sh",
                        "-w ${rallyWorkdir}",
                        "-v ${workDir}/entrypoint.sh:/entrypoint.sh",
                        "-v ${pluginsDir}/:${rallyPluginsDir}",
                        "-v ${scenariosDir}/:${rallyScenariosDir}",
                        "-v ${artifacts}/:${rallyResultsDir}",
                        "-v ${secrets}/:${rallySecrets}",
                    ],
                    'envOpts'         : env_vars,
                    'runCommands'     : commands_list,
                ]
                common.infoMsg('Docker config:')
                println configRun
                common.infoMsg('Docker commands list:')
                println commands

            } else {

                // Perform parallel testing of the components with Rally
                def components = [
                    Common: [],
                    Stacklight: [],
                ]

                // get list of directories inside scenarios path
                def scenPath = "${scenariosDir}/${rally.RALLY_SCENARIOS}"
                def mainComponents = sh(
                    script: "find ${scenPath} -maxdepth 1 -mindepth 1 -type d -exec basename {} \\;",
                    returnStdout: true,
                ).trim()
                if (! mainComponents) {
                    error(
                        "No directories found inside RALLY_SCENARIOS ${rally.RALLY_SCENARIOS}\n" +
                        "Either set PARALLEL_PERFORMANCE=false or populate ${rally.RALLY_SCENARIOS} " +
                        "with component directories which include corresponding scenarios"
                    )
                }
                components['Common'].addAll(mainComponents.split('\n'))
                common.infoMsg( "Adding for parallel execution sub dirs found in " +
                    "RALLY_SCENARIOS (${rally.RALLY_SCENARIOS}):"
                )
                print mainComponents

                if (rally.STACKLIGHT_RALLY.toBoolean() == true) {
                    def slScenPath = "${scenariosDir}/${rally.RALLY_SL_SCENARIOS}"
                    def slComponents = sh(
                        script: "find ${slScenPath} -maxdepth 1 -mindepth 1 -type d -exec basename {} \\;",
                        returnStdout: true,
                    ).trim()
                    if (! slComponents) {
                        error(
                            "No directories found inside RALLY_SCENARIOS ${rally.RALLY_SL_SCENARIOS}\n" +
                            "Either set PARALLEL_PERFORMANCE=false or populate ${rally.RALLY_SL_SCENARIOS} " +
                            "with component directories which include corresponding scenarios"
                        )
                    }
                    components['Stacklight'].addAll(slComponents.split('\n'))
                    common.infoMsg( "Adding for parallel execution sub dirs found in " +
                        "RALLY_SL_SCENARIOS (${rally.RALLY_SL_SCENARIOS}):"
                    )
                    print slComponents
                }

                // build up a map with tasks for parallel execution
                def allComponents = components.values().flatten()
                for (int i=0; i < allComponents.size(); i++) {
                    // randomize run so we don't bump each other at the startup
                    // also we need to let first thread create rally deployment
                    // so all the rest rally threads can use it after
                    def sleepSeconds = 15 * i

                    def task = allComponents[i]
                    def task_name = 'rally_' + task
                    def curComponent = components.find { task in it.value }.key
                    // inherit platform common data
                    def curPlatform = platform

                    // setup scenarios and stacklight switch per component
                    def commonScens = "${rally.RALLY_SCENARIOS}/${task}"
                    def stacklightScens = "${rally.RALLY_SL_SCENARIOS}/${task}"

                    switch (curComponent) {
                        case 'Common':
                            stacklightScens = ''
                            curPlatform['stacklight']['enabled'] = false
                        break
                        case 'Stacklight':
                            commonScens = ''
                            curPlatform['stacklight']['enabled'] = true
                        break
                    }

                    def curCommands = validate.runRallyTests(
                        curPlatform, commonScens,
                        stacklightScens, rally.RALLY_TASK_ARGS_FILE,
                        rally.RALLY_DB_CONN_STRING, tags,
                        rally.RALLY_TRENDS.toBoolean(), rally.SKIP_LIST,
                        generateReport
                    )

                    // copy required files for the current task
                    def taskWorkDir = "${env.WORKSPACE}/rally_" + task
                    def taskPluginsDir = "${taskWorkDir}/rally-plugins"
                    def taskScenariosDir = "${taskWorkDir}/rally-scenarios"
                    def taskArtifacts = "${taskWorkDir}/validation_artifacts"
                    def taskSecrets = "${taskWorkDir}/secrets"
                    sh "rm -rf ${taskWorkDir} || true"
                    sh "cp -ra ${workDir} ${taskWorkDir}"

                    def curCommandsList = curCommands.collectEntries{ [ (it.key) : { sh("${it.value}") } ] }
                    def curConfigRun = [
                        'image': TEST_IMAGE,
                        'baseRepoPreConfig': false,
                        'dockerMaxCpus': 2,
                        'dockerHostname': 'localhost',
                        'dockerExtraOpts': [
                            "--network=host",
                            "--entrypoint=/entrypoint.sh",
                            "-w ${rallyWorkdir}",
                            "-v ${taskWorkDir}/entrypoint.sh:/entrypoint.sh",
                            "-v ${taskPluginsDir}/:${rallyPluginsDir}",
                            "-v ${taskScenariosDir}/:${rallyScenariosDir}",
                            "-v ${taskArtifacts}/:${rallyResultsDir}",
                            "-v ${taskSecrets}/:${rallySecrets}",
                        ],
                        'envOpts'         : env_vars,
                        'runCommands'     : curCommandsList,
                    ]

                    tasksParallel['rally_' + task] = {
                        sleep sleepSeconds
                        common.infoMsg("Docker config for task $task")
                        println curConfigRun
                        common.infoMsg("Docker commands list for task $task")
                        println curCommands
                        parallelResults[task_name] = salt_testing.setupDockerAndTest(curConfigRun)
                    }
                }
            }
        }

        stage('Run Rally tests') {

            def dockerStatuses = [:]

            // start tests in Docker
            if (! rally.PARALLEL_PERFORMANCE.toBoolean()) {
                testResult = salt_testing.setupDockerAndTest(configRun)
                dockerStatuses['rally'] = (testResult) ? 'OK' : 'FAILED'
            } else {
                common.infoMsg('Jobs to run in threads: ' + tasksParallel.keySet().join(' '))
                parallel tasksParallel
                parallelResults.each { task ->
                    dockerStatuses[task.key] = (task.value) ? 'OK' : 'FAILED'
                }
            }
            // safely archiving all possible results
            dockerStatuses.each { task ->
                print "Collecting results for ${task.key} (docker status = '${task.value}')"
                try {
                    archiveArtifacts artifacts: "${task.key}/validation_artifacts/*"
                } catch (Throwable e) {
                    print 'failed to get artifacts'
                }
            }
            // setting final job status
            def failed = dockerStatuses.findAll { it.value == 'FAILED' }
            if (failed.size() == dockerStatuses.size()) {
                currentBuild.result = 'FAILURE'
            } else if (dockerStatuses.find { it.value != 'OK' }) {
                currentBuild.result = 'UNSTABLE'
            }
        }

        stage('Clean env') {
            // remove secrets
            sh 'find ./ -type d -name secrets -exec rm -rf \\\"{}\\\" \\; || true'
        }
    }
}
