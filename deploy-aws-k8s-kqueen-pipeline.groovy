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
 *   AWS_STACK_REGION           CloudFormation AWS region
 *   AWS_API_CREDENTIALS        AWS Access key ID with  AWS secret access key
 *   AWS_SSH_KEY                AWS key pair name (used for SSH access)
 *
 *   SALT_MASTER_CREDENTIALS    Credentials to the Salt API
 *   SALT_MASTER_URL            URL of Salt master
 *
 * optional parameters for overwriting soft params
 *   SALT_OVERRIDES              YAML with overrides for Salt deployment
 */

common = new com.mirantis.mk.Common()
git = new com.mirantis.mk.Git()
aws = new com.mirantis.mk.Aws()
orchestrate = new com.mirantis.mk.Orchestrate()
python = new com.mirantis.mk.Python()
salt = new com.mirantis.mk.Salt()


// Define global variables
def venv
def venvPepper
def outputs = [:]

def ipRegex = "\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}"
def aws_env_vars
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

                outputs.put('stack_type', "aws")
                // setup environment
                aws.setupVirtualEnv(venv)
                // set aws_env_vars
                aws_env_vars = aws.getEnvVars(AWS_API_CREDENTIALS, AWS_STACK_REGION)
                // We just use STACK_NAME from param
                currentBuild.description = STACK_NAME
                outputs.put('stack_name', STACK_NAME)

                // get templates
                git.checkoutGitRepository('template', STACK_TEMPLATE_URL, STACK_TEMPLATE_BRANCH, STACK_TEMPLATE_CREDENTIALS)

                // start stack
                def stack_params = [
                    "ParameterKey=KeyName,ParameterValue=" + AWS_SSH_KEY,
                    "ParameterKey=CmpNodeCount,ParameterValue=" + STACK_COMPUTE_COUNT
                ]
                def template_file = 'cfn/' + STACK_TEMPLATE + '.yml'
                aws.createStack(venv, aws_env_vars, template_file, STACK_NAME, stack_params)

                // wait for stack to be ready
                aws.waitForStatus(venv, aws_env_vars, STACK_NAME, 'CREATE_COMPLETE', ['ROLLBACK_COMPLETE'], 2400, 30)

                // get outputs
                saltMasterHost = aws.getOutputs(venv, aws_env_vars, STACK_NAME, 'SaltMasterIP')

                // check that saltMasterHost is valid
                if (!saltMasterHost || !saltMasterHost.matches(ipRegex)) {
                    common.errorMsg("saltMasterHost is not a valid ip, value is: ${saltMasterHost}")
                    throw new Exception("saltMasterHost is not a valid ip")
                }

                currentBuild.description = "${STACK_NAME} ${saltMasterHost}"
                SALT_MASTER_URL = "http://${saltMasterHost}:6969"

                outputs.put('salt_api', SALT_MASTER_URL)

                // Setup virtualenv for pepper
                python.setupPepperVirtualenv(venvPepper, SALT_MASTER_URL, SALT_MASTER_CREDENTIALS)
            }

            // Set up override params
            if (common.validInputParam('SALT_OVERRIDES')) {
                stage('Set Salt overrides') {
                    salt.setSaltOverrides(venvPepper,  SALT_OVERRIDES)
                }
            }

            //
            // Install
            //

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


