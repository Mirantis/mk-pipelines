/**
 *
 * Launch pytest frameworks in Jenkins
 *
 * Expected parameters:
 *   SALT_MASTER_URL                 URL of Salt master
 *   SALT_MASTER_CREDENTIALS         Credentials to the Salt API
 *   IMAGE                           Docker image link to use for running container with test framework.
 *   EXTRA_PARAMS                    Yaml context which contains additional setting for job
 *
 */

common = new com.mirantis.mk.Common()
validate = new com.mirantis.mcp.Validate()
salt_testing = new com.mirantis.mk.SaltModelTesting()

def EXTRA_PARAMS = readYaml(text: env.getProperty('EXTRA_PARAMS')) ?: [:]
def env_vars = EXTRA_PARAMS.get("envs") ?: []
def override_config = env.getProperty('EXTRA_PARAMS') ?: ""

def IMAGE = (env.getProperty('IMAGE')) ?: 'docker-prod-local.docker.mirantis.net/mirantis/cvp/cvp-sanity-checks:stable'
def SLAVE_NODE = (env.getProperty('SLAVE_NODE')) ?: 'docker'

/*
YAML example
=====
# commands is a map of commands which looks like step_name: shell_command
commands:
  001_prepare: rm /var/lib/g.txt
  002_prepare: git clone http://repo_with_tests.git
  003_test: cd repo_with_tests && pytest /var/lib/ --collect-only
  004_collect: cp cvp-spt /var/lib/validation_artifacts/
# envs is a list of new environment variables
envs:
  - SALT_USERNAME=admin
  - SALT_PASSWORD=password
  - drivetrain_version=testing
*/

node (SLAVE_NODE) {
    def artifacts_dir = 'validation_artifacts'
    def test_suite_name = "${env.JOB_NAME}"
    def xml_file = "${test_suite_name}_report.xml"

    def configRun = [:]
    try {
        withEnv(env_vars) {
            stage('Initialization') {
                def container_workdir = '/var/lib'
                def tests_set = (env.getProperty('tests_set')) ?: ''
                def script = "pytest --junitxml ${container_workdir}/${artifacts_dir}/${xml_file} -vv ${tests_set}"

                sh "mkdir -p ${artifacts_dir}"

                // Enrichment for docker commands
                def commands = EXTRA_PARAMS.get("commands") ?: ['010_start_tests': "tox -e ${test_suite_name} -- ${script}"]
                def commands_list = commands.collectEntries{ [ (it.key) : { sh("${it.value}") } ] }

                // Enrichment for env variables
                def creds = common.getCredentials(SALT_MASTER_CREDENTIALS)
                def env_vars_list  =  [
                    "SALT_USERNAME=${creds.username}",
                    "SALT_PASSWORD=${creds.password}",
                    "SALT_URL=${SALT_MASTER_URL}",
                    "REQUESTS_CA_BUNDLE=/etc/ssl/certs/ca-certificates.crt",
                    "OVERRIDE_CONFIG=${override_config}"
                    ] + env_vars

                // Generating final config
                def force_pull = (env.getProperty('force_pull')) ?: false
                configRun = [
                    'image': IMAGE,
                    'dockerPull': force_pull.toBoolean(),
                    'baseRepoPreConfig': false,
                    'dockerMaxCpus': 2,
                    'dockerExtraOpts' : [
                        "--network=host",
                        "-v /root/qa_results/:/root/qa_results/",
                        "-v /etc/ssl/certs/:/etc/ssl/certs/:ro",
                        "-v ${env.WORKSPACE}/${artifacts_dir}/:${container_workdir}/${artifacts_dir}/",
                    ],
                    'envOpts'         : env_vars_list,
                    'runCommands'     : commands_list
                ]
            }

            stage('Run Tests') {
                salt_testing.setupDockerAndTest(configRun)
            }

            stage ('Publish results') {
                archiveArtifacts artifacts: "${artifacts_dir}/*"
                junit "${artifacts_dir}/*.xml"
                if (env.JOB_NAME.contains("cvp-spt")) {
                    plot csvFileName: 'plot-glance.csv',
                        group: 'SPT',
                        style: 'line',
                        title: 'SPT Glance results',
                        xmlSeries: [[
                        file: "${artifacts_dir}/${xml_file}",
                        nodeType: 'NODESET',
                        url: '',
                        xpath: '/testsuite/testcase[@classname="tests.test_glance"]/properties/property']]
                    plot csvFileName: 'plot-hw2hw.csv',
                        group: 'SPT',
                        style: 'line',
                        title: 'SPT HW2HW results',
                        xmlSeries: [[
                        file: "${artifacts_dir}/${xml_file}",
                        nodeType: 'NODESET',
                        url: '',
                        xpath: '/testsuite/testcase[@classname="tests.test_hw2hw"]/properties/property']]
                    plot csvFileName: 'plot-vm2vm.csv',
                        group: 'SPT',
                        style: 'line',
                        title: 'SPT VM2VM results',
                        xmlSeries: [[
                        file: "${artifacts_dir}/${xml_file}",
                        nodeType: 'NODESET',
                        url: '',
                        xpath: '/testsuite/testcase[@classname="tests.test_vm2vm"]/properties/property']]
                }
                try {
                    sh """
                        for i in ${artifacts_dir}/*.xml; do
                            grep 'failures="0"' \$i
                            grep 'errors="0"' \$i
                        done
                    """
                } catch(err) {
                    currentBuild.result = "FAILURE"
                    common.errorMsg("[ERROR] Failures or errors is not zero in ${artifacts_dir}/*.xml")
                    throw err
                }
            }
        }
    }
    catch (Throwable e) {
        currentBuild.result = "FAILURE"
        throw e
    }
    finally {
        sh "rm -rf ${artifacts_dir}"
    }
}
