/**
 *
 * Launch stack and work with it
 *
 * Expected parameters:
 *   STACK_NAME                 Infrastructure stack name
 *   STACK_TEMPLATE             File with stack template
 *   STACK_TYPE                 Deploy OpenStack/AWS [heat/aws], use 'physical' if no stack should be started
 *
 *   STACK_TEMPLATE_URL         URL to git repo with stack templates
 *   STACK_TEMPLATE_CREDENTIALS Credentials to the templates repo
 *   STACK_TEMPLATE_BRANCH      Stack templates repo branch
 *
 *   STACK_DELETE               Delete stack when finished (bool)
 *   STACK_REUSE                Reuse existing stack (don't create one, only read outputs)
 *   STACK_INSTALL              What should be installed (k8s, openstack, ...)
 *   STACK_TEST                 Run tests (bool)
 *   STACK_CLEANUP_JOB          Name of job for deleting stack
 *
 *   STACK_COMPUTE_COUNT        Number of compute nodes to launch
 *   STACK_CLUSTER_NAME         The name of cluster model to use
 *   STATIC_MGMT_NETWORK        Check if model contains static IP address definitions for all nodes
 *
 *   AWS_STACK_REGION           CloudFormation AWS region
 *   AWS_API_CREDENTIALS        AWS Access key ID with  AWS secret access key
 *   AWS_SSH_KEY                AWS key pair name (used for SSH access)
 *
 *   HEAT_STACK_ENVIRONMENT     Heat stack environmental parameters
 *   HEAT_STACK_ZONE            Heat stack availability zone
 *   HEAT_STACK_PUBLIC_NET      Heat stack floating IP pool
 *   OPENSTACK_API_URL          OpenStack API address
 *   OPENSTACK_API_CREDENTIALS  Credentials to the OpenStack API
 *   OPENSTACK_API_PROJECT      OpenStack project to connect to
 *   OPENSTACK_API_CLIENT       Versions of OpenStack python clients
 *   OPENSTACK_API_VERSION      Version of the OpenStack API (2/3)
 *   SLAVE_NODE                 Lable or node name where the job will be run

 *   SALT_MASTER_CREDENTIALS    Credentials to the Salt API
 *  required for STACK_TYPE=physical
 *   SALT_MASTER_URL            URL of Salt master

 *   BOOTSTRAP_EXTRA_REPO_PARAMS  optional parameter to define a list of extra repos with parameters
 *                                which have to be added during bootstrap.
 *                                Format: repo 1, repo priority 1, repo pin 1; repo 2, repo priority 2, repo pin 2;

 *   SALT_VERSION               Version of Salt  which is going to be installed i.e. 'stable 2016.3' or 'stable 2017.7' etc.
 *
 *   EXTRA_TARGET               The value will be added to target nodes
 *   BATCH_SIZE                 Use batching for states, which may be targeted for huge amount of nodes. Format:
                                - 10 - number of nodes
                                - 10% - percentage of all targeted nodes
 *   DIST_UPGRADE_NODES         Whether to run "apt-get dist-upgrade" on all nodes in cluster before deployment
 *   UPGRADE_SALTSTACK          Whether to install recent versions of saltstack packages

 *
 * Test settings:
 *   TEST_K8S_API_SERVER     Kubernetes API address
 *   TEST_K8S_CONFORMANCE_IMAGE   Path to docker image with conformance e2e tests
 *
 *   TEST_DOCKER_INSTALL          Install docker on the target if true
 *   TEST_TEMPEST_IMAGE           Tempest image link
 *   TEST_TEMPEST_PATTERN         If not false, run tests matched to pattern only
 *   TEST_TEMPEST_TARGET          Salt target for tempest node
 *   TESTRAIL_REPORT              Whether upload results to testrail or not
 *   TESTRAIL_REPORTER_IMAGE      Docker image for testrail reporter
 *   TESTRAIL_QA_CREDENTIALS      Credentials for upload to testrail
 *   TESTRAIL_MILESTONE           Product version for tests
 *   TESTRAIL_PLAN                Testrail test plan
 *   TESTRAIL_GROUP               Testrail test group
 *   TESTRAIL_SUITE               Testrail test suite
 *
 * optional parameters for overwriting soft params
 *   SALT_OVERRIDES              YAML with overrides for Salt deployment
 *
 */

common = new com.mirantis.mk.Common()
git = new com.mirantis.mk.Git()
openstack = new com.mirantis.mk.Openstack()
aws = new com.mirantis.mk.Aws()
orchestrate = new com.mirantis.mk.Orchestrate()
python = new com.mirantis.mk.Python()
salt = new com.mirantis.mk.Salt()
test = new com.mirantis.mk.Test()
debian = new com.mirantis.mk.Debian()

_MAX_PERMITTED_STACKS = 2
overwriteFile = "/srv/salt/reclass/classes/cluster/override.yml"

// Define global variables
def venv
def venvPepper
def outputs = [:]

def ipRegex = "\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}"

if (STACK_TYPE == 'aws') {
    def aws_env_vars
} else if (STACK_TYPE == 'heat') {
    def envParams
    def openstackCloud
}

def slave_node = 'python'

if (common.validInputParam('SLAVE_NODE')) {
    slave_node = SLAVE_NODE
}

def extra_tgt = ''
if (common.validInputParam('EXTRA_TARGET')) {
    extra_tgt = "${EXTRA_TARGET}"
}
def batch_size = ''
if (common.validInputParam('BATCH_SIZE')) {
    batch_size = "${BATCH_SIZE}"
}
def upgrade_nodes = false
if (common.validInputParam('DIST_UPGRADE_NODES')) {
    upgrade_nodes = "${DIST_UPGRADE_NODES}".toBoolean()
}
def upgrade_salt = false
if (common.validInputParam('UPGRADE_SALTSTACK')){
    upgrade_salt = "${UPGRADE_SALTSTACK}".toBoolean()
}

timeout(time: 12, unit: 'HOURS') {
    node(slave_node) {
        try {
            // Set build-specific variables
            def workspace = common.getWorkspace()
            venv = "${workspace}/venv"
            venvPepper = "${workspace}/venvPepper"
            if(common.validInputParam("STACK_DELETE") && STACK_DELETE.toBoolean()){
                common.warningMsg("You running stack with STACK_DELETE option enabled, deployed stack will be removed at the end!")
            }
            //
            // Prepare machines
            //
            stage ('Create infrastructure') {

                outputs.put('stack_type', STACK_TYPE)

                if (STACK_TYPE == 'heat') {
                    // value defaults
                    envParams = [
                        'cluster_zone': HEAT_STACK_ZONE,
                        'cluster_public_net': HEAT_STACK_PUBLIC_NET
                    ]

                    if (STACK_REUSE.toBoolean() == true && STACK_NAME == '') {
                        error("If you want to reuse existing stack you need to provide it's name")
                    }

                    if (STACK_REUSE.toBoolean() == false) {
                        // Don't allow to set custom heat stack name
                        wrap([$class: 'BuildUser']) {
                            if (env.BUILD_USER_ID) {
                                STACK_NAME = "${env.BUILD_USER_ID}-${JOB_NAME}-${BUILD_NUMBER}"
                            } else {
                                STACK_NAME = "jenkins-${JOB_NAME}-${BUILD_NUMBER}"
                            }
                            currentBuild.description = STACK_NAME
                        }
                    } else {
                        // In case name was copied with unicode zero-width space chars -
                        // remove them
                        STACK_NAME = STACK_NAME.trim().replaceAll("\\p{C}", "")
                    }

                    // no underscore in STACK_NAME
                    STACK_NAME = STACK_NAME.replaceAll('_', '-')
                    outputs.put('stack_name', STACK_NAME)

                    // set description
                    currentBuild.description = "${STACK_NAME}"

                    // get templates
                    git.checkoutGitRepository('template', STACK_TEMPLATE_URL, STACK_TEMPLATE_BRANCH, STACK_TEMPLATE_CREDENTIALS)

                    // create openstack env
                    openstack.setupOpenstackVirtualenv(venv, OPENSTACK_API_CLIENT)
                    openstackCloud = openstack.createOpenstackEnv(venv,
                        OPENSTACK_API_URL, OPENSTACK_API_CREDENTIALS,
                        OPENSTACK_API_PROJECT, OPENSTACK_API_PROJECT_DOMAIN,
                        OPENSTACK_API_PROJECT_ID, OPENSTACK_API_USER_DOMAIN,
                        OPENSTACK_API_VERSION)
                    openstack.getKeystoneToken(openstackCloud, venv)

                    //
                    // Verify possibility of create stack for given user and stack type
                    //
                    wrap([$class: 'BuildUser']) {
                        if (env.BUILD_USER_ID && !env.BUILD_USER_ID.equals("jenkins") && !STACK_REUSE.toBoolean()) {
                            def existingStacks = openstack.getStacksForNameContains(openstackCloud, "${env.BUILD_USER_ID}-${JOB_NAME}".replaceAll('_', '-'), venv)
                            if (existingStacks.size() >= _MAX_PERMITTED_STACKS) {
                                STACK_DELETE = "false"
                                throw new Exception("You cannot create new stack, you already have ${_MAX_PERMITTED_STACKS} stacks of this type (${JOB_NAME}). \nStack names: ${existingStacks}")
                            }
                        }
                    }
                    // launch stack
                    if (STACK_REUSE.toBoolean() == false) {

                        // set reclass repo in heat env
                        try {
                            envParams.put('cfg_reclass_branch', STACK_RECLASS_BRANCH)
                            envParams.put('cfg_reclass_address', STACK_RECLASS_ADDRESS)
                        } catch (MissingPropertyException e) {
                            common.infoMsg("Property STACK_RECLASS_BRANCH or STACK_RECLASS_ADDRESS not found! Using default values from template.")
                        }

                        // put formulas revision - stable, testing or nightly
                        if (common.validInputParam('FORMULA_PKG_REVISION')) {
                            common.infoMsg("Setting formulas revision to ${FORMULA_PKG_REVISION}")
                            envParams.put('cfg_formula_pkg_revision', FORMULA_PKG_REVISION)
                        }

                        // put extra repo definitions
                        if (common.validInputParam('BOOTSTRAP_EXTRA_REPO_PARAMS')) {
                            common.infoMsg("Setting additional repo during bootstrap to ${BOOTSTRAP_EXTRA_REPO_PARAMS}")
                            envParams.put('cfg_bootstrap_extra_repo_params', BOOTSTRAP_EXTRA_REPO_PARAMS)
                        }

                        // put extra salt-formulas # FIXME: looks like some outdated logic. See #PROD-23127
                        if (common.validInputParam('EXTRA_FORMULAS')) {
                            common.infoMsg("Setting extra salt-formulas to ${EXTRA_FORMULAS}")
                            envParams.put('cfg_extra_formulas', EXTRA_FORMULAS)
                        }

                        // add cluster name if specified
                        if (common.validInputParam('STACK_CLUSTER_NAME')) {
                            common.infoMsg("Setting cluster_name to ${STACK_CLUSTER_NAME}")
                            envParams.put('cluster_name', STACK_CLUSTER_NAME)
                        }

                        if (common.validInputParam('STACK_COMPUTE_COUNT')) {
                            if (STACK_COMPUTE_COUNT.toInteger() > 0){
                                common.infoMsg("Setting cluster_node_count to ${STACK_COMPUTE_COUNT}")
                                envParams.put('cluster_node_count', STACK_COMPUTE_COUNT)
                            }
                        }


                        if (common.validInputParam('SALT_VERSION')) {
                            common.infoMsg("Setting salt version to ${SALT_VERSION}")
                            envParams.put('cfg_saltversion', SALT_VERSION)
                        }

                        // If stack wasn't removed by the same user which has created it,
                        // nova key pair won't be removed, so need to make sure that no
                        // key pair with the same name exists before creating the stack.
                        if (openstack.getKeyPair(openstackCloud, STACK_NAME, venv)){
                            try {
                                openstack.deleteKeyPair(openstackCloud, STACK_NAME, venv)
                            } catch (Exception e) {
                                common.errorMsg("Key pair failed to remove with error ${e.message}")
                            }
                        }

                        openstack.createHeatStack(openstackCloud, STACK_NAME, STACK_TEMPLATE, envParams, HEAT_STACK_ENVIRONMENT, venv)
                    }

                    // get SALT_MASTER_URL
                    saltMasterHost = openstack.getHeatStackOutputParam(openstackCloud, STACK_NAME, 'salt_master_ip', venv)
                    // check that saltMasterHost is valid
                    if (!saltMasterHost || !saltMasterHost.matches(ipRegex)) {
                        common.errorMsg("saltMasterHost is not a valid ip, value is: ${saltMasterHost}")
                        throw new Exception("saltMasterHost is not a valid ip")
                    }

                    currentBuild.description = "${STACK_NAME} ${saltMasterHost}"

                    SALT_MASTER_URL = "http://${saltMasterHost}:6969"

                } else if (STACK_TYPE == 'aws') {

                    // setup environment
                    aws.setupVirtualEnv(venv)

                    // set aws_env_vars
                    aws_env_vars = aws.getEnvVars(AWS_API_CREDENTIALS, AWS_STACK_REGION)

                    if (STACK_REUSE.toBoolean() == true && STACK_NAME == '') {
                        error("If you want to reuse existing stack you need to provide it's name")
                    }

                    if (STACK_REUSE.toBoolean() == false) {
                        // Don't allow to set custom stack name
                        wrap([$class: 'BuildUser']) {
                            if (env.BUILD_USER_ID) {
                                STACK_NAME = "${env.BUILD_USER_ID}-${JOB_NAME}-${BUILD_NUMBER}"
                            } else {
                                STACK_NAME = "jenkins-${JOB_NAME}-${BUILD_NUMBER}"
                            }
                        }

                        // no underscore in STACK_NAME
                        STACK_NAME = STACK_NAME.replaceAll('_', '-')
                    }

                    // set description
                    currentBuild.description = STACK_NAME
                    outputs.put('stack_name', STACK_NAME)

                    if (STACK_REUSE.toBoolean() == false) {
                        // get templates
                        git.checkoutGitRepository('template', STACK_TEMPLATE_URL, STACK_TEMPLATE_BRANCH, STACK_TEMPLATE_CREDENTIALS)

                        // start stack
                        def stack_params = [
                            "ParameterKey=KeyName,ParameterValue=" + AWS_SSH_KEY,
                            "ParameterKey=CmpNodeCount,ParameterValue=" + STACK_COMPUTE_COUNT
                        ]
                        def template_file = 'cfn/' + STACK_TEMPLATE + '.yml'
                        aws.createStack(venv, aws_env_vars, template_file, STACK_NAME, stack_params)
                    }

                    // wait for stack to be ready
                    aws.waitForStatus(venv, aws_env_vars, STACK_NAME, 'CREATE_COMPLETE')

                    // get outputs
                    saltMasterHost = aws.getOutputs(venv, aws_env_vars, STACK_NAME, 'SaltMasterIP')

                    // check that saltMasterHost is valid
                    if (!saltMasterHost || !saltMasterHost.matches(ipRegex)) {
                        common.errorMsg("saltMasterHost is not a valid ip, value is: ${saltMasterHost}")
                        throw new Exception("saltMasterHost is not a valid ip")
                    }

                    currentBuild.description = "${STACK_NAME} ${saltMasterHost}"
                    SALT_MASTER_URL = "http://${saltMasterHost}:6969"

                } else if (STACK_TYPE != 'physical') {
                    throw new Exception("STACK_TYPE ${STACK_TYPE} is not supported")
                }

                outputs.put('salt_api', SALT_MASTER_URL)

                // Setup virtualenv for pepper
                python.setupPepperVirtualenv(venvPepper, SALT_MASTER_URL, SALT_MASTER_CREDENTIALS)
            }


            // Set up override params
            if (common.validInputParam('SALT_OVERRIDES')) {
                stage('Set Salt overrides') {
                    salt.setSaltOverrides(venvPepper,  SALT_OVERRIDES, '/srv/salt/reclass', extra_tgt)
                }
            }

            //
            // Install
            //
            if (!batch_size) {
                // if no batch size provided get current worker threads and set batch size to 2/3 of it to avoid
                // 'SaltReqTimeoutError: Message timed out' issue on Salt targets for large amount of nodes
                // do not use toDouble/Double as it requires additional approved method
                def workerThreads = salt.getWorkerThreads(venvPepper).toInteger()
                batch_size = (workerThreads * 2 / 3).toString().tokenize('.')[0]
            }

            // Check if all minions are reachable and ready
            salt.checkTargetMinionsReady(['saltId': venvPepper, 'target': '*', batch: batch_size])

            if (common.checkContains('STACK_INSTALL', 'core')) {
                stage('Install core infrastructure') {
                    def staticMgmtNetwork = false
                    if (common.validInputParam('STATIC_MGMT_NETWORK')) {
                        staticMgmtNetwork = STATIC_MGMT_NETWORK.toBoolean()
                    }
                    orchestrate.installFoundationInfra(venvPepper, staticMgmtNetwork, extra_tgt, batch_size)

                    if (upgrade_salt) {
                        debian.upgradeSaltPackages(venvPepper, 'I@salt:master')
                        debian.upgradeSaltPackages(venvPepper, 'I@salt:minion and not I@salt:master')
                    }

                    if (common.checkContains('STACK_INSTALL', 'kvm')) {
                        if (upgrade_nodes) {
                            debian.osUpgradeNode(venvPepper, 'I@salt:control', 'dist-upgrade', 30, 20, batch_size)
                            salt.checkTargetMinionsReady(['saltId': venvPepper, 'target': 'I@salt:control', wait: 60, timeout: 10])
                        }
                        orchestrate.installInfraKvm(venvPepper, extra_tgt)
                        orchestrate.installFoundationInfra(venvPepper, staticMgmtNetwork, extra_tgt, batch_size)
                    }

                    orchestrate.validateFoundationInfra(venvPepper, extra_tgt)
                }
                if (upgrade_nodes) {
                    debian.osUpgradeNode(venvPepper, 'not ( I@salt:master or I@salt:control )', 'dist-upgrade', false, 30, 10, batch_size)
                    salt.checkTargetMinionsReady(['saltId': venvPepper, 'target': 'not ( I@salt:master or I@salt:control )', wait: 60, timeout: 10])
                }
            }

            stage('Install infra') {
              if (common.checkContains('STACK_INSTALL', 'core') ||
                    common.checkContains('STACK_INSTALL', 'openstack') ||
                      common.checkContains('STACK_INSTALL', 'oss') ||
                        common.checkContains('STACK_INSTALL', 'cicd')) {
                  orchestrate.installInfra(venvPepper, extra_tgt)
              }
            }

            stage('Install Orchestrated Apps'){
                orchestrate.OrchestrateApplications(venvPepper, "I@salt:master ${extra_tgt}", "orchestration.deploy.applications")
            }

            // install k8s
            if (common.checkContains('STACK_INSTALL', 'k8s')) {

                stage('Install Kubernetes infra') {
                    if (STACK_TYPE == 'aws') {
                        // configure kubernetes_control_address - save loadbalancer
                        def awsOutputs = aws.getOutputs(venv, aws_env_vars, STACK_NAME)
                        common.prettyPrint(awsOutputs)
                        if (awsOutputs.containsKey('ControlLoadBalancer')) {
                            salt.runSaltProcessStep(venvPepper, "I@salt:master ${extra_tgt}", 'reclass.cluster_meta_set', ['kubernetes_control_address', awsOutputs['ControlLoadBalancer']], null, true)
                            outputs.put('kubernetes_apiserver', 'https://' + awsOutputs['ControlLoadBalancer'])
                        }
                    }

                    // ensure certificates are generated properly
                    salt.runSaltProcessStep(venvPepper, "I@kubernetes:* ${extra_tgt}", 'saltutil.refresh_pillar', [], null, true)
                    salt.enforceState(venvPepper, "I@kubernetes:* ${extra_tgt}", ['salt.minion.cert'], true)
                }

                if (common.checkContains('STACK_INSTALL', 'contrail')) {
                    stage('Install Contrail control') {
                        orchestrate.installContrailNetwork(venvPepper, extra_tgt)
                    }
                }

                stage('Install Kubernetes control') {
                    orchestrate.installKubernetesControl(venvPepper, extra_tgt)
                    if (common.checkContains('STACK_INSTALL', 'contrail')) {
                        orchestrate.checkContrailApiReadiness(venvPepper, extra_tgt)
                    }

                    // collect artifacts (kubeconfig)
                    writeFile(file: 'kubeconfig', text: salt.getFileContent(venvPepper, "I@kubernetes:master and *01* ${extra_tgt}", '/etc/kubernetes/admin-kube-config'))
                    archiveArtifacts(artifacts: 'kubeconfig')
                }

                if (common.checkContains('STACK_INSTALL', 'contrail')) {
                    stage('Install Contrail compute') {
                        orchestrate.installContrailCompute(venvPepper, extra_tgt)
                    }
                }

                stage('Install Kubernetes computes') {
                    if (common.validInputParam('STACK_COMPUTE_COUNT')) {
                        if (STACK_COMPUTE_COUNT > 0) {
                            if (STACK_TYPE == 'aws') {
                                // get stack info
                                def scaling_group = aws.getOutputs(venv, aws_env_vars, STACK_NAME, 'ComputesScalingGroup')

                                //update autoscaling group
                                aws.updateAutoscalingGroup(venv, aws_env_vars, scaling_group, ["--desired-capacity " + STACK_COMPUTE_COUNT])

                                // wait for computes to boot up
                                aws.waitForAutoscalingInstances(venv, aws_env_vars, scaling_group)
                                sleep(60)

                            } else if (STACK_TYPE == 'heat') {
                                envParams.put('cluster_node_count', STACK_COMPUTE_COUNT)

                                openstack.createHeatStack(openstackCloud, STACK_NAME, STACK_TEMPLATE, envParams, HEAT_STACK_ENVIRONMENT, venv, "update")
                                sleep(60)
                            }

                        }
                    }

                    orchestrate.installKubernetesCompute(venvPepper, extra_tgt)
                    // Setup kubernetes addons for opencontrail. More info in the definition of the func.
                    orchestrate.setupKubeAddonForContrail(venvPepper, extra_tgt)
                }
                orchestrate.installKubernetesClient(venvPepper, extra_tgt)
            }

            // install ceph
            if (common.checkContains('STACK_INSTALL', 'ceph')) {
                stage('Install Ceph MONs') {
                    orchestrate.installCephMon(venvPepper, "I@ceph:mon ${extra_tgt}", extra_tgt)
                }

                stage('Install Ceph OSDs') {
                    orchestrate.installCephOsd(venvPepper, "I@ceph:osd ${extra_tgt}", true, extra_tgt)
                }

                stage('Install Ceph clients') {
                    orchestrate.installCephClient(venvPepper, extra_tgt)
                }
            }

            // install docker swarm
            orchestrate.installDockerSwarm(venvPepper, extra_tgt)

            // install openstack
            if (common.checkContains('STACK_INSTALL', 'openstack')) {
                // install control, tests, ...
                stage('Install OpenStack control') {
                    orchestrate.installOpenstackControl(venvPepper, extra_tgt)
                }

                // Workaround for PROD-17765 issue to prevent crashes of keystone.role_present state.
                // More details: https://mirantis.jira.com/browse/PROD-17765
                salt.restartSaltMinion(venvPepper, "I@keystone:client ${extra_tgt}")
                salt.minionsReachable(venvPepper, 'I@salt:master', 'I@keystone:client ${extra_tgt}', null, 10, 6)

                stage('Install OpenStack network') {

                    if (common.checkContains('STACK_INSTALL', 'contrail')) {
                        orchestrate.installContrailNetwork(venvPepper, extra_tgt)
                        orchestrate.checkContrailApiReadiness(venvPepper, extra_tgt)
                    } else if (common.checkContains('STACK_INSTALL', 'ovs')) {
                        orchestrate.installOpenstackNetwork(venvPepper, extra_tgt)
                    }

                    // Wait for network to come up, 150s should be enough
                    common.retry(10, 15) {
                        salt.cmdRun(venvPepper, "I@keystone:server ${extra_tgt}", '. /root/keystonercv3; openstack network list')
                    }
                }

                if (salt.testTarget(venvPepper, "I@ironic:conductor ${extra_tgt}")){
                    stage('Install OpenStack Ironic conductor') {
                        orchestrate.installIronicConductor(venvPepper, extra_tgt)
                    }
                }

                if (salt.testTarget(venvPepper, "I@manila:share ${extra_tgt}")){
                    stage('Install OpenStack Manila data and share') {
                       orchestrate.installManilaShare(venvPepper, extra_tgt)
                    }
                }

                stage('Install OpenStack compute') {
                    orchestrate.installOpenstackCompute(venvPepper, extra_tgt, batch_size)

                    if (common.checkContains('STACK_INSTALL', 'contrail')) {
                        orchestrate.installContrailCompute(venvPepper, extra_tgt)
                        orchestrate.installBackup(venvPepper, 'contrail', extra_tgt)
                    }
                }

            }

            // connect ceph
            if (common.checkContains('STACK_INSTALL', 'ceph')) {

                stage('Connect Ceph') {
                    orchestrate.connectCeph(venvPepper, extra_tgt)
                }
            }

            if (common.checkContains('STACK_INSTALL', 'oss')) {
              stage('Install Oss infra') {
                orchestrate.installOssInfra(venvPepper, extra_tgt)
              }
            }

            if (common.checkContains('STACK_INSTALL', 'cicd')) {
                stage('Install Cicd') {
                    orchestrate.installCicd(venvPepper, extra_tgt)
                }
            }

            if (common.checkContains('STACK_INSTALL', 'sl-legacy')) {
                stage('Install StackLight v1') {
                    orchestrate.installStacklightv1Control(venvPepper, extra_tgt)
                    orchestrate.installStacklightv1Client(venvPepper, extra_tgt)
                }
            }

            if (common.checkContains('STACK_INSTALL', 'stacklight')) {
                stage('Install StackLight') {
                    orchestrate.installStacklight(venvPepper, extra_tgt)
                }
            }

            if (common.checkContains('STACK_INSTALL', 'oss')) {
              stage('Install OSS') {
                if (!common.checkContains('STACK_INSTALL', 'stacklight')) {
                  // In case if StackLightv2 enabled containers already started
                  salt.enforceState(venvPepper, "I@docker:swarm:role:master and I@devops_portal:config ${extra_tgt}", 'docker.client', true)
                }
                orchestrate.installOss(venvPepper, extra_tgt)
              }
            }

            //
            // Test
            //
            def artifacts_dir = '_artifacts/'

            if (common.checkContains('STACK_TEST', 'k8s')) {
                stage('Run k8s conformance e2e tests') {
                    def image = TEST_K8S_CONFORMANCE_IMAGE
                    def autodetect = AUTODETECT
                    def ctl_target = 'I@kubernetes:master'
                    firstTarget = salt.getFirstMinion(venvPepper, ctl_target)
                    def containerd_enabled = salt.getPillar(
                        venvPepper, firstTarget, "kubernetes:common:containerd:enabled"
                    )["return"][0].values()[0].toBoolean()
                    if (containerd_enabled) {
                        def config = ['master': venvPepper,
                                      'target': firstTarget,
                                      'junitResults': true,
                                      'autodetect': autodetect,
                                      'image': image]
                        test.executeConformance(config)
                    } else {
                        def output_file = image.replaceAll('/', '-') + '.output'
                        def target = "I@keystone:server:role:primary ${extra_tgt}"
                        def conformance_output_file = 'conformance_test.tar'

                        // run image
                        test.runConformanceTests(venvPepper, target, TEST_K8S_API_SERVER, image)

                        // collect output
                        sh "mkdir -p ${artifacts_dir}"
                        file_content = salt.getFileContent(venvPepper, target, '/tmp/' + output_file)
                        writeFile file: "${artifacts_dir}${output_file}", text: file_content
                        sh "cat ${artifacts_dir}${output_file}"

                        // collect artifacts
                        archiveArtifacts artifacts: "${artifacts_dir}${output_file}"

                        // Copy test results
                        test.CopyConformanceResults(venvPepper, target, artifacts_dir, conformance_output_file)
                    }
                }
            }

            if (common.checkContains('STACK_TEST', 'openstack')) {
                if (common.checkContains('TEST_DOCKER_INSTALL', 'true')) {
                    test.install_docker(venvPepper, TEST_TEMPEST_TARGET)
                }
                stage('Run OpenStack tests') {
                    test.runTempestTests(venvPepper, TEST_TEMPEST_IMAGE, TEST_TEMPEST_TARGET, TEST_TEMPEST_PATTERN)
                }

                stage('Copy Tempest results to config node') {
                    test.copyTempestResults(venvPepper, TEST_TEMPEST_TARGET)
                }

                stage('Archive rally artifacts') {
                    test.archiveRallyArtifacts(venvPepper, TEST_TEMPEST_TARGET)
                }

                if (common.validInputParam('TESTRAIL_REPORT') && TESTRAIL_REPORT.toBoolean()) {
                    stage('Upload test results to TestRail') {
                        def date = sh(script: 'date +%Y-%m-%d', returnStdout: true).trim()
                        def plan = TESTRAIL_PLAN ?: "[${TESTRAIL_MILESTONE}]System-Devcloud-${date}"
                        def group = TESTRAIL_GROUP ?: STACK_TEMPLATE

                        salt.cmdRun(venvPepper, TEST_TEMPEST_TARGET, "cd /root/rally_reports && cp \$(ls -t *xml | head -n1) report.xml")
                        test.uploadResultsTestrail("/root/rally_reports/report.xml",
                                TESTRAIL_REPORTER_IMAGE, group, TESTRAIL_QA_CREDENTIALS,
                                plan, TESTRAIL_MILESTONE, TESTRAIL_SUITE)
                    }
                }
            }


            if (common.checkContains('STACK_TEST', 'ceph')) {
                stage('Run infra tests') {
                    sleep(120)
                    def cmd = "apt-get install -y python-pip && pip install -r /usr/share/salt-formulas/env/ceph/files/testinfra/requirements.txt && python -m pytest --junitxml=/root/report.xml /usr/share/salt-formulas/env/ceph/files/testinfra/"
                    salt.cmdRun(venvPepper, 'I@salt:master', cmd, false)
                    writeFile(file: 'report.xml', text: salt.getFileContent(venvPepper, 'I@salt:master', '/root/report.xml'))
                    junit(keepLongStdio: true, testResults: 'report.xml')
                }
            }

            if (common.checkContains('STACK_TEST', 'opencontrail')) {
                stage('Run opencontrail tests') {
                    def opencontrail_tests_dir = "/opt/opencontrail_test/fuel-plugin-contrail/plugin_test/vapor/"
                    def report_dir = "/opt/opencontrail-test-report/"
                    def cmd = ". ${opencontrail_tests_dir}exports.sh && " +
                              "cd ${opencontrail_tests_dir} && " +
                              "py.test --junit-xml=${report_dir}report.xml" +
                              " --html=${report_dir}report.html -v vapor/tests/ -k 'not destructive' "

                    salt.runSaltProcessStep(venvPepper, 'I@salt:master', 'saltutil.refresh_pillar', [], null, true)
                    salt.enforceState(venvPepper, 'I@opencontrail:test' , 'opencontrail.test' , true)

                    salt.cmdRun(venvPepper, 'I@opencontrail:test', cmd, false)

                    writeFile(file: 'report.xml', text: salt.getFileContent(venvPepper,
                              'I@opencontrail:test', "${report_dir}report.xml"))
                    junit(keepLongStdio: true, testResults: 'report.xml')
                }
            }


            stage('Finalize') {
                if (common.checkContains('STACK_INSTALL', 'finalize')) {
                    def gluster_compound = "I@glusterfs:server ${extra_tgt}"
                    def salt_ca_compound = "I@salt:minion:ca:salt_master_ca ${extra_tgt}"
                    // Enforce highstate asynchronous only on the nodes which are not glusterfs servers
                    salt.enforceHighstate(venvPepper, '* and not ' + gluster_compound + ' and not ' + salt_ca_compound, batch_size)
                    // Iterate over nonempty set of gluster servers and apply highstates one by one
                    // TODO: switch to batch once salt 2017.7+ would be used
                    def saltcaMinions = salt.getMinionsSorted(venvPepper, salt_ca_compound)
                    if ( !saltcaMinions.isEmpty() ) {
                        for ( target in saltcaMinions ) {
                            salt.enforceHighstate(venvPepper, target)
                        }
                    }
                    def glusterMinions = salt.getMinionsSorted(venvPepper, gluster_compound)
                    if ( !glusterMinions.isEmpty() ) {
                        for ( target in glusterMinions ) {
                            salt.enforceHighstate(venvPepper, target)
                        }
                    }
                }

                outputsPretty = common.prettify(outputs)
                print(outputsPretty)
                writeFile(file: 'outputs.json', text: outputsPretty)
                archiveArtifacts(artifacts: 'outputs.json')
            }

        } catch (Throwable e) {
            currentBuild.result = 'FAILURE'
            throw e
        } finally {


            //
            // Clean
            //

            if (common.validInputParam('STACK_NAME')) {
                // send notification
                common.sendNotification(currentBuild.result, STACK_NAME, ["slack"])
            }

            if (common.validInputParam('STACK_DELETE') && STACK_DELETE.toBoolean() == true) {
                stage('Trigger cleanup job') {
                    common.errorMsg('Stack cleanup job triggered')
                    build(job: STACK_CLEANUP_JOB, parameters: [
                        [$class: 'StringParameterValue', name: 'STACK_NAME', value: STACK_NAME],
                        [$class: 'StringParameterValue', name: 'STACK_TYPE', value: STACK_TYPE],
                        [$class: 'StringParameterValue', name: 'OPENSTACK_API_URL', value: OPENSTACK_API_URL],
                        [$class: 'StringParameterValue', name: 'OPENSTACK_API_CREDENTIALS', value: OPENSTACK_API_CREDENTIALS],
                        [$class: 'StringParameterValue', name: 'OPENSTACK_API_PROJECT', value: OPENSTACK_API_PROJECT],
                        [$class: 'StringParameterValue', name: 'OPENSTACK_API_PROJECT_DOMAIN', value: OPENSTACK_API_PROJECT_DOMAIN],
                        [$class: 'StringParameterValue', name: 'OPENSTACK_API_PROJECT_ID', value: OPENSTACK_API_PROJECT_ID],
                        [$class: 'StringParameterValue', name: 'OPENSTACK_API_USER_DOMAIN', value: OPENSTACK_API_USER_DOMAIN],
                        [$class: 'StringParameterValue', name: 'OPENSTACK_API_CLIENT', value: OPENSTACK_API_CLIENT],
                        [$class: 'StringParameterValue', name: 'OPENSTACK_API_VERSION', value: OPENSTACK_API_VERSION]
                    ])
                }
            } else {
                if (currentBuild.result == 'FAILURE') {
                    common.errorMsg("Deploy job FAILED and was not deleted. Please fix the problem and delete stack on you own.")

                    if (common.validInputParam('SALT_MASTER_URL')) {
                        common.errorMsg("Salt master URL: ${SALT_MASTER_URL}")
                    }
                }
            }
        }
    }
}
