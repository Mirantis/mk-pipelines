/**









* This pipeline is deprecated, please use cloud-deploy-pipeline












 *
 * Launch heat stack with basic k8s
 * Flow parameters:
 *   STACK_NAME                  Heat stack name
 *   STACK_TYPE                  Orchestration engine: heat, ''
 *   STACK_INSTALL               What should be installed (k8s, openstack, ...)
 *   STACK_TEST                  What should be tested (k8s, openstack, ...)
 *
 *   STACK_TEMPLATE_URL          URL to git repo with stack templates
 *   STACK_TEMPLATE_BRANCH       Stack templates repo branch
 *   STACK_TEMPLATE_CREDENTIALS  Credentials to the stack templates repo
 *   STACK_TEMPLATE              Heat stack HOT template
 *   STACK_RECLASS_ADDRESS       Stack reclass address
 *   STACK_RECLASS_BRANCH        Stack reclass repo branch
 *   STACK_DELETE                Delete stack when finished (bool)
 *   STACK_REUSE                 Reuse stack (don't create one)
 *   STACK_CLEANUP_JOB           Name of job for deleting Heat stack
 *
 * Expected parameters:
 * required for STACK_TYPE=heat
 *   HEAT_STACK_ENVIRONMENT       Heat stack environmental parameters
 *   HEAT_STACK_ZONE              Heat stack availability zone
 *   HEAT_STACK_PUBLIC_NET        Heat stack floating IP pool
 *   OPENSTACK_API_URL            OpenStack API address
 *   OPENSTACK_API_CREDENTIALS    Credentials to the OpenStack API
 *   OPENSTACK_API_PROJECT        OpenStack project to connect to
 *   OPENSTACK_API_PROJECT_DOMAIN Domain for OpenStack project
 *   OPENSTACK_API_PROJECT_ID     ID for OpenStack project
 *   OPENSTACK_API_USER_DOMAIN    Domain for OpenStack user
 *   OPENSTACK_API_CLIENT         Versions of OpenStack python clients
 *   OPENSTACK_API_VERSION        Version of the OpenStack API (2/3)
 *
 *   SALT_MASTER_CREDENTIALS    Credentials to the Salt API
 *
 * required for STACK_TYPE=NONE or empty string
 *   SALT_MASTER_URL            URL of Salt-API
 *
 * Test settings:
 *   TEST_K8S_API_SERVER     Kubernetes API address
 *   TEST_K8S_CONFORMANCE_IMAGE   Path to docker image with conformance e2e tests
 *
 *   TEST_TEMPEST_IMAGE           Tempest image link
 *   TEST_DOCKER_INSTALL          Install docker on the target if tue
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
orchestrate = new com.mirantis.mk.Orchestrate()
salt = new com.mirantis.mk.Salt()
test = new com.mirantis.mk.Test()

_MAX_PERMITTED_STACKS = 2

timestamps {
    node {
        // try to get STACK_INSTALL or fallback to INSTALL if exists
        try {
          def temporary = STACK_INSTALL
        } catch (MissingPropertyException e) {
          try {
            STACK_INSTALL = INSTALL
            env['STACK_INSTALL'] = INSTALL
          } catch (MissingPropertyException e2) {
            common.errorMsg("Property STACK_INSTALL or INSTALL not found!")
          }
        }
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
                    openstackCloud = openstack.createOpenstackEnv(
                        OPENSTACK_API_URL, OPENSTACK_API_CREDENTIALS,
                        OPENSTACK_API_PROJECT, OPENSTACK_API_PROJECT_DOMAIN,
                        OPENSTACK_API_PROJECT_ID, OPENSTACK_API_USER_DOMAIN,
                        OPENSTACK_API_VERSION)
                    openstack.getKeystoneToken(openstackCloud, openstackEnv)
                    //
                    // Verify possibility of create stack for given user and stack type
                    //
                    wrap([$class: 'BuildUser']) {
                        if (env.BUILD_USER_ID && !env.BUILD_USER_ID.equals("jenkins") && !env.BUILD_USER_ID.equals("mceloud") && !STACK_REUSE.toBoolean()) {
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
                            try {
                                envParams.put('cfg_reclass_branch', STACK_RECLASS_BRANCH)
                                envParams.put('cfg_reclass_address', STACK_RECLASS_ADDRESS)
                            } catch (MissingPropertyException e) {
                                common.infoMsg("Property STACK_RECLASS_BRANCH or STACK_RECLASS_ADDRESS not found! Using default values from template.")
                            }
                            openstack.createHeatStack(openstackCloud, STACK_NAME, STACK_TEMPLATE, envParams, HEAT_STACK_ENVIRONMENT, openstackEnv)
                        }
                    }

                    // get SALT_MASTER_URL
                    saltMasterHost = openstack.getHeatStackOutputParam(openstackCloud, STACK_NAME, 'salt_master_ip', openstackEnv)
                    currentBuild.description = "${STACK_NAME}: ${saltMasterHost}"

                    SALT_MASTER_URL = "http://${saltMasterHost}:6969"
                }
            }

            //
            // Connect to Salt master
            //

            def saltMaster
            stage('Connect to Salt API') {
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

                // install infra libs for k8s
                stage('Install Kubernetes infra') {
                    orchestrate.installKubernetesInfra(saltMaster)
                }

                // If k8s install with contrail network manager then contrail need to be install first
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
                    //test.runConformanceTests(saltMaster, TEST_K8S_API_SERVER, TEST_K8S_CONFORMANCE_IMAGE)

                    def image = TEST_K8S_CONFORMANCE_IMAGE
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

            if (STACK_TYPE == 'heat') {
                // send notification
                common.sendNotification(currentBuild.result, STACK_NAME, ["slack"])

                if (STACK_DELETE.toBoolean() == true) {
                    common.errorMsg('Heat job cleanup triggered')
                    stage('Trigger cleanup job') {
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
    }
}
