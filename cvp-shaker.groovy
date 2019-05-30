/*
 *
 * Launch CVP Shaker network tests
 *
 * Expected parameters:
 *
 *   SALT_MASTER_URL             URL of Salt master
 *   SALT_MASTER_CREDENTIALS     Credentials that are used in this Jenkins for accessing Salt master (usually "salt")
 *   IMAGE                       Docker image link to use for running container with Shaker.
 *   SHAKER_PARAMS               Yaml context which contains parameters for running Shaker
 *
*/

/*
SHAKER_PARAMS yaml example:
---
  SHAKER_SERVER_ENDPOINT: '10.13.0.15:5999'
  SHAKER_SCENARIOS: 'scenarios/essential'
  SKIP_LIST: ''
  MATRIX: '{host:[ping.online.net, bouygues.iperf.fr]}'
  image_builder:
    - SHAKER_FLAVOR_DISK=4
    - SHAKER_FLAVOR_RAM=512
    - SHAKER_FLAVOR_VCPUS=1
    - SHAKER_IMAGE_BUILDER_MODE='dib'
  shaker:
    - SHAKER_AGENT_JOIN_TIMEOUT=300
    - SHAKER_AGENT_LOSS_TIMEOUT=120
    - SCENARIO_AVAILABILITY_ZONE='nova,internal'
    - SCENARIO_COMPUTE_NODES=2
    - SHAKER_EXTERNAL_NET='public'

Where:
  "SHAKER_SERVER_ENDPOINT" - Address for Shaker server connections (host:port). Should be accessible
  from tenant's VM network (usually equals to public address of cicd node)
  "SHAKER_SCENARIOS" - Path to shaker scenarios in the cvp-shaker docker image
  (can be directory or specific file). Main categories are
    scenarios/essential/l2
    scenarios/essential/l3
    scenarios/additional/cross_az
    scenarios/additional/external
    scenarios/additional/qos
  "MATRIX" - Set the matrix of extra parameters for the scenario. The value is specified in JSON format.
  To override a scenario duration one may provide: "{time: 10}", or to override list of hosts:
  "{host:[ping.online.net, iperf.eenet.ee]}". When several parameters are overridden all combinations are
  tested. It is a required field for some of external-category scenarios when the host name with iperf3
  server needs to be provided as a command-line parameter, e.g. ``{host: 10.13.100.4}``.
  "SKIP_LIST" - Comma-separated list of Shaker scenarios to skip, directories or files inside scenarios/
  of cvp-shaker, e.g. "dense_l2.yaml,full_l2.yaml,l3"
  "image_builder" - shaker-image-builder env variables
    SHAKER_FLAVOR_DISK=4
    SHAKER_FLAVOR_RAM=512
    SHAKER_FLAVOR_VCPUS=1
    SHAKER_IMAGE_BUILDER_MODE='dib'
  "shaker" - main shaker runner env variables
    SHAKER_AGENT_JOIN_TIMEOUT=300
    SHAKER_AGENT_LOSS_TIMEOUT=120
    SCENARIO_AVAILABILITY_ZONE='nova,internal'
    SCENARIO_COMPUTE_NODES=2
    SHAKER_EXTERNAL_NET='public'

For the more detailed description of the last two categories please refer to the shaker documentation
https://pyshaker.readthedocs.io/en/latest/tools.html
*/

common = new com.mirantis.mk.Common()
salt = new com.mirantis.mk.Salt()
validate = new com.mirantis.mcp.Validate()
salt_testing = new com.mirantis.mk.SaltModelTesting()


def IMAGE = (env.getProperty('IMAGE')) ?: 'docker-prod-local.docker.mirantis.net/mirantis/cvp/cvp-shaker:proposed'
def SLAVE_NODE = (env.getProperty('SLAVE_NODE')) ?: 'docker'
def SHAKER_PARAMS = readYaml(text: env.getProperty('SHAKER_PARAMS')) ?: [:]
def artifacts_dir = 'validation_artifacts'
def configRun = [:]
def workdir = '/opt/shaker/'
def container_artifacts = '/artifacts'
def html_file = "${container_artifacts}/shaker-report.html"
def cmd_shaker_args = "--debug --cleanup-on-error --report ${html_file}"

node (SLAVE_NODE) {
    stage('Initialization') {

        // prepare artifacts directory
        sh "rm -rf ${artifacts_dir} || true"
        sh "mkdir -p ${artifacts_dir}"
        // Get Openstack credentials
        def saltMaster = salt.connection(SALT_MASTER_URL, SALT_MASTER_CREDENTIALS)
        try {
            keystone_creds = validate._get_keystone_creds_v3(saltMaster)
            if (!keystone_creds) {
                keystone_creds = validate._get_keystone_creds_v2(saltMaster)
            }
        } catch (Throwable e) {
            error("This test requires Openstack keystone credentials to start (${e.toString()})")
        }

        // Get shaker env variables
        def general_params = [
            SHAKER_SERVER_ENDPOINT: (SHAKER_PARAMS.get('SHAKER_SERVER_ENDPOINT')),
            SHAKER_SCENARIOS: (SHAKER_PARAMS.get('SHAKER_SCENARIOS')) ?: 'scenarios/essential',
            SKIP_LIST: (SHAKER_PARAMS.get('SKIP_LIST')),
            MATRIX: (SHAKER_PARAMS.get('MATRIX'))
        ]
        if (! general_params['SHAKER_SERVER_ENDPOINT']) {
            throw new Exception("SHAKER_SERVER_ENDPOINT address was not set in the SHAKER_PARAMS")
        }
        def builder_vars = SHAKER_PARAMS.get("image_builder") ?: [
            "SHAKER_FLAVOR_DISK=4",
            "SHAKER_FLAVOR_RAM=512",
            "SHAKER_FLAVOR_VCPUS=1"
        ]
        def shaker_vars = SHAKER_PARAMS.get("shaker") ?: []
        def env_vars_list = general_params.collect{ "${it.key}=${it.value}" }
        env_vars_list = env_vars_list + keystone_creds + builder_vars + shaker_vars

        // Get shaker scenarios cmd
        def scen_cmd = validate.bundle_up_scenarios(
            workdir +
            general_params['SHAKER_SCENARIOS'].replaceAll("^/+", ""),
            general_params['SKIP_LIST']
        )

        // Override scenarios params:
        if (general_params['MATRIX']) {
            cmd_shaker_args += " --matrix '${general_params.MATRIX}'"
        }

        // Define docker commands
        def commands = [
            '001_build_image': "shaker-image-builder --debug",
            '002_run_shaker': scen_cmd +
                "|paste -sd ',' - " +
                "|xargs shaker ${cmd_shaker_args} --scenario"
        ]
        def commands_list = commands.collectEntries{ [ (it.key) : { sh("${it.value}") } ] }

        configRun = [
            'image': IMAGE,
            'baseRepoPreConfig': false,
            'dockerMaxCpus': 2,
            // suppress sudo resolve warnings
            // which break image build
            'dockerHostname': 'localhost',
            'dockerExtraOpts': [
                "--network=host",
                "--privileged",
                "-v ${env.WORKSPACE}/${artifacts_dir}/:${container_artifacts}"
            ],
            'envOpts'         : env_vars_list,
            'runCommands'     : commands_list
        ]
    }

    stage('Run Shaker tests') {
        timeout(time: 10, unit: 'HOURS') {
            if (! salt_testing.setupDockerAndTest(configRun)) {
                common.warningMsg('Docker contrainer failed to run Shaker')
                currentBuild.result = 'FAILURE'
            }
        }
    }

    stage('Collect results') {
        archiveArtifacts artifacts: "${artifacts_dir}/*"
    }

}
