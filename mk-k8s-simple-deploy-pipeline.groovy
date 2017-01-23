/**
 *
 * Launch heat stack with basic k8s
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
 *   CONFORMANCE_IMAGE          Path to docker image with conformance e2e tests
 *   K8S_API_SERVER             Kubernetes API address
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

    if (HEAT_STACK_NAME == "") {
        HEAT_STACK_NAME = JOB_NAME + "-b" + BUILD_NUMBER
    }

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
        salt.installFoundationInfra(saltMaster)
        salt.validateFoundationInfra(saltMaster)
    }

    stage("Install Kubernetes infra") {
        salt.installOpenstackMcpInfra(saltMaster)
    }

    stage("Install Kubernetes control") {
        salt.installOpenstackMcpControl(saltMaster)
    }

    //stage("Install Kubernetes compute") {
    //    salt.installOpenstackMcpCompute(saltMaster)
    //}

    stage("Run k8s conformance e2e tests") {
        salt.runConformanceTests(saltMaster, K8S_API_SERVER, CONFORMANCE_IMAGE)
    }

    if (HEAT_STACK_DELETE == "1") {
        stage('Trigger cleanup job') {
            build job: 'mk-k8s-cleanup', parameters: [[$class: 'StringParameterValue', name: 'HEAT_STACK_NAME', value: HEAT_STACK_NAME]]
        }
    }

}
