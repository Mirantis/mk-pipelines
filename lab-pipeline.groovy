/**
 *
 * Launch heat stack with basic k8s
 * Flow parameters:
 *   STACK_TYPE                 Orchestration engine: heat, ''
 *   INSTALL                    What should be installed (k8s, openstack, ...)
 *   TEST                       What should be tested (k8s, openstack, ...)
 *
 * Expected parameters:
 *
 * required for STACK_TYPE=heat
 *   HEAT_TEMPLATE_URL          URL to git repo with Heat templates
 *   HEAT_TEMPLATE_CREDENTIALS  Credentials to the Heat templates repo
 *   HEAT_TEMPLATE_BRANCH       Heat templates repo branch
 *   HEAT_STACK_TEMPLATE        Heat stack HOT template
 *   HEAT_STACK_ENVIRONMENT     Heat stack environmental parameters
 *   HEAT_STACK_ZONE            Heat stack availability zone
 *   HEAT_STACK_PUBLIC_NET      Heat stack floating IP pool
 *   HEAT_STACK_DELETE          Delete Heat stack when finished (bool)
 *   HEAT_STACK_CLEANUP_JOB     Name of job for deleting Heat stack
 *   HEAT_STACK_REUSE           Reuse Heat stack (don't create one)
 *   OPENSTACK_API_URL          OpenStack API address
 *   OPENSTACK_API_CREDENTIALS  Credentials to the OpenStack API
 *   OPENSTACK_API_PROJECT      OpenStack project to connect to
 *   OPENSTACK_API_CLIENT       Versions of OpenStack python clients
 *   OPENSTACK_API_VERSION      Version of the OpenStack API (2/3)
 *
 *   SALT_MASTER_CREDENTIALS    Credentials to the Salt API
 *
 * required for STACK_TYPE=NONE or empty string
 *   SALT_MASTER_URL            URL of Salt-API

 *   K8S_API_SERVER             Kubernetes API address
 *   K8S_CONFORMANCE_IMAGE      Path to docker image with conformance e2e tests
 *
 *   TEMPEST_IMAGE_LINK         Tempest image link
 *
 * optional parameters for overwriting soft params
 *   KUBERNETES_HYPERKUBE_IMAGE  Docker repository and tag for hyperkube image
 *
 */

common = new com.mirantis.mk.Common()
git = new com.mirantis.mk.Git()
openstack = new com.mirantis.mk.Openstack()
orchestrate = new com.mirantis.mk.Orchestrate()
salt = new com.mirantis.mk.Salt()
test = new com.mirantis.mk.Test()

_MAX_PERMITTED_STACKS = 2
overwriteFile = "/srv/salt/reclass/classes/cluster/overwrite.yml"

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

                    if (HEAT_STACK_REUSE.toBoolean() == true && HEAT_STACK_NAME == '') {
                        error("If you want to reuse existing stack you need to provide it's name")
                    }

                    if (HEAT_STACK_REUSE.toBoolean() == false) {
                        // Don't allow to set custom heat stack name
                        wrap([$class: 'BuildUser']) {
                            if (env.BUILD_USER_ID) {
                                HEAT_STACK_NAME = "${env.BUILD_USER_ID}-${JOB_NAME}-${BUILD_NUMBER}"
                            } else {
                                HEAT_STACK_NAME = "jenkins-${JOB_NAME}-${BUILD_NUMBER}"
                            }
                            currentBuild.description = HEAT_STACK_NAME
                        }
                    }

                    // set description
                    currentBuild.description = "${HEAT_STACK_NAME}"

                    // get templates
                    git.checkoutGitRepository('template', HEAT_TEMPLATE_URL, HEAT_TEMPLATE_BRANCH, HEAT_TEMPLATE_CREDENTIALS)

                    // create openstack env
                    openstack.setupOpenstackVirtualenv(openstackEnv, openstackVersion)
                    openstackCloud = openstack.createOpenstackEnv(OPENSTACK_API_URL, OPENSTACK_API_CREDENTIALS, OPENSTACK_API_PROJECT)
                    openstack.getKeystoneToken(openstackCloud, openstackEnv)
                    //
                    // Verify possibility of create stack for given user and stack type
                    //
                    wrap([$class: 'BuildUser']) {
                        if (env.BUILD_USER_ID && !env.BUILD_USER_ID.equals("jenkins")) {
                            def existingStacks = openstack.getStacksForNameContains(openstackCloud, "${env.BUILD_USER_ID}-${JOB_NAME}", openstackEnv)
                            if(existingStacks.size() >= _MAX_PERMITTED_STACKS){
                                HEAT_STACK_DELETE = "false"
                                throw new Exception("You cannot create new stack, you already have ${_MAX_PERMITTED_STACKS} stacks of this type (${JOB_NAME}). \nStack names: ${existingStacks}")
                            }
                        }
                    }
                    // launch stack
                    if (HEAT_STACK_REUSE.toBoolean() == false) {
                        stage('Launch new Heat stack') {
                            // create stack
                            envParams = [
                                'instance_zone': HEAT_STACK_ZONE,
                                'public_net': HEAT_STACK_PUBLIC_NET
                            ]
                            openstack.createHeatStack(openstackCloud, HEAT_STACK_NAME, HEAT_STACK_TEMPLATE, envParams, HEAT_STACK_ENVIRONMENT, openstackEnv)
                        }
                    }

                    // get SALT_MASTER_URL
                    saltMasterHost = openstack.getHeatStackOutputParam(openstackCloud, HEAT_STACK_NAME, 'salt_master_ip', openstackEnv)
                    currentBuild.description = "${HEAT_STACK_NAME}: ${saltMasterHost}"

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

            //
            // Install
            //

            if (common.checkContains('INSTALL', 'core')) {
                stage('Install core infrastructure') {
                    orchestrate.installFoundationInfra(saltMaster)

                    if (common.checkContains('INSTALL', 'kvm')) {
                        orchestrate.installInfraKvm(saltMaster)
                        orchestrate.installFoundationInfra(saltMaster)
                    }

                    orchestrate.validateFoundationInfra(saltMaster)
                }
            }

            // install k8s
            if (common.checkContains('INSTALL', 'k8s')) {
                stage('Install Kubernetes infra') {
                    orchestrate.installKubernetesInfra(saltMaster)
                }

                stage('Install Kubernetes control') {

                    // Overwrite Kubernetes vars if specified
                    if (env.getEnvironment().containsKey("KUBERNETES_HYPERKUBE_IMAGE")) {
                        salt.runSaltProcessStep(saltMaster, 'I@salt:master', 'file.append', overwriteFile, "    kubernetes_hyperkube_image: ${KUBERNETES_HYPERKUBE_IMAGE}")
                    }

                    orchestrate.installKubernetesControl(saltMaster)
                }
            }

            // install openstack
            if (common.checkContains('INSTALL', 'openstack')) {
                // install Infra and control, tests, ...

                stage('Install OpenStack infra') {
                    orchestrate.installOpenstackInfra(saltMaster)
                }

                stage('Install OpenStack control') {
                    orchestrate.installOpenstackControl(saltMaster)
                }

                stage('Install OpenStack network') {

                    if (common.checkContains('INSTALL', 'contrail')) {
                        orchestrate.installContrailNetwork(saltMaster)
                    } else if (common.checkContains('INSTALL', 'ovs')) {
                        orchestrate.installOpenstackNetwork(saltMaster)
                    }

                    salt.runSaltProcessStep(saltMaster, 'I@keystone:server', 'cmd.run', ['. /root/keystonerc; neutron net-list'])
                    salt.runSaltProcessStep(saltMaster, 'I@keystone:server', 'cmd.run', ['. /root/keystonerc; nova net-list'])
                }

                stage('Install OpenStack compute') {
                    orchestrate.installOpenstackCompute(saltMaster)

                    if (common.checkContains('INSTALL', 'contrail')) {
                        orchestrate.installContrailCompute(saltMaster)
                    }
                }

            }


            if (common.checkContains('INSTALL', 'stacklight')) {
                stage('Install StackLight') {
                    orchestrate.installStacklightControl(saltMaster)
                    orchestrate.installStacklightClient(saltMaster)
                }
            }

            //
            // Test
            //
            def artifacts_dir = '_artifacts/'

            if (common.checkContains('TEST', 'k8s')) {
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

            if (common.checkContains('TEST', 'openstack')) {
                stage('Run OpenStack tests') {
                    test.runTempestTests(saltMaster, TEMPEST_IMAGE_LINK)
                }

                stage('Copy Tempest results to config node') {
                    test.copyTempestResults(saltMaster)
                }
            }

            stage('Finalize') {
                if (INSTALL != '') {
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

            if (STACK_TYPE == 'heat') {
                // send notification
                common.sendNotification(currentBuild.result, HEAT_STACK_NAME, ["slack"])

                if (HEAT_STACK_DELETE.toBoolean() == true) {
                    common.errorMsg('Heat job cleanup triggered')
                    stage('Trigger cleanup job') {
                        build job: 'deploy-heat-cleanup', parameters: [[$class: 'StringParameterValue', name: 'HEAT_STACK_NAME', value: HEAT_STACK_NAME]]
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
