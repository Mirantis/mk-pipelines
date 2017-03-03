/**
 *
 * Launch heat stack with basic k8s
 * Flow parameters:
 *   STACK_TYPE                 Orchestration engine: heat, ''
 *   INSTALL                    What should be installed (k8s, openstack, ...)
 *   TEST                       What should be tested (k8s, openstack, ...)
 *
 * Expected parameters:
 *
 * required for STACK_TYPE=heat
 *   HEAT_TEMPLATE_URL          URL to git repo with Heat templates
 *   HEAT_TEMPLATE_CREDENTIALS  Credentials to the Heat templates repo
 *   HEAT_TEMPLATE_BRANCH       Heat templates repo branch
 *   HEAT_STACK_TEMPLATE        Heat stack HOT template
 *   HEAT_STACK_ENVIRONMENT     Heat stack environmental parameters
 *   HEAT_STACK_ZONE            Heat stack availability zone
 *   HEAT_STACK_PUBLIC_NET      Heat stack floating IP pool
 *   HEAT_STACK_DELETE          Delete Heat stack when finished (bool)
 *   HEAT_STACK_CLEANUP_JOB     Name of job for deleting Heat stack
 *   HEAT_STACK_REUSE           Reuse Heat stack (don't create one)
 *   OPENSTACK_API_URL          OpenStack API address
 *   OPENSTACK_API_CREDENTIALS  Credentials to the OpenStack API
 *   OPENSTACK_API_PROJECT      OpenStack project to connect to
 *   OPENSTACK_API_CLIENT       Versions of OpenStack python clients
 *   OPENSTACK_API_VERSION      Version of the OpenStack API (2/3)
 *
 *   SALT_MASTER_CREDENTIALS    Credentials to the Salt API
 *
 * required for STACK_TYPE=NONE or empty string
 *   SALT_MASTER_URL            URL of Salt-API

 *   K8S_API_SERVER             Kubernetes API address
 *   K8S_CONFORMANCE_IMAGE      Path to docker image with conformance e2e tests
 *
 */

git = new com.mirantis.mk.Git()
openstack = new com.mirantis.mk.Openstack()
salt = new com.mirantis.mk.Salt()


timestamps {
    node {
        try {


            //
            // Prepare machines
            //

            stage ('Create infrastructure') {
                if (STACK_TYPE == 'heat') {
                    // value defaults
                    def openstackCloud
                    def openstackVersion = OPENSTACK_API_CLIENT ? OPENSTACK_API_CLIENT : 'liberty'
                    def openstackEnv = "${env.WORKSPACE}/venv"

                    if (HEAT_STACK_REUSE.toBoolean() == true && HEAT_STACK_NAME == '') {
                        error("If you want to reuse existing stack you need to provide it's name")
                    }

                    if (HEAT_STACK_REUSE.toBoolean() == false) {
                        // Don't allow to set custom heat stack name
                        wrap([$class: 'BuildUser']) {
                            HEAT_STACK_NAME = "${env.BUILD_USER_ID}-${JOB_NAME}-${BUILD_NUMBER}"
                            currentBuild.description = HEAT_STACK_NAME
                        }
                    }

                    // set description
                    currentBuild.description = "${HEAT_STACK_NAME}"

                    // get templates
                    git.checkoutGitRepository('template', HEAT_TEMPLATE_URL, HEAT_TEMPLATE_BRANCH, HEAT_TEMPLATE_CREDENTIALS)

                    // create openstack env
                    openstack.setupOpenstackVirtualenv(openstackEnv, openstackVersion)
                    openstackCloud = openstack.createOpenstackEnv(OPENSTACK_API_URL, OPENSTACK_API_CREDENTIALS, OPENSTACK_API_PROJECT)
                    openstack.getKeystoneToken(openstackCloud, openstackEnv)

                    // launch stack
                    if (HEAT_STACK_REUSE.toBoolean() == false) {
                        stage('Launch new Heat stack') {
                            // create stack
                            envParams = [
                                'instance_zone': HEAT_STACK_ZONE,
                                'public_net': HEAT_STACK_PUBLIC_NET
                            ]
                            openstack.createHeatStack(openstackCloud, HEAT_STACK_NAME, HEAT_STACK_TEMPLATE, envParams, HEAT_STACK_ENVIRONMENT, openstackEnv)
                        }
                    }

                    // get SALT_MASTER_URL
                    saltMasterHost = openstack.getHeatStackOutputParam(openstackCloud, HEAT_STACK_NAME, 'salt_master_ip', openstackEnv)
                    currentBuild.description = "${HEAT_STACK_NAME}: ${saltMasterHost}"
                    SALT_MASTER_URL = "http://${saltMasterHost}:8088"
                }
            }

            //
            // Connect to Salt master
            //

            def saltMaster
            stage('Connect to Salt API') {
                saltMaster = salt.connection(SALT_MASTER_URL, SALT_MASTER_CREDENTIALS)
            }

            //
            // Install
            //

            if (INSTALL.toLowerCase().contains('core')) {
                stage('Install core infrastructure') {
                    // salt.master, reclass
                    // refresh_pillar
                    // sync_all
                    // linux,openssh,salt.minion.ntp

                    //orchestrate.installFoundationInfra(saltMaster)
                    salt.enforceState(saltMaster, 'I@salt:master', ['salt.master', 'reclass'], true)
                    salt.runSaltProcessStep(saltMaster, 'I@linux:system', 'saltutil.refresh_pillar', [], null, true)
                    salt.runSaltProcessStep(saltMaster, 'I@linux:system', 'saltutil.sync_all', [], null, true)
                    salt.enforceState(saltMaster, 'I@linux:system', ['linux', 'openssh', 'salt.minion', 'ntp'], true)


                    if (INSTALL.toLowerCase().contains('kvm')) {
                        //orchestrate.installInfraKvm(saltMaster)
                        salt.runSaltProcessStep(saltMaster, 'I@linux:system', 'saltutil.refresh_pillar', [], null, true)
                        salt.runSaltProcessStep(saltMaster, 'I@linux:system', 'saltutil.sync_all', [], null, true)

                        salt.enforceState(saltMaster, 'I@salt:control', ['salt.minion', 'linux.system', 'linux.network', 'ntp'], true)
                        salt.enforceState(saltMaster, 'I@salt:control', 'libvirt', true)
                        salt.enforceState(saltMaster, 'I@salt:control', 'salt.control', true)

                        sleep(300)

                        salt.runSaltProcessStep(saltMaster, '* and not kvm*', 'saltutil.refresh_pillar', [], null, true)
                        salt.runSaltProcessStep(saltMaster, '* and not kvm*', 'saltutil.sync_all', [], null, true)

                        // workaround - install apt-transport-https
                        salt.runSaltProcessStep(saltMaster, '* and not kvm*', 'cmd.run', ['apt-get update -y && apt-get install -y apt-transport-https'], null, true)
                        salt.runSaltProcessStep(saltMaster, '* and not kvm*', 'pkg.install', ['apt-transport-https', 'refresh=True'], null, true)
                        salt.enforceState(saltMaster, 'I@linux:system', ['linux', 'openssh', 'salt.minion', 'ntp'], true)
                    }

                    //orchestrate.validateFoundationInfra(saltMaster)
                    salt.runSaltProcessStep(saltMaster, 'I@salt:master', 'cmd.run', ['salt-key'], null, true)
                    salt.runSaltProcessStep(saltMaster, 'I@salt:minion', 'test.version', [], null, true)
                    salt.runSaltProcessStep(saltMaster, 'I@salt:master', 'cmd.run', ['reclass-salt --top'], null, true)
                    salt.runSaltProcessStep(saltMaster, 'I@reclass:storage', 'reclass.inventory', [], null, true)
                    salt.runSaltProcessStep(saltMaster, 'I@salt:minion', 'state.show_top', [], null, true)
                }
            }

            // install k8s
            if (INSTALL.toLowerCase().contains('k8s')) {
                stage('Install Kubernetes infra') {
                    //orchestrate.installOpenstackMcpInfra(saltMaster)

                    // Comment nameserver
                    salt.runSaltProcessStep(saltMaster, 'I@kubernetes:master', 'cmd.run', ["sed -i 's/nameserver 10.254.0.10/#nameserver 10.254.0.10/g' /etc/resolv.conf"])

                    // Install glusterfs
                    salt.runSaltProcessStep(saltMaster, 'I@glusterfs:server', 'state.sls', ['glusterfs.server.service'])

                    // Install keepalived
                    salt.runSaltProcessStep(saltMaster, 'ctl01*', 'state.sls', ['keepalived'])
                    salt.runSaltProcessStep(saltMaster, 'I@keepalived:cluster', 'state.sls', ['keepalived'])

                    // Check the keepalived VIPs
                    salt.runSaltProcessStep(saltMaster, 'I@keepalived:cluster', 'cmd.run', ['ip a | grep 172.16.10.2'])

                    // Setup glusterfs
                    salt.runSaltProcessStep(saltMaster, 'ctl01*', 'state.sls', ['glusterfs.server.setup'])
                    salt.runSaltProcessStep(saltMaster, 'ctl02*', 'state.sls', ['glusterfs.server.setup'])
                    salt.runSaltProcessStep(saltMaster, 'ctl03*', 'state.sls', ['glusterfs.server.setup'])
                    salt.runSaltProcessStep(saltMaster, 'I@glusterfs:server', 'cmd.run', ['gluster peer status'])
                    salt.runSaltProcessStep(saltMaster, 'I@glusterfs:server', 'cmd.run', ['gluster volume status'])

                    // Install haproxy
                    salt.runSaltProcessStep(saltMaster, 'I@haproxy:proxy', 'state.sls', ['haproxy'])
                    salt.runSaltProcessStep(saltMaster, 'I@haproxy:proxy', 'service.status', ['haproxy'])

                    // Install docker
                    salt.runSaltProcessStep(saltMaster, 'I@docker:host', 'state.sls', ['docker.host'])
                    salt.runSaltProcessStep(saltMaster, 'I@docker:host', 'cmd.run', ['docker ps'])

                    // Install bird
                    salt.runSaltProcessStep(saltMaster, 'I@bird:server', 'state.sls', ['bird'])

                    // Install etcd
                    salt.runSaltProcessStep(saltMaster, 'I@etcd:server', 'state.sls', ['etcd.server.service'])
                    salt.runSaltProcessStep(saltMaster, 'I@etcd:server', 'cmd.run', ['etcdctl cluster-health'])

                }

                stage('Install Kubernetes control') {
                    //orchestrate.installOpenstackMcpControl(saltMaster)

                    // Install Kubernetes pool and Calico
                    salt.runSaltProcessStep(saltMaster, 'I@kubernetes:pool', 'state.sls', ['kubernetes.pool'])
                    salt.runSaltProcessStep(saltMaster, 'I@kubernetes:pool', 'cmd.run', ['calicoctl node status'])

                    // Setup etcd server
                    salt.runSaltProcessStep(saltMaster, 'I@kubernetes:master', 'state.sls', ['etcd.server.setup'])

                    // Run k8s without master.setup
                    salt.runSaltProcessStep(saltMaster, 'I@kubernetes:master', 'state.sls', ['kubernetes', 'exclude=kubernetes.master.setup'])

                    // Run k8s master setup
                    salt.runSaltProcessStep(saltMaster, 'ctl01*', 'state.sls', ['kubernetes.master.setup'])

                    // Revert comment nameserver
                    salt.runSaltProcessStep(saltMaster, 'I@kubernetes:master', 'cmd.run', ["sed -i 's/nameserver 10.254.0.10/#nameserver 10.254.0.10/g' /etc/resolv.conf"])

                    // Set route
                    salt.runSaltProcessStep(saltMaster, 'I@kubernetes:pool', 'cmd.run', ['ip r a 10.254.0.0/16 dev ens4'])

                    // Restart kubelet
                    salt.runSaltProcessStep(saltMaster, 'I@kubernetes:pool', 'service.restart', ['kubelet'])
                }

            }

            // install openstack
            if (INSTALL.toLowerCase().contains('openstack')) {
                // install Infra and control, tests, ...

                stage('Install OpenStack infra') {
                    //orchestrate.installOpenstackMkInfra(saltMaster, physical)

                    // Install keepaliveds
                    //runSaltProcessStep(master, 'I@keepalived:cluster', 'state.sls', ['keepalived'], 1)
                    salt.enforceState(saltMaster, 'ctl01*', 'keepalived', true)
                    salt.enforceState(saltMaster, 'I@keepalived:cluster', 'keepalived', true)

                    // Check the keepalived VIPs
                    salt.runSaltProcessStep(saltMaster, 'I@keepalived:cluster', 'cmd.run', ['ip a | grep 172.16.10.2'])

                    // Install glusterfs
                    salt.enforceState(saltMaster, 'I@glusterfs:server', 'glusterfs.server.service', true)

                    //runSaltProcessStep(saltMaster, 'I@glusterfs:server', 'state.sls', ['glusterfs.server.setup'], 1)
                    if (INSTALL.toLowerCase().contains('kvm')) {
                        salt.enforceState(saltMaster, 'kvm01*', 'glusterfs.server.setup', true)
                        salt.enforceState(saltMaster, 'kvm02*', 'glusterfs.server.setup', true)
                        salt.enforceState(saltMaster, 'kvm03*', 'glusterfs.server.setup', true)
                    } else {
                        salt.enforceState(saltMaster, 'ctl01*', 'glusterfs.server.setup', true)
                        salt.enforceState(saltMaster, 'ctl02*', 'glusterfs.server.setup', true)
                        salt.enforceState(saltMaster, 'ctl03*', 'glusterfs.server.setup', true)
                    }

                    salt.runSaltProcessStep(saltMaster, 'I@glusterfs:server', 'cmd.run', ['gluster peer status'], null, true)
                    salt.runSaltProcessStep(saltMaster, 'I@glusterfs:server', 'cmd.run', ['gluster volume status'], null, true)

                    // Install rabbitmq
                    salt.enforceState(saltMaster, 'I@rabbitmq:server', 'rabbitmq', true)

                    // Check the rabbitmq status
                    salt.runSaltProcessStep(saltMaster, 'I@rabbitmq:server', 'cmd.run', ['rabbitmqctl cluster_status'])

                    // Install galera
                    salt.enforceState(saltMaster, 'I@galera:master', 'galera', true)
                    salt.enforceState(saltMaster, 'I@galera:slave', 'galera', true)

                    // Check galera status
                    salt.runSaltProcessStep(saltMaster, 'I@galera:master', 'mysql.status')
                    salt.runSaltProcessStep(saltMaster, 'I@galera:slave', 'mysql.status')

                    // Install haproxy
                    salt.enforceState(saltMaster, 'I@haproxy:proxy', 'haproxy', true)
                    salt.runSaltProcessStep(saltMaster, 'I@haproxy:proxy', 'service.status', ['haproxy'])
                    salt.runSaltProcessStep(saltMaster, 'I@haproxy:proxy', 'service.restart', ['rsyslog'])

                    // Install memcached
                    salt.enforceState(saltMaster, 'I@memcached:server', 'memcached', true)

                }

                stage('Install OpenStack control') {
                    //orchestrate.installOpenstackMkControl(saltMaster)

                    // setup keystone service
                    //runSaltProcessStep(saltMaster, 'I@keystone:server', 'state.sls', ['keystone.server'], 1)
                    salt.enforceState(saltMaster, 'ctl01*', 'keystone.server', true)
                    salt.enforceState(saltMaster, 'I@keystone:server', 'keystone.server', true)
                    // populate keystone services/tenants/roles/users

                    // keystone:client must be called locally
                    salt.runSaltProcessStep(saltMaster, 'I@keystone:client', 'cmd.run', ['salt-call state.sls keystone.client'], null, true)
                    salt.runSaltProcessStep(saltMaster, 'I@keystone:server', 'cmd.run', ['. /root/keystonerc; keystone service-list'], null, true)

                    // Install glance and ensure glusterfs clusters
                    //runSaltProcessStep(saltMaster, 'I@glance:server', 'state.sls', ['glance.server'], 1)
                    salt.enforceState(saltMaster, 'ctl01*', 'glance.server', true)
                    salt.enforceState(saltMaster, 'I@glance:server', 'glance.server', true)
                    salt.enforceState(saltMaster, 'I@glance:server', 'glusterfs.client', true)

                    // Update fernet tokens before doing request on keystone server
                    salt.enforceState(saltMaster, 'I@keystone:server', 'keystone.server', true)

                    // Check glance service
                    salt.runSaltProcessStep(saltMaster, 'I@keystone:server', 'cmd.run', ['. /root/keystonerc; glance image-list'], null, true)

                    // Install and check nova service
                    //runSaltProcessStep(saltMaster, 'I@nova:controller', 'state.sls', ['nova'], 1)
                    salt.enforceState(saltMaster, 'ctl01*', 'nova', true)
                    salt.enforceState(saltMaster, 'I@nova:controller', 'nova', true)
                    salt.runSaltProcessStep(saltMaster, 'I@keystone:server', 'cmd.run', ['. /root/keystonerc; nova service-list'], null, true)

                    // Install and check cinder service
                    //runSaltProcessStep(saltMaster, 'I@cinder:controller', 'state.sls', ['cinder'], 1)
                    salt.enforceState(saltMaster, 'ctl01*', 'cinder', true)
                    salt.enforceState(saltMaster, 'I@cinder:controller', 'cinder', true)
                    salt.runSaltProcessStep(saltMaster, 'I@keystone:server', 'cmd.run', ['. /root/keystonerc; cinder list'], null, true)

                    // Install neutron service
                    //runSaltProcessStep(saltMaster, 'I@neutron:server', 'state.sls', ['neutron'], 1)
                    if (INSTALL.toLowerCase().contains('kvm')) {
                        salt.enforceState(saltMaster, 'ntw01*', 'neutron', true)
                    } else {
                        salt.enforceState(saltMaster, 'ctl01*', 'neutron', true)
                    }

                    salt.enforceState(saltMaster, 'I@neutron:server', 'neutron', true)
                    salt.runSaltProcessStep(saltMaster, 'I@keystone:server', 'cmd.run', ['. /root/keystonerc; neutron agent-list'], null, true)

                    // Install heat service
                    //runSaltProcessStep(saltMaster, 'I@heat:server', 'state.sls', ['heat'], 1)
                    salt.enforceState(saltMaster, 'ctl01*', 'heat', true)
                    salt.enforceState(saltMaster, 'I@heat:server', 'heat', true)
                    salt.runSaltProcessStep(saltMaster, 'I@keystone:server', 'cmd.run', ['. /root/keystonerc; heat resource-type-list'], null, true)

                    // Install horizon dashboard
                    salt.enforceState(saltMaster, 'I@horizon:server', 'horizon', true)
                    salt.enforceState(saltMaster, 'I@nginx:server', 'nginx', true)

                }

                stage('Install OpenStack network') {
                    //orchestrate.installOpenstackMkNetwork(saltMaster, physical)

                    // Install opencontrail database services
                    //runSaltProcessStep(saltMaster, 'I@opencontrail:database', 'state.sls', ['opencontrail.database'], 1)
                    salt.enforceState(saltMaster, 'ntw01*', 'opencontrail.database', true)
                    salt.enforceState(saltMaster, 'I@opencontrail:database', 'opencontrail.database', true)

                    // Install opencontrail control services
                    //runSaltProcessStep(saltMaster, 'I@opencontrail:control', 'state.sls', ['opencontrail'], 1)
                    salt.enforceState(saltMaster, 'ntw01*', 'opencontrail', true)
                    salt.enforceState(saltMaster, 'I@opencontrail:control', 'opencontrail', true)
                    salt.enforceState(saltMaster, 'I@opencontrail:collector', 'opencontrail', true)

                    // Provision opencontrail control services
                    if (INSTALL.toLowerCase().contains('kvm')) {
                        salt.runSaltProcessStep(saltMaster, 'I@opencontrail:control:id:1', 'cmd.run', ['/usr/share/contrail-utils/provision_control.py --api_server_ip 10.167.4.20 --api_server_port 8082 --host_name ntw01 --host_ip 10.167.4.21 --router_asn 64512 --admin_password password --admin_user admin --admin_tenant_name admin --oper add'], null, true)
                        salt.runSaltProcessStep(saltMaster, 'I@opencontrail:control:id:1', 'cmd.run', ['/usr/share/contrail-utils/provision_control.py --api_server_ip 10.167.4.20 --api_server_port 8082 --host_name ntw02 --host_ip 10.167.4.22 --router_asn 64512 --admin_password password --admin_user admin --admin_tenant_name admin --oper add'], null, true)
                        salt.runSaltProcessStep(saltMaster, 'I@opencontrail:control:id:1', 'cmd.run', ['/usr/share/contrail-utils/provision_control.py --api_server_ip 10.167.4.20 --api_server_port 8082 --host_name ntw03 --host_ip 10.167.4.23 --router_asn 64512 --admin_password password --admin_user admin --admin_tenant_name admin --oper add'], null, true)
                    } else {
                        salt.runSaltProcessStep(saltMaster, 'I@opencontrail:control:id:1', 'cmd.run', ['/usr/share/contrail-utils/provision_control.py --api_server_ip 172.16.10.254 --api_server_port 8082 --host_name ctl01 --host_ip 172.16.10.101 --router_asn 64512 --admin_password workshop --admin_user admin --admin_tenant_name admin --oper add'], null, true)
                        salt.runSaltProcessStep(saltMaster, 'I@opencontrail:control:id:1', 'cmd.run', ['/usr/share/contrail-utils/provision_control.py --api_server_ip 172.16.10.254 --api_server_port 8082 --host_name ctl02 --host_ip 172.16.10.102 --router_asn 64512 --admin_password workshop --admin_user admin --admin_tenant_name admin --oper add'], null, true)
                        salt.runSaltProcessStep(saltMaster, 'I@opencontrail:control:id:1', 'cmd.run', ['/usr/share/contrail-utils/provision_control.py --api_server_ip 172.16.10.254 --api_server_port 8082 --host_name ctl03 --host_ip 172.16.10.103 --router_asn 64512 --admin_password workshop --admin_user admin --admin_tenant_name admin --oper add'], null, true)
                    }

                    // Test opencontrail
                    salt.runSaltProcessStep(saltMaster, 'I@opencontrail:control', 'cmd.run', ['contrail-status'], null, true)
                    salt.runSaltProcessStep(saltMaster, 'I@keystone:server', 'cmd.run', ['. /root/keystonerc; neutron net-list'], null, true)
                    salt.runSaltProcessStep(saltMaster, 'I@keystone:server', 'cmd.run', ['. /root/keystonerc; nova net-list'], null, true)
                }

                stage('Install OpenStack compute') {
                    //orchestrate.installOpenstackMkCompute(saltMaster, physical)
                    // Configure compute nodes
                    retry(2) {
                        salt.runSaltProcessStep(saltMaster, 'I@nova:compute', 'state.apply', [], null, true)
                    }

                    // Provision opencontrail virtual routers
                    if (INSTALL.toLowerCase().contains('kvm')) {
                        salt.runSaltProcessStep(saltMaster, 'I@opencontrail:control:id:1', 'cmd.run', ['/usr/share/contrail-utils/provision_vrouter.py --host_name cmp001 --host_ip 10.167.4.101 --api_server_ip 10.167.4.20 --oper add --admin_user admin --admin_password password --admin_tenant_name admin'], null, true)
                    } else {
                        salt.runSaltProcessStep(saltMaster, 'I@opencontrail:control:id:1', 'cmd.run', ['/usr/share/contrail-utils/provision_vrouter.py --host_name cmp01 --host_ip 172.16.10.105 --api_server_ip 172.16.10.254 --oper add --admin_user admin --admin_password workshop --admin_tenant_name admin'], null, true)
                    }

                    salt.runSaltProcessStep(saltMaster, 'I@nova:compute', 'system.reboot', [], null, true)
                    sleep(10)
                }
            }

            //
            // Test
            //

            if (TEST.toLowerCase().contains('k8s')) {
                stage('Run k8s bootstrap tests') {
                    orchestrate.runConformanceTests(saltMaster, K8S_API_SERVER, 'tomkukral/k8s-scripts')
                }

                stage('Run k8s conformance e2e tests') {
                    orchestrate.runConformanceTests(saltMaster, K8S_API_SERVER, K8S_CONFORMANCE_IMAGE)
                }
            }


        } catch (Throwable e) {
            currentBuild.result = 'FAILURE'
            throw e
        } finally {

            //
            // Clean
            //

            if (HEAT_STACK_DELETE.toBoolean() == true && STACK_TYPE == 'heat') {
                stage('Trigger cleanup job') {
                    build job: 'deploy-heat-cleanup', parameters: [[$class: 'StringParameterValue', name: 'HEAT_STACK_NAME', value: HEAT_STACK_NAME]]
                }
            }
        }
    }
}
