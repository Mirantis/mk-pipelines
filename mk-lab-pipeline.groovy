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
 *   HEAT_STACK_REUSE           Reuse Heat stack (don't create one)
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
 *
 *   INSTALL                    What should be installed (k8s, openstack, ...)
 *   TESTS                      Run tests (bool)
 */

git = new com.mirantis.mk.Git()
openstack = new com.mirantis.mk.Openstack()
salt = new com.mirantis.mk.Salt()
orchestrate = new com.mirantis.mk.Orchestrate()

node {

    // connection objects
    def openstackCloud
    def saltMaster

    // value defaults
    def openstackVersion = OPENSTACK_API_CLIENT ? OPENSTACK_API_CLIENT : 'liberty'
    def openstackEnv = "${env.WORKSPACE}/venv"

    if (HEAT_STACK_NAME == '') {
        HEAT_STACK_NAME = BUILD_TAG
    }

    //
    // Bootstrap
    //

    stage ('Download Heat templates') {
        git.checkoutGitRepository('template', HEAT_TEMPLATE_URL, HEAT_TEMPLATE_BRANCH, HEAT_TEMPLATE_CREDENTIALS)
    }

    stage('Install OpenStack CLI') {
        openstack.setupOpenstackVirtualenv(openstackEnv, openstackVersion)
    }

    stage('Connect to OpenStack cloud') {
        openstackCloud = openstack.createOpenstackEnv(OPENSTACK_API_URL, OPENSTACK_API_CREDENTIALS, OPENSTACK_API_PROJECT)
        openstack.getKeystoneToken(openstackCloud, openstackEnv)
    }

    if (HEAT_STACK_REUSE == 'false') {
        stage('Launch new Heat stack') {
            envParams = [
                'availability_zone': HEAT_STACK_ZONE,
                'public_net': HEAT_STACK_PUBLIC_NET
            ]
            openstack.createHeatStack(openstackCloud, HEAT_STACK_NAME, HEAT_STACK_TEMPLATE, envParams, HEAT_STACK_ENVIRONMENT, openstackEnv)
        }
    }

    stage('Connect to Salt master') {
        saltMasterHost = openstack.getHeatStackOutputParam(openstackCloud, HEAT_STACK_NAME, 'salt_master_ip', openstackEnv)
        saltMasterUrl = "http://${saltMasterHost}:8088"
        saltMaster = salt.connection(saltMasterUrl, SALT_MASTER_CREDENTIALS)
    }

    //
    // Install
    //

    stage('Install core infra') {
        // salt.master, reclass
        // refresh_pillar
        // sync_all
        // linux,openssh,salt.minion.ntp

        orchestrate.installFoundationInfra(saltMaster)
        orchestrate.validateFoundationInfra(saltMaster)
    }


    if (INSTALL.toLowerCase().contains('k8s')) {
        stage('Install Kubernetes infra') {
            orchestrate.installOpenstackMcpInfra(saltMaster)
        }

        stage('Install Kubernetes control') {
            orchestrate.installOpenstackMcpControl(saltMaster)
        }

        if (TESTS.toLowerCase().contains('k8s')) {
            stage('Run k8s bootstrap tests') {
                orchestrate.runConformanceTests(saltMaster, K8S_API_SERVER, 'tomkukral/k8s-scripts')
            }

            stage('Run k8s conformance e2e tests') {
                orchestrate.runConformanceTests(saltMaster, K8S_API_SERVER, K8S_CONFORMANCE_IMAGE)
            }
        }
    }

    if (INSTALL.toLowerCase().contains('openstack')) {
        // install Infra and control, tests, ...

        stage('Install OpenStack infra') {
            orchestrate.installOpenstackMkInfra(saltMaster)
        }

        stage('Install OpenStack control') {
            orchestrate.installOpenstackMkControl(saltMaster)
        }

        stage('Install OpenStack network') {
            orchestrate.installOpenstackMkNetwork(saltMaster)
        }

        stage('Install OpenStack compute') {
            orchestrate.installOpenstackMkCompute(saltMaster)
        }

        //if (TESTS.toLowerCase().contains('openstack')) {
        //    stage('Run OpenStack tests') {
        //        orchestrate...
        //    }
        //}
    }

    //
    // Cleanup
    //

    if (HEAT_STACK_DELETE == 'true') {
        stage('Trigger cleanup job') {
            build job: 'deploy_heat_cleanup', parameters: [[$class: 'StringParameterValue', name: 'HEAT_STACK_NAME', value: HEAT_STACK_NAME]]
        }
    }
}
