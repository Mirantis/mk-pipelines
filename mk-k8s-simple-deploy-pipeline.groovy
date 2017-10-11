/**
 * DO NOT USE THIS OUTDATED PIPELINE - add your steps to lab-pipeline
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
 *   RUN_TESTS                  Run test (0/1)
 *   HEAT_STACK_DELETE          Delete Heat stack when finished (0/1)
 */

git = new com.mirantis.mk.Git()
openstack = new com.mirantis.mk.Openstack()
salt = new com.mirantis.mk.Salt()
orchestrate = new com.mirantis.mk.Orchestrate()
test = new com.mirantis.mk.Test()
def python = new com.mirantis.mk.Python()

def pepperEnv = "pepperEnv"
artifacts_dir = "_artifacts"

node {

    // connection objects
    def openstackCloud

    // value defaults
    def openstackVersion = OPENSTACK_API_CLIENT ? OPENSTACK_API_CLIENT : 'liberty'
    def openstackEnv = "${env.WORKSPACE}/venv"

    if (HEAT_STACK_NAME == "") {
        HEAT_STACK_NAME = BUILD_TAG
    }

    stage ('Download Heat templates') {
        git.checkoutGitRepository('template', HEAT_TEMPLATE_URL, HEAT_TEMPLATE_BRANCH, HEAT_TEMPLATE_CREDENTIALS)
    }

    stage('Install OpenStack env') {
        openstack.setupOpenstackVirtualenv(openstackEnv, openstackVersion)
    }

    stage('Connect to OpenStack cloud') {
        openstackCloud = openstack.createOpenstackEnv(OPENSTACK_API_URL, OPENSTACK_API_CREDENTIALS, OPENSTACK_API_PROJECT,
        "", OPENSTACK_API_PROJECT_DOMAIN_ID, OPENSTACK_API_USER_DOMAIN_ID, OPENSTACK_API_VERSION)
        openstack.getKeystoneToken(openstackCloud, openstackEnv)
    }

    stage('Launch new Heat stack') {
        envParams = [
                'instance_zone': HEAT_STACK_ZONE,
                'public_net': HEAT_STACK_PUBLIC_NET
        ]
        openstack.createHeatStack(openstackCloud, HEAT_STACK_NAME, HEAT_STACK_TEMPLATE, envParams, HEAT_STACK_ENVIRONMENT, openstackEnv)
    }

    stage("Connect to Salt master") {
        saltMasterHost = openstack.getHeatStackOutputParam(openstackCloud, HEAT_STACK_NAME, 'salt_master_ip', openstackEnv)
        saltMasterUrl = "http://${saltMasterHost}:8088"
        python.setupPepperVirtualenv(venvPepper, saltMasterUrl, SALT_MASTER_CREDENTIALS)
    }

    stage("Install core infra") {
        orchestrate.installFoundationInfra(pepperEnv)
        orchestrate.validateFoundationInfra(pepperEnv)
    }

    stage("Install Kubernetes infra") {
        orchestrate.installOpenstackMcpInfra(pepperEnv)
    }

    stage("Install Kubernetes control") {
        orchestrate.installOpenstackMcpControl(pepperEnv)
    }

    if (RUN_TESTS == "1") {
        sleep(30)
        stage('Run k8s bootstrap tests') {
            test.runConformanceTests(pepperEnv, 'ctl01*', K8S_API_SERVER, 'tomkukral/k8s-scripts')
        }

        stage("Run k8s conformance e2e tests") {
            test.runConformanceTests(pepperEnv, 'ctl01*', K8S_API_SERVER, CONFORMANCE_IMAGE)
        }

        stage("Copy k8s e2e test output to config node ") {
            test.copyTestsOutput(pepperEnv,CONFORMANCE_IMAGE)
        }

        stage("Copy k8s e2e test output to host ") {
            sh '''
                mkdir ${env.WORKSPACE}/${artifacts_dir}
               '''
            try {
                test.catTestsOutput(pepperEnv,CONFORMANCE_IMAGE) >> ${env.WORKSPACE}/${artifacts_dir}/$CONFORMANCE_IMAGE
            } catch (InterruptedException x) {
                echo "The job was aborted"
            } finally {
                archiveArtifacts allowEmptyArchive: true, artifacts: '_artifacts/*', excludes: null
                junit keepLongStdio: true, testResults: '_artifacts/**.xml'
                sh "sudo chown -R jenkins:jenkins ${env.WORKSPACE}"
            }

        }
    }

    if (HEAT_STACK_DELETE == "1") {
        stage('Trigger cleanup job') {
            build job: 'mk-k8s-cleanup', parameters: [[$class: 'StringParameterValue', name: 'HEAT_STACK_NAME', value: HEAT_STACK_NAME]]
        }
    }

}
