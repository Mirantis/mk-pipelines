/**
 * Helper pipeline for AWS deployments from kqueen
 *
 * Expected parameters:
 *   STACK_NAME                 Infrastructure stack name
 *   STACK_TEMPLATE             File with stack template
 *
 *   STACK_TEMPLATE_URL         URL to git repo with stack templates
 *   STACK_TEMPLATE_CREDENTIALS Credentials to the templates repo
 *   STACK_TEMPLATE_BRANCH      Stack templates repo branch
 *   STACK_COMPUTE_COUNT        Number of compute nodes to launch
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
 */

common = new com.mirantis.mk.Common()
git = new com.mirantis.mk.Git()
openstack = new com.mirantis.mk.Openstack()
orchestrate = new com.mirantis.mk.Orchestrate()
python = new com.mirantis.mk.Python()
salt = new com.mirantis.mk.Salt()

// Define global variables
def venv
def venvPepper
def outputs = [:]

def ipRegex = "\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}"
def envParams
timeout(time: 12, unit: 'HOURS') {
    node("python") {
         try {
            // Set build-specific variables
            venv = "${env.WORKSPACE}/venv"
            venvPepper = "${env.WORKSPACE}/venvPepper"

            //
            // Prepare machines
            //
            stage ('Create infrastructure') {
                // value defaults
                envParams = [
                    'cluster_zone': HEAT_STACK_ZONE,
                    'cluster_public_net': HEAT_STACK_PUBLIC_NET
                ]

                // no underscore in STACK_NAME
                STACK_NAME = STACK_NAME.replaceAll('_', '-')
                outputs.put('stack_name', STACK_NAME)

                // set description
                currentBuild.description = STACK_NAME

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

                // set reclass repo in heat env
                try {
                    envParams.put('cfg_reclass_branch', STACK_RECLASS_BRANCH)
                    envParams.put('cfg_reclass_address', STACK_RECLASS_ADDRESS)
                } catch (MissingPropertyException e) {
                    common.infoMsg("Property STACK_RECLASS_BRANCH or STACK_RECLASS_ADDRESS not found! Using default values from template.")
                }

                // launch stack
                openstack.createHeatStack(openstackCloud, STACK_NAME, STACK_TEMPLATE, envParams, HEAT_STACK_ENVIRONMENT, venv)

                // get SALT_MASTER_URL
                saltMasterHost = openstack.getHeatStackOutputParam(openstackCloud, STACK_NAME, 'salt_master_ip', venv)
                // check that saltMasterHost is valid
                if (!saltMasterHost || !saltMasterHost.matches(ipRegex)) {
                    common.errorMsg("saltMasterHost is not a valid ip, value is: ${saltMasterHost}")
                    throw new Exception("saltMasterHost is not a valid ip")
                }

                currentBuild.description = "${STACK_NAME} ${saltMasterHost}"

                SALT_MASTER_URL = "http://${saltMasterHost}:6969"

                // Setup virtualenv for pepper
                python.setupPepperVirtualenv(venvPepper, SALT_MASTER_URL, SALT_MASTER_CREDENTIALS)
            }

            stage('Install core infrastructure') {
                def staticMgmtNetwork = false
                if (common.validInputParam('STATIC_MGMT_NETWORK')) {
                    staticMgmtNetwork = STATIC_MGMT_NETWORK.toBoolean()
                }
                orchestrate.installFoundationInfra(venvPepper, staticMgmtNetwork)

                if (common.checkContains('STACK_INSTALL', 'kvm')) {
                    orchestrate.installInfraKvm(venvPepper)
                    orchestrate.installFoundationInfra(venvPepper, staticMgmtNetwork)
                }

                orchestrate.validateFoundationInfra(venvPepper)
            }

            stage('Install Kubernetes infra') {
                // configure kubernetes_control_address - save loadbalancer
                def awsOutputs = aws.getOutputs(venv, aws_env_vars, STACK_NAME)
                common.prettyPrint(awsOutputs)
                if (awsOutputs.containsKey('ControlLoadBalancer')) {
                    salt.runSaltProcessStep(venvPepper, 'I@salt:master', 'reclass.cluster_meta_set', ['kubernetes_control_address', awsOutputs['ControlLoadBalancer']], null, true)
                    outputs.put('kubernetes_apiserver', 'https://' + awsOutputs['ControlLoadBalancer'])
                }

                // ensure certificates are generated properly
                salt.runSaltProcessStep(venvPepper, '*', 'saltutil.refresh_pillar', [], null, true)
                salt.enforceState(venvPepper, '*', ['salt.minion.cert'], true)

                orchestrate.installKubernetesInfra(venvPepper)
            }

            stage('Install Kubernetes control') {
                orchestrate.installKubernetesControl(venvPepper)

                // collect artifacts (kubeconfig)
                writeFile(file: 'kubeconfig', text: salt.getFileContent(venvPepper, 'I@kubernetes:master and *01*', '/etc/kubernetes/admin-kube-config'))
                archiveArtifacts(artifacts: 'kubeconfig')
            }

            stage('Install Kubernetes computes') {
                if (common.validInputParam('STACK_COMPUTE_COUNT')) {
                    if (STACK_COMPUTE_COUNT > 0) {
                        // get stack info
                        def scaling_group = aws.getOutputs(venv, aws_env_vars, STACK_NAME, 'ComputesScalingGroup')

                        //update autoscaling group
                        aws.updateAutoscalingGroup(venv, aws_env_vars, scaling_group, ["--desired-capacity " + STACK_COMPUTE_COUNT])

                        // wait for computes to boot up
                        aws.waitForAutoscalingInstances(venv, aws_env_vars, scaling_group)
                        sleep(60)
                    }
                }

                orchestrate.installKubernetesCompute(venvPepper)
            }

            stage('Finalize') {
                outputsPretty = common.prettify(outputs)
                print(outputsPretty)
                writeFile(file: 'outputs.json', text: outputsPretty)
                archiveArtifacts(artifacts: 'outputs.json')
            }

        } catch (Throwable e) {
            currentBuild.result = 'FAILURE'
            throw e
        } finally {
            if (currentBuild.result == 'FAILURE') {
                common.errorMsg("Deploy job FAILED and was not deleted. Please fix the problem and delete stack on you own.")

                if (common.validInputParam('SALT_MASTER_URL')) {
                    common.errorMsg("Salt master URL: ${SALT_MASTER_URL}")
                }
            }
        }
    }
}


