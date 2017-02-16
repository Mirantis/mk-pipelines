/**
 *
 * Launch heat stack with basic k8s
 *
 * Expected parameters:
 *   HEAT_TEMPLATE_URL          URL to git repo with Heat templates
 *   HEAT_TEMPLATE_CREDENTIALS  Credentials to the Heat templates repo
 *   HEAT_TEMPLATE_BRANCH       Heat templates repo branch
 *   HEAT_STACK_NAME            Heat stack name
 *   HEAT_STACK_TEMPLATE        Heat stack HOT template
 *   HEAT_STACK_ENVIRONMENT     Heat stack environmental parameters
 *   HEAT_STACK_ZONE            Heat stack availability zone
 *   HEAT_STACK_PUBLIC_NET      Heat stack floating IP pool
 *   HEAT_STACK_DELETE          Delete Heat stack when finished (bool)
 *   HEAT_STACK_CLEANUP_JOB     Name of job for deleting Heat stack
 *
 *   SALT_MASTER_CREDENTIALS    Credentials to the Salt API
 *
 *   OPENSTACK_API_URL          OpenStack API address
 *   OPENSTACK_API_CREDENTIALS  Credentials to the OpenStack API
 *   OPENSTACK_API_PROJECT      OpenStack project to connect to
 *   OPENSTACK_API_CLIENT       Versions of OpenStack python clients
 *   OPENSTACK_API_VERSION      Version of the OpenStack API (2/3)
 *
 *   K8S_API_SERVER             Kubernetes API address
 *   K8S_CONFORMANCE_IMAGE      Path to docker image with conformance e2e tests
 *   K8S_RUN_CONFORMANCE_TEST   Run test (bool)
 *
 *   INSTALL_K8S                Install K8S (bool)
 *   INSTALL_OPENSTACK          Install OpenStack (bool)
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
        HEAT_STACK_NAME = BUILD_TAG
    }

    stage ('Download Heat templates') {
        git.checkoutGitRepository('template', HEAT_TEMPLATE_URL, HEAT_TEMPLATE_BRANCH, HEAT_TEMPLATE_CREDENTIALS)
    }

    stage('Install OpenStack cli') {
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
        // salt.master, reclass
        // refresh_pillar
        // sync_all
        // linux,openssh,salt.minion.ntp

        salt.installFoundationInfra(saltMaster)
        salt.validateFoundationInfra(saltMaster)
    }

    if (INSTALL_K8S) {
        stage("Install Kubernetes infra") {
            salt.installOpenstackMcpInfra(saltMaster)
        }

        stage("Install Kubernetes control") {
            salt.installOpenstackMcpControl(saltMaster)
        }

        if (K8S_RUN_CONFORMANCE_TESTS) {
            stage("Run k8s conformance e2e tests") {
                salt.runConformanceTests(saltMaster, K8S_API_SERVER, K8S_CONFORMANCE_IMAGE)
            }
        }
    }

    //if (INSTALL_OPENSTACK) {
    // install Infra and control, tests, ...
    //}

    if (HEAT_STACK_DELETE) {
        stage('Trigger cleanup job') {
            build job: HEAT_STACK_CLEANUP_JOB, parameters: [[$class: 'StringParameterValue', name: 'HEAT_STACK_NAME', value: HEAT_STACK_NAME]]
        }
    }

}
