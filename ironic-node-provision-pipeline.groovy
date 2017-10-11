/**
 *
 * Provision ironic nodes
 *
 * Expected parameters:
 *   STACK_NAME                 Infrastructure stack name
 *   STACK_TYPE                 Deploy OpenStack/AWS [heat/aws], use 'physical' if no stack should be started
 *
 *   AWS_STACK_REGION           CloudFormation AWS region
 *   AWS_API_CREDENTIALS        AWS Access key ID with  AWS secret access key
 *   AWS_SSH_KEY                AWS key pair name (used for SSH access)
 *
 *   HEAT_STACK_ZONE            Heat stack availability zone
 *   OPENSTACK_API_URL          OpenStack API address
 *   OPENSTACK_API_CREDENTIALS  Credentials to the OpenStack API
 *   OPENSTACK_API_PROJECT      OpenStack project to connect to
 *   OPENSTACK_API_CLIENT       Versions of OpenStack python clients
 *   OPENSTACK_API_VERSION      Version of the OpenStack API (2/3)

 *   SALT_MASTER_CREDENTIALS    Credentials to the Salt API
 *                              required for STACK_TYPE=physical
 *   SALT_MASTER_URL            URL of Salt master

 * Ironic settings:
 *   IRONIC_AUTHORIZATION_PROFILE:    Name of profile with authorization info
 *   IRONIC_DEPLOY_NODES:             Space separated list of ironic node name to deploy
                                      'all' - trigger deployment of all nodes
 *   IRONIC_DEPLOY_PROFILE:           Name of profile to apply to nodes during deployment
 *   IRONIC_DEPLOY_PARTITION_PROFILE: Name of partition profile to apply
 *   IRONIC_DEPLOY_TIMEOUT:           Timeout in minutes to wait for deploy
 *
 **/

common = new com.mirantis.mk.Common()
git = new com.mirantis.mk.Git()
openstack = new com.mirantis.mk.Openstack()
aws = new com.mirantis.mk.Aws()
orchestrate = new com.mirantis.mk.Orchestrate()
salt = new com.mirantis.mk.Salt()
test = new com.mirantis.mk.Test()
def python = new com.mirantis.mk.Python()

def pepperEnv = "pepperEnv"
def venv
def outputs = [:]

def ipRegex = "\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}"

def waitIronicDeployment(pepperEnv, node_names, target, auth_profile, deploy_timeout=60) {
    def failed_nodes = []
    timeout (time:  deploy_timeout.toInteger(), unit: 'MINUTES'){
        while (node_names.size() != 0) {
            common.infoMsg("Waiting for nodes: " + node_names.join(", ") + " to be deployed.")
            res = salt.runSaltProcessStep(pepperEnv, target, 'ironicng.list_nodes', ["profile=${auth_profile}"], null, false)
            for (n in res['return'][0].values()[0]['nodes']){
                if (n['name'] in node_names) {
                    if (n['provision_state'] == 'active'){
                        common.successMsg("Node " + n['name'] + " deployment succeed.")
                        node_names.remove(n['name'])
                        continue
                    } else if (n['provision_state'] == 'deploy failed'){
                        common.warningMsg("Node " + n['name'] + " deployment failed.")
                        node_names.remove(n['name'])
                        failed_nodes.add(n['name'])
                        continue
                    }
                }
            }
            sleep(5)
        }
    }
    return failed_nodes
}


node("python") {
    try {
        // Set build-specific variables
        venv = "${env.WORKSPACE}/venv"

        def required_params = ['IRONIC_AUTHORIZATION_PROFILE', 'IRONIC_DEPLOY_NODES']
        def missed_params = []
        for (param in required_params) {
            if (env[param] == '' ) {
                missed_params.add(param)
            }
        }
        if (missed_params){
            common.errorMsg(missed_params.join(', ') + " should be set.")
        }

        if (IRONIC_DEPLOY_PROFILE == '' && IRONIC_DEPLOY_NODES != 'all'){
            common.errorMsg("IRONIC_DEPLOY_PROFILE should be set when deploying specific nodes.")
        }

        if (SALT_MASTER_URL == '' && STACK_NAME == ''){
            common.errorMsg("Any of SALT_MASTER_URL or STACK_NAME should be defined.")
        }

        if (SALT_MASTER_URL == '' && STACK_NAME != '') {
            // Get SALT_MASTER_URL machines
            stage ('Getting SALT_MASTER_URL') {

                outputs.put('stack_type', STACK_TYPE)

                if (STACK_TYPE == 'heat') {
                    // value defaults
                    envParams = [
                        'cluster_zone': HEAT_STACK_ZONE,
                        'cluster_public_net': HEAT_STACK_PUBLIC_NET
                    ]

                    // create openstack env
                    openstack.setupOpenstackVirtualenv(venv, OPENSTACK_API_CLIENT)
                    openstackCloud = openstack.createOpenstackEnv(
                        OPENSTACK_API_URL, OPENSTACK_API_CREDENTIALS,
                        OPENSTACK_API_PROJECT, OPENSTACK_API_PROJECT_DOMAIN,
                        OPENSTACK_API_PROJECT_ID, OPENSTACK_API_USER_DOMAIN,
                        OPENSTACK_API_VERSION)
                    openstack.getKeystoneToken(openstackCloud, venv)


                    // get SALT_MASTER_URL
                    saltMasterHost = openstack.getHeatStackOutputParam(openstackCloud, STACK_NAME, 'salt_master_ip', venv)

                } else if (STACK_TYPE == 'aws') {

                    // setup environment
                    aws.setupVirtualEnv(venv)

                    // set aws_env_vars
                    aws_env_vars = aws.getEnvVars(AWS_API_CREDENTIALS, AWS_STACK_REGION)

                    // get outputs
                    saltMasterHost = aws.getOutputs(venv, aws_env_vars, STACK_NAME, 'SaltMasterIP')
                }

                if (SALT_MASTER_URL == ''){
                    // check that saltMasterHost is valid
                    if (!saltMasterHost || !saltMasterHost.matches(ipRegex)) {
                        common.errorMsg("saltMasterHost is not a valid ip, value is: ${saltMasterHost}")
                        throw new Exception("saltMasterHost is not a valid ip")
                    }
                    currentBuild.description = "${STACK_NAME} ${saltMasterHost}"
                    SALT_MASTER_URL = "http://${saltMasterHost}:6969"
                } else {
                    currentBuild.description = "${STACK_NAME}"
                }
            }
        }

        outputs.put('salt_api', SALT_MASTER_URL)

        python.setupPepperVirtualenv(venvPepper, SALT_MASTER_URL, SALT_MASTER_CREDENTIALS)



        def nodes_to_deploy=[]

        stage('Trigger deployment on nodes') {
            if (IRONIC_DEPLOY_PARTITION_PROFILE == '' && IRONIC_DEPLOY_PROFILE == '' && IRONIC_DEPLOY_NODES == 'all'){
                common.infoMsg("Trigger ironic.deploy")
                salt.enforceState(pepperEnv, RUN_TARGET, ['ironic.deploy'], true)
            } else {
                if (IRONIC_DEPLOY_NODES == 'all'){
                     res = salt.runSaltProcessStep(pepperEnv, RUN_TARGET, 'ironicng.list_nodes', ["profile=${IRONIC_AUTHORIZATION_PROFILE}"], null, true)
                     // We trigger deployment on single salt minion
                     for (n in res['return'][0].values()[0]['nodes']){
                        nodes_to_deploy.add(n['name'])
                     }
                } else {
                    nodes_to_deploy = IRONIC_DEPLOY_NODES.tokenize(',')
                }

                def cmd_params = ["profile=${IRONIC_AUTHORIZATION_PROFILE}", "deployment_profile=${IRONIC_DEPLOY_PROFILE}"]

                if (IRONIC_DEPLOY_PARTITION_PROFILE){
                    cmd_params.add("partition_profile=${IRONIC_DEPLOY_PARTITION_PROFILE}")
                }

                for (n in nodes_to_deploy){
                    common.infoMsg("Trigger deployment of ${n}")
                  salt.runSaltProcessStep(pepperEnv, RUN_TARGET, 'ironicng.deploy_node', ["${n}"] + cmd_params, null, true)
                }
            }
        }

        stage('Waiting for deployment is done.') {
            def failed_nodes = waitIronicDeployment(pepperEnv, nodes_to_deploy, RUN_TARGET, IRONIC_AUTHORIZATION_PROFILE, IRONIC_DEPLOY_TIMEOUT)
            if (failed_nodes){
                common.errorMsg("Some nodes: " + failed_nodes.join(", ") + " are failed to deploy")
                currentBuild.result = 'FAILURE'
            } else {
                common.successMsg("All nodes are deployed successfully.")
            }
        }

        outputsPretty = common.prettify(outputs)
        print(outputsPretty)
        writeFile(file: 'outputs.json', text: outputsPretty)
        archiveArtifacts(artifacts: 'outputs.json')
    } catch (Throwable e) {
        currentBuild.result = 'FAILURE'
        throw e
    }
}
