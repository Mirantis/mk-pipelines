/**
 *
 * Launch heat stack with MCP lab
 *
 * Expected parameters:
 *   HEAT_TEMPLATE_URL          URL to git repo with Heat templates
 *   HEAT_TEMPLATE_CREDENTIALS  Credentials to the Heat templates repo
 *   HEAT_TEMPLATE_BRANCH       Heat templates repo branch
 *   OPENSTACK_API_URL          OpenStack API address
 *   OPENSTACK_API_CREDENTIALS  Credentials to the OpenStack API
 *   OPENSTACK_API_PROJECT      OpenStack project to connect to
 *   OPENSTACK_API_CLIENT       Versions of OpenStack python clients
 *   OPENSTACK_API_VERSION      Version of the OpenStack API (2/3)
 *   SALT_MASTER_CREDENTIALS    Credentials to the Salt API
 *   HEAT_STACK_NAME            Heat stack name
 *   HEAT_STACK_TEMPLATE        Heat stack HOT template
 *   HEAT_STACK_ENVIRONMENT     Heat stack environmental parameters
 *   HEAT_STACK_ZONE            Heat stack availability zone
 *   HEAT_STACK_PUBLIC_NET      Heat stack floating IP pool
 */

git = new com.mirantis.mk.git()
openstack = new com.mirantis.mk.openstack()
salt = new com.mirantis.mk.salt()

node {

    // connection objects
    def openstackCloud
    def saltMaster

    // value defaults
    def openstackVersion = OPENSTACK_API_CLIENT ? OPENSTACK_API_CLIENT : "liberty"
    def openstackEnv = "${env.WORKSPACE}/venv"

    stage ('Download Heat templates') {
        git.checkoutGitRepository('template', HEAT_TEMPLATE_URL, HEAT_TEMPLATE_BRANCH, HEAT_TEMPLATE_CREDENTIALS)
    }

    stage('Install OpenStack env') {
        openstack.setupOpenstackVirtualenv(openstackEnv, openstackVersion)
    }

    stage('Connect to OpenStack cloud') {
        openstackCloud = openstack.createOpenstackEnv(OPENSTACK_API_URL, OPENSTACK_API_CREDENTIALS, OPENSTACK_API_PROJECT)
        openstack.getKeystoneToken(openstackCloud, openstackEnv)
    }

    stage('Launch new Heat stack') {
        envParams = [
            'availability_zone': HEAT_STACK_ZONE,
            'public_net': HEAT_STACK_PUBLIC_NET
        ]
        openstack.createHeatStack(openstackCloud, HEAT_STACK_NAME, HEAT_STACK_TEMPLATE, envParams, HEAT_STACK_ENVIRONMENT, openstackEnv)
    }

    stage("Connect to Salt master") {
        saltMasterHost = openstack.getHeatStackOutputParam(openstackCloud, HEAT_STACK_NAME, 'salt_master_ip', openstackEnv)
        saltMasterUrl = "http://${saltMasterHost}:8000"
        saltMaster = salt.createSaltConnection(saltMasterUrl, SALT_MASTER_CREDENTIALS)
    }

    stage("Install core infra") {
        installFoundationInfra(saltMaster)
        validateFoundationInfra(saltMaster)
    }

    stage("Install Kubernetes infra") {
        installOpenstackMcpInfra(saltMaster)
    }

    stage("Install Kubernetes control") {
        installOpenstackMcpControl(saltMaster)
    }

    stage("Install Kubernetes compute") {
        installOpenstackMcpCompute(saltMaster)
    }

    //stage('Delete Heat stack') {
    //    openstack.deleteHeatStack(openstackCloud, HEAT_STACK_NAME, openstackEnv)
    //}

}
