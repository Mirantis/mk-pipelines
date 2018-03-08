/**
 * Update packages on given nodes
 *
 * Expected parameters:
 *   SALT_MASTER_CREDENTIALS            Credentials to the Salt API.
 *   SALT_MASTER_URL                    Full Salt API address [http://10.10.10.1:8000].
 *   STAGE_TEST_UPGRADE                 Run test upgrade stage (bool)
 *   STAGE_REAL_UPGRADE                 Run real upgrade stage (bool)
 *   STAGE_ROLLBACK_UPGRADE             Run rollback upgrade stage (bool)
 *   SKIP_VM_RELAUNCH                   Set to true if vms should not be recreated (bool)
 *   OPERATING_SYSTEM_RELEASE_UPGRADE   Set to true if operating system of vms should be upgraded to newer release (bool)
 *
**/

def common = new com.mirantis.mk.Common()
def salt = new com.mirantis.mk.Salt()
def python = new com.mirantis.mk.Python()

def stopServices(pepperEnv, probe, target, type) {
    def openstack = new com.mirantis.mk.Openstack()
    def services = []
    if (type == 'prx') {
        services.add('keepalived')
        services.add('nginx')
    } else if (type == 'ctl') {
        services.add('keepalived')
        services.add('haproxy')
        services.add('nova')
        services.add('cinder')
        services.add('glance')
        services.add('heat')
        services.add('neutron')
        services.add('apache2')
    }
    openstack.stopServices(pepperEnv, probe, target, services)
}

def retryStateRun(pepperEnv, target, state) {
    def common = new com.mirantis.mk.Common()
    def salt = new com.mirantis.mk.Salt()
    try {
        salt.enforceState(pepperEnv, target, state)
    } catch (Exception e) {
        common.warningMsg("running ${state} state again")
        salt.enforceState(pepperEnv, target, state)
    }
}

def stateRun(pepperEnv, target, state) {
    def common = new com.mirantis.mk.Common()
    def salt = new com.mirantis.mk.Salt()
    try {
        salt.enforceState(pepperEnv, target, state)
    } catch (Exception e) {
        common.warningMsg("Some parts of ${state} state failed. We should continue to run.")
    }
}


def vcpTestUpgrade(pepperEnv) {
    def common = new com.mirantis.mk.Common()
    def salt = new com.mirantis.mk.Salt()
    def test_upgrade_node = "upg01"
    salt.runSaltProcessStep(pepperEnv, 'I@salt:master', 'saltutil.refresh_pillar', [], null, true, 2)

    stateRun(pepperEnv, 'I@salt:master', 'linux.system.repo')
    stateRun(pepperEnv, 'I@salt:master', 'salt.master')
    stateRun(pepperEnv, 'I@salt:master', 'reclass')
    stateRun(pepperEnv, 'I@salt:master', 'linux.system.repo')

    try {
        salt.runSaltProcessStep(pepperEnv, '*', 'saltutil.refresh_pillar', [], null, true, 2)
    } catch (Exception e) {
        common.warningMsg("No response from some minions. We should continue to run")
    }

    try {
        salt.runSaltProcessStep(pepperEnv, '*', 'saltutil.sync_all', [], null, true, 2)
    } catch (Exception e) {
        common.warningMsg("No response from some minions. We should continue to run")
    }

    def domain = salt.getDomainName(pepperEnv)

    def backupninja_backup_host = salt.getReturnValues(salt.getPillar(pepperEnv, '( I@galera:master or I@galera:slave ) and I@backupninja:client', '_param:backupninja_backup_host'))

    if (SKIP_VM_RELAUNCH.toBoolean() == false) {

        def upgNodeProvider = salt.getNodeProvider(pepperEnv, test_upgrade_node)

        salt.runSaltProcessStep(pepperEnv, "${upgNodeProvider}", 'virt.destroy', ["${test_upgrade_node}.${domain}"])
        salt.runSaltProcessStep(pepperEnv, "${upgNodeProvider}", 'virt.undefine', ["${test_upgrade_node}.${domain}"])

        try {
            salt.cmdRun(pepperEnv, 'I@salt:master', "salt-key -d ${test_upgrade_node}.${domain} -y")
        } catch (Exception e) {
            common.warningMsg("${test_upgrade_node}.${domain} does not match any accepted, unaccepted or rejected keys. The key did not exist yet or was already removed. We should continue to run")
        }

        // salt 'kvm02*' state.sls salt.control
        salt.enforceState(pepperEnv, "${upgNodeProvider}", 'salt.control')
        // wait until upg node is registered in salt-key
        salt.minionPresent(pepperEnv, 'I@salt:master', test_upgrade_node)
        // salt '*' saltutil.refresh_pillar
        salt.runSaltProcessStep(pepperEnv, "${test_upgrade_node}*", 'saltutil.refresh_pillar', [])
        // salt '*' saltutil.sync_all
        salt.runSaltProcessStep(pepperEnv, "${test_upgrade_node}*", 'saltutil.sync_all', [])
    }

    stateRun(pepperEnv, "${test_upgrade_node}*", ['linux', 'openssh'])

    try {
        salt.runSaltProcessStep(master, "${test_upgrade_node}*", 'state.sls', ["salt.minion"], null, true, 60)
    } catch (Exception e) {
        common.warningMsg(e)
    }
    stateRun(pepperEnv, "${test_upgrade_node}*", ['ntp', 'rsyslog'])
    salt.enforceState(pepperEnv, "${test_upgrade_node}*", ['linux', 'openssh', 'salt.minion', 'ntp', 'rsyslog'])
    salt.enforceState(pepperEnv, "${test_upgrade_node}*", ['rabbitmq', 'memcached'])
    try {
        salt.enforceState(pepperEnv, '( I@galera:master or I@galera:slave ) and I@backupninja:client', ['openssh.client', 'salt.minion'])
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
    salt.enforceState(pepperEnv, '( I@galera:master or I@galera:slave ) and I@backupninja:client', 'backupninja')
    salt.runSaltProcessStep(pepperEnv, '( I@galera:master or I@galera:slave ) and I@backupninja:client', 'ssh.rm_known_host', ["root", "${backupninja_backup_host}"])
    try {
        salt.cmdRun(pepperEnv, '( I@galera:master or I@galera:slave ) and I@backupninja:client', "arp -d ${backupninja_backup_host}")
    } catch (Exception e) {
        common.warningMsg('The ARP entry does not exist. We should continue to run.')
    }
    salt.runSaltProcessStep(pepperEnv, '( I@galera:master or I@galera:slave ) and I@backupninja:client', 'ssh.set_known_host', ["root", "${backupninja_backup_host}"])
    salt.cmdRun(pepperEnv, '( I@galera:master or I@galera:slave ) and I@backupninja:client', 'backupninja -n --run /etc/backup.d/101.mysql')
    salt.cmdRun(pepperEnv, '( I@galera:master or I@galera:slave ) and I@backupninja:client', 'backupninja -n --run /etc/backup.d/200.backup.rsync > /tmp/backupninjalog')

    salt.enforceState(pepperEnv, 'I@xtrabackup:server', 'xtrabackup')
    salt.enforceState(pepperEnv, 'I@xtrabackup:client', 'openssh.client')
    salt.cmdRun(pepperEnv, 'I@xtrabackup:client', "su root -c 'salt-call state.sls xtrabackup'")
    salt.cmdRun(pepperEnv, 'I@xtrabackup:client', "su root -c '/usr/local/bin/innobackupex-runner.sh -f -s'")

    def databases = salt.cmdRun(pepperEnv, 'I@mysql:client','salt-call mysql.db_list | grep upgrade | awk \'/-/ {print \$2}\'')
    if(databases && databases != ""){
        def databasesList = salt.getReturnValues(databases).trim().tokenize("\n")
        for( i = 0; i < databasesList.size(); i++){
            if(databasesList[i].toLowerCase().contains('upgrade')){
                salt.runSaltProcessStep(pepperEnv, 'I@mysql:client', 'mysql.db_remove', ["${databasesList[i]}"])
                common.warningMsg("removing database ${databasesList[i]}")
                salt.runSaltProcessStep(pepperEnv, 'I@mysql:client', 'file.remove', ["/root/mysql/flags/${databasesList[i]}-installed"])
            }
        }
        salt.enforceState(pepperEnv, 'I@mysql:client', 'mysql.client')
    }else{
        common.errorMsg("No _upgrade databases were returned")
    }

    try {
        salt.enforceState(pepperEnv, "${test_upgrade_node}*", 'keystone.server')
        salt.runSaltProcessStep(pepperEnv, "${test_upgrade_node}*", 'service.restart', ['apache2'])
    } catch (Exception e) {
        common.warningMsg('Restarting Apache2')
        salt.runSaltProcessStep(pepperEnv, "${test_upgrade_node}*", 'service.restart', ['apache2'])
    }
    retryStateRun(pepperEnv, "${test_upgrade_node}*", 'keystone.client')
    retryStateRun(pepperEnv, "${test_upgrade_node}*", 'glance')
    salt.enforceState(pepperEnv, "${test_upgrade_node}*", 'keystone.server')

    retryStateRun(pepperEnv, "${test_upgrade_node}*", 'nova')
    retryStateRun(pepperEnv, "${test_upgrade_node}*", 'nova') // run nova state again as sometimes nova does not enforce itself for some reason
    retryStateRun(pepperEnv, "${test_upgrade_node}*", 'cinder')
    retryStateRun(pepperEnv, "${test_upgrade_node}*", 'neutron')
    retryStateRun(pepperEnv, "${test_upgrade_node}*", 'heat')

    salt.cmdRun(pepperEnv, "${test_upgrade_node}*", '. /root/keystonercv3; openstack service list; openstack image list; openstack flavor list; openstack compute service list; openstack server list; openstack network list; openstack volume list; openstack orchestration service list')

    if (STAGE_TEST_UPGRADE.toBoolean() == true && STAGE_REAL_UPGRADE.toBoolean() == true) {
        stage('Ask for manual confirmation') {
            input message: "Do you want to continue with upgrade?"
        }
    }
}


def vcpRealUpgrade(pepperEnv) {
    def common = new com.mirantis.mk.Common()
    def salt = new com.mirantis.mk.Salt()
    def openstack = new com.mirantis.mk.Openstack()
    def virsh = new com.mirantis.mk.Virsh()

    def upgrade_target = []
    upgrade_target.add('I@horizon:server')
    upgrade_target.add('I@keystone:server and not upg*')

    def proxy_general_target = "I@horizon:server"
    def control_general_target = "I@keystone:server and not upg*"
    def upgrade_general_target = "( I@keystone:server and not upg* ) or I@horizon:server"

    def snapshotName = "upgradeSnapshot1"

    def domain = salt.getDomainName(pepperEnv)
    def errorOccured = false

    for (tgt in upgrade_target) {
        def target_hosts = salt.getMinionsSorted(pepperEnv, "${tgt}")
        def node = salt.getFirstMinion(pepperEnv, "${tgt}")
        def general_target = ""

        if (tgt.toString().contains('horizon:server')) {
            general_target = 'prx'
        } else if (tgt.toString().contains('keystone:server')) {
            general_target = 'ctl'
        }

        if (OPERATING_SYSTEM_RELEASE_UPGRADE.toBoolean() == false) {
            stopServices(pepperEnv, node, tgt, general_target)
        }

        def node_count = 1
        for (t in target_hosts) {
            def target = salt.stripDomainName(t)
            def nodeProvider = salt.getNodeProvider(pepperEnv, "${general_target}0${node_count}")
            if ((OPERATING_SYSTEM_RELEASE_UPGRADE.toBoolean() == true) && (SKIP_VM_RELAUNCH.toBoolean() == false)) {
                salt.runSaltProcessStep(pepperEnv, "${nodeProvider}", 'virt.destroy', ["${target}.${domain}"])
                sleep(2)
                try {
                    salt.cmdRun(pepperEnv, "${nodeProvider}", "[ ! -f /root/${target}.${domain}.qcow2.bak ] && cp /var/lib/libvirt/images/${target}.${domain}/system.qcow2 ./${target}.${domain}.qcow2.bak")
                } catch (Exception e) {
                    common.warningMsg('File already exists')
                }
                salt.runSaltProcessStep(pepperEnv, "${nodeProvider}", 'virt.undefine', ["${target}.${domain}"])
                try {
                    salt.cmdRun(pepperEnv, 'I@salt:master', "salt-key -d ${target}.${domain} -y")
                } catch (Exception e) {
                    common.warningMsg('does not match any accepted, unaccepted or rejected keys. They were probably already removed. We should continue to run')
                }
            } else if (OPERATING_SYSTEM_RELEASE_UPGRADE.toBoolean() == false) {
                virsh.liveSnapshotPresent(pepperEnv, nodeProvider, target, snapshotName)
            }
            node_count++
        }
    }

    if ((OPERATING_SYSTEM_RELEASE_UPGRADE.toBoolean() == true) && (SKIP_VM_RELAUNCH.toBoolean() == false)) {
        salt.cmdRun(pepperEnv, 'I@xtrabackup:client', "su root -c '/usr/local/bin/innobackupex-runner.sh -f -s'")

        salt.enforceState(pepperEnv, 'I@salt:control', 'salt.control')

        for (tgt in upgrade_target) {
            salt.minionsPresent(pepperEnv, 'I@salt:master', tgt)
        }
    }

    // salt '*' saltutil.refresh_pillar
    salt.runSaltProcessStep(pepperEnv, upgrade_general_target, 'saltutil.refresh_pillar', [])
    // salt '*' saltutil.sync_all
    salt.runSaltProcessStep(pepperEnv, upgrade_general_target, 'saltutil.sync_all', [])

    if (OPERATING_SYSTEM_RELEASE_UPGRADE.toBoolean() == false) {

        try {
            salt.enforceState(pepperEnv, upgrade_general_target, ['linux.system.repo'])
        } catch (Exception e) {
            common.warningMsg(e)
        }

        salt.runSaltProcessStep(pepperEnv, upgrade_general_target, 'pkg.install', ['salt-minion'], null, true, 5)
        salt.minionsReachable(pepperEnv, 'I@salt:master', upgrade_general_target)

        // Apply package upgrades
        args = 'export DEBIAN_FRONTEND=noninteractive; apt-get -y -q --allow-downgrades --allow-unauthenticated -o Dpkg::Options::=\"--force-confdef\" -o Dpkg::Options::=\"--force-confold\" dist-upgrade;'
        common.warningMsg("Running apt dist-upgrade on ${proxy_general_target} and ${control_general_target}, this might take a while...")
        out = salt.runSaltProcessStep(pepperEnv, upgrade_general_target, 'cmd.run', [args])
        // stop services again
        def proxy_node = salt.getFirstMinion(pepperEnv, proxy_general_target)
        def control_node = salt.getFirstMinion(pepperEnv, control_general_target)
        stopServices(pepperEnv, proxy_node, proxy_general_target, 'prx')
        stopServices(pepperEnv, control_node, control_general_target, 'ctl')
        salt.printSaltCommandResult(out)
        if (out.toString().contains("dpkg returned an error code")) {
            input message: "Apt dist-upgrade failed, please fix it manually and then click on proceed. If unable to fix it, click on abort and run the rollback stage."
        }
        // run base states
        try {
            salt.enforceState(pepperEnv, upgrade_general_target, ['linux', 'openssh', 'salt.minion', 'ntp', 'rsyslog'])
        } catch (Exception e) {
            common.warningMsg(e)
        }
        salt.enforceState(pepperEnv, control_general_target, ['keepalived', 'haproxy'])
    } else {
        // initial VM setup
        try {
            salt.enforceState(pepperEnv, upgrade_general_target, ['linux', 'openssh'])
        } catch (Exception e) {
            common.warningMsg(e)
        }
        try {
            salt.runSaltProcessStep(master, upgrade_general_target, 'state.sls', ["salt.minion"], null, true, 60)
        } catch (Exception e) {
            common.warningMsg(e)
        }
        try {
            salt.enforceState(pepperEnv, upgrade_general_target, ['ntp', 'rsyslog'])
        } catch (Exception e) {
            common.warningMsg(e)
        }
        salt.enforceState(pepperEnv, upgrade_general_target, ['linux', 'openssh', 'salt.minion', 'ntp', 'rsyslog'])
        salt.enforceState(pepperEnv, control_general_target, ['keepalived', 'haproxy'])
        salt.runSaltProcessStep(pepperEnv, control_general_target, 'service.restart', ['rsyslog'])
    }

    try {
        try {
            salt.enforceState(pepperEnv, control_general_target, ['memcached', 'keystone.server'])
            salt.runSaltProcessStep(pepperEnv, control_general_target, 'service.restart', ['apache2'])
        } catch (Exception e) {
            common.warningMsg('Restarting Apache2 and enforcing keystone.server state again')
            salt.runSaltProcessStep(pepperEnv, control_general_target, 'service.restart', ['apache2'])
            salt.enforceState(pepperEnv, control_general_target, 'keystone.server')
        }
        // salt 'ctl01*' state.sls keystone.client
        retryStateRun(pepperEnv, "I@keystone:client and ${control_general_target}", 'keystone.client')
        retryStateRun(pepperEnv, control_general_target, 'glance')
        salt.enforceState(pepperEnv, control_general_target, 'glusterfs.client')
        salt.enforceState(pepperEnv, control_general_target, 'keystone.server')
        retryStateRun(pepperEnv, control_general_target, 'nova')
        retryStateRun(pepperEnv, control_general_target, 'cinder')
        retryStateRun(pepperEnv, control_general_target, 'neutron')
        retryStateRun(pepperEnv, control_general_target, 'heat')
    } catch (Exception e) {
        errorOccured = true
        if (OPERATING_SYSTEM_RELEASE_UPGRADE.toBoolean() == false) {
            input message: "Some states that require syncdb failed. Please check the reason.Click proceed only if you want to restore database into it's pre-upgrade state. If you want restore production database and also the VMs into its pre-upgrade state please click on abort and run the rollback stage."
        } else {
            input message: "Some states that require syncdb failed. Please check the reason and click proceed only if you want to restore database into it's pre-upgrade state. Otherwise, click abort."
        }
        openstack.restoreGaleraDb(pepperEnv)
        common.errorMsg("Stage Real control upgrade failed")
    }
    if(!errorOccured){

        if (OPERATING_SYSTEM_RELEASE_UPGRADE.toBoolean() == true) {

            try {
                if (salt.testTarget(pepperEnv, "I@ceph:client and ${control_general_target}*")) {
                    salt.enforceState(pepperEnv, "I@ceph:client and ${control_general_target}*", 'ceph.client')
                }
            } catch (Exception er) {
                common.warningMsg("Ceph client state on controllers failed. Please fix it manually")
            }
            try {
                if (salt.testTarget(pepperEnv, "I@ceph:common and ${control_general_target}*")) {
                    salt.enforceState(pepperEnv, "I@ceph:common and ${control_general_target}*", ['ceph.common', 'ceph.setup.keyring'])
                }
            } catch (Exception er) {
                common.warningMsg("Ceph common state on controllers failed. Please fix it manually")
            }
            try {
                if (salt.testTarget(pepperEnv, "I@ceph:common and ${control_general_target}*")) {
                    salt.runSaltProcessStep(master, "I@ceph:common and ${control_general_target}*", 'service.restart', ['glance-api', 'glance-glare', 'glance-registry'])
                }
            } catch (Exception er) {
                common.warningMsg("Restarting Glance services on controllers failed. Please fix it manually")
            }
        }

        // salt 'cmp*' cmd.run 'service nova-compute restart'
        salt.runSaltProcessStep(pepperEnv, 'I@nova:compute', 'service.restart', ['nova-compute'])
        salt.runSaltProcessStep(pepperEnv, control_general_target, 'service.restart', ['nova-conductor'])
        salt.runSaltProcessStep(pepperEnv, control_general_target, 'service.restart', ['nova-scheduler'])

        retryStateRun(pepperEnv, proxy_general_target, 'keepalived')
        retryStateRun(pepperEnv, proxy_general_target, 'horizon')
        retryStateRun(pepperEnv, proxy_general_target, 'nginx')
        retryStateRun(pepperEnv, proxy_general_target, 'memcached')

        try {
            salt.enforceHighstate(pepperEnv, control_general_target)
        } catch (Exception er) {
            common.errorMsg("Highstate was executed on controller nodes but something failed. Please check it and fix it accordingly.")
        }

        try {
            salt.enforceHighstate(pepperEnv, proxy_general_target)
        } catch (Exception er) {
            common.errorMsg("Highstate was executed on proxy nodes but something failed. Please check it and fix it accordingly.")
        }

        try {
            salt.cmdRun(pepperEnv, "${control_general_target}01*", '. /root/keystonercv3; openstack service list; openstack image list; openstack flavor list; openstack compute service list; openstack server list; openstack network list; openstack volume list; openstack orchestration service list')
        } catch (Exception er) {
            common.errorMsg(er)
        }

        /*
        if (OPERATING_SYSTEM_RELEASE_UPGRADE.toBoolean() == false) {
            input message: "Please verify if the control upgrade was successful! If so, by clicking proceed the original VMs disk images will be backed up and snapshot will be merged to the upgraded VMs which will finalize the upgrade procedure"
            node_count = 1
            for (t in proxy_target_hosts) {
                def target = salt.stripDomainName(t)
                def nodeProvider = salt.getNodeProvider(pepperEnv, "${general_target}0${node_count}")
                try {
                    salt.cmdRun(pepperEnv, "${nodeProvider}", "[ ! -f /root/${target}.${domain}.qcow2.bak ] && cp /var/lib/libvirt/images/${target}.${domain}/system.qcow2 ./${target}.${domain}.qcow2.bak")
                } catch (Exception e) {
                    common.warningMsg('File already exists')
                }
                virsh.liveSnapshotMerge(pepperEnv, nodeProvider, target, snapshotName)
                node_count++
            }
            node_count = 1
            for (t in control_target_hosts) {
                def target = salt.stripDomainName(t)
                def nodeProvider = salt.getNodeProvider(pepperEnv, "${general_target}0${node_count}")
                try {
                    salt.cmdRun(pepperEnv, "${nodeProvider}", "[ ! -f /root/${target}.${domain}.qcow2.bak ] && cp /var/lib/libvirt/images/${target}.${domain}/system.qcow2 ./${target}.${domain}.qcow2.bak")
                } catch (Exception e) {
                    common.warningMsg('File already exists')
                }
                virsh.liveSnapshotMerge(pepperEnv, nodeProvider, target, snapshotName)
                node_count++
            }
            input message: "Please scroll up and look for red highlighted messages containing 'virsh blockcommit' string.
            If there are any fix it manually.  Otherwise click on proceed."
        }
        */
    }
}


def vcpRollback(pepperEnv) {
    def common = new com.mirantis.mk.Common()
    def salt = new com.mirantis.mk.Salt()
    def openstack = new com.mirantis.mk.Openstack()
    def virsh = new com.mirantis.mk.Virsh()
    def snapshotName = "upgradeSnapshot1"
    try {
        salt.runSaltProcessStep(pepperEnv, '*', 'saltutil.refresh_pillar', [], null, true, 2)
    } catch (Exception e) {
        common.warningMsg("No response from some minions. We should continue to run")
    }

    def domain = salt.getDomainName(pepperEnv)

    def rollback_target = []
    rollback_target.add('I@horizon:server')
    rollback_target.add('I@keystone:server and not upg*')

    def control_general_target = "I@keystone:server and not upg*"
    def upgrade_general_target = "( I@keystone:server and not upg* ) or I@horizon:server"

    openstack.restoreGaleraDb(pepperEnv)

    for (tgt in rollback_target) {
        def target_hosts = salt.getMinionsSorted(pepperEnv, "${tgt}")
        def node = salt.getFirstMinion(pepperEnv, "${tgt}")
        def general_target = salt.getMinionsGeneralName(pepperEnv, "${tgt}")

        if (tgt.toString().contains('horizon:server')) {
            general_target = 'prx'
        } else if (tgt.toString().contains('keystone:server')) {
            general_target = 'ctl'
        }

        def node_count = 1
        for (t in target_hosts) {
            def target = salt.stripDomainName(t)
            def nodeProvider = salt.getNodeProvider(pepperEnv, "${general_target}0${node_count}")
            salt.runSaltProcessStep(pepperEnv, "${nodeProvider}", 'virt.destroy', ["${target}.${domain}"])
            sleep(2)
            if (OPERATING_SYSTEM_RELEASE_UPGRADE.toBoolean() == true) {
                salt.runSaltProcessStep(pepperEnv, "${nodeProvider}", 'file.copy', ["/root/${target}.${domain}.qcow2.bak", "/var/lib/libvirt/images/${target}.${domain}/system.qcow2"])
                try {
                    salt.cmdRun(pepperEnv, 'I@salt:master', "salt-key -d ${target}.${domain} -y")
                } catch (Exception e) {
                    common.warningMsg('does not match any accepted, unaccepted or rejected keys. They were probably already removed. We should continue to run')
                }
                salt.runSaltProcessStep(pepperEnv, "${nodeProvider}", 'virt.start', ["${target}.${domain}"])
            } else {
                salt.cmdRun(pepperEnv, "${nodeProvider}", "virsh define /var/lib/libvirt/images/${target}.${domain}.xml")
                salt.runSaltProcessStep(pepperEnv, "${nodeProvider}", 'virt.start', ["${target}.${domain}"])
                virsh.liveSnapshotAbsent(pepperEnv, nodeProvider, target, snapshotName)
            }
            node_count++
        }
    }

    // salt 'cmp*' cmd.run 'service nova-compute restart'
    salt.runSaltProcessStep(pepperEnv, 'I@nova:compute', 'service.restart', ['nova-compute'])

    if (OPERATING_SYSTEM_RELEASE_UPGRADE.toBoolean() == true) {
        for (tgt in rollback_target) {
            salt.minionsPresent(pepperEnv, 'I@salt:master', tgt)
        }
    }

    salt.minionsReachable(pepperEnv, 'I@salt:master', upgrade_general_target)

    salt.runSaltProcessStep(pepperEnv, control_general_target, 'service.restart', ['nova-conductor'])
    salt.runSaltProcessStep(pepperEnv, control_general_target, 'service.restart', ['nova-scheduler'])

    def control_node = salt.getFirstMinion(pepperEnv, control_general_target)

    salt.cmdRun(pepperEnv, "${control_node}*", '. /root/keystonerc; nova service-list; glance image-list; nova flavor-list; nova hypervisor-list; nova list; neutron net-list; cinder list; heat service-list')
}


def pepperEnv = "pepperEnv"
timeout(time: 12, unit: 'HOURS') {
    node() {

        stage('Setup virtualenv for Pepper') {
                python.setupPepperVirtualenv(pepperEnv, SALT_MASTER_URL, SALT_MASTER_CREDENTIALS)
        }

        if (STAGE_TEST_UPGRADE.toBoolean() == true) {
            stage('Test upgrade') {
                vcpTestUpgrade(pepperEnv)
            }
        }

        if (STAGE_REAL_UPGRADE.toBoolean() == true) {
            stage('Real upgrade') {
                // # actual upgrade
                vcpRealUpgrade(pepperEnv)
            }

            if (STAGE_REAL_UPGRADE.toBoolean() == true && STAGE_ROLLBACK_UPGRADE.toBoolean() == true) {
                stage('Ask for manual confirmation') {
                    input message: "Please verify if the control upgrade was successful. If it did not succeed, in the worst scenario, you can click on proceed to continue with control-upgrade-rollback. Do you want to continue with the rollback?"
                }
            }
        }

        if (STAGE_ROLLBACK_UPGRADE.toBoolean() == true) {
            stage('Rollback upgrade') {
                stage('Ask for manual confirmation') {
                    input message: "Before rollback please check the documentation for reclass model changes. Do you really want to continue with the rollback?"
                }
                vcpRollback(pepperEnv)
            }
        }
    }
}