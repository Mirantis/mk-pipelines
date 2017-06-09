/**
 *
 * Launch heat/cloudformation stack
 *
 * Expected parameters:
 *   STACK_NAME                 Infrastructure stack name
 *   STACK_TEMPLATE             Stack HOT/CFN template
 *   STACK_TYPE                 Deploy OpenStack/AWS [heat/aws]
 *
 *   STACK_TEMPLATE_URL         URL to git repo with stack templates
 *   STACK_TEMPLATE_CREDENTIALS Credentials to the templates repo
 *   STACK_TEMPLATE_BRANCH      Stack templates repo branch
 *
 *   STACK_DELETE               Delete stack when finished (bool)
 *   STACK_REUSE                Reuse existing stack (don't create one)
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
 *
 *   SALT_MASTER_CREDENTIALS    Credentials to the Salt API
 *   SALT_MASTER_URL            URL of Salt master
 *
 *   K8S_API_SERVER             Kubernetes API address
 *   K8S_CONFORMANCE_IMAGE      Path to docker image with conformance e2e tests
 *
 *   TEMPEST_IMAGE_LINK         Tempest image link
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

def saltMaster

timestamps {
    node {
        try {
            //
            // Prepare machines
            //
            stage ('Create infrastructure') {

                if (STACK_TYPE == 'heat') {
                    // value defaults
                    def openstackCloud
                    def openstackVersion = OPENSTACK_API_CLIENT ? OPENSTACK_API_CLIENT : 'liberty'
                    def openstackEnv = "${env.WORKSPACE}/venv"

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
                    openstack.setupOpenstackVirtualenv(openstackEnv, openstackVersion)
                    openstackCloud = openstack.createOpenstackEnv(OPENSTACK_API_URL, OPENSTACK_API_CREDENTIALS, OPENSTACK_API_PROJECT)
                    openstack.getKeystoneToken(openstackCloud, openstackEnv)
                    //
                    // Verify possibility of create stack for given user and stack type
                    //
                    wrap([$class: 'BuildUser']) {
                        if (env.BUILD_USER_ID && !env.BUILD_USER_ID.equals("jenkins") && !STACK_REUSE.toBoolean()) {
                            def existingStacks = openstack.getStacksForNameContains(openstackCloud, "${env.BUILD_USER_ID}-${JOB_NAME}", openstackEnv)
                            if(existingStacks.size() >= _MAX_PERMITTED_STACKS){
                                STACK_DELETE = "false"
                                throw new Exception("You cannot create new stack, you already have ${_MAX_PERMITTED_STACKS} stacks of this type (${JOB_NAME}). \nStack names: ${existingStacks}")
                            }
                        }
                    }
                    // launch stack
                    if (STACK_REUSE.toBoolean() == false) {
                        stage('Launch new Heat stack') {
                            // create stack
                            envParams = [
                                'instance_zone': HEAT_STACK_ZONE,
                                'public_net': HEAT_STACK_PUBLIC_NET
                            ]
                            openstack.createHeatStack(openstackCloud, STACK_NAME, STACK_TEMPLATE, envParams, HEAT_STACK_ENVIRONMENT, openstackEnv)
                        }
                    }

                    // get SALT_MASTER_URL
                    saltMasterHost = openstack.getHeatStackOutputParam(openstackCloud, STACK_NAME, 'salt_master_ip', openstackEnv)
                    currentBuild.description = "${STACK_NAME} ${saltMasterHost}"

                    SALT_MASTER_URL = "http://${saltMasterHost}:6969"
                } else if (STACK_TYPE == 'aws') {


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

                    // prepare configuration
                    def venv_path = 'aws_venv'
                    def env_vars = aws.getEnvVars(AWS_API_CREDENTIALS, AWS_STACK_REGION)

                    if (STACK_REUSE.toBoolean() == false) {
                        // get templates
                        git.checkoutGitRepository('template', STACK_TEMPLATE_URL, STACK_TEMPLATE_BRANCH, STACK_TEMPLATE_CREDENTIALS)

                        // setup environment
                        aws.setupVirtualEnv(venv_path)

                        // start stack
                        def stack_params = [
                            "ParameterKey=KeyName,ParameterValue=" + AWS_SSH_KEY,
                            "ParameterKey=CmpNodeCount,ParameterValue=" + STACK_COMPUTE_COUNT
                        ]
                        def template_file = 'cfn/' + STACK_TEMPLATE + '.yml'
                        aws.createStack(venv_path, env_vars, template_file, STACK_NAME, stack_params)
                    }

                    // wait for stack to be ready
                    aws.waitForStatus(venv_path, env_vars, STACK_NAME, 'CREATE_COMPLETE')

                    // get outputs
                    saltMasterHost = aws.getOutputs(venv_path, env_vars, STACK_NAME, 'SaltMasterIP')
                    currentBuild.description = "${STACK_NAME} ${saltMasterHost}"
                    SALT_MASTER_URL = "http://${saltMasterHost}:6969"

                } else {
                    throw new Exception("STACK_TYPE ${STACK_TYPE} is not supported")
                }

                // Connect to Salt master
                saltMaster = salt.connection(SALT_MASTER_URL, SALT_MASTER_CREDENTIALS)
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
                    orchestrate.installKubernetesInfra(saltMaster)
                }

                stage('Install Kubernetes control') {

                    orchestrate.installKubernetesControl(saltMaster)

                }

                stage('Scale Kubernetes computes') {
                    if (STACK_COMPUTE_COUNT > 0) {
                        if (STACK_TYPE == 'aws') {
                            // configure aws
                            def venv_path = 'aws_venv'
                            def env_vars = aws.getEnvVars(AWS_API_CREDENTIALS, AWS_STACK_REGION)

                            // get stack info
                            def scaling_group = aws.getOutputs(venv_path, env_vars, STACK_NAME, 'ComputesScalingGroup')

                            //update autoscaling group
                            aws.updateAutoscalingGroup(venv_path, env_vars, scaling_group, ["--desired-capacity " + STACK_COMPUTE_COUNT])

                            // wait for computes to boot up
                            aws.waitForAutoscalingInstances(venv_path, env_vars, scaling_group)
                        }

                        orchestrate.installKubernetesCompute(saltMaster)
                    }
                }

                if (common.checkContains('STACK_INSTALL', 'contrail')) {
                    state('Install Contrail for Kubernetes') {
                        orchestrate.installContrailNetwork(saltMaster)
                        orchestrate.installContrailCompute(saltMaster)
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

                stage('Install OpenStack compute') {
                    orchestrate.installOpenstackCompute(saltMaster)

                    if (common.checkContains('STACK_INSTALL', 'contrail')) {
                        orchestrate.installContrailCompute(saltMaster)
                    }
                }

            }


            if (common.checkContains('STACK_INSTALL', 'stacklight')) {
                stage('Install StackLight') {
                    orchestrate.installStacklightControl(saltMaster)
                    orchestrate.installStacklightClient(saltMaster)
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
                    test.runConformanceTests(saltMaster, K8S_API_SERVER, image)

                    // collect output
                    sh "mkdir -p ${artifacts_dir}"
                    file_content = salt.getFileContent(saltMaster, 'ctl01*', '/tmp/' + output_file)
                    writeFile file: "${artifacts_dir}${output_file}", text: file_content
                    sh "cat ${artifacts_dir}${output_file}"

                    // collect artifacts
                    archiveArtifacts artifacts: "${artifacts_dir}${output_file}"
                }

                stage('Run k8s conformance e2e tests') {
                    //test.runConformanceTests(saltMaster, K8S_API_SERVER, K8S_CONFORMANCE_IMAGE)

                    def image = K8S_CONFORMANCE_IMAGE
                    def output_file = image.replaceAll('/', '-') + '.output'

                    // run image
                    test.runConformanceTests(saltMaster, K8S_API_SERVER, image)

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
                stage('Run deployment tests') {
                    test.runTempestTests(saltMaster, TEMPEST_IMAGE_LINK)
                }

                stage('Copy test results to config node') {
                    test.copyTempestResults(saltMaster)
                }
            }

            stage('Finalize') {
                if (STACK_INSTALL != '') {
                    try {
                        salt.runSaltProcessStep(saltMaster, '*', 'state.apply', [], null, true)
                    } catch (Exception e) {
                        common.warningMsg('State apply failed but we should continue to run')
                    }
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
                        [$class: 'StringParameterValue', name: 'STACK_TYPE', value: STACK_TYPE]
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
}
