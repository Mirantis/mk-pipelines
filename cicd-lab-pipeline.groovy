/**
 *
 * Launch heat stack with CI/CD lab infrastructure
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
 *   SALT_MASTER_PORT           Port of salt-api, defaults to 8000
 *
 *   OPENSTACK_API_URL          OpenStack API address
 *   OPENSTACK_API_CREDENTIALS  Credentials to the OpenStack API
 *   OPENSTACK_API_PROJECT      OpenStack project to connect to
 *   OPENSTACK_API_CLIENT       Versions of OpenStack python clients
 *   OPENSTACK_API_VERSION      Version of the OpenStack API (2/3)
 *
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
                'instance_zone': HEAT_STACK_ZONE,
                'public_net': HEAT_STACK_PUBLIC_NET
            ]
            openstack.createHeatStack(openstackCloud, HEAT_STACK_NAME, HEAT_STACK_TEMPLATE, envParams, HEAT_STACK_ENVIRONMENT, openstackEnv)
        }
    }

    stage('Connect to Salt master') {
        def saltMasterPort
        try {
            saltMasterPort = SALT_MASTER_PORT
        } catch (MissingPropertyException e) {
            saltMasterPort = 8000
        }
        saltMasterHost = openstack.getHeatStackOutputParam(openstackCloud, HEAT_STACK_NAME, 'salt_master_ip', openstackEnv)
        saltMasterUrl = "http://${saltMasterHost}:${saltMasterPort}"
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

    stage("Deploy GlusterFS") {
        salt.runSaltProcessStep(saltMaster, 'I@glusterfs:server', 'state.sls', ['glusterfs.server.service'])
        salt.runSaltProcessStep(saltMaster, 'ci01*', 'state.sls', ['glusterfs.server.setup'])
        salt.runSaltProcessStep(saltMaster, 'I@glusterfs:client', 'state.sls', ['glusterfs.client'])
    }

    stage("Deploy GlusterFS") {
        salt.runSaltProcessStep(saltMaster, 'I@haproxy:proxy', 'state.sls', ['haproxy,keepalived'])
    }

    stage("Setup Docker Swarm") {
        salt.runSaltProcessStep(saltMaster, 'I@docker:host', 'state.sls', ['docker.host'])
        salt.runSaltProcessStep(saltMaster, 'I@docker:swarm:role:master', 'state.sls', ['docker.swarm'])
        salt.runSaltProcessStep(saltMaster, 'I@docker:swarm:role:master', 'mine.flush')
        salt.runSaltProcessStep(saltMaster, 'I@docker:swarm:role:master', 'mine.update')
        salt.runSaltProcessStep(saltMaster, 'I@docker:swarm', 'state.sls', ['docker.swarm'])
        salt.runSaltProcessStep(saltMaster, 'I@docker:swarm:role:master', 'cmd.run', ['docker node ls'])
    }

    stage("Deploy Docker services") {
        salt.runSaltProcessStep(saltMaster, 'I@docker:swarm:role:master', 'state.sls', ['docker.client'])
        salt.runSaltProcessStep(saltMaster, 'I@docker:swarm:role:master', 'cmd.run', ['docker service ls'])

        retry(30) {
            salt.runSaltProcessStep(saltMaster, 'I@docker:swarm:role:master', 'cmd.run', ["""/bin/bash -c '! docker service ls | grep -E "0/[0-9]+"'"""])
            sleep(10)
        }
    }

    stage("Configure CI/CD services") {
        salt.runSaltProcessStep(saltMaster, 'I@docker:swarm:role:master', 'state.sls', ['aptly'])
        retry(2) {
            // Needs to run twice to pass __virtual__ method of gerrit module
            // after installation of dependencies
            salt.runSaltProcessStep(saltMaster, 'I@docker:swarm:role:master', 'state.sls', ['gerrit'])
        }
        salt.runSaltProcessStep(saltMaster, 'I@docker:swarm:role:master', 'state.sls', ['jenkins'])
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
