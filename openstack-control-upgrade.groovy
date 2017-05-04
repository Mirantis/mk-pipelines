/**
 * Update packages on given nodes
 *
 * Expected parameters:
 *   SALT_MASTER_CREDENTIALS    Credentials to the Salt API.
 *   SALT_MASTER_URL            Full Salt API address [http://10.10.10.1:8000].
 *
**/

def common = new com.mirantis.mk.Common()
def salt = new com.mirantis.mk.Salt()


def saltMaster

timestamps {
    node() {

        stage('Connect to Salt API') {
            saltMaster = salt.connection(SALT_MASTER_URL, SALT_MASTER_CREDENTIALS)
        }

        state('Prepare upgrade') {
            // #ignorovat no response a chyby - nene, chyby opravíme

            // read domain variable
            def _pillar = salt.getPillar(master, 'ctl01*', '_param:cluster_domain')
            def domain = _pillar['return'][0].values()[0]

            // # test sync db
            // salt 'kvm02*' cmd.run 'virsh destroy upg01.${domain}'
            salt.runSaltProcessStep(saltMaster, 'kvm02*', 'cmd.run', "virsh destroy upg01.${domain}", [], null, true)

            // salt 'kvm02*' cmd.run 'virsh undefine upg01.${domain}'
            salt.runSaltProcessStep(saltMaster, 'kvm02*', 'cmd.run', "virsh undefine upg01.${domain}", [], null, true)

            // salt-key -d upg01.${domain} -y
            salt.runSaltProcessStep(saltMaster, 'I@salt:master', 'cmd.run', "salt-key -d upg01.${domain} -y", [], null, true)
            // salt 'kvm02*' state.sls salt.control
            salt.enforceState(saltMaster, 'kvm02*', 'salt.control')

            sleep(60)

            // salt '*' saltutil.refresh_pillar
            salt.runSaltProcessStep(master, '*', 'saltutil.refresh_pillar', [], null, true)
            // salt '*' saltutil.sync_all
            salt.runSaltProcessStep(master, '*', 'saltutil.sync_all', [], null, true)

            // salt "upg*" state.sls linux,openssh,salt.minion,ntp,rsyslog
            salt.enforceState(master, 'upg*', ['linux', 'openssh', 'salt.minion', 'ntp', 'rsyslog'])
            // salt '*' state.apply salt.minion.grains
            salt.enforceState(master, '*', 'salt.minion.grains')
            // salt "upg*" state.sls linux,openssh,salt.minion,ntp,rsyslog
            salt.enforceState(master, 'upg*', ['linux', 'openssh', 'salt.minion', 'ntp', 'rsyslog'])

            // read backupninja variable
            def _pillar = salt.getPillar(master, 'I@backupninja:server', '_param:single_address')
            def backupninja_backup_host = _pillar['return'][0].values()[0]

            // salt "upg*" state.sls rabbitmq
            salt.enforceState(master, 'upg*', 'rabbitmq')
            // salt "upg*" state.sls memcached
            salt.enforceState(master, 'upg*', 'memcached')
            // salt -C 'I@backupninja:server' state.sls backupninja
            salt.enforceState(master, 'I@backupninja:server', 'backupninja')
            // salt -C 'I@backupninja:client' state.sls backupninja
            salt.enforceState(master, 'I@backupninja:client', 'backupninja')
            // salt -C 'I@backupninja:client' cmd.run 'ssh-keygen -f "/root/.ssh/known_hosts" -R ${backupninja_backup_host}'
            salt.runSaltProcessStep(master, 'I@backupninja:client', 'cmd.run', "ssh-keygen -f /root/.ssh/known_hosts -R ${backupninja_backup_host}", [], null, true)
            // salt -C 'I@backupninja:client' cmd.run 'ssh-keyscan -H ${backupninja_backup_host} >> /root/.ssh/known_hosts'
            salt.runSaltProcessStep(master, 'I@backupninja:client', 'cmd.run', "ssh-keyscan -H ${backupninja_backup_host} >> /root/.ssh/known_hosts", [], null, true)
            // salt -C 'I@backupninja:client' cmd.run 'backupninja -n --run /etc/backup.d/101.mysql'
            salt.runSaltProcessStep(master, 'I@backupninja:client', 'cmd.run', "backupninja -n --run /etc/backup.d/101.mysql", [], null, true)
            // salt -C 'I@backupninja:client' cmd.run 'backupninja -n --run /etc/backup.d/200.backup.rsync'
            salt.runSaltProcessStep(master, 'I@backupninja:client', 'cmd.run', "backupninja -n --run /etc/backup.d/200.backup.rsync", [], null, true)
            // salt -C 'I@mysql:client' state.sls mysql.client
            salt.enforceState(master, 'I@mysql:client', 'mysql.client')

            // salt "upg*" state.sls keystone.server
            // salt "upg*" state.sls keystone.client.service
            salt.enforceState(master, 'upg*', ['keystone.server', 'keystone.client.service'])
            // salt 'upg*' state.sls glance
            // salt "upg*" state.sls keystone.server
            // salt 'upg*' state.sls nova
            // salt 'upg*' state.sls cinder
            // salt 'upg*' state.sls neutron
            // salt 'upg*' state.sls heat
            salt.enforceState(master, 'upg*', ['glance', 'keystone.server', 'nova', 'cinder', 'neutron', 'heat'])

            // salt 'upg01*' cmd.run ". /root/keystonercv3; openstack service list"
            // salt 'upg01*' cmd.run ". /root/keystonercv3; openstack image list"
            // salt 'upg01*' cmd.run ". /root/keystonercv3; openstack flavor list"
            // salt 'upg01*' cmd.run ". /root/keystonercv3; openstack compute service list"
            // salt 'upg01*' cmd.run ". /root/keystonercv3; openstack server list"
            // salt 'upg01*' cmd.run ". /root/keystonercv3; openstack network list"
            // salt 'upg01*' cmd.run ". /root/keystonercv3; openstack orchestration service list"
            salt.runSaltProcessStep(master, 'upg01*', 'cmd.run', '. /root/keystonercv3; openstack service list; openstack image list; openstack flavor list; openstack compute service list; openstack server list; openstack network list; openstack orchestration service list', null, true)
        }

        stage('Ask for manual confirmation') {
            input message: "Do you want to continue with update?"
        }

        state('Start upgrade') {
            // # actual upgrade
            // salt 'kvm01*' cmd.run 'virsh destroy prx01.${domain}'
            salt.runSaltProcessStep(master, 'kvm01*', 'cmd.run', "virsh destroy prx01.${domain}", null, true)
            // salt 'kvm03*' cmd.run 'virsh destroy prx02.${domain}'
            salt.runSaltProcessStep(master, 'kvm03*', 'cmd.run', "virsh destroy prx02.${domain}", null, true)
            // salt 'kvm01*' cmd.run 'virsh destroy ctl01.${domain}'
            salt.runSaltProcessStep(master, 'kvm01*', 'cmd.run', "virsh destroy ctl01.${domain}", null, true)
            // salt 'kvm02*' cmd.run 'virsh destroy ctl02.${domain}'
            salt.runSaltProcessStep(master, 'kvm02*', 'cmd.run', "virsh destroy ctl02.${domain}", null, true)
            // salt 'kvm03*' cmd.run 'virsh destroy ctl03.${domain}'
            salt.runSaltProcessStep(master, 'kvm03*', 'cmd.run', "virsh destroy ctl03.${domain}", null, true)

            // salt 'kvm01*' cmd.run '[ ! -f ./prx01.${domain}.qcow2.bak ] && cp /var/lib/libvirt/images/prx01.${domain}/system.qcow2 ./prx01.${domain}.qcow2.bak'
            salt.runSaltProcessStep(master, 'kvm01*', 'cmd.run', "[ ! -f ./prx01.${domain}.qcow2.bak ] && cp /var/lib/libvirt/images/prx01.${domain}/system.qcow2 ./prx01.${domain}.qcow2.bak", null, true)
            // salt 'kvm03*' cmd.run '[ ! -f ./prx02.${domain}.qcow2.bak ] && cp /var/lib/libvirt/images/prx02.${domain}/system.qcow2 ./prx02.${domain}.qcow2.bak'
            salt.runSaltProcessStep(master, 'kvm03*', 'cmd.run', "[ ! -f ./prx02.${domain}.qcow2.bak ] && cp /var/lib/libvirt/images/prx02.${domain}/system.qcow2 ./prx02.${domain}.qcow2.bak", null, true)
            // salt 'kvm01*' cmd.run '[ ! -f ./ctl01.${domain}.qcow2.bak ] && cp /var/lib/libvirt/images/ctl01.${domain}/system.qcow2 ./ctl01.${domain}.qcow2.bak'
            salt.runSaltProcessStep(master, 'kvm01*', 'cmd.run', "[ ! -f ./ctl01.${domain}.qcow2.bak ] && cp /var/lib/libvirt/images/ctl01.${domain}/system.qcow2 ./ctl01.${domain}.qcow2.bak", null, true)
            // salt 'kvm02*' cmd.run '[ ! -f ./ctl02.${domain}.qcow2.bak ] && cp /var/lib/libvirt/images/ctl02.${domain}/system.qcow2 ./ctl02.${domain}.qcow2.bak'
            salt.runSaltProcessStep(master, 'kvm02*', 'cmd.run', "[ ! -f ./ctl02.${domain}.qcow2.bak ] && cp /var/lib/libvirt/images/ctl02.${domain}/system.qcow2 ./ctl02.${domain}.qcow2.bak", null, true)
            // salt 'kvm03*' cmd.run '[ ! -f ./ctl03.${domain}.qcow2.bak ] && cp /var/lib/libvirt/images/ctl03.${domain}/system.qcow2 ./ctl03.${domain}.qcow2.bak'
            salt.runSaltProcessStep(master, 'kvm03*', 'cmd.run', "[ ! -f ./ctl03.${domain}.qcow2.bak ] && cp /var/lib/libvirt/images/ctl03.${domain}/system.qcow2 ./ctl03.${domain}.qcow2.bak", null, true)

            // salt 'kvm01*' cmd.run 'virsh undefine prx01.${domain}'
            salt.runSaltProcessStep(master, 'kvm01*', 'cmd.run', "virsh undefine prx01.${domain}", null, true)
            // salt 'kvm03*' cmd.run 'virsh undefine prx02.${domain}'
            salt.runSaltProcessStep(master, 'kvm03*', 'cmd.run', "virsh undefine prx02.${domain}", null, true)
            // salt 'kvm01*' cmd.run 'virsh undefine ctl01.${domain}'
            salt.runSaltProcessStep(master, 'kvm01*', 'cmd.run', "virsh undefine ctl01.${domain}", null, true)
            // salt 'kvm02*' cmd.run 'virsh undefine ctl02.${domain}'
            salt.runSaltProcessStep(master, 'kvm02*', 'cmd.run', "virsh undefine ctl02.${domain}", null, true)
            // salt 'kvm03*' cmd.run 'virsh undefine ctl03.${domain}'
            salt.runSaltProcessStep(master, 'kvm03*', 'cmd.run', "virsh undefine ctl03.${domain}", null, true)

            // salt-key -d ctl01.${domain} -y
            // salt-key -d ctl02.${domain} -y
            // salt-key -d ctl03.${domain} -y
            // salt-key -d prx01.${domain} -y
            // salt-key -d prx02.${domain} -y
            salt.runSaltProcessStep(master, 'I@salt', 'cmd.run', "salt-key -d ctl01.${domain},ctl01.${domain},ctl03.${domain},prx01.${domain},prx02.${domain}", null, true)

            // salt 'kvm*' state.sls salt.control
            salt.enforceState(master, 'kvm*', 'salt.control')

            sleep(60)

            // salt '*' saltutil.refresh_pillar
            salt.runSaltProcessStep(master, '*', 'saltutil.refresh_pillar', [], null, true)
            // salt '*' saltutil.sync_all
            salt.runSaltProcessStep(master, '*', 'saltutil.sync_all', [], null, true)

            // salt "ctl*" state.sls linux,openssh,salt.minion,ntp,rsyslog
            // salt 'prx*' state.sls linux,openssh,salt.minion,ntp,rsyslog
            // salt "ctl*" state.sls linux,openssh,salt.minion,ntp,rsyslog
            salt.enforceState(master, 'ctl* or prx* or ctl*', ['linux', 'openssh', 'salt.minion', 'ntp', 'rsyslog'])
            // salt 'ctl*' state.sls keepalived
            // salt 'ctl*' state.sls haproxy
            salt.enforceState(master, 'ctl*', ['keepalived', 'haproxy'])
            // salt 'ctl*' service.restart rsyslog
            salt.runSaltProcessStep(master, 'ctl*', 'service.restart', ['rsyslog'], null, true)
            // salt "ctl*" state.sls memcached
            // salt "ctl*" state.sls keystone.server
            salt.enforceState(master, 'ctl*', ['memcached', 'keystone.server'])
            // salt 'ctl01*' state.sls keystone.client
            salt.enforceState(master, 'ctl01*', ['keystone.client'])
            // salt 'ctl*' state.sls glance
            salt.enforceState(master, 'ctl*', 'glance')
            // salt 'ctl*' state.sls glusterfs.client
            salt.enforceState(master, 'ctl*', 'glusterfs.client')
            // salt 'ctl*' state.sls keystone.server
            salt.enforceState(master, 'ctl*', 'keystone.server')
            // salt 'ctl*' state.sls nova
            salt.enforceState(master, 'ctl*', 'nova')
            // salt 'ctl*' state.sls cinder
            salt.enforceState(master, 'ctl*', 'cinder')
            // salt 'ctl*' state.sls neutron
            salt.enforceState(master, 'ctl*', 'neutron')
            // salt 'ctl*' state.sls heat
            salt.enforceState(master, 'ctl*', 'heat')

            // salt 'cmp*' cmd.run 'service nova-compute restart'
            salt.runSaltProcessStep(master, 'cmp*', 'service.restart', ['nova-compute'], null, true)

            // salt 'prx*' state.sls linux,openssh,salt.minion,ntp,rsyslog - TODO: proč? už to jednou projelo
            // salt 'ctl*' state.sls keepalived
            // salt 'prx*' state.sls keepalived
            salt.enforceState(master, 'prx*', 'keepalived')
            // salt 'prx*' state.sls horizon
            salt.enforceState(master, 'prx*', 'horizon')
            // salt 'prx*' state.sls nginx
            salt.enforceState(master, 'prx*', 'nginx')

            // salt 'ctl01*' cmd.run ". /root/keystonercv3; openstack service list"
            // salt 'ctl01*' cmd.run ". /root/keystonercv3; openstack image list"
            // salt 'ctl01*' cmd.run ". /root/keystonercv3; openstack flavor list"
            // salt 'ctl01*' cmd.run ". /root/keystonercv3; openstack compute service list"
            // salt 'ctl01*' cmd.run ". /root/keystonercv3; openstack server list"
            // salt 'ctl01*' cmd.run ". /root/keystonercv3; openstack network list"
            // salt 'ctl01*' cmd.run ". /root/keystonercv3; openstack orchestration service list"
            salt.runSaltProcessStep(master, 'upg01*', 'cmd.run', '. /root/keystonercv3; openstack service list; openstack image list; openstack flavor list; openstack compute service list; openstack server list; openstack network list; openstack orchestration service list', null, true)
        }

    }
}
