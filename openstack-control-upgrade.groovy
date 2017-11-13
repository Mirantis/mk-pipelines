/**
 * Update packages on given nodes
 *
 * Expected parameters:
 *   SALT_MASTER_CREDENTIALS    Credentials to the Salt API.
 *   SALT_MASTER_URL            Full Salt API address [http://10.10.10.1:8000].
 *   STAGE_TEST_UPGRADE         Run test upgrade stage (bool)
 *   STAGE_REAL_UPGRADE         Run real upgrade stage (bool)
 *   STAGE_ROLLBACK_UPGRADE     Run rollback upgrade stage (bool)
 *
**/

def common = new com.mirantis.mk.Common()
def salt = new com.mirantis.mk.Salt()
def python = new com.mirantis.mk.Python()

def pepperEnv = "pepperEnv"

node() {

    stage('Setup virtualenv for Pepper') {
            python.setupPepperVirtualenv(pepperEnv, SALT_MASTER_URL, SALT_MASTER_CREDENTIALS)
    }

    if (STAGE_TEST_UPGRADE.toBoolean() == true) {
        stage('Test upgrade') {


            try {
                salt.enforceState(pepperEnv, 'I@salt:master', 'reclass')
            } catch (Exception e) {
                common.warningMsg("Some parts of Reclass state failed. The most probable reasons were uncommited changes. We should continue to run")
            }

            try {
                salt.runSaltProcessStep(pepperEnv, '*', 'saltutil.refresh_pillar', [], null, true)
            } catch (Exception e) {
                common.warningMsg("No response from some minions. We should continue to run")
            }

            try {
                salt.runSaltProcessStep(pepperEnv, '*', 'saltutil.sync_all', [], null, true)
            } catch (Exception e) {
                common.warningMsg("No response from some minions. We should continue to run")
            }

            def _pillar = salt.getGrain(pepperEnv, 'I@salt:master', 'domain')
            def domain = _pillar['return'][0].values()[0].values()[0]
            print(_pillar)
            print(domain)

            // read backupninja variable
            _pillar = salt.getPillar(pepperEnv, 'I@backupninja:client', '_param:backupninja_backup_host')
            def backupninja_backup_host = _pillar['return'][0].values()[0]
            print(_pillar)
            print(backupninja_backup_host)

            _pillar = salt.getGrain(pepperEnv, 'I@salt:control', 'id')
            def kvm01 = _pillar['return'][0].values()[0].values()[0]
            print(_pillar)
            print(kvm01)

            _pillar = salt.getPillar(pepperEnv, "${kvm01}", 'salt:control:cluster:internal:node:upg01:provider')
            def upgNodeProvider = _pillar['return'][0].values()[0]
            print(_pillar)
            print(upgNodeProvider)


            salt.runSaltProcessStep(pepperEnv, "${upgNodeProvider}", 'virt.destroy', ["upg01.${domain}"], null, true)
            salt.runSaltProcessStep(pepperEnv, "${upgNodeProvider}", 'virt.undefine', ["upg01.${domain}"], null, true)


            try {
                salt.cmdRun(pepperEnv, 'I@salt:master', "salt-key -d upg01.${domain} -y")
            } catch (Exception e) {
                common.warningMsg("upg01.${domain} does not match any accepted, unaccepted or rejected keys. The key did not exist yet or was already removed. We should continue to run")
            }


            // salt 'kvm02*' state.sls salt.control
            salt.enforceState(pepperEnv, "${upgNodeProvider}", 'salt.control')

            // wait until upg node is registered in salt-key
            salt.minionPresent(pepperEnv, 'I@salt:master', 'upg01')

            // salt '*' saltutil.refresh_pillar
            salt.runSaltProcessStep(pepperEnv, 'upg*', 'saltutil.refresh_pillar', [], null, true)
            // salt '*' saltutil.sync_all
            salt.runSaltProcessStep(pepperEnv, 'upg*', 'saltutil.sync_all', [], null, true)

            // salt "upg*" state.sls linux,openssh,salt.minion,ntp,rsyslog
            try {
                salt.enforceState(pepperEnv, 'upg*', ['linux', 'openssh', 'salt.minion', 'ntp', 'rsyslog'])
            } catch (Exception e) {
                common.warningMsg('Received no response because salt-minion was restarted. We should continue to run')
            }
            salt.enforceState(pepperEnv, 'upg*', ['linux', 'openssh', 'salt.minion', 'ntp', 'rsyslog'])

            // salt "upg*" state.sls rabbitmq
            salt.enforceState(pepperEnv, 'upg*', ['rabbitmq', 'memcached'])
            try {
                salt.enforceState(pepperEnv, 'I@backupninja:client', ['openssh.client', 'salt.minion'])
            } catch (Exception e) {
                common.warningMsg('salt-minion was restarted. We should continue to run')
            }
            try {
                salt.enforceState(pepperEnv, 'I@backupninja:server', ['salt.minion'])
            } catch (Exception e) {
                common.warningMsg('salt-minion was restarted. We should continue to run')
            }
            // salt '*' state.apply salt.minion.grains
            //salt.enforceState(pepperEnv, '*', 'salt.minion.grains')
            // salt -C 'I@backupninja:server' state.sls backupninja
            salt.enforceState(pepperEnv, 'I@backupninja:server', 'backupninja')
            // salt -C 'I@backupninja:client' state.sls backupninja
            salt.enforceState(pepperEnv, 'I@backupninja:client', 'backupninja')
            salt.runSaltProcessStep(pepperEnv, 'I@backupninja:client', 'ssh.rm_known_host', ["root", "${backupninja_backup_host}"], null, true)
            try {
                salt.cmdRun(pepperEnv, 'I@backupninja:client', "arp -d ${backupninja_backup_host}")
            } catch (Exception e) {
                common.warningMsg('The ARP entry does not exist. We should continue to run.')
            }
            salt.runSaltProcessStep(pepperEnv, 'I@backupninja:client', 'ssh.set_known_host', ["root", "${backupninja_backup_host}"], null, true)
            salt.cmdRun(pepperEnv, 'I@backupninja:client', 'backupninja -n --run /etc/backup.d/101.mysql')
            salt.cmdRun(pepperEnv, 'I@backupninja:client', 'backupninja -n --run /etc/backup.d/200.backup.rsync > /tmp/backupninjalog')

            salt.enforceState(pepperEnv, 'I@xtrabackup:server', 'xtrabackup')
            salt.enforceState(pepperEnv, 'I@xtrabackup:client', 'openssh.client')
            salt.cmdRun(pepperEnv, 'I@xtrabackup:client', "su root -c 'salt-call state.sls xtrabackup'")
            salt.cmdRun(pepperEnv, 'I@xtrabackup:client', "su root -c '/usr/local/bin/innobackupex-runner.sh'")

            def databases = salt.cmdRun(pepperEnv, 'I@mysql:client','salt-call mysql.db_list | grep upgrade | awk \'/-/ {print \$2}\'')
            if(databases && databases != ""){
                def databasesList = databases['return'][0].values()[0].trim().tokenize("\n")
                for( i = 0; i < databasesList.size(); i++){
                    if(databasesList[i].toLowerCase().contains('upgrade')){
                        salt.runSaltProcessStep(pepperEnv, 'I@mysql:client', 'mysql.db_remove', ["${databasesList[i]}"], null, true)
                        common.warningMsg("removing database ${databasesList[i]}")
                        salt.runSaltProcessStep(pepperEnv, 'I@mysql:client', 'file.remove', ["/root/mysql/flags/${databasesList[i]}-installed"], null, true)
                    }
                }
                salt.enforceState(pepperEnv, 'I@mysql:client', 'mysql.client')
            }else{
                common.errorMsg("No _upgrade databases were returned")
            }

            try {
                salt.enforceState(pepperEnv, 'upg*', 'keystone.server')
                salt.runSaltProcessStep(pepperEnv, 'upg*', 'service.restart', ['apache2'], null, true)
            } catch (Exception e) {
                common.warningMsg('Restarting Apache2')
                salt.runSaltProcessStep(pepperEnv, 'upg*', 'service.restart', ['apache2'], null, true)
            }
            try {
                salt.enforceState(pepperEnv, 'upg*', 'keystone.client')
            } catch (Exception e) {
                common.warningMsg('running keystone.client state again')
                salt.enforceState(pepperEnv, 'upg*', 'keystone.client')
            }
            try {
                salt.enforceState(pepperEnv, 'upg*', 'glance')
            } catch (Exception e) {
                common.warningMsg('running glance state again')
                salt.enforceState(pepperEnv, 'upg*', 'glance')
            }
            salt.enforceState(pepperEnv, 'upg*', 'keystone.server')
            try {
                salt.enforceState(pepperEnv, 'upg*', 'nova')
            } catch (Exception e) {
                common.warningMsg('running nova state again')
                salt.enforceState(pepperEnv, 'upg*', 'nova')
            }
            // run nova state again as sometimes nova does not enforce itself for some reason
            try {
                salt.enforceState(pepperEnv, 'upg*', 'nova')
            } catch (Exception e) {
                common.warningMsg('running nova state again')
                salt.enforceState(pepperEnv, 'upg*', 'nova')
            }
            try {
                salt.enforceState(pepperEnv, 'upg*', 'cinder')
            } catch (Exception e) {
                common.warningMsg('running cinder state again')
                salt.enforceState(pepperEnv, 'upg*', 'cinder')
            }
            try {
                salt.enforceState(pepperEnv, 'upg*', 'neutron')
            } catch (Exception e) {
                common.warningMsg('running neutron state again')
                salt.enforceState(pepperEnv, 'upg*', 'neutron')
            }
            try {
                salt.enforceState(pepperEnv, 'upg*', 'heat')
            } catch (Exception e) {
                common.warningMsg('running heat state again')
                salt.enforceState(pepperEnv, 'upg*', 'heat')
            }
            salt.cmdRun(pepperEnv, 'upg01*', '. /root/keystonercv3; openstack service list; openstack image list; openstack flavor list; openstack compute service list; openstack server list; openstack network list; openstack volume list; openstack orchestration service list')

            if (STAGE_TEST_UPGRADE.toBoolean() == true && STAGE_REAL_UPGRADE.toBoolean() == true) {
                stage('Ask for manual confirmation') {
                    input message: "Do you want to continue with upgrade?"
                }
            }
        }
    }

    if (STAGE_REAL_UPGRADE.toBoolean() == true) {
        stage('Real upgrade') {
            // # actual upgrade

            _pillar = salt.getGrain(pepperEnv, 'I@salt:master', 'domain')
            domain = _pillar['return'][0].values()[0].values()[0]
            print(_pillar)
            print(domain)

            _pillar = salt.getGrain(pepperEnv, 'I@salt:control', 'id')
            kvm01 = _pillar['return'][0].values()[0].values()[0]
            print(_pillar)
            print(kvm01)

            def errorOccured = false

            def proxy_general_target = ""
            def proxy_target_hosts = salt.getMinions(pepperEnv, 'I@horizon:server')
            def node_count = 1

            for (t in proxy_target_hosts) {
                def target = t.split("\\.")[0]
                proxy_general_target = target.replaceAll('\\d+$', "")
                _pillar = salt.getPillar(pepperEnv, "${kvm01}", "salt:control:cluster:internal:node:prx0${node_count}:provider")
                def nodeProvider = _pillar['return'][0].values()[0]
                salt.runSaltProcessStep(pepperEnv, "${nodeProvider}", 'virt.destroy', ["${target}.${domain}"], null, true)
                sleep(2)
                try {
                    salt.cmdRun(pepperEnv, "${nodeProvider}", "[ ! -f /root/${target}.${domain}.qcow2.bak ] && cp /var/lib/libvirt/images/${target}.${domain}/system.qcow2 ./${target}.${domain}.qcow2.bak")
                } catch (Exception e) {
                    common.warningMsg('File already exists')
                }
                salt.runSaltProcessStep(pepperEnv, "${nodeProvider}", 'virt.undefine', ["${target}.${domain}"], null, true)
                try {
                    salt.cmdRun(pepperEnv, 'I@salt:master', "salt-key -d ${target}.${domain} -y")
                } catch (Exception e) {
                    common.warningMsg('does not match any accepted, unaccepted or rejected keys. They were probably already removed. We should continue to run')
                }
                node_count++
            }
            def control_general_target = ""
            def control_target_hosts = salt.getMinions(pepperEnv, 'I@keystone:server')
            node_count = 1

            for (t in control_target_hosts) {
                def target = t.split("\\.")[0]
                control_general_target = target.replaceAll('\\d+$', "")
                _pillar = salt.getPillar(pepperEnv, "${kvm01}", "salt:control:cluster:internal:node:ctl0${node_count}:provider")
                def nodeProvider = _pillar['return'][0].values()[0]
                salt.runSaltProcessStep(pepperEnv, "${nodeProvider}", 'virt.destroy', ["${target}.${domain}"], null, true)
                sleep(2)
                try {
                    salt.cmdRun(pepperEnv, "${nodeProvider}", "[ ! -f /root/${target}.${domain}.qcow2.bak ] && cp /var/lib/libvirt/images/${target}.${domain}/system.qcow2 ./${target}.${domain}.qcow2.bak")
                } catch (Exception e) {
                    common.warningMsg('File already exists')
                }
                salt.runSaltProcessStep(pepperEnv, "${nodeProvider}", 'virt.undefine', ["${target}.${domain}"], null, true)
                try {
                    salt.cmdRun(pepperEnv, 'I@salt:master', "salt-key -d ${target}.${domain} -y")
                } catch (Exception e) {
                    common.warningMsg('does not match any accepted, unaccepted or rejected keys. They were probably already removed. We should continue to run')
                }
                node_count++
            }

            salt.cmdRun(pepperEnv, 'I@xtrabackup:client', "su root -c '/usr/local/bin/innobackupex-runner.sh'")

            // salt 'kvm*' state.sls salt.control
            salt.enforceState(pepperEnv, 'I@salt:control', 'salt.control')

            for (t in control_target_hosts) {
                def target = t.split("\\.")[0]
                // wait until ctl and prx nodes are registered in salt-key
                salt.minionPresent(pepperEnv, 'I@salt:master', '${target}')
            }
            for (t in proxy_target_hosts) {
                def target = t.split("\\.")[0]
                // wait until ctl and prx nodes are registered in salt-key
                salt.minionPresent(pepperEnv, 'I@salt:master', '${target}')
            }

            // salt '*' saltutil.refresh_pillar
            salt.runSaltProcessStep(pepperEnv, '*', 'saltutil.refresh_pillar', [], null, true)
            // salt '*' saltutil.sync_all
            salt.runSaltProcessStep(pepperEnv, '*', 'saltutil.sync_all', [], null, true)

            try {
                salt.enforceState(pepperEnv, "${proxy_general_target}* or ${control_general_target}*", ['linux', 'openssh', 'salt.minion', 'ntp', 'rsyslog'])
            } catch (Exception e) {
                common.warningMsg('Received no response because salt-minion was restarted. We should continue to run')
            }
            salt.enforceState(pepperEnv, "${proxy_general_target}* or ${control_general_target}*", ['linux', 'openssh', 'salt.minion', 'ntp', 'rsyslog'])

            // salt 'ctl*' state.sls keepalived
            // salt 'ctl*' state.sls haproxy
            salt.enforceState(pepperEnv, "${control_general_target}*", ['keepalived', 'haproxy'])
            // salt 'ctl*' service.restart rsyslog
            salt.runSaltProcessStep(pepperEnv, "${control_general_target}*", 'service.restart', ['rsyslog'], null, true)
            // salt "ctl*" state.sls memcached
            // salt "ctl*" state.sls keystone.server
            try {
                try {
                    salt.enforceState(pepperEnv, "${control_general_target}*", ['memcached', 'keystone.server'])
                    salt.runSaltProcessStep(pepperEnv, "${control_general_target}*", 'service.restart', ['apache2'], null, true)
                } catch (Exception e) {
                    common.warningMsg('Restarting Apache2 and enforcing keystone.server state again')
                    salt.runSaltProcessStep(pepperEnv, "${control_general_target}*", 'service.restart', ['apache2'], null, true)
                    salt.enforceState(pepperEnv, "${control_general_target}*", 'keystone.server')
                }
                // salt 'ctl01*' state.sls keystone.client
                try {
                    salt.enforceState(pepperEnv, "I@keystone:client and ${control_general_target}*", 'keystone.client')
                } catch (Exception e) {
                    common.warningMsg('running keystone.client state again')
                    salt.enforceState(pepperEnv, "I@keystone:client and ${control_general_target}*", 'keystone.client')
                }
                try {
                    salt.enforceState(pepperEnv, "${control_general_target}*", 'glance')
                } catch (Exception e) {
                    common.warningMsg('running glance state again')
                    salt.enforceState(pepperEnv, "${control_general_target}*", 'glance')
                }                // salt 'ctl*' state.sls glusterfs.client
                salt.enforceState(pepperEnv, "${control_general_target}*", 'glusterfs.client')
                // salt 'ctl*' state.sls keystone.server
                salt.enforceState(pepperEnv, "${control_general_target}*", 'keystone.server')
                // salt 'ctl*' state.sls nova
                try {
                    salt.enforceState(pepperEnv, "${control_general_target}*", 'nova')
                } catch (Exception e) {
                    common.warningMsg('running nova state again')
                    salt.enforceState(pepperEnv, "${control_general_target}*", 'nova')
                }
                // salt 'ctl*' state.sls cinder
                try {
                    salt.enforceState(pepperEnv, "${control_general_target}*", 'cinder')
                } catch (Exception e) {
                    common.warningMsg('running cinder state again')
                    salt.enforceState(pepperEnv, "${control_general_target}*", 'cinder')
                }
                try {
                    salt.enforceState(pepperEnv, "${control_general_target}*", 'neutron')
                } catch (Exception e) {
                    common.warningMsg('running neutron state again')
                    salt.enforceState(pepperEnv, "${control_general_target}*", 'neutron')
                }
                // salt 'ctl*' state.sls heat
                try {
                    salt.enforceState(pepperEnv, "${control_general_target}*", 'heat')
                } catch (Exception e) {
                    common.warningMsg('running heat state again')
                    salt.enforceState(pepperEnv, "${control_general_target}*", 'heat')
                }

            } catch (Exception e) {
                errorOccured = true
                common.warningMsg('Some states that require syncdb failed. Restoring production databases')

                // database restore section
                try {
                    salt.runSaltProcessStep(pepperEnv, 'I@galera:slave', 'service.stop', ['mysql'], null, true)
                } catch (Exception er) {
                    common.warningMsg('Mysql service already stopped')
                }
                try {
                    salt.runSaltProcessStep(pepperEnv, 'I@galera:master', 'service.stop', ['mysql'], null, true)
                } catch (Exception er) {
                    common.warningMsg('Mysql service already stopped')
                }
                try {
                    salt.cmdRun(pepperEnv, 'I@galera:slave', "rm /var/lib/mysql/ib_logfile*")
                } catch (Exception er) {
                    common.warningMsg('Files are not present')
                }
                try {
                    salt.cmdRun(pepperEnv, 'I@galera:master', "mkdir /root/mysql/mysql.bak")
                } catch (Exception er) {
                    common.warningMsg('Directory already exists')
                }
                try {
                    salt.cmdRun(pepperEnv, 'I@galera:master', "rm -rf /root/mysql/mysql.bak/*")
                } catch (Exception er) {
                    common.warningMsg('Directory already empty')
                }
                try {
                    salt.cmdRun(pepperEnv, 'I@galera:master', "mv /var/lib/mysql/* /root/mysql/mysql.bak")
                } catch (Exception er) {
                    common.warningMsg('Files were already moved')
                }
                try {
                    salt.runSaltProcessStep(pepperEnv, 'I@galera:master', 'file.remove', ["/var/lib/mysql/.galera_bootstrap"], null, true)
                } catch (Exception er) {
                    common.warningMsg('File is not present')
                }
                salt.cmdRun(pepperEnv, 'I@galera:master', "sed -i '/gcomm/c\\wsrep_cluster_address=\"gcomm://\"' /etc/mysql/my.cnf")
                _pillar = salt.getPillar(pepperEnv, "I@galera:master", 'xtrabackup:client:backup_dir')
                backup_dir = _pillar['return'][0].values()[0]
                if(backup_dir == null || backup_dir.isEmpty()) { backup_dir='/var/backups/mysql/xtrabackup' }
                print(backup_dir)
                salt.runSaltProcessStep(pepperEnv, 'I@galera:master', 'file.remove', ["${backup_dir}/dbrestored"], null, true)
                salt.cmdRun(pepperEnv, 'I@xtrabackup:client', "su root -c 'salt-call state.sls xtrabackup'")
                salt.runSaltProcessStep(pepperEnv, 'I@galera:master', 'service.start', ['mysql'], null, true)

                // wait until mysql service on galera master is up
                salt.commandStatus(pepperEnv, 'I@galera:master', 'service mysql status', 'running')

                salt.runSaltProcessStep(pepperEnv, 'I@galera:slave', 'service.start', ['mysql'], null, true)
                //

                common.errorMsg("Stage Real control upgrade failed")
            }
            if(!errorOccured){

                ceph = null

                try {
                    ceph = salt.cmdRun(pepperEnv, "${control_general_target}*", "salt-call grains.item roles | grep ceph.client")

                } catch (Exception er) {
                    common.infoMsg("Ceph is not used")
                }

                if(ceph != null) {
                    try {
                        salt.enforceState(pepperEnv, "${control_general_target}*", 'ceph.client')
                    } catch (Exception er) {
                        common.warningMsg("Ceph client state on controllers failed. Please fix it manually")
                    }
                }

                // salt 'cmp*' cmd.run 'service nova-compute restart'
                salt.runSaltProcessStep(pepperEnv, 'I@nova:compute', 'service.restart', ['nova-compute'], null, true)
                salt.runSaltProcessStep(pepperEnv, "${control_general_target}*", 'service.restart', ['nova-conductor'], null, true)
                salt.runSaltProcessStep(pepperEnv, "${control_general_target}*", 'service.restart', ['nova-scheduler'], null, true)


                // salt 'prx*' state.sls linux,openssh,salt.minion,ntp,rsyslog
                // salt 'ctl*' state.sls keepalived
                // salt 'prx*' state.sls keepalived
                salt.enforceState(pepperEnv, "${proxy_general_target}*", 'keepalived')
                // salt 'prx*' state.sls horizon
                salt.enforceState(pepperEnv, "${proxy_general_target}*", 'horizon')
                // salt 'prx*' state.sls nginx
                salt.enforceState(pepperEnv, "${proxy_general_target}*", 'nginx')
                // salt "prx*" state.sls memcached
                salt.enforceState(pepperEnv, "${proxy_general_target}*", 'memcached')

                try {
                    salt.enforceHighstate(pepperEnv, "${control_general_target}*")
                } catch (Exception er) {
                    common.errorMsg("Highstate was executed on controller nodes but something failed. Please check it and fix it accordingly.")
                }

                try {
                    salt.enforceHighstate(pepperEnv, "${proxy_general_target}*")
                } catch (Exception er) {
                    common.errorMsg("Highstate was executed on proxy nodes but something failed. Please check it and fix it accordingly.")
                }

                salt.cmdRun(pepperEnv, "${control_general_target}01*", '. /root/keystonercv3; openstack service list; openstack image list; openstack flavor list; openstack compute service list; openstack server list; openstack network list; openstack volume list; openstack orchestration service list')
            }
        }

        if (STAGE_REAL_UPGRADE.toBoolean() == true && STAGE_ROLLBACK_UPGRADE.toBoolean() == true) {
            stage('Ask for manual confirmation') {
                input message: "Please verify if the control upgrade was successful. If it did not succeed, in the worst scenario, you can click YES to continue with control-upgrade-rollback. Do you want to continue with the rollback?"
            }
        }
    }

    if (STAGE_ROLLBACK_UPGRADE.toBoolean() == true) {
        stage('Rollback upgrade') {

            stage('Ask for manual confirmation') {
                input message: "Do you really want to continue with the rollback?"
            }

            _pillar = salt.getGrain(pepperEnv, 'I@salt:master', 'domain')
            domain = _pillar['return'][0].values()[0].values()[0]
            print(_pillar)
            print(domain)

            _pillar = salt.getGrain(pepperEnv, 'I@salt:control', 'id')
            kvm01 = _pillar['return'][0].values()[0].values()[0]
            print(_pillar)
            print(kvm01)

            def proxy_general_target = ""
            def proxy_target_hosts = salt.getMinions(pepperEnv, 'I@horizon:server')
            def node_count = 1

            for (t in proxy_target_hosts) {
                def target = t.split("\\.")[0]
                proxy_general_target = target.replaceAll('\\d+$', "")
                _pillar = salt.getPillar(pepperEnv, "${kvm01}", "salt:control:cluster:internal:node:prx0${node_count}:provider")
                def nodeProvider = _pillar['return'][0].values()[0]
                salt.runSaltProcessStep(pepperEnv, "${nodeProvider}", 'virt.destroy', ["${target}.${domain}"], null, true)
                sleep(2)
                salt.runSaltProcessStep(pepperEnv, "${nodeProvider}", 'file.copy', ["/root/${target}.${domain}.qcow2.bak", "/var/lib/libvirt/images/${target}.${domain}/system.qcow2"], null, true)
                try {
                    salt.cmdRun(pepperEnv, 'I@salt:master', "salt-key -d ${target}.${domain} -y")
                } catch (Exception e) {
                    common.warningMsg('does not match any accepted, unaccepted or rejected keys. They were probably already removed. We should continue to run')
                }
                node_count++
            }
            def control_general_target = ""
            def control_target_hosts = salt.getMinions(pepperEnv, 'I@keystone:server')
            node_count = 1

            for (t in control_target_hosts) {
                def target = t.split("\\.")[0]
                control_general_target = target.replaceAll('\\d+$', "")
                _pillar = salt.getPillar(pepperEnv, "${kvm01}", "salt:control:cluster:internal:node:ctl0${node_count}:provider")
                def nodeProvider = _pillar['return'][0].values()[0]
                salt.runSaltProcessStep(pepperEnv, "${nodeProvider}", 'virt.destroy', ["${target}.${domain}"], null, true)
                sleep(2)
                salt.runSaltProcessStep(pepperEnv, "${nodeProvider}", 'file.copy', ["/root/${target}.${domain}.qcow2.bak", "/var/lib/libvirt/images/${target}.${domain}/system.qcow2"], null, true)
                try {
                    salt.cmdRun(pepperEnv, 'I@salt:master', "salt-key -d ${target}.${domain} -y")
                } catch (Exception e) {
                    common.warningMsg('does not match any accepted, unaccepted or rejected keys. They were probably already removed. We should continue to run')
                }
                node_count++
            }

            // database restore section
            try {
                salt.runSaltProcessStep(pepperEnv, 'I@galera:slave', 'service.stop', ['mysql'], null, true)
            } catch (Exception e) {
                common.warningMsg('Mysql service already stopped')
            }
            try {
                salt.runSaltProcessStep(pepperEnv, 'I@galera:master', 'service.stop', ['mysql'], null, true)
            } catch (Exception e) {
                common.warningMsg('Mysql service already stopped')
            }
            try {
                salt.cmdRun(pepperEnv, 'I@galera:slave', "rm /var/lib/mysql/ib_logfile*")
            } catch (Exception e) {
                common.warningMsg('Files are not present')
            }
            try {
                salt.cmdRun(pepperEnv, 'I@galera:master', "rm -rf /var/lib/mysql/*")
            } catch (Exception e) {
                common.warningMsg('Directory already empty')
            }
            try {
                salt.runSaltProcessStep(pepperEnv, 'I@galera:master', 'file.remove', ["/var/lib/mysql/.galera_bootstrap"], null, true)
            } catch (Exception e) {
                common.warningMsg('File is not present')
            }
            salt.cmdRun(pepperEnv, 'I@galera:master', "sed -i '/gcomm/c\\wsrep_cluster_address=\"gcomm://\"' /etc/mysql/my.cnf")
            _pillar = salt.getPillar(pepperEnv, "I@galera:master", 'xtrabackup:client:backup_dir')
            backup_dir = _pillar['return'][0].values()[0]
            if(backup_dir == null || backup_dir.isEmpty()) { backup_dir='/var/backups/mysql/xtrabackup' }
            print(backup_dir)
            salt.runSaltProcessStep(pepperEnv, 'I@galera:master', 'file.remove', ["${backup_dir}/dbrestored"], null, true)
            salt.cmdRun(pepperEnv, 'I@xtrabackup:client', "su root -c 'salt-call state.sls xtrabackup'")
            salt.runSaltProcessStep(pepperEnv, 'I@galera:master', 'service.start', ['mysql'], null, true)

            // wait until mysql service on galera master is up
            salt.commandStatus(pepperEnv, 'I@galera:master', 'service mysql status', 'running')

            salt.runSaltProcessStep(pepperEnv, 'I@galera:slave', 'service.start', ['mysql'], null, true)
            //

            node_count = 1
            for (t in control_target_hosts) {
                def target = t.split("\\.")[0]
                _pillar = salt.getPillar(pepperEnv, "${kvm01}", "salt:control:cluster:internal:node:ctl0${node_count}:provider")
                def nodeProvider = _pillar['return'][0].values()[0]
                salt.runSaltProcessStep(pepperEnv, "${nodeProvider}", 'virt.start', ["${target}.${domain}"], null, true)
                node_count++
            }
            node_count = 1
            for (t in proxy_target_hosts) {
                def target = t.split("\\.")[0]
                _pillar = salt.getPillar(pepperEnv, "${kvm01}", "salt:control:cluster:internal:node:prx0${node_count}:provider")
                def nodeProvider = _pillar['return'][0].values()[0]
                salt.runSaltProcessStep(pepperEnv, "${nodeProvider}", 'virt.start', ["${target}.${domain}"], null, true)
                node_count++
            }

            // salt 'cmp*' cmd.run 'service nova-compute restart'
            salt.runSaltProcessStep(pepperEnv, 'I@nova:compute', 'service.restart', ['nova-compute'], null, true)

            for (t in control_target_hosts) {
                def target = t.split("\\.")[0]
                salt.minionPresent(pepperEnv, 'I@salt:master', "${target}")
            }
            for (t in proxy_target_hosts) {
                def target = t.split("\\.")[0]
                salt.minionPresent(pepperEnv, 'I@salt:master', "${target}")
            }

            salt.runSaltProcessStep(pepperEnv, "${control_general_target}*", 'service.restart', ['nova-conductor'], null, true)
            salt.runSaltProcessStep(pepperEnv, "${control_general_target}*", 'service.restart', ['nova-scheduler'], null, true)

            salt.cmdRun(pepperEnv, "${control_general_target}01*", '. /root/keystonerc; nova service-list; glance image-list; nova flavor-list; nova hypervisor-list; nova list; neutron net-list; cinder list; heat service-list')
        }
    }
}
