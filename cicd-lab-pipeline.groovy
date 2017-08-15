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

common = new com.mirantis.mk.Common()
git = new com.mirantis.mk.Git()
openstack = new com.mirantis.mk.Openstack()
salt = new com.mirantis.mk.Salt()
orchestrate = new com.mirantis.mk.Orchestrate()
_MAX_PERMITTED_STACKS = 2

node {
    try {
        // connection objects
        def openstackCloud
        def saltMaster

        // value defaults
        def openstackVersion = OPENSTACK_API_CLIENT ? OPENSTACK_API_CLIENT : 'liberty'
        def openstackEnv = "${env.WORKSPACE}/venv"

        try {
            sshPubKey = SSH_PUBLIC_KEY
        } catch (MissingPropertyException e) {
            sshPubKey = false
        }

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
            openstackCloud = openstack.createOpenstackEnv(
                OPENSTACK_API_URL, OPENSTACK_API_CREDENTIALS,
                OPENSTACK_API_PROJECT, OPENSTACK_API_PROJECT_DOMAIN,
                OPENSTACK_API_PROJECT_ID, OPENSTACK_API_USER_DOMAIN,
                OPENSTACK_API_VERSION)
            openstack.getKeystoneToken(openstackCloud, openstackEnv)
            wrap([$class: 'BuildUser']) {
                if (env.BUILD_USER_ID && !env.BUILD_USER_ID.equals("jenkins") && !HEAT_STACK_REUSE.toBoolean()) {
                    def existingStacks = openstack.getStacksForNameContains(openstackCloud, "${env.BUILD_USER_ID}-${JOB_NAME}", openstackEnv)
                    if(existingStacks.size() >= _MAX_PERMITTED_STACKS){
                        HEAT_STACK_DELETE = "false"
                        throw new Exception("You cannot create new stack, you already have ${_MAX_PERMITTED_STACKS} stacks of this type (${JOB_NAME}). \nStack names: ${existingStacks}")
                    }
                }
            }
        }

        if (HEAT_STACK_REUSE.toBoolean() == false) {
            stage('Launch new Heat stack') {
                envParams = [
                    'cluster_zone': HEAT_STACK_ZONE,
                    'cluster_public_net': HEAT_STACK_PUBLIC_NET
                ]
                openstack.createHeatStack(openstackCloud, HEAT_STACK_NAME, HEAT_STACK_TEMPLATE, envParams, HEAT_STACK_ENVIRONMENT, openstackEnv)
            }
        }

        stage('Connect to Salt master') {
            def saltMasterPort
            try {
                saltMasterPort = SALT_MASTER_PORT
            } catch (MissingPropertyException e) {
                saltMasterPort = 6969
            }
            saltMasterHost = openstack.getHeatStackOutputParam(openstackCloud, HEAT_STACK_NAME, 'salt_master_ip', openstackEnv)
            currentBuild.description = "${HEAT_STACK_NAME}: ${saltMasterHost}"
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
            salt.enforceState(saltMaster, 'I@glusterfs:server', 'glusterfs.server.service', true)
            retry(2) {
                salt.enforceState(saltMaster, 'ci01*', 'glusterfs.server.setup', true)
            }
            sleep(5)
            salt.enforceState(saltMaster, 'I@glusterfs:client', 'glusterfs.client', true)

            timeout(5) {
                println "Waiting for GlusterFS volumes to get mounted.."
                salt.cmdRun(saltMaster, 'I@glusterfs:client', 'while true; do systemctl -a|grep "GlusterFS File System"|grep -v mounted >/dev/null || break; done')
            }
            print common.prettyPrint(salt.cmdRun(saltMaster, 'I@glusterfs:client', 'mount|grep fuse.glusterfs || echo "Command failed"'))
        }

        stage("Deploy GlusterFS") {
            salt.enforceState(saltMaster, 'I@haproxy:proxy', 'haproxy,keepalived')
        }

        stage("Setup Docker Swarm") {
            salt.enforceState(saltMaster, 'I@docker:host', 'docker.host', true)
            salt.enforceState(saltMaster, 'I@docker:swarm:role:master', 'docker.swarm', true)
            salt.enforceState(saltMaster, 'I@docker:swarm:role:master', 'salt', true)
            salt.runSaltProcessStep(saltMaster, 'I@docker:swarm:role:master', 'mine.flush')
            salt.runSaltProcessStep(saltMaster, 'I@docker:swarm:role:master', 'mine.update')
            salt.enforceState(saltMaster, 'I@docker:swarm', 'docker.swarm', true)
            print common.prettyPrint(salt.cmdRun(saltMaster, 'I@docker:swarm:role:master', 'docker node ls'))
        }

        stage("Configure OSS services") {
            salt.enforceState(saltMaster, 'I@devops_portal:config', 'devops_portal.config')
            salt.enforceState(saltMaster, 'I@rundeck:server', 'rundeck.server')
        }

        stage("Deploy Docker services") {
            // We need /etc/aptly-publisher.yaml to be present before
            // services are deployed
            // XXX: for some weird unknown reason, refresh_pillar is
            // required to execute here
            salt.runSaltProcessStep(saltMaster, 'I@aptly:publisher', 'saltutil.refresh_pillar', [], null, true)
            salt.enforceState(saltMaster, 'I@aptly:publisher', 'aptly.publisher', true)
            retry(3) {
                sleep(5)
                salt.enforceState(saltMaster, 'I@docker:swarm:role:master', 'docker.client')
            }
            // XXX: Workaround to have `/var/lib/jenkins` on all
            // nodes where are jenkins_slave services are created.
            salt.runSaltProcessStep(saltMaster, 'I@docker:swarm', 'cmd.run', ['mkdir -p /var/lib/jenkins'])
        }

        stage("Configure CI/CD services") {
            salt.syncAll(saltMaster, '*')

            // Aptly
            timeout(10) {
                println "Waiting for Aptly to come up.."
                retry(2) {
                    // XXX: retry to workaround magical VALUE_TRIMMED
                    // response from salt master + to give slow cloud some
                    // more time to settle down
                    salt.cmdRun(saltMaster, 'I@aptly:server', 'while true; do curl -sf http://172.16.10.254:8084/api/version >/dev/null && break; done')
                }
            }
            salt.enforceState(saltMaster, 'I@aptly:server', 'aptly', true)

            // OpenLDAP
            timeout(10) {
                println "Waiting for OpenLDAP to come up.."
                salt.cmdRun(saltMaster, 'I@openldap:client', 'while true; do curl -sf ldap://172.16.10.254 >/dev/null && break; done')
            }
            salt.enforceState(saltMaster, 'I@openldap:client', 'openldap', true)

            // Gerrit
            timeout(10) {
                println "Waiting for Gerrit to come up.."
                salt.cmdRun(saltMaster, 'I@gerrit:client', 'while true; do curl -sf 172.16.10.254:8080 >/dev/null && break; done')
            }
            salt.enforceState(saltMaster, 'I@gerrit:client', 'gerrit', true)

            // Jenkins
            timeout(10) {
                println "Waiting for Jenkins to come up.."
                salt.cmdRun(saltMaster, 'I@jenkins:client', 'while true; do curl -sf 172.16.10.254:8081 >/dev/null && break; done')
            }
            retry(2) {
                // XXX: needs retry as first run installs python-jenkins
                // thus make jenkins modules available for second run
                salt.enforceState(saltMaster, 'I@jenkins:client', 'jenkins', true)
            }

            // Postgres client - initialize OSS services databases
            timeout(300){
                println "Waiting for postgresql database to come up.."
                salt.cmdRun(saltMaster, 'I@postgresql:client', 'while true; do if docker service logs postgresql_db | grep "ready to accept"; then break; else sleep 5; fi; done')
            }
            // XXX: first run usually fails on some inserts, but we need to create databases at first 
            salt.enforceState(saltMaster, 'I@postgresql:client', 'postgresql.client', true, false)

            // Setup postgres database with integration between
            // Pushkin notification service and Security Monkey security audit service
            timeout(10) {
                println "Waiting for Pushkin to come up.."
                salt.cmdRun(saltMaster, 'I@postgresql:client', 'while true; do curl -sf 172.16.10.254:8887/apps >/dev/null && break; done')
            }
            salt.enforceState(saltMaster, 'I@postgresql:client', 'postgresql.client', true)

            // Rundeck
            timeout(10) {
                println "Waiting for Rundeck to come up.."
                salt.cmdRun(saltMaster, 'I@rundeck:client', 'while true; do curl -sf 172.16.10.254:4440 >/dev/null && break; done')
            }
            salt.enforceState(saltMaster, 'I@rundeck:client', 'rundeck.client', true)

            // Elasticsearch
            timeout(10) {
                println 'Waiting for Elasticsearch to come up..'
                salt.cmdRun(saltMaster, 'I@elasticsearch:client', 'while true; do curl -sf 172.16.10.254:9200 >/dev/null && break; done')
            }
            retry(3){
              sleep(10)
              // XXX: first run sometimes fails on update indexes, so we need to wait
              salt.enforceState(saltMaster, 'I@elasticsearch:client', 'elasticsearch.client', true)
            }
        }

        stage("Finalize") {
            //
            // Deploy user's ssh key
            //
            def adminUser
            def authorizedKeysFile
            def adminUserCmdOut = salt.cmdRun(saltMaster, 'I@salt:master', "[ ! -d /home/ubuntu ] || echo 'ubuntu user exists'")
            if (adminUserCmdOut =~ /ubuntu user exists/) {
                adminUser = "ubuntu"
                authorizedKeysFile = "/home/ubuntu/.ssh/authorized_keys"
            } else {
                adminUser = "root"
                authorizedKeysFile = "/root/.ssh/authorized_keys"
            }

            if (sshPubKey) {
                println "Deploying provided ssh key at ${authorizedKeysFile}"
                salt.cmdRun(saltMaster, '*', "echo '${sshPubKey}' | tee -a ${authorizedKeysFile}")
            }

            //
            // Generate docs
            //
            try {
                try {
                    // Run sphinx state to install sphinx-build needed in
                    // upcomming orchestrate
                    salt.enforceState(saltMaster, 'I@sphinx:server', 'sphinx')
                } catch (Throwable e) {
                    true
                }
                retry(3) {
                    // TODO: fix salt.orchestrateSystem
                    // print salt.orchestrateSystem(saltMaster, ['expression': '*', 'type': 'compound'], 'sphinx.orch.generate_doc')
                    def out = salt.cmdRun(saltMaster, 'I@salt:master', 'salt-run state.orchestrate sphinx.orch.generate_doc || echo "Command execution failed"')
                    print common.prettyPrint(out)
                    if (out =~ /Command execution failed/) {
                        throw new Exception("Command execution failed")
                    }
                }
            } catch (Throwable e) {
                // We don't want sphinx docs to ruin whole build, so possible
                // errors are just ignored here
                true
            }
            salt.enforceState(saltMaster, 'I@nginx:server', 'nginx')

            def failedSvc = salt.cmdRun(saltMaster, '*', """systemctl --failed | grep -E 'loaded[ \t]+failed' && echo 'Command execution failed' || true""")
            print common.prettyPrint(failedSvc)
            if (failedSvc =~ /Command execution failed/) {
                common.errorMsg("Some services are not running. Environment may not be fully functional!")
            }

            common.successMsg("""
============================================================
Your CI/CD lab has been deployed and you can enjoy it:
Use sshuttle to connect to your private subnet:

    sshuttle -r ${adminUser}@${saltMasterHost} 172.16.10.0/24

And visit services running at 172.16.10.254 (vip address):

    9600    HAProxy statistics
    8080    Gerrit
    8081    Jenkins
    8089    LDAP administration
    4440    Rundeck
    8084    DevOps Portal
    8091    Docker swarm visualizer
    8090    Reclass-generated documentation

If you provided SSH_PUBLIC_KEY, you can use it to login,
otherwise you need to get private key connected to this
heat template.

DON'T FORGET TO TERMINATE YOUR STACK WHEN YOU DON'T NEED IT!
============================================================""")
        }
    } catch (Throwable e) {
        // If there was an error or exception thrown, the build failed
        currentBuild.result = "FAILURE"
        throw e
    } finally {
        // Cleanup
        if (HEAT_STACK_DELETE.toBoolean() == true) {
            stage('Trigger cleanup job') {
                build(job: 'deploy-stack-cleanup', parameters: [
                    [$class: 'StringParameterValue', name: 'STACK_NAME', value: HEAT_STACK_NAME],
                    [$class: 'StringParameterValue', name: 'OPENSTACK_API_PROJECT', value: OPENSTACK_API_PROJECT],
                ])
            }
        }
    }
}
