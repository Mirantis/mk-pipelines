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
    try {
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
            salt.enforceState(saltMaster, 'I@glusterfs:server', 'glusterfs.server.service', true)
            salt.enforceState(saltMaster, 'ci01*', 'glusterfs.server.setup', true)
            sleep(5)
            salt.enforceState(saltMaster, 'I@glusterfs:client', 'glusterfs.client', true)
            print salt.cmdRun(saltMaster, 'I@glusterfs:client', 'mount|grep fuse.glusterfs || echo "Command failed"')
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
            print salt.cmdRun(saltMaster, 'I@docker:swarm:role:master', 'docker node ls')
        }

        stage("Deploy Docker services") {
            salt.enforceState(saltMaster, 'I@docker:swarm:role:master', 'docker.client')

            // XXX: Hack to fix dependency of gerrit on mysql
            print salt.cmdRun(saltMaster, 'I@docker:swarm:role:master', "docker service rm gerrit; sleep 5; rm -rf /srv/volumes/gerrit/*")
            sleep(10)
            print salt.cmdRun(saltMaster, 'I@docker:swarm:role:master', "apt-get install -y mysql-client; mysql -ppassword -h172.16.10.11 -P13306 -e'drop database gerrit;create database gerrit;'")
            salt.enforceState(saltMaster, 'I@docker:swarm:role:master', 'docker.client')
            // ---- cut here (end of hack) ----

            retry(30) {
                out = salt.cmdRun(saltMaster, 'I@docker:swarm:role:master', """/bin/bash -c 'docker service ls | grep -E "0/[0-9]+"' && echo 'Some services are not running'""")
                for (int a = 0; a < out['return'].size(); a++) {
                    def entry = out['return'].get(a)
                    for (int i = 0; i < entry.size(); i++) {
                        def node = entry.get(i)
                        if (node) {
                            if (node.value =~ /Some services are not running/) {
                                sleep(10)
                                throw new Exception("$node.key: $node.value")
                            } else {
                                print out
                            }
                        }
                    }
                }
            }
            // Give services some time to settle
            sleep(30)
        }

        stage("Configure CI/CD services") {
            // Aptly
            salt.enforceState(saltMaster, 'I@aptly:server', 'aptly', true)

            // Gerrit
            timeout(10) {
                println "Waiting for Gerrit to come up.."
                salt.cmdRun(saltMaster, 'I@gerrit:client', 'while true; do curl -svf 172.16.10.254:8080 >/dev/null && break; done')
            }
            retry(2) {
                // Needs to run twice to pass __virtual__ method of gerrit module
                // after installation of dependencies
                try {
                    salt.enforceState(saltMaster, 'I@gerrit:client', 'gerrit', true)
                } catch (Exception e) {
                    print "Restarting Salt minion"
                    salt.cmdRun(saltMaster, 'I@gerrit:client', "exec 0>&-; exec 1>&-; exec 2>&-; nohup /bin/sh -c 'salt-call --local service.restart salt-minion' &")
                    sleep(30)
                    throw e
                }
            }

            // Jenkins
            timeout(10) {
                println "Waiting for Jenkins to come up.."
                salt.cmdRun(saltMaster, 'I@jenkins:client', 'while true; do curl -svf 172.16.10.254:8081 >/dev/null && break; done')
            }
            retry(2) {
                // Same for jenkins
                try {
                    salt.enforceState(saltMaster, 'I@jenkins:client', 'jenkins', true)
                } catch (Exception e) {
                    print "Restarting Salt minion"
                    salt.cmdRun(saltMaster, 'I@jenkins:client', "exec 0>&-; exec 1>&-; exec 2>&-; nohup /bin/sh -c 'salt-call --local service.restart salt-minion' &")
                    sleep(30)
                    throw e
                }
            }
        }

        stage("Finalize") {
            //
            // Generate docs
            //
            try {
                retry(3) {
                    salt.runSaltProcessStep(saltMaster, '*', 'state.orchestrate', ['sphinx.orch.generate_doc'])
                }
            } catch (Throwable e) {
                // We don't want sphinx docs to ruin whole build, so possible
                // errors are just ignored here
                true
            }
            salt.enforceState(saltMaster, 'I@nginx:server', 'nginx')

            if (HEAT_STACK_DELETE != 'true') {
                print """============================================================
Your CI/CD lab has been deployed and you can enjoy it:
   Use sshuttle -r ubuntu@${saltMasterHost} 172.16.10.0/24
   to connect to your private subnet and visit services
   running at 172.16.10.254 (vip address):
       9600    haproxy stats
       8080    gerrit
       8081    jenkins
       8091    Docker swarm visualizer
       8090    Reclass-generated documentation

Don't forget to terminate your stack when you don't needed!
============================================================"""
            }
        }
    } catch (Throwable e) {
        // If there was an error or exception thrown, the build failed
        currentBuild.result = "FAILURE"
        throw e
    } finally {
        // Cleanup
        if (HEAT_STACK_DELETE == 'true') {
            stage('Trigger cleanup job') {
                build job: 'deploy_heat_cleanup', parameters: [[$class: 'StringParameterValue', name: 'HEAT_STACK_NAME', value: HEAT_STACK_NAME]]
            }
        }
    }
}
