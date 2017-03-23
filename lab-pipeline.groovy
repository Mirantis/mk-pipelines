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
 *   TEMPEST_IMAGE_LINK         Tempest image link
 *
 */

git = new com.mirantis.mk.Git()
openstack = new com.mirantis.mk.Openstack()
salt = new com.mirantis.mk.Salt()
common = new com.mirantis.mk.Common()


timestamps {
    node {
        try {
            withEnv(["ASK_ON_ERROR=${ASK_ON_ERROR}"]){
            //
            // Prepare machines
            //
            stage ('Create infrastructure') {

                // ensure STACK_TYPE is set
                if (!binding.variables.containsKey('STACK_TYPE')) {
                    STACK_TYPE = ''
                }

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
                            if (env.BUILD_USER_ID) {
                                HEAT_STACK_NAME = "${env.BUILD_USER_ID}-${JOB_NAME}-${BUILD_NUMBER}"
                            } else {
                                HEAT_STACK_NAME = "jenkins-${JOB_NAME}-${BUILD_NUMBER}"
                            }
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

                    if (common.checkContains('INSTALL', 'kvm')) {
                        saltPort = 6969
                    } else {
                        saltPort = 6969
                    }

                    SALT_MASTER_URL = "http://${saltMasterHost}:${saltPort}"
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

            if (common.checkContains('INSTALL', 'core')) {
                stage('Install core infrastructure') {
                    // salt.master, reclass
                    // refresh_pillar
                    // sync_all
                    // linux,openssh,salt.minion.ntp

                    //orchestrate.installFoundationInfra(saltMaster)
                    salt.enforceState(saltMaster, 'I@salt:master', ['salt.master', 'reclass'], true)
                    salt.enforceState(saltMaster, '*', ['salt.minion'], true)
                    salt.runSaltProcessStep(saltMaster, 'I@linux:system', 'saltutil.refresh_pillar', [], null, true)
                    salt.runSaltProcessStep(saltMaster, 'I@linux:system', 'saltutil.sync_all', [], null, true)
                    salt.enforceState(saltMaster, 'I@linux:system', ['linux', 'openssh', 'salt.minion', 'ntp'], true)


                    if (common.checkContains('INSTALL', 'kvm')) {
                        //orchestrate.installInfraKvm(saltMaster)
                        //salt.runSaltProcessStep(saltMaster, 'I@linux:system', 'saltutil.refresh_pillar', [], null, true)
                        //salt.runSaltProcessStep(saltMaster, 'I@linux:system', 'saltutil.sync_all', [], null, true)

                        //salt.enforceState(saltMaster, 'I@salt:control', ['salt.minion', 'linux.system', 'linux.network', 'ntp'], true)
                        salt.enforceState(saltMaster, 'I@salt:control', 'libvirt', true)
                        salt.enforceState(saltMaster, 'I@salt:control', 'salt.control', true)

                        sleep(600)

                        salt.runSaltProcessStep(saltMaster, '* and not kvm*', 'saltutil.refresh_pillar', [], null, true)
                        salt.runSaltProcessStep(saltMaster, '* and not kvm*', 'saltutil.sync_all', [], null, true)

                        // workaround - install apt-transport-https
                        //salt.runSaltProcessStep(saltMaster, '* and not kvm*', 'cmd.run', ['apt-get update -y && apt-get install -y apt-transport-https'], null, true)
                        //salt.runSaltProcessStep(saltMaster, '* and not kvm*', 'pkg.install', ['apt-transport-https', 'refresh=True'], null, true)
                        salt.enforceState(saltMaster, 'I@linux:system', ['linux', 'salt.minion'], true, false)
                        salt.enforceState(saltMaster, 'I@linux:system', ['openssh', 'salt.minion', 'ntp'], true, false)
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
            if (common.checkContains('INSTALL', 'k8s')) {
                stage('Install Kubernetes infra') {
                    //orchestrate.installOpenstackMcpInfra(saltMaster)

                    // Comment nameserver
                    salt.runSaltProcessStep(saltMaster, 'I@kubernetes:master', 'cmd.run', ["sed -i 's/nameserver 10.254.0.10/#nameserver 10.254.0.10/g' /etc/resolv.conf"])

                    // Install glusterfs
                    salt.runSaltProcessStep(saltMaster, 'I@glusterfs:server', 'state.sls', ['glusterfs.server.service'])

                    // Install keepalived
                    salt.runSaltProcessStep(saltMaster, 'I@keepalived:cluster and *01*', 'state.sls', ['keepalived'])
                    salt.runSaltProcessStep(saltMaster, 'I@keepalived:cluster', 'state.sls', ['keepalived'])

                    // Check the keepalived VIPs
                    salt.runSaltProcessStep(saltMaster, 'I@keepalived:cluster', 'cmd.run', ['ip a | grep 172.16.10.2'])

                    // Setup glusterfs
                    salt.runSaltProcessStep(saltMaster, 'I@glusterfs:server and *01*', 'state.sls', ['glusterfs.server.setup'])
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
                    salt.runSaltProcessStep(saltMaster, 'I@kubernetes:master and *01*', 'state.sls', ['kubernetes.master.setup'])

                    // Revert comment nameserver
                    salt.runSaltProcessStep(saltMaster, 'I@kubernetes:master', 'cmd.run', ["sed -i 's/nameserver 10.254.0.10/#nameserver 10.254.0.10/g' /etc/resolv.conf"])

                    // Set route
                    salt.runSaltProcessStep(saltMaster, 'I@kubernetes:pool', 'cmd.run', ['ip r a 10.254.0.0/16 dev ens4'])

                    // Restart kubelet
                    salt.runSaltProcessStep(saltMaster, 'I@kubernetes:pool', 'service.restart', ['kubelet'])
                }

            }

            // install openstack
            if (common.checkContains('INSTALL', 'openstack')) {
                // install Infra and control, tests, ...

                stage('Install OpenStack infra') {
                    //orchestrate.installOpenstackMkInfra(saltMaster, physical)

                    // Install glusterfs
                    salt.enforceState(saltMaster, 'I@glusterfs:server', 'glusterfs.server.service', true)

                    // Install keepaliveds
                    //runSaltProcessStep(master, 'I@keepalived:cluster', 'state.sls', ['keepalived'], 1)
                    salt.enforceState(saltMaster, 'I@keepalived:cluster and *01*', 'keepalived', true)
                    salt.enforceState(saltMaster, 'I@keepalived:cluster', 'keepalived', true)

                    // Check the keepalived VIPs
                    salt.runSaltProcessStep(saltMaster, 'I@keepalived:cluster', 'cmd.run', ['ip a | grep 172.16.10.2'])

                    salt.enforceState(saltMaster, 'I@glusterfs:server and *01*', 'glusterfs.server.setup', true)

                    salt.runSaltProcessStep(saltMaster, 'I@glusterfs:server', 'cmd.run', ['gluster peer status'], null, true)
                    salt.runSaltProcessStep(saltMaster, 'I@glusterfs:server', 'cmd.run', ['gluster volume status'], null, true)

                    // Install rabbitmq
                    salt.enforceState(saltMaster, 'I@rabbitmq:server', 'rabbitmq', true, false)

                    // Check the rabbitmq status
                    salt.runSaltProcessStep(saltMaster, 'I@rabbitmq:server', 'cmd.run', ['rabbitmqctl cluster_status'])

                    // Install galera
                    retry(2) {
                        salt.enforceState(saltMaster, 'I@galera:master', 'galera', true)
                    }
                    salt.enforceState(saltMaster, 'I@galera:slave', 'galera', true)

                    // Check galera status
                    salt.runSaltProcessStep(saltMaster, 'I@galera:master', 'mysql.status')
                    salt.runSaltProcessStep(saltMaster, 'I@galera:slave', 'mysql.status')

                    // // Setup mysql client
                    // salt.enforceState(saltMaster, 'I@mysql:client', 'mysql.client', true)

                    // Install haproxy
                    salt.enforceState(saltMaster, 'I@haproxy:proxy', 'haproxy', true)
                    salt.runSaltProcessStep(saltMaster, 'I@haproxy:proxy', 'service.status', ['haproxy'])
                    salt.runSaltProcessStep(saltMaster, 'I@haproxy:proxy', 'service.restart', ['rsyslog'])

                    // Install memcached
                    salt.enforceState(saltMaster, 'I@memcached:server', 'memcached', true)

                }

                stage('Install OpenStack control') {
                    //orchestrate.installOpenstackMkControl(saltMaster)

                    // Install horizon dashboard
                    salt.enforceState(saltMaster, 'I@horizon:server', 'horizon', true)
                    salt.enforceState(saltMaster, 'I@nginx:server', 'nginx', true)

                    // setup keystone service
                    //runSaltProcessStep(saltMaster, 'I@keystone:server', 'state.sls', ['keystone.server'], 1)
                    salt.enforceState(saltMaster, 'I@keystone:server and *01*', 'keystone.server', true)
                    salt.enforceState(saltMaster, 'I@keystone:server', 'keystone.server', true)
                    // populate keystone services/tenants/roles/users

                    // keystone:client must be called locally
                    salt.runSaltProcessStep(saltMaster, 'I@keystone:client', 'cmd.run', ['salt-call state.sls keystone.client'], null, true)
                    salt.runSaltProcessStep(saltMaster, 'I@keystone:server', 'cmd.run', ['. /root/keystonerc; keystone service-list'], null, true)

                    // Install glance and ensure glusterfs clusters
                    //runSaltProcessStep(saltMaster, 'I@glance:server', 'state.sls', ['glance.server'], 1)
                    salt.enforceState(saltMaster, 'I@glance:server and *01*', 'glance.server', true)
                    salt.enforceState(saltMaster, 'I@glance:server', 'glance.server', true)
                    salt.enforceState(saltMaster, 'I@glance:server', 'glusterfs.client', true)

                    // Update fernet tokens before doing request on keystone server
                    salt.enforceState(saltMaster, 'I@keystone:server', 'keystone.server', true)

                    // Check glance service
                    salt.runSaltProcessStep(saltMaster, 'I@keystone:server', 'cmd.run', ['. /root/keystonerc; glance image-list'], null, true)

                    // Install and check nova service
                    //runSaltProcessStep(saltMaster, 'I@nova:controller', 'state.sls', ['nova'], 1)
                    salt.enforceState(saltMaster, 'I@nova:controller and *01*', 'nova.controller', true)
                    salt.enforceState(saltMaster, 'I@nova:controller', 'nova.controller', true)
                    salt.runSaltProcessStep(saltMaster, 'I@keystone:server', 'cmd.run', ['. /root/keystonerc; nova service-list'], null, true)

                    // Install and check cinder service
                    //runSaltProcessStep(saltMaster, 'I@cinder:controller', 'state.sls', ['cinder'], 1)
                    salt.enforceState(saltMaster, 'I@cinder:controller and *01*', 'cinder', true)
                    salt.enforceState(saltMaster, 'I@cinder:controller', 'cinder', true)
                    salt.runSaltProcessStep(saltMaster, 'I@keystone:server', 'cmd.run', ['. /root/keystonerc; cinder list'], null, true)

                    // Install neutron service
                    //runSaltProcessStep(saltMaster, 'I@neutron:server', 'state.sls', ['neutron'], 1)

                    salt.enforceState(saltMaster, 'I@neutron:server and *01*', 'neutron.server', true)
                    salt.enforceState(saltMaster, 'I@neutron:server', 'neutron.server', true)
                    salt.runSaltProcessStep(saltMaster, 'I@keystone:server', 'cmd.run', ['. /root/keystonerc; neutron agent-list'], null, true)

                    // Install heat service
                    //runSaltProcessStep(saltMaster, 'I@heat:server', 'state.sls', ['heat'], 1)
                    salt.enforceState(saltMaster, 'I@heat:server and *01*', 'heat', true)
                    salt.enforceState(saltMaster, 'I@heat:server', 'heat', true)
                    salt.runSaltProcessStep(saltMaster, 'I@keystone:server', 'cmd.run', ['. /root/keystonerc; heat resource-type-list'], null, true)

                    // Restart nova api
                    salt.runSaltProcessStep(saltMaster, 'I@nova:controller', 'service.restart', ['nova-api'])

                }

                stage('Install OpenStack network') {
                    //orchestrate.installOpenstackMkNetwork(saltMaster, physical)

                    if (common.checkContains('INSTALL', 'contrail')) {
                        // Install opencontrail database services
                        //runSaltProcessStep(saltMaster, 'I@opencontrail:database', 'state.sls', ['opencontrail.database'], 1)
                        try {
                            salt.enforceState(saltMaster, 'I@opencontrail:database and *01*', 'opencontrail.database', true)
                        } catch (Exception e) {
                            common.warningMsg('Exception in state opencontrail.database on I@opencontrail:database and *01*')
                        }

                        try {
                            salt.enforceState(saltMaster, 'I@opencontrail:database', 'opencontrail.database', true)
                        } catch (Exception e) {
                            common.warningMsg('Exception in state opencontrail.database on I@opencontrail:database')
                        }

                        // Install opencontrail control services
                        //runSaltProcessStep(saltMaster, 'I@opencontrail:control', 'state.sls', ['opencontrail'], 1)
                        salt.runSaltProcessStep(saltMaster, 'I@opencontrail:control and *01*', 'state.sls', ['opencontrail', 'exclude=opencontrail.client'])
                        salt.runSaltProcessStep(saltMaster, 'I@opencontrail:control', 'state.sls', ['opencontrail', 'exclude=opencontrail.client'])
                        salt.runSaltProcessStep(saltMaster, 'I@opencontrail:collector', 'state.sls', ['opencontrail', 'exclude=opencontrail.client'])

                        // Test opencontrail
                        salt.runSaltProcessStep(saltMaster, 'I@opencontrail:control', 'cmd.run', ['contrail-status'], null, true)
                    } else if (common.checkContains('INSTALL', 'ovs')) {
                        // Apply gateway
                        salt.runSaltProcessStep(saltMaster, 'I@neutron:gateway', 'state.apply', [], null, true)
                    }

                    // Pring information
                    salt.runSaltProcessStep(saltMaster, 'I@keystone:server', 'cmd.run', ['. /root/keystonerc; neutron net-list'], null, true)
                    salt.runSaltProcessStep(saltMaster, 'I@keystone:server', 'cmd.run', ['. /root/keystonerc; nova net-list'], null, true)
                }

                stage('Install OpenStack compute') {
                    //orchestrate.installOpenstackMkCompute(saltMaster, physical)
                    // Configure compute nodes
                    retry(2) {
                        salt.runSaltProcessStep(saltMaster, 'I@nova:compute', 'state.apply', [], null, true)
                    }

                    if (common.checkContains('INSTALL', 'contrail')) {
                        // Provision opencontrail control services
                        salt.enforceState(saltMaster, 'I@opencontrail:database:id:1', 'opencontrail.client', true)
                        // Provision opencontrail virtual routers
                        salt.enforceState(saltMaster, 'I@opencontrail:compute', 'opencontrail.client', true)

                        salt.runSaltProcessStep(saltMaster, 'I@nova:compute', 'cmd.run', ['exec 0>&-; exec 1>&-; exec 2>&-; nohup bash -c "ip link | grep vhost && echo no_reboot || sleep 5 && reboot & "'], null, true)
                        sleep(300)
                    }
                }

            }


            if (common.checkContains('INSTALL', 'stacklight')) {
                stage('Install StackLight') {
                    // infra install
                    // Install the StackLight backends
                    salt.enforceState(saltMaster, '*01* and  I@elasticsearch:server', 'elasticsearch.server', true)
                    salt.enforceState(saltMaster, 'I@elasticsearch:server', 'elasticsearch.server', true)

                    salt.enforceState(saltMaster, '*01* and I@influxdb:server', 'influxdb', true)
                    salt.enforceState(saltMaster, 'I@influxdb:server', 'influxdb', true)

                    salt.enforceState(saltMaster, '*01* and I@kibana:server', 'kibana.server', true)
                    salt.enforceState(saltMaster, 'I@kibana:server', 'kibana.server', true)

                    salt.enforceState(saltMaster, '*01* and I@grafana:server','grafana.server', true)
                    salt.enforceState(saltMaster, 'I@grafana:server','grafana.server', true)

                    salt.enforceState(saltMaster, 'I@nagios:server', 'nagios.server', true)
                    salt.enforceState(saltMaster, 'I@elasticsearch:client', 'elasticsearch.client.service', true)
                    salt.enforceState(saltMaster, 'I@kibana:client', 'kibana.client.service', true)
                    // nw salt.enforceState('I@kibana:client or I@elasticsearch:client' --async service.restart salt-minion

                    sleep(10)

                    salt.runSaltProcessStep(saltMaster, 'I@elasticsearch.client', 'cmd.run', ['salt-call state.sls elasticsearch.client'], null, true)
                    // salt.enforceState(saltMaster, 'I@elasticsearch:client', 'elasticsearch.client', true)
                    salt.runSaltProcessStep(saltMaster, 'I@kibana.client', 'cmd.run', ['salt-call state.sls kibana.client'], null, true)
                    // salt.enforceState(saltMaster, 'I@kibana:client', 'kibana.client', true)

                    // install monitor
                    // Restart salt-minion to make sure that it uses the latest Jinja library
                    // no way: salt '*' --async service.restart salt-minion; sleep 15

                    // Start by flusing Salt Mine to make sure it is clean
                    // Also clean-up the grains files to make sure that we start from a clean state
                    // nw salt "*" mine.flush
                    // nw salt "*" file.remove /etc/salt/grains.d/collectd
                    // nw salt "*" file.remove /etc/salt/grains.d/grafana
                    // nw salt "*" file.remove /etc/salt/grains.d/heka
                    // nw salt "*" file.remove /etc/salt/grains.d/sensu
                    // nw salt "*" file.remove /etc/salt/grains

                    // Install collectd, heka and sensu services on the nodes, this will also
                    // generate the metadata that goes into the grains and eventually into Salt Mine
                    salt.enforceState(saltMaster, '*', 'collectd', true)
                    salt.enforceState(saltMaster, '*', 'salt.minion', true)
                    salt.enforceState(saltMaster, '*', 'heka', true)

                    // Gather the Grafana metadata as grains
                    salt.enforceState(saltMaster, 'I@grafana:collector', 'grafana.collector', true)

                    // Update Salt Mine
                    salt.enforceState(saltMaster, '*', 'salt.minion.grains', true)
                    salt.runSaltProcessStep(saltMaster, '*', 'saltutil.refresh_modules', [], null, true)
                    salt.runSaltProcessStep(saltMaster, '*', 'mine.update', [], null, true)

                    sleep(5)

                    // Update Heka
                    salt.enforceState(saltMaster, 'I@heka:aggregator:enabled:True or I@heka:remote_collector:enabled:True', 'heka', true)

                    // Update collectd
                    salt.enforceState(saltMaster, 'I@collectd:remote_client:enabled:True', 'collectd', true)

                    // Update Nagios
                    salt.enforceState(saltMaster, 'I@nagios:server', 'nagios', true)
                    // Stop the Nagios service because the package starts it by default and it will
                    // started later only on the node holding the VIP address
                    salt.runSaltProcessStep(saltMaster, 'I@nagios:server', 'service.stop', ['nagios3'], null, true)

                    // Update Sensu
                    // TODO for stacklight team, should be fixed in model
                    //salt.enforceState(saltMaster, 'I@sensu:server', 'sensu', true)

                    // Finalize the configuration of Grafana (add the dashboards...)
                    salt.enforceState(saltMaster, 'I@grafana:client', 'grafana.client.service', true)
                    // nw salt -C 'I@grafana:client' --async service.restart salt-minion; sleep 10

                    salt.runSaltProcessStep(saltMaster, 'I@grafana.client and *01*', 'cmd.run', ['salt-call state.sls grafana.client'], null, true)
                    // salt.enforceState(saltMaster, 'I@grafana:client and *01*', 'grafana.client', true)

                    // Get the StackLight monitoring VIP addres
                    //vip=$(salt-call pillar.data _param:stacklight_monitor_address --out key|grep _param: |awk '{print $2}')
                    //vip=${vip:=172.16.10.253}
                    def pillar = salt.getPillar(saltMaster, 'ctl01*', '_param:stacklight_monitor_address')
                    print(pillar)
                    def stacklight_vip = pillar['return'][0].values()[0]

                    if (stacklight_vip) {
                        // (re)Start manually the services that are bound to the monitoring VIP
                        common.infoMsg("restart services on node with IP: ${stacklight_vip}")
                        salt.runSaltProcessStep(saltMaster, "G@ipv4:${stacklight_vip}", 'service.restart', ['remote_collectd'], null, true)
                        salt.runSaltProcessStep(saltMaster, "G@ipv4:${stacklight_vip}", 'service.restart', ['remote_collector'], null, true)
                        salt.runSaltProcessStep(saltMaster, "G@ipv4:${stacklight_vip}", 'service.restart', ['aggregator'], null, true)
                        salt.runSaltProcessStep(saltMaster, "G@ipv4:${stacklight_vip}", 'service.restart', ['nagios3'], null, true)
                    } else {
                        throw new Exception("Missing stacklight_vip")
                    }
                }
            }

            //
            // Test
            //

            if (common.checkContains('TEST', 'k8s')) {
                stage('Run k8s bootstrap tests') {
                    orchestrate.runConformanceTests(saltMaster, K8S_API_SERVER, 'tomkukral/k8s-scripts')
                }

                stage('Run k8s conformance e2e tests') {
                    orchestrate.runConformanceTests(saltMaster, K8S_API_SERVER, K8S_CONFORMANCE_IMAGE)
                }
            }

            if (common.checkContains('TEST', 'openstack')) {
                stage('Run OpenStack tests') {
                    test.runTempestTests(saltMaster, TEMPEST_IMAGE_LINK)
                }

                stage('Copy Tempest results to config node') {
                    test.copyTempestResults(saltMaster)
                }
            }

            stage('Finalize') {
                try {
                    salt.runSaltProcessStep(saltMaster, '*', 'state.apply', [], null, true)
                } catch (Exception e) {
                    common.warningMsg('State apply failed but we should continue to run')
                }
            }
          }
        } catch (Throwable e) {
            currentBuild.result = 'FAILURE'
            throw e
        } finally {


            //
            // Clean
            //

            if (STACK_TYPE == 'heat') {
                // send notification
                common.sendNotification(currentBuild.result, HEAT_STACK_NAME, ["slack"])

                if (HEAT_STACK_DELETE.toBoolean() == true) {
                    common.errorMsg('Heat job cleanup triggered')
                    stage('Trigger cleanup job') {
                        build job: 'deploy-heat-cleanup', parameters: [[$class: 'StringParameterValue', name: 'HEAT_STACK_NAME', value: HEAT_STACK_NAME]]
                    }
                } else {
                    if (currentBuild.result == 'FAILURE') {
                        common.errorMsg("Deploy job FAILED and was not deleted. Please fix the problem and delete stack on you own.")

                        if (SALT_MASTER_URL) {
                            common.errorMsg("Salt master URL: ${SALT_MASTER_URL}")
                        }
                    }

                }
            }
        }
    }
}
