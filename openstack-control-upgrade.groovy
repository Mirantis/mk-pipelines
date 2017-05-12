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


            salt.enforceState(master, 'I@salt:master', 'reclass')

            salt.runSaltProcessStep(master, '*', 'saltutil.refresh_pillar', [], null, true)
            // salt '*' saltutil.sync_all
            salt.runSaltProcessStep(master, '*', 'saltutil.sync_all', [], null, true)
            

            def _pillar = salt.getPillar(master, 'I@salt:master', 'grains.item domain')
            def domain = _pillar['return'][0].values()[0]
            println _pillar
            println domain

            // read backupninja variable
            _pillar = salt.getPillar(master, 'I@backupninja:server', '_param:single_address')
            def backupninja_backup_host = _pillar['return'][0].values()[0]
            println _pillar
            println backupninja_backup_host

            _pillar = salt.getPillar(master, 'I@salt:control', 'grains.item id')
            def kvm01 = _pillar['return'][0].values()[0]
            def kvm02 = _pillar['return'][0].values()[1]
            def kvm03 = _pillar['return'][0].values()[2]
            println _pillar
            println kvm01
            println kvm02
            println kvm03

            _pillar = salt.getPillar(master, '${kvm01}', 'salt:control:cluster:internal:node:upg01:provider')
            def upgNodeProvider = _pillar['return'][0].values()[0]
            println _pillar
            println upgNodeProvider


            salt.runSaltProcessStep(master, '${upgNodeProvider}', 'virt.destroy upg01.${domain}', [], null, true)
            salt.runSaltProcessStep(master, '${upgNodeProvider}', 'virt.undefine upg01.${domain}', [], null, true)

            // salt-key -d upg01.${domain} -y
            salt.runSaltProcessStep(saltMaster, 'I@salt:master', 'cmd.run', "salt-key -d upg01.${domain} -y", [], null, true)
            // salt 'kvm02*' state.sls salt.control
            salt.enforceState(saltMaster, '${upgNodeProvider}', 'salt.control')

            sleep(60)

            // salt '*' saltutil.refresh_pillar
            salt.runSaltProcessStep(master, 'upg*', 'saltutil.refresh_pillar', [], null, true)
            // salt '*' saltutil.sync_all
            salt.runSaltProcessStep(master, 'upg*', 'saltutil.sync_all', [], null, true)

            // salt "upg*" state.sls linux,openssh,salt.minion,ntp,rsyslog
            salt.enforceState(master, 'upg*', ['linux', 'openssh', 'salt.minion', 'ntp', 'rsyslog'])
            // salt '*' state.apply salt.minion.grains
            salt.enforceState(master, '*', 'salt.minion.grains')
            // salt "upg*" state.sls linux,openssh,salt.minion,ntp,rsyslog
            salt.enforceState(master, 'upg*', ['linux', 'openssh', 'salt.minion', 'ntp', 'rsyslog'])

            // salt "upg*" state.sls rabbitmq
            salt.enforceState(master, 'upg*', 'rabbitmq')
            // salt "upg*" state.sls memcached
            salt.enforceState(master, 'upg*', 'memcached')
            salt.enforceState(master, 'I@backupninja:client', 'openssh.client')
            // salt -C 'I@backupninja:server' state.sls backupninja
            salt.enforceState(master, 'I@backupninja:server', 'backupninja')
            // salt -C 'I@backupninja:client' state.sls backupninja
            salt.enforceState(master, 'I@backupninja:client', 'backupninja')
            salt.runSaltProcessStep(master, 'I@backupninja:client', 'ssh.rm_known_host root ${backupninja_backup_host}', [], null, true)
            salt.runSaltProcessStep(master, 'I@backupninja:client', 'cmd.run', "arp -d ${backupninja_backup_host}", [], null, true)
            salt.runSaltProcessStep(master, 'I@backupninja:client', 'ssh.set_known_host root ${backupninja_backup_host}', [], null, true)
            salt.runSaltProcessStep(master, 'I@backupninja:client', 'cmd.run', "backupninja -n --run /etc/backup.d/101.mysql", [], null, true)
            salt.runSaltProcessStep(master, 'I@backupninja:client', 'cmd.run', "backupninja -n --run /etc/backup.d/200.backup.rsync", [], null, true)
            

            def databases = salt.runSaltProcessStep(master, 'I@mysql:client', 'mysql.db_list | grep upgrade | awk "/-/ {print $2}"', [], null, true)
            for (String database : databases) { System.out.println(database) }
            for (String database : databases) { salt.runSaltProcessStep(master, 'I@mysql:client', 'mysql.db_remove ${database}', [], null, true) }
            for (String database : databases) { salt.runSaltProcessStep(master, 'I@mysql:client', 'file.remove /root/mysql/flags/${database}-installed', [], null, true) }


            salt.enforceState(master, 'I@mysql:client', 'mysql.client')

            salt.enforceState(master, 'upg*', ['keystone.server', 'keystone.client'])

            salt.enforceState(master, 'upg*', ['glance', 'keystone.server', 'nova', 'cinder', 'neutron', 'heat'])

            salt.runSaltProcessStep(master, 'upg01*', 'cmd.run', '. /root/keystonercv3; openstack service list; openstack image list; openstack flavor list; openstack compute service list; openstack server list; openstack network list; openstack volume list; openstack orchestration service list', null, true)
        }

        stage('Ask for manual confirmation') {
            input message: "Do you want to continue with upgrade?"
        }
        state('Start upgrade') {
            // # actual upgrade

            _pillar = salt.getPillar(master, '${kvm01}', 'salt:control:cluster:internal:node:ctl01:provider')
            def ctl01NodeProvider = _pillar['return'][0].values()[0]

            _pillar = salt.getPillar(master, '${kvm01}', 'salt:control:cluster:internal:node:ctl02:provider')
            def ctl02NodeProvider = _pillar['return'][0].values()[0]

            _pillar = salt.getPillar(master, '${kvm01}', 'salt:control:cluster:internal:node:ctl03:provider')
            def ctl03NodeProvider = _pillar['return'][0].values()[0]

            _pillar = salt.getPillar(master, '${kvm01}', 'salt:control:cluster:internal:node:prx01:provider')
            def prx01NodeProvider = _pillar['return'][0].values()[0]

            _pillar = salt.getPillar(master, '${kvm01}', 'salt:control:cluster:internal:node:prx02:provider')
            def prx02NodeProvider = _pillar['return'][0].values()[0]


            salt.runSaltProcessStep(master, '${prx01NodeProvider}', 'virt.destroy prx01.${domain}', [], null, true)
            salt.runSaltProcessStep(master, '${prx02NodeProvider}', 'virt.destroy prx01.${domain}', [], null, true)
            salt.runSaltProcessStep(master, '${ctl01NodeProvider}', 'virt.destroy ctl01.${domain}', [], null, true)
            salt.runSaltProcessStep(master, '${ctl02NodeProvider}', 'virt.destroy ctl02.${domain}', [], null, true)
            salt.runSaltProcessStep(master, '${ctl03NodeProvider}', 'virt.destroy ctl03.${domain}', [], null, true)


            // salt 'kvm01*' cmd.run '[ ! -f ./prx01.${domain}.qcow2.bak ] && cp /var/lib/libvirt/images/prx01.${domain}/system.qcow2 ./prx01.${domain}.qcow2.bak'
            salt.runSaltProcessStep(master, '${prx01NodeProvider}', 'cmd.run', "[ ! -f ./prx01.${domain}.qcow2.bak ] && cp /var/lib/libvirt/images/prx01.${domain}/system.qcow2 ./prx01.${domain}.qcow2.bak", null, true)
            // salt 'kvm03*' cmd.run '[ ! -f ./prx02.${domain}.qcow2.bak ] && cp /var/lib/libvirt/images/prx02.${domain}/system.qcow2 ./prx02.${domain}.qcow2.bak'
            salt.runSaltProcessStep(master, '${prx02NodeProvider}', 'cmd.run', "[ ! -f ./prx02.${domain}.qcow2.bak ] && cp /var/lib/libvirt/images/prx02.${domain}/system.qcow2 ./prx02.${domain}.qcow2.bak", null, true)
            // salt 'kvm01*' cmd.run '[ ! -f ./ctl01.${domain}.qcow2.bak ] && cp /var/lib/libvirt/images/ctl01.${domain}/system.qcow2 ./ctl01.${domain}.qcow2.bak'
            salt.runSaltProcessStep(master, '${ctl01NodeProvider}', 'cmd.run', "[ ! -f ./ctl01.${domain}.qcow2.bak ] && cp /var/lib/libvirt/images/ctl01.${domain}/system.qcow2 ./ctl01.${domain}.qcow2.bak", null, true)
            // salt 'kvm02*' cmd.run '[ ! -f ./ctl02.${domain}.qcow2.bak ] && cp /var/lib/libvirt/images/ctl02.${domain}/system.qcow2 ./ctl02.${domain}.qcow2.bak'
            salt.runSaltProcessStep(master, '${ctl02NodeProvider}', 'cmd.run', "[ ! -f ./ctl02.${domain}.qcow2.bak ] && cp /var/lib/libvirt/images/ctl02.${domain}/system.qcow2 ./ctl02.${domain}.qcow2.bak", null, true)
            // salt 'kvm03*' cmd.run '[ ! -f ./ctl03.${domain}.qcow2.bak ] && cp /var/lib/libvirt/images/ctl03.${domain}/system.qcow2 ./ctl03.${domain}.qcow2.bak'
            salt.runSaltProcessStep(master, '${ctl03NodeProvider}', 'cmd.run', "[ ! -f ./ctl03.${domain}.qcow2.bak ] && cp /var/lib/libvirt/images/ctl03.${domain}/system.qcow2 ./ctl03.${domain}.qcow2.bak", null, true)


            salt.runSaltProcessStep(master, '${prx01NodeProvider}', 'virt.undefine prx01.${domain}', [], null, true)
            salt.runSaltProcessStep(master, '${prx02NodeProvider}', 'virt.undefine prx02.${domain}', [], null, true)
            salt.runSaltProcessStep(master, '${ctl01NodeProvider}', 'virt.undefine ctl01.${domain}', [], null, true)
            salt.runSaltProcessStep(master, '${ctl02NodeProvider}', 'virt.undefine ctl02.${domain}', [], null, true)
            salt.runSaltProcessStep(master, '${ctl03NodeProvider}', 'virt.undefine ctl03.${domain}', [], null, true)


            salt.runSaltProcessStep(master, 'I@backupninja:client', 'cmd.run', "backupninja -n --run /etc/backup.d/101.mysql", [], null, true)
            salt.runSaltProcessStep(master, 'I@backupninja:client', 'cmd.run', "backupninja -n --run /etc/backup.d/200.backup.rsync", [], null, true)
            

            salt.runSaltProcessStep(master, 'I@salt', 'cmd.run', "salt-key -d ctl01.${domain},ctl01.${domain},ctl03.${domain},prx01.${domain},prx02.${domain}", null, true)

            // salt 'kvm*' state.sls salt.control
            salt.enforceState(master, 'I@salt:control', 'salt.control')

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
            salt.enforceState(master, 'I@keystone:client and ctl*', 'keystone.client')
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

            salt.runSaltProcessStep(master, 'ctl01*', 'cmd.run', '. /root/keystonercv3; openstack service list; openstack image list; openstack flavor list; openstack compute service list; openstack server list; openstack network list; openstack volume list; openstack orchestration service list', null, true)
        }

        stage('Verification') {
            input message: "Please verify the control upgrade and if was not successful, in the worst scenario, you can use the openstack-control-upgrade-rollover pipeline"
        }
    }
}
