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

 *   SALT_MASTER_CREDENTIALS    Credentials to the Salt API
 *  required for STACK_TYPE=physical
 *   SALT_MASTER_URL            URL of Salt master

 * Test settings:
 *   TEST_K8S_API_SERVER     Kubernetes API address
 *   TEST_K8S_CONFORMANCE_IMAGE   Path to docker image with conformance e2e tests
 *
 *   TEST_DOCKER_INSTALL          Install docker on the target if true
 *   TEST_TEMPEST_IMAGE           Tempest image link
 *   TEST_TEMPEST_PATTERN         If not false, run tests matched to pattern only
 *   TEST_TEMPEST_TARGET          Salt target for tempest node
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
salt = new com.mirantis.mk.Salt()
test = new com.mirantis.mk.Test()

_MAX_PERMITTED_STACKS = 2
overwriteFile = "/srv/salt/reclass/classes/cluster/override.yml"

// Define global variables
def saltMaster
def venv

if (STACK_TYPE == 'aws') {
    def aws_env_vars
}

node("python") {
    try {
        // Set build-specific variables
        venv = "${env.WORKSPACE}/venv"

        //
        // Prepare machines
        //
        stage ('Create infrastructure') {

            if (STACK_TYPE == 'heat') {
                // value defaults
                def openstackCloud

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
                }

                // set description
                currentBuild.description = "${STACK_NAME}"

                // get templates
                git.checkoutGitRepository('template', STACK_TEMPLATE_URL, STACK_TEMPLATE_BRANCH, STACK_TEMPLATE_CREDENTIALS)

                // create openstack env
                openstack.setupOpenstackVirtualenv(venv, OPENSTACK_API_CLIENT)
                openstackCloud = openstack.createOpenstackEnv(
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
                        def existingStacks = openstack.getStacksForNameContains(openstackCloud, "${env.BUILD_USER_ID}-${JOB_NAME}", venv)
                        if(existingStacks.size() >= _MAX_PERMITTED_STACKS){
                            STACK_DELETE = "false"
                            throw new Exception("You cannot create new stack, you already have ${_MAX_PERMITTED_STACKS} stacks of this type (${JOB_NAME}). \nStack names: ${existingStacks}")
                        }
                    }
                }
                // launch stack
                if (STACK_REUSE.toBoolean() == false) {
                    stage('Launch new Heat stack') {
                        envParams = [
                            'cluster_zone': HEAT_STACK_ZONE,
                            'cluster_public_net': HEAT_STACK_PUBLIC_NET
                        ]

                        // set reclass repo in heat env
                        try {
                            envParams.put('cfg_reclass_branch', STACK_RECLASS_BRANCH)
                            envParams.put('cfg_reclass_address', STACK_RECLASS_ADDRESS)
                        } catch (MissingPropertyException e) {
                            common.infoMsg("Property STACK_RECLASS_BRANCH or STACK_RECLASS_ADDRESS not found! Using default values from template.")
                        }

                        openstack.createHeatStack(openstackCloud, STACK_NAME, STACK_TEMPLATE, envParams, HEAT_STACK_ENVIRONMENT, venv, false)
                    }
                }

                // get SALT_MASTER_URL
                saltMasterHost = openstack.getHeatStackOutputParam(openstackCloud, STACK_NAME, 'salt_master_ip', venv)
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
                currentBuild.description = "${STACK_NAME} ${saltMasterHost}"
                SALT_MASTER_URL = "http://${saltMasterHost}:6969"

            } else if (STACK_TYPE != 'physical') {
                throw new Exception("STACK_TYPE ${STACK_TYPE} is not supported")
            }

            // Connect to Salt master
            saltMaster = salt.connection(SALT_MASTER_URL, SALT_MASTER_CREDENTIALS)
        }


        // Set up override params
        if (env.getEnvironment().containsKey('SALT_OVERRIDES')) {
            stage('Set Salt overrides') {
                salt.setSaltOverrides(saltMaster,  SALT_OVERRIDES)
            }
        }

        //
        // Install
        //

        if (common.checkContains('STACK_INSTALL', 'core')) {
            stage('Install core infrastructure') {
                orchestrate.installFoundationInfra(saltMaster)

                if (common.checkContains('STACK_INSTALL', 'kvm')) {
                    orchestrate.installInfraKvm(saltMaster)
                    orchestrate.installFoundationInfra(saltMaster)
                }

                orchestrate.validateFoundationInfra(saltMaster)
            }
        }

        // install k8s
        if (common.checkContains('STACK_INSTALL', 'k8s')) {

            stage('Install Kubernetes infra') {
                if (STACK_TYPE == 'aws') {
                    // configure kubernetes_control_address - save loadbalancer
                    def kubernetes_control_address = aws.getOutputs(venv, aws_env_vars, STACK_NAME, 'ControlLoadBalancer')
                    print(kubernetes_control_address)
                    salt.runSaltProcessStep(saltMaster, 'I@salt:master', 'reclass.cluster_meta_set', ['kubernetes_control_address', kubernetes_control_address], null, true)
                }

                // ensure certificates are generated properly
                salt.runSaltProcessStep(saltMaster, '*', 'saltutil.refresh_pillar', [], null, true)
                salt.enforceState(saltMaster, '*', ['salt.minion.cert'], true)

                orchestrate.installKubernetesInfra(saltMaster)
            }

            if (common.checkContains('STACK_INSTALL', 'contrail')) {
                stage('Install Contrail for Kubernetes') {
                    orchestrate.installContrailNetwork(saltMaster)
                    orchestrate.installContrailCompute(saltMaster)
                    orchestrate.installKubernetesContrailCompute(saltMaster)
                }
            }

            stage('Install Kubernetes control') {

                orchestrate.installKubernetesControl(saltMaster)
            }

            stage('Scale Kubernetes computes') {
                if (STACK_COMPUTE_COUNT > 0) {
                    if (STACK_TYPE == 'aws') {
                        // get stack info
                        def scaling_group = aws.getOutputs(venv, aws_env_vars, STACK_NAME, 'ComputesScalingGroup')

                        //update autoscaling group
                        aws.updateAutoscalingGroup(venv, aws_env_vars, scaling_group, ["--desired-capacity " + STACK_COMPUTE_COUNT])

                        // wait for computes to boot up
                        aws.waitForAutoscalingInstances(venv, aws_env_vars, scaling_group)
                        sleep(60)
                    }

                    orchestrate.installKubernetesCompute(saltMaster)
                }
            }
        }

        // install openstack
        if (common.checkContains('STACK_INSTALL', 'openstack')) {
            // install Infra and control, tests, ...

            stage('Install OpenStack infra') {
                orchestrate.installOpenstackInfra(saltMaster)
            }

            stage('Install OpenStack control') {
                orchestrate.installOpenstackControl(saltMaster)
            }

            stage('Install OpenStack network') {

                if (common.checkContains('STACK_INSTALL', 'contrail')) {
                    orchestrate.installContrailNetwork(saltMaster)
                } else if (common.checkContains('STACK_INSTALL', 'ovs')) {
                    orchestrate.installOpenstackNetwork(saltMaster)
                }

                salt.runSaltProcessStep(saltMaster, 'I@keystone:server', 'cmd.run', ['. /root/keystonerc; neutron net-list'])
                salt.runSaltProcessStep(saltMaster, 'I@keystone:server', 'cmd.run', ['. /root/keystonerc; nova net-list'])
            }

            if (salt.testTarget(saltMaster, 'I@ironic:conductor')){
                stage('Install OpenStack Ironic conductor') {
                    orchestrate.installIronicConductor(saltMaster)
                }
            }


            stage('Install OpenStack compute') {
                orchestrate.installOpenstackCompute(saltMaster)

                if (common.checkContains('STACK_INSTALL', 'contrail')) {
                    orchestrate.installContrailCompute(saltMaster)
                }
            }

        }


        if (common.checkContains('STACK_INSTALL', 'sl-legacy')) {
            stage('Install StackLight v1') {
                orchestrate.installStacklightv1Control(saltMaster)
                orchestrate.installStacklightv1Client(saltMaster)
            }
        }

        if (common.checkContains('STACK_INSTALL', 'stacklight')) {
            stage('Install StackLight') {
                orchestrate.installDockerSwarm(saltMaster)
                orchestrate.installStacklight(saltMaster)
            }
        }

        //
        // Test
        //
        def artifacts_dir = '_artifacts/'

        if (common.checkContains('STACK_TEST', 'k8s')) {
            stage('Run k8s bootstrap tests') {
                def image = 'tomkukral/k8s-scripts'
                def output_file = image.replaceAll('/', '-') + '.output'

                // run image
                test.runConformanceTests(saltMaster, TEST_K8S_API_SERVER, image)

                // collect output
                sh "mkdir -p ${artifacts_dir}"
                file_content = salt.getFileContent(saltMaster, 'ctl01*', '/tmp/' + output_file)
                writeFile file: "${artifacts_dir}${output_file}", text: file_content
                sh "cat ${artifacts_dir}${output_file}"

                // collect artifacts
                archiveArtifacts artifacts: "${artifacts_dir}${output_file}"
            }

            stage('Run k8s conformance e2e tests') {
                def image = K8S_CONFORMANCE_IMAGE
                def output_file = image.replaceAll('/', '-') + '.output'

                // run image
                test.runConformanceTests(saltMaster, TEST_K8S_API_SERVER, image)

                // collect output
                sh "mkdir -p ${artifacts_dir}"
                file_content = salt.getFileContent(saltMaster, 'ctl01*', '/tmp/' + output_file)
                writeFile file: "${artifacts_dir}${output_file}", text: file_content
                sh "cat ${artifacts_dir}${output_file}"

                // collect artifacts
                archiveArtifacts artifacts: "${artifacts_dir}${output_file}"
            }
        }

        if (common.checkContains('STACK_TEST', 'openstack')) {
            if (common.checkContains('TEST_DOCKER_INSTALL', 'true')) {
                test.install_docker(saltMaster, TEST_TEMPEST_TARGET)
            }
            stage('Run OpenStack tests') {
                test.runTempestTests(saltMaster, TEST_TEMPEST_IMAGE, TEST_TEMPEST_TARGET, TEST_TEMPEST_PATTERN)
            }

            stage('Copy Tempest results to config node') {
                test.copyTempestResults(saltMaster, TEST_TEMPEST_TARGET)
            }
        }

        if (common.checkContains('STACK_INSTALL', 'finalize')) {
            stage('Finalize') {
                salt.runSaltProcessStep(saltMaster, '*', 'state.apply', [], null, true)
            }
        }

    } catch (Throwable e) {
        currentBuild.result = 'FAILURE'
        throw e
    } finally {


        //
        // Clean
        //

        if (STACK_NAME && STACK_NAME != '') {
            // send notification
            common.sendNotification(currentBuild.result, STACK_NAME, ["slack"])
        }

        if (STACK_DELETE.toBoolean() == true) {
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

                if (SALT_MASTER_URL) {
                    common.errorMsg("Salt master URL: ${SALT_MASTER_URL}")
                }
            }
        }
    }
}
