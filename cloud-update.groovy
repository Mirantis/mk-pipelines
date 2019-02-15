/**
 * Update packages on given nodes
 *
 * Expected parameters:
 *   SALT_MASTER_CREDENTIALS    Credentials to the Salt API.
 *   SALT_MASTER_URL            Full Salt API address [https://10.10.10.1:8000].
 *   SNAPSHOT_NAME              Snapshot name
 *   CFG_NODE_PROVIDER          Physical machine name hosting Salt-Master VM (ex. kvm01*)
 *   INTERACTIVE                Ask interactive questions during pipeline run (bool)
 *   PER_NODE                   Target nodes will be managed one by one (bool)
 *   ROLLBACK_BY_REDEPLOY       Omit taking live snapshots. Rollback is planned to be done by redeployment (bool)
 *   STOP_SERVICES              Stop API services before update (bool)
 *   TARGET_KERNEL_UPDATES      Comma separated list of nodes to update kernel if newer version is available (Valid values are cfg,msg,dbs,log,mon,mtr,ntw,nal,cmn,rgw,cid,kvm,osd)
 *   TARGET_REBOOT              Comma separated list of nodes to reboot after update or physical machine rollback (Valid values are cfg,msg,dbs,log,mon,mtr,ntw,nal,cmn,rgw,cid,kvm,osd)
 *   TARGET_HIGHSTATE           Comma separated list of nodes to run Salt Highstate on after update or physical machine rollback (Valid values are cfg,msg,dbs,log,mon,mtr,ntw,nal,cmn,rgw,cid,kvm,osd)
 *   TARGET_UPDATES             Comma separated list of nodes to update (Valid values are cfg,msg,dbs,log,mon,mtr,ntw,nal,cmn,rgw,cid,kvm,osd)
 *   TARGET_ROLLBACKS           Comma separated list of nodes to rollback (Valid values are msg,dbs,log,mon,mtr,ntw,nal,cmn,rgw,kvm,osd)
 *   TARGET_SNAPSHOT_MERGES     Comma separated list of nodes to merge live snapshot for (Valid values are cfg,msg,dbs,log,mon,mtr,ntw,nal,cmn,rgw,cid)
 *   MSG_TARGET                 Salt targeted MSG nodes (ex. msg*)
 *   DBS_TARGET                 Salt targeted DBS nodes (ex. dbs*)
 *   LOG_TARGET                 Salt targeted LOG nodes (ex. log*)
 *   MON_TARGET                 Salt targeted MON nodes (ex. mon*)
 *   MTR_TARGET                 Salt targeted MTR nodes (ex. mtr*)
 *   NTW_TARGET                 Salt targeted NTW nodes (ex. ntw*)
 *   NAL_TARGET                 Salt targeted NAL nodes (ex. nal*)
 *   CMN_TARGET                 Salt targeted CMN nodes (ex. cmn*)
 *   RGW_TARGET                 Salt targeted RGW nodes (ex. rgw*)
 *   CID_TARGET                 Salt targeted CID nodes (ex. cid*)
 *   KVM_TARGET                 Salt targeted physical KVM nodes (ex. kvm01*)
 *   CEPH_OSD_TARGET            Salt targeted physical Ceph OSD nodes (ex. osd001*)
 *   ROLLBACK_PKG_VERSIONS      Space separated list of pkgs=versions to rollback to on physical targeted machines (ex. pkg_name1=pkg_version1 pkg_name2=pkg_version2)
 *   PURGE_PKGS                 Space separated list of pkgs=versions to be purged on physical targeted machines (ex. pkg_name1=pkg_version1 pkg_name2=pkg_version2)
 *   REMOVE_PKGS                Space separated list of pkgs=versions to be removed on physical targeted machines (ex. pkg_name1=pkg_version1 pkg_name2=pkg_version2)
 *   RESTORE_GALERA             Restore Galera DB (bool)
 *   RESTORE_CONTRAIL_DB        Restore Cassandra and Zookeeper DBs for OpenContrail (bool)
 *   RUN_CVP_TESTS              Run cloud validation pipelines before and after upgrade
 *   MINIONS_TEST_TIMEOUT       Time in seconds for a Salt result to receive a response when calling a minionsReachable method.
 *
**/
def common = new com.mirantis.mk.Common()
def orchestrate = new com.mirantis.mk.Orchestrate()
def salt = new com.mirantis.mk.Salt()
def python = new com.mirantis.mk.Python()
def virsh = new com.mirantis.mk.Virsh()

def updates = TARGET_UPDATES.tokenize(",").collect{it -> it.trim()}
def rollbacks = TARGET_ROLLBACKS.tokenize(",").collect{it -> it.trim()}
def merges = TARGET_SNAPSHOT_MERGES.tokenize(",").collect{it -> it.trim()}
def reboots = TARGET_REBOOT.tokenize(",").collect{it -> it.trim()}

def pepperEnv = "pepperEnv"
def minions
def result
def packages
def command
def commandKwargs

wait = 10
if (common.validInputParam('MINIONS_TEST_TIMEOUT') && MINIONS_TEST_TIMEOUT.isInteger()) {
    wait = "${MINIONS_TEST_TIMEOUT}".toInteger()
}

def updatePkgs(pepperEnv, target, targetType="", targetPackages="") {
    def salt = new com.mirantis.mk.Salt()
    def common = new com.mirantis.mk.Common()
    def kernelUpdates = TARGET_KERNEL_UPDATES.tokenize(",").collect{it -> it.trim()}
    def distUpgrade = false
    def commandKwargs
    def pkgs
    def out

    salt.enforceState(pepperEnv, target, 'linux.system.repo')

    stage("List package upgrades") {
        common.infoMsg("Listing all the packages that have a new update available on ${target}")
        if (kernelUpdates.contains(targetType)) {
            pkgs = salt.getReturnValues(salt.runSaltProcessStep(pepperEnv, target, 'pkg.list_upgrades', [], null, true))
        } else {
            pkgs = salt.getReturnValues(salt.runSaltProcessStep(pepperEnv, target, 'pkg.list_upgrades', ['dist_upgrade=False'], null, true))
        }
        if(targetPackages != "" && targetPackages != "*"){
            common.infoMsg("Note that only the ${targetPackages} would be installed from the above list of available updates on the ${target}")
        }
    }

    if (INTERACTIVE.toBoolean()) {
        stage("Confirm live package upgrades on ${target}") {
            if (targetPackages=="") {
                def userInput = input(
                 id: 'userInput', message: 'Insert package names for update', parameters: [
                 [$class: 'TextParameterDefinition', defaultValue: pkgs.keySet().join(",").toString(), description: 'Package names (or *)', name: 'packages']
                ])
                if (userInput!= "" && userInput!= "*") {
                    targetPackages = userInput
                }
            } else {
                input message: "Approve live package upgrades on ${target} nodes?"
            }
        }
    } else {
        targetPackages = pkgs.keySet().join(",").toString()
    }

    if (targetPackages != "") {
        // list installed versions of pkgs that will be upgraded
        if (targetType == 'kvm' || targetType == 'osd') {
            def installedPkgs = []
            def newPkgs = []
            def targetPkgList = targetPackages.tokenize(',')
            for (pkg in targetPkgList) {
                def version
                try {
                    def pkgsDetails = salt.getReturnValues(salt.runSaltProcessStep(pepperEnv, target, 'pkg.info_installed', [pkg], null, true))
                    version = pkgsDetails.get(pkg).get('version')
                } catch (Exception er) {
                    common.infoMsg("${pkg} not installed yet")
                }
                if (version?.trim()) {
                    installedPkgs.add(pkg + '=' + version)
                } else {
                    newPkgs.add(pkg)
                }
            }
            common.warningMsg("the following list of pkgs will be upgraded")
            common.warningMsg(installedPkgs.join(" "))
            common.warningMsg("the following list of pkgs will be newly installed")
            common.warningMsg(newPkgs.join(" "))
        }
        // set variables
        command = "pkg.install"
        packages = targetPackages
        commandKwargs = ['only_upgrade': 'true','force_yes': 'true']

    }else {
        command = "pkg.upgrade"
        if (kernelUpdates.contains(targetType)) {
            commandKwargs = ['dist_upgrade': 'true']
            distUpgrade = true
        }
        packages = null
    }

    stage("stop services on ${target}") {
        if ((STOP_SERVICES.toBoolean()) && (targetType != 'cid')) {
            if (targetType == 'ntw' || targetType == 'nal') {
                contrailServices(pepperEnv, target, 'stop')
            } else {
                def probe = salt.getFirstMinion(pepperEnv, "${target}")
                services(pepperEnv, probe, target, 'stop')
            }
        }
    }

    stage('Apply package upgrades') {
        // salt master pkg
        if (targetType == 'cfg') {
            common.warningMsg('salt-master pkg upgrade, rerun the pipeline if disconnected')
            salt.runSaltProcessStep(pepperEnv, target, 'pkg.install', ['salt-master'], null, true, 5)
            salt.minionsReachable(pepperEnv, 'I@salt:master', '*', null, wait)
        }
        // salt minion pkg
        salt.runSaltProcessStep(pepperEnv, target, 'pkg.install', ['salt-minion'], null, true, 5)
        salt.minionsReachable(pepperEnv, 'I@salt:master', target, null, wait)
        common.infoMsg('Performing pkg upgrades ... ')
        common.retry(3){
            out = salt.runSaltCommand(pepperEnv, 'local', ['expression': target, 'type': 'compound'], command, true, packages, commandKwargs)
            salt.printSaltCommandResult(out)
        }
        def osRelease = salt.getGrain(pepperEnv, target, 'lsb_distrib_codename')
        if (osRelease.toString().toLowerCase().contains('trusty')) {
            args = 'export DEBIAN_FRONTEND=noninteractive; apt-get -y -q --force-yes -o Dpkg::Options::=\"--force-confold\" '
        } else {
            args = 'export DEBIAN_FRONTEND=noninteractive; apt-get -y -q -f --allow-downgrades -o Dpkg::Options::=\"--force-confold\" '
        }
        if (out.toString().contains('errors:')) {
            try {
                if (packages?.trim()) {
                    packages = packages.replaceAll(',', ' ')
                    common.retry(3){
                        out = salt.runSaltProcessStep(pepperEnv, target, 'cmd.run', [args + ' install ' + packages])
                    }
                } else {
                    if (distUpgrade) {
                        common.retry(3){
                            out = salt.runSaltProcessStep(pepperEnv, target, 'cmd.run', [args + ' dist-upgrade'])
                        }
                    } else {
                        common.retry(3){
                            out = salt.runSaltProcessStep(pepperEnv, target, 'cmd.run', [args + ' upgrade'])
                        }
                    }                    }
                if (out.toString().contains('E: ')) {
                    common.errorMsg(out)
                    if (INTERACTIVE.toBoolean()) {
                        input message: "Pkgs update failed to be updated on ${target}. Please fix it manually."
                    } else {
                        salt.printSaltCommandResult(out)
                        throw new Exception("Pkgs update failed")
                    }
                }
            } catch (Exception e) {
                common.errorMsg(out)
                common.errorMsg(e)
                if (INTERACTIVE.toBoolean()) {
                    input message: "Pkgs update failed to be updated on ${target}. Please fix it manually."
                } else {
                    throw new Exception("Pkgs update failed")
                }
            }
        }
    }
}

def rollbackPkgs(pepperEnv, target, targetType = "", targetPackages="") {
    def salt = new com.mirantis.mk.Salt()
    def common = new com.mirantis.mk.Common()
    def probe = salt.getFirstMinion(pepperEnv, "${target}")
    def distUpgrade
    def pkgs
    def out

    salt.enforceState(pepperEnv, target, 'linux.system.repo')

    if (ROLLBACK_PKG_VERSIONS == "") {
        stage("List package upgrades") {
            common.infoMsg("Listing all the packages that have a new update available on ${target}")
            pkgs = salt.getReturnValues(salt.runSaltProcessStep(pepperEnv, target, 'pkg.list_upgrades', [], null, true))
            if(targetPackages != "" && targetPackages != "*"){
                common.infoMsg("Note that only the ${targetPackages} would be installed from the above list of available updates on the ${target}")
            }
        }

        if (INTERACTIVE.toBoolean()) {
            stage("Confirm live package upgrades on ${target}") {
                if(targetPackages==""){
                    timeout(time: 2, unit: 'HOURS') {
                        def userInput = input(
                         id: 'userInput', message: 'Insert package names for update', parameters: [
                         [$class: 'TextParameterDefinition', defaultValue: pkgs.keySet().join(",").toString(), description: 'Package names (or *)', name: 'packages']
                        ])
                        if(userInput!= "" && userInput!= "*"){
                            targetPackages = userInput
                        }
                    }
                }else{
                    timeout(time: 2, unit: 'HOURS') {
                       input message: "Approve live package upgrades on ${target} nodes?"
                    }
                }
            }
        } else {
            targetPackages = pkgs.keySet().join(",").toString()
        }
    } else {
        targetPackages = ROLLBACK_PKG_VERSIONS
    }

    if (targetPackages != "") {
        // set variables
        packages = targetPackages
    } else {
        distUpgrade = true
        packages = null
    }

    stage("stop services on ${target}") {
        try {
            if (INTERACTIVE.toBoolean()) {
                input message: "Click PROCEED to interactively stop services on ${target}. Otherwise click ABORT to skip stopping them and continue."
            }
        } catch (Exception er) {
            common.infoMsg('skipping stopping services')
            return
        }
        if (STOP_SERVICES.toBoolean()) {
            services(pepperEnv, probe, target, 'stop')
        }
    }

    stage('Apply package downgrades') {
        args = 'export DEBIAN_FRONTEND=noninteractive; apt-get -y -q --allow-downgrades -o Dpkg::Options::=\"--force-confold\" '
        common.infoMsg('Performing pkgs purge/remove ... ')
        try {
            if (PURGE_PKGS != "") {
                def purgePackages = PURGE_PKGS.replaceAll(',', ' ')
                common.retry(3){
                    out = salt.runSaltProcessStep(pepperEnv, target, 'cmd.run', [args + ' purge ' + purgePackages])
                }
            }
            if (REMOVE_PKGS != "") {
                def removePackages = REMOVE_PKGS.replaceAll(',', ' ')
                common.retry(3){
                    out = salt.runSaltProcessStep(pepperEnv, target, 'cmd.run', [args + ' remove ' + removePackages])
                }
            }
            if (out.toString().contains('E: ')) {
                common.errorMsg(out)
                if (INTERACTIVE.toBoolean()) {
                    input message: "Pkgs ${packages} purge failed on ${target}. Please fix it manually."
                } else {
                    salt.printSaltCommandResult(out)
                    throw new Exception("Pkgs {packages} purge failed")
                }
            }
        } catch (Exception e) {
            common.errorMsg(out)
            common.errorMsg(e)
            if (INTERACTIVE.toBoolean()) {
                input message: "Pkgs {packages} purge on ${target}. Please fix it manually."
            } else {
                throw new Exception("Pkgs {packages} purge failed")
            }
        }

        common.infoMsg('Performing pkg downgrades ... ')
        try {
            packages = packages.replaceAll(',', ' ')
            if (packages?.trim()) {
                packages = packages.replaceAll(',', ' ')
                common.retry(3){
                    out = salt.runSaltProcessStep(pepperEnv, target, 'cmd.run', [args + ' install salt-minion'], null, true, 5)
                }
                salt.minionsReachable(pepperEnv, 'I@salt:master', target, null, wait)
                common.retry(3){
                    out = salt.runSaltProcessStep(pepperEnv, target, 'cmd.run', [args + ' install ' + packages])
                }
            } else {
                if (distUpgrade) {
                    common.retry(3){
                        out = salt.runSaltProcessStep(pepperEnv, target, 'cmd.run', [args + ' dist-upgrade'])
                    }
                } else {
                    common.retry(3){
                        out = salt.runSaltProcessStep(pepperEnv, target, 'cmd.run', [args + ' upgrade'])
                    }
                }
            }
            if (out.toString().contains('E: ')) {
                common.errorMsg(out)
                if (INTERACTIVE.toBoolean()) {
                    input message: "Pkgs rollback failed on ${target}. Please fix it manually."
                } else {
                    salt.printSaltCommandResult(out)
                    throw new Exception("Pkgs rollback failed")
                }
            }
        } catch (Exception e) {
            common.errorMsg(out)
            common.errorMsg(e)
            if (INTERACTIVE.toBoolean()) {
                input message: "Pkgs rollback failed on ${target}. Please fix it manually."
            } else {
                throw new Exception("Pkgs rollback failed")
            }
        }
    }
}

def getNodeProvider(pepperEnv, nodeName, type='') {
    def salt = new com.mirantis.mk.Salt()
    def common = new com.mirantis.mk.Common()
    def kvms = salt.getMinions(pepperEnv, 'I@salt:control')
    for (kvm in kvms) {
        try {
            vms = salt.getReturnValues(salt.runSaltProcessStep(pepperEnv, kvm, 'virt.list_domains', [], null, true))
            if (vms.toString().contains(nodeName)) {
                if (type == 'master' && !CFG_NODE_PROVIDER?.trim()) {
                    CFG_NODE_PROVIDER = kvm
                } else {
                    return kvm
                    //break
                }
            }
        } catch (Exception er) {
            common.infoMsg("${nodeName} not present on ${kvm}")
        }
    }
}

def services(pepperEnv, probe, target, action='stop') {
    def services = ["keepalived","haproxy","nginx","nova-api","cinder","glance","heat","neutron","apache2","rabbitmq-server"]
    if (action == 'stop') {
        def openstack = new com.mirantis.mk.Openstack()
        openstack.stopServices(pepperEnv, probe, target, services, INTERACTIVE.toBoolean())
    } else {
        def salt = new com.mirantis.mk.Salt()
        for (s in services) {
            def outputServicesStr = salt.getReturnValues(salt.cmdRun(pepperEnv, probe, "service --status-all | grep ${s} | awk \'{print \$4}\'"))
            def servicesList = outputServicesStr.tokenize("\n").init() //init() returns the items from the Iterable excluding the last item
            if (servicesList) {
                for (name in servicesList) {
                    if (!name.contains('Salt command')) {
                        salt.runSaltProcessStep(pepperEnv, "${target}*", 'service.start', ["${name}"])
                    }
                }
            }
        }
    }
}

// must be treated separately due to OC on Trusty
def contrailServices(pepperEnv, target, action='stop') {
    def salt = new com.mirantis.mk.Salt()
    def common = new com.mirantis.mk.Common()
    def services = []
    if (action == 'stop') {
        services.add('supervisor-control')
        services.add('supervisor-config')
        services.add('supervisor-database')
        services.add('zookeeper')
        services.add('ifmap-server')
        services.add('haproxy')
        services.add('keepalived')
    } else {
        services.add('keepalived')
        services.add('haproxy')
        services.add('ifmap-server')
        services.add('zookeeper')
        services.add('supervisor-database')
        services.add('supervisor-config')
        services.add('supervisor-control')
    }
    for (s in services) {
        try {
            salt.runSaltProcessStep(pepperEnv, target, "service.${action}", [s], null, true)
        } catch (Exception er) {
            common.warningMsg(er)
        }
    }
}

def periodicCheck(pepperEnv, target, maxRetries=50) {
    def salt = new com.mirantis.mk.Salt()
    def common = new com.mirantis.mk.Common()
    def count = 0
    while(count < maxRetries) {
        try {
            sleep(10)
            salt.minionsReachable(pepperEnv, 'I@salt:master', target, null, wait)
            break
        } catch (Exception e) {
            common.warningMsg("${target} not ready yet. Waiting ...")
        }
        count++
    }
}

def highstate(pepperEnv, target, type) {
    def salt = new com.mirantis.mk.Salt()
    def common = new com.mirantis.mk.Common()
    def highstates = TARGET_HIGHSTATE.tokenize(",").collect{it -> it.trim()}
    def reboots = TARGET_REBOOT.tokenize(",").collect{it -> it.trim()}
    // optionally run highstate
    if (highstates.contains(type)) {
        stage("Apply highstate on ${target} nodes") {
            try {
                common.retry(3){
                    out = salt.enforceHighstate(pepperEnv, target)
                    salt.printSaltCommandResult(out)
                }
            } catch (Exception e) {
                common.errorMsg(e)
                if (INTERACTIVE.toBoolean()) {
                    input message: "Highstate failed on ${target}. Fix it manually or run rollback on ${target}."
                } else {
                    throw new Exception("highstate failed")
                }
            }
        }
    } else if (!reboots.contains(type) && STOP_SERVICES.toBoolean() && type != 'cid') {
        if (type == 'ntw' || type == 'nal') {
            contrailServices(pepperEnv, target, 'start')
        } else {
            def probe = salt.getFirstMinion(pepperEnv, "${target}")
            services(pepperEnv, probe, target, 'start')
        }
    }
    // optionally reboot
    if (reboots.contains(type)) {
        stage("Reboot ${target} nodes") {
            if (type == 'cfg') {
                try {
                    salt.runSaltProcessStep(pepperEnv, target, 'system.reboot', null, null, true, 5)
                } catch (Exception e) {
                    periodicCheck(pepperEnv, target)
                }
            } else {
                salt.runSaltProcessStep(pepperEnv, target, 'system.reboot', null, null, true, 5)
                sleep 10
                salt.minionsReachable(pepperEnv, 'I@salt:master', target, null, wait)
            }
        }
    }
}

def rollback(pepperEnv, tgt, generalTarget) {
    def common = new com.mirantis.mk.Common()
    try {
        if (INTERACTIVE.toBoolean()) {
            input message: "Are you sure to rollback ${generalTarget}? To rollback click on PROCEED. To skip rollback click on ABORT."
        }
    } catch (Exception er) {
        common.infoMsg('skipping rollback')
        return
    }
    try {
        rollbackLiveSnapshot(pepperEnv, tgt, generalTarget)
    } catch (Exception err) {
        common.errorMsg(err)
        if (INTERACTIVE.toBoolean()) {
            input message: "Rollback for ${tgt} failed please fix it manually before clicking PROCEED."
        } else {
            throw new Exception("Rollback failed for ${tgt}")
        }
    }
}

def liveSnapshot(pepperEnv, tgt, generalTarget) {
    def salt = new com.mirantis.mk.Salt()
    def common = new com.mirantis.mk.Common()
    def virsh = new com.mirantis.mk.Virsh()
    def domain = salt.getDomainName(pepperEnv)
    def target_hosts = salt.getMinionsSorted(pepperEnv, "${tgt}")
    common.warningMsg(target_hosts)
    for (t in target_hosts) {
        def target = salt.stripDomainName(t)
        def nodeProvider = getNodeProvider(pepperEnv, t)
        virsh.liveSnapshotPresent(pepperEnv, nodeProvider, target, SNAPSHOT_NAME)
    }
}

def mergeSnapshot(pepperEnv, tgt, generalTarget='') {
    def salt = new com.mirantis.mk.Salt()
    def virsh = new com.mirantis.mk.Virsh()
    def domain = salt.getDomainName(pepperEnv)
    def target_hosts = salt.getMinionsSorted(pepperEnv, "${tgt}")
    for (t in target_hosts) {
        if (tgt == 'I@salt:master') {
            def master = salt.getReturnValues(salt.getPillar(pepperEnv, t, 'linux:network:hostname'))
            getNodeProvider(pepperEnv, master, 'master')
            virsh.liveSnapshotMerge(pepperEnv, CFG_NODE_PROVIDER, master, SNAPSHOT_NAME)
        } else {
            def target = salt.stripDomainName(t)
            def nodeProvider = getNodeProvider(pepperEnv, t)
            virsh.liveSnapshotMerge(pepperEnv, nodeProvider, target, SNAPSHOT_NAME)
        }
    }
    salt.minionsReachable(pepperEnv, 'I@salt:master', tgt, null, wait)
}



def rollbackLiveSnapshot(pepperEnv, tgt, generalTarget) {
    def salt = new com.mirantis.mk.Salt()
    def virsh = new com.mirantis.mk.Virsh()
    def common = new com.mirantis.mk.Common()
    def domain = salt.getDomainName(pepperEnv)
    def target_hosts = salt.getMinionsSorted(pepperEnv, "${tgt}")
    // first destroy all vms
    for (t in target_hosts) {
        def target = salt.stripDomainName(t)
        def nodeProvider = getNodeProvider(pepperEnv, t)
        salt.runSaltProcessStep(pepperEnv, "${nodeProvider}*", 'virt.destroy', ["${target}.${domain}"], null, true)
    }
    // rollback vms
    for (t in target_hosts) {
        def target = salt.stripDomainName(t)
        def nodeProvider = getNodeProvider(pepperEnv, t)
        virsh.liveSnapshotRollback(pepperEnv, nodeProvider, target, SNAPSHOT_NAME)
    }
    try {
        salt.minionsReachable(pepperEnv, 'I@salt:master', tgt)
    } catch (Exception e) {
        common.errorMsg(e)
        if (INTERACTIVE.toBoolean()) {
            input message: "Not all minions ${tgt} returned after snapshot revert. Do you want to PROCEED?."
        } else {
            throw new Exception("Not all minions ${tgt} returned after snapshot revert")
        }
    }
}

def removeNode(pepperEnv, tgt, generalTarget) {
    def salt = new com.mirantis.mk.Salt()
    def virsh = new com.mirantis.mk.Virsh()
    def common = new com.mirantis.mk.Common()
    def domain = salt.getDomainName(pepperEnv)
    def target_hosts = salt.getMinionsSorted(pepperEnv, "${tgt}")
    // first destroy all vms
    for (t in target_hosts) {
        def target = salt.stripDomainName(t)
        def nodeProvider = getNodeProvider(pepperEnv, t)
        salt.runSaltProcessStep(pepperEnv, "${nodeProvider}*", 'virt.destroy', ["${target}.${domain}"], null, true)
        //salt.runSaltProcessStep(pepperEnv, "${nodeProvider}*", 'virt.undefine', ["${target}.${domain}"], null, true)
        try {
            salt.cmdRun(pepperEnv, 'I@salt:master', "salt-key -d ${target}.${domain} -y")
        } catch (Exception e) {
            common.warningMsg('does not match any accepted, unaccepted or rejected keys. They were probably already removed. We should continue to run')
        }
    }
}

def saltMasterBackup(pepperEnv) {
    def salt = new com.mirantis.mk.Salt()
    salt.enforceState(pepperEnv, 'I@salt:master', 'backupninja')
    salt.cmdRun(pepperEnv, 'I@salt:master', "su root -c 'backupninja -n --run /etc/backup.d/200.backup.rsync'")
}

def backupCeph(pepperEnv, tgt) {
    def salt = new com.mirantis.mk.Salt()
    salt.enforceState(pepperEnv, 'I@ceph:backup:server', 'ceph.backup')
    salt.enforceState(pepperEnv, "I@ceph:backup:client and ${tgt}", 'ceph.backup')
    salt.cmdRun(pepperEnv, "I@ceph:backup:client and ${tgt}", "su root -c '/usr/local/bin/ceph-backup-runner-call.sh -s'")
}

def backupGalera(pepperEnv) {
    def salt = new com.mirantis.mk.Salt()
    salt.enforceState(pepperEnv, 'I@xtrabackup:server', ['linux.system.repo', 'xtrabackup'])
    salt.enforceState(pepperEnv, 'I@xtrabackup:client', ['linux.system.repo', 'openssh.client'])
    salt.cmdRun(pepperEnv, 'I@xtrabackup:client', "su root -c 'salt-call state.sls xtrabackup'")
    salt.cmdRun(pepperEnv, 'I@xtrabackup:client', "su root -c '/usr/local/bin/innobackupex-runner.sh -s -f'")
}

// cluster galera - wsrep_cluster_size
def clusterGalera(pepperEnv) {
    def salt = new com.mirantis.mk.Salt()
    def common = new com.mirantis.mk.Common()
    try {
        salt.runSaltProcessStep(pepperEnv, 'I@galera:slave', 'service.stop', ['mysql'])
    } catch (Exception er) {
        common.warningMsg('Mysql service already stopped')
    }
    try {
        salt.runSaltProcessStep(pepperEnv, 'I@galera:master', 'service.stop', ['mysql'])
    } catch (Exception er) {
        common.warningMsg('Mysql service already stopped')
    }
    try {
        salt.cmdRun(pepperEnv, 'I@galera:slave', "rm /var/lib/mysql/ib_logfile*")
    } catch (Exception er) {
        common.warningMsg('Files are not present')
    }
    salt.cmdRun(pepperEnv, 'I@galera:master', "sed -i '/gcomm/c\\wsrep_cluster_address=\"gcomm://\"' /etc/mysql/my.cnf")
    salt.runSaltProcessStep(pepperEnv, 'I@galera:master', 'service.start', ['mysql'])
    // wait until mysql service on galera master is up
    try {
        salt.commandStatus(pepperEnv, 'I@galera:master', 'service mysql status', 'running')
    } catch (Exception er) {
        if (INTERACTIVE.toBoolean()) {
            input message: "Database is not running please fix it first and only then click on PROCEED."
        } else {
            throw new Exception("Database is not running correctly")
        }
    }
    salt.runSaltProcessStep(pepperEnv, 'I@galera:slave', 'service.start', ['mysql'])
}

def restoreGalera(pepperEnv) {
    def salt = new com.mirantis.mk.Salt()
    def common = new com.mirantis.mk.Common()
    def galera = new com.mirantis.mk.Galera()
    galera.restoreGaleraDb(pepperEnv)
}

def backupZookeeper(pepperEnv) {
    def salt = new com.mirantis.mk.Salt()
    def common = new com.mirantis.mk.Common()
    salt.enforceState(pepperEnv, 'I@zookeeper:backup:server', 'zookeeper.backup')
    salt.enforceState(pepperEnv, 'I@zookeeper:backup:client', 'zookeeper.backup')
    try {
        salt.cmdRun(pepperEnv, 'I@opencontrail:control', "su root -c '/usr/local/bin/zookeeper-backup-runner.sh -s'")
    } catch (Exception er) {
        throw new Exception('Zookeeper failed to backup. Please fix it before continuing.')
    }
}

def backupCassandra(pepperEnv) {
    def salt = new com.mirantis.mk.Salt()
    def common = new com.mirantis.mk.Common()

    salt.enforceState(pepperEnv, 'I@cassandra:backup:server', 'cassandra.backup')
    salt.enforceState(pepperEnv, 'I@cassandra:backup:client', 'cassandra.backup')
    try {
        salt.cmdRun(pepperEnv, 'I@cassandra:backup:client', "su root -c '/usr/local/bin/cassandra-backup-runner-call.sh -s'")
    } catch (Exception er) {
        throw new Exception('Cassandra failed to backup. Please fix it before continuing.')
    }
}

def backupContrail(pepperEnv) {
    backupZookeeper(pepperEnv)
    backupCassandra(pepperEnv)
}

// cassandra and zookeeper
def restoreContrailDb(pepperEnv) {
    def salt = new com.mirantis.mk.Salt()
    def common = new com.mirantis.mk.Common()
    build job: "deploy-zookeeper-restore", parameters: [
      [$class: 'StringParameterValue', name: 'SALT_MASTER_CREDENTIALS', value: SALT_MASTER_CREDENTIALS],
      [$class: 'StringParameterValue', name: 'SALT_MASTER_URL', value: SALT_MASTER_URL]
    ]
    build job: "deploy-cassandra-db-restore", parameters: [
      [$class: 'StringParameterValue', name: 'SALT_MASTER_CREDENTIALS', value: SALT_MASTER_CREDENTIALS],
      [$class: 'StringParameterValue', name: 'SALT_MASTER_URL', value: SALT_MASTER_URL]
    ]
}

def verifyAPIs(pepperEnv, target) {
    def salt = new com.mirantis.mk.Salt()
    def common = new com.mirantis.mk.Common()
    def cmds = ["openstack service list",
                "openstack image list",
                "openstack flavor list",
                "openstack compute service list",
                "openstack server list",
                "openstack network list",
                "openstack volume list",
                "openstack orchestration service list"]
    def sourcerc = ". /root/keystonercv3;"
    def cmdOut = ">/dev/null 2>&1;echo \$?"
    for (c in cmds) {
        def command = sourcerc + c + cmdOut
        def out = salt.cmdRun(pepperEnv, target, "${command}")
        if (!out.toString().toLowerCase().contains('0')) {
            common.errorMsg(out)
            if (INTERACTIVE.toBoolean()) {
                input message: "APIs are not working as expected. Please fix it manually."
            } else {
                throw new Exception("APIs are not working as expected")
            }
        }
    }
}

def verifyGalera(pepperEnv, target, count=0, maxRetries=200) {
    def salt = new com.mirantis.mk.Salt()
    def common = new com.mirantis.mk.Common()
    def out
    while(count < maxRetries) {
        try {
            out = salt.getReturnValues(salt.cmdRun(pepperEnv, target, 'salt-call -l quiet mysql.status | grep -A1 wsrep_cluster_size'))
        } catch (Exception er) {
            common.infoMsg(er)
        }
        if ((!out.toString().contains('wsrep_cluster_size')) || (out.toString().contains('0'))) {
            count++
            if (count == maxRetries) {
                if (INTERACTIVE.toBoolean()) {
                    input message: "Galera is not working as expected. Please check it and fix it first before clicking on PROCEED."
                } else {
                    common.errorMsg(out)
                    throw new Exception("Galera is not working as expected")
                }
            }
            sleep(time: 500, unit: 'MILLISECONDS')
        } else {
            break
        }
    }
}

def verifyContrail(pepperEnv, target) {
    def salt = new com.mirantis.mk.Salt()
    def common = new com.mirantis.mk.Common()
    salt.commandStatus(pepperEnv, target, "contrail-status | grep -v == | grep -v \'disabled on boot\' | grep -v nodemgr | grep -v active | grep -v backup", null, false)
}


def verifyService(pepperEnv, target, service) {
    def salt = new com.mirantis.mk.Salt()
    def common = new com.mirantis.mk.Common()
    def targetHosts = salt.getMinionsSorted(pepperEnv, target)
    for (t in targetHosts) {
        try {
            salt.commandStatus(pepperEnv, t, "service ${service} status", 'running')
        } catch (Exception er) {
            common.errorMsg(er)
            if (INTERACTIVE.toBoolean()) {
                input message: "${service} service is not running correctly on ${t}. Please fix it first manually and only then click on PROCEED."
            } else {
                throw new Exception("${service} service is not running correctly on ${t}")
            }
        }
    }
}

def verifyCeph(pepperEnv, target, type) {
    def salt = new com.mirantis.mk.Salt()
    def common = new com.mirantis.mk.Common()
    def targetHosts = salt.getMinionsSorted(pepperEnv, target)
    for (t in targetHosts) {
        def hostname = salt.getReturnValues(salt.getPillar(pepperEnv, t, 'linux:network:hostname'))
        try {
            salt.commandStatus(pepperEnv, t, "systemctl status ceph-${type}${hostname}", 'running')
        } catch (Exception er) {
            common.errorMsg(er)
            if (INTERACTIVE.toBoolean()) {
                input message: "Ceph-${type}${hostname} service is not running correctly on ${t}. Please fix it first manually and only then click on PROCEED."
            } else {
                throw new Exception("Ceph-${type}${hostname} service is not running correctly on ${t}")
            }
        }
    }
}

def verifyCephOsds(pepperEnv, target) {
    def salt = new com.mirantis.mk.Salt()
    def common = new com.mirantis.mk.Common()
    def targetHosts = salt.getMinionsSorted(pepperEnv, target)
    for (t in targetHosts) {
        def osd_ids = []
        // get list of osd disks of the host
        salt.runSaltProcessStep(pepperEnv, t, 'saltutil.sync_grains', [], null, true, 5)
        def cephGrain = salt.getGrain(pepperEnv, t, 'ceph')
        if(cephGrain['return'].isEmpty()){
            throw new Exception("Ceph salt grain cannot be found!")
        }
        common.print(cephGrain)
        def ceph_disks = cephGrain['return'][0].values()[0].values()[0]['ceph_disk']
        for (i in ceph_disks) {
            def osd_id = i.getKey().toString()
            osd_ids.add('osd.' + osd_id)
            print("Will check osd." + osd_id)
        }
        for (i in osd_ids) {
            try {
                salt.commandStatus(pepperEnv, t, "ceph osd tree | grep -w ${i}", 'up')
            } catch (Exception er) {
                common.errorMsg(er)
                if (INTERACTIVE.toBoolean()) {
                    input message: "Ceph ${i} is not running correctly on ${t}. Please fix it first manually and only then click on PROCEED."
                } else {
                    throw new Exception("Ceph ${i} is not running correctly on ${t}")
                }
            }
        }
    }
}


timeout(time: 12, unit: 'HOURS') {
    node() {
        try {
            if(RUN_CVP_TESTS.toBoolean() == true){
                stage('Run CVP tests before upgrade.') {
                    build job: "cvp-sanity"
                    build job: "cvp-func"
                    build job: "cvp-ha"
                    build job: "cvp-perf"
                }
            }

            stage('Setup virtualenv for Pepper') {
                python.setupPepperVirtualenv(pepperEnv, SALT_MASTER_URL, SALT_MASTER_CREDENTIALS)
            }

            // TODO, add possibility to update just specific components like kernel, openstack, contrail, ovs, rabbitmq, galera, etc.

            /*
                * Update section
            */

            // Go through applications that using orchestrated deployment.
            orchestrate.OrchestrateApplications(pepperEnv, "I@salt:master", "orchestration.deploy.applications")

            if (updates.contains("cfg")) {
                def target = 'I@salt:master'
                def type = 'cfg'
                if (salt.testTarget(pepperEnv, target)) {
                    def master = salt.getReturnValues(salt.getPillar(pepperEnv, target, 'linux:network:hostname'))
                    getNodeProvider(pepperEnv, master, 'master')
                    if (!ROLLBACK_BY_REDEPLOY.toBoolean()) {
                        virsh.liveSnapshotPresent(pepperEnv, CFG_NODE_PROVIDER, master, SNAPSHOT_NAME)
                    } else {
                        saltMasterBackup(pepperEnv)
                    }
                    if (PER_NODE.toBoolean()) {
                        def targetHosts = salt.getMinionsSorted(pepperEnv, target)
                        for (t in targetHosts) {
                            updatePkgs(pepperEnv, t, type)
                            highstate(pepperEnv, t, type)
                        }
                    } else {
                        updatePkgs(pepperEnv, target, type)
                        highstate(pepperEnv, target, type)
                    }
                }
            }

            if (updates.contains("msg")) {
                def target = MSG_TARGET
                def type = 'msg'
                if (salt.testTarget(pepperEnv, target)) {
                    if (!ROLLBACK_BY_REDEPLOY.toBoolean()) {
                        liveSnapshot(pepperEnv, target, type)
                    }
                    if (PER_NODE.toBoolean()) {
                        def targetHosts = salt.getMinionsSorted(pepperEnv, target)
                        for (t in targetHosts) {
                            updatePkgs(pepperEnv, t, type)
                            highstate(pepperEnv, t, type)
                        }
                    } else {
                        updatePkgs(pepperEnv, target, type)
                        highstate(pepperEnv, target, type)
                    }
                    verifyService(pepperEnv, target, 'rabbitmq-server')
                }
            }

            if (updates.contains("dbs")) {
                def target = DBS_TARGET
                def type = 'dbs'
                if (salt.testTarget(pepperEnv, target)) {
                    backupGalera(pepperEnv)
                    if (!ROLLBACK_BY_REDEPLOY.toBoolean()) {
                        liveSnapshot(pepperEnv, target, type)
                    }
                    if (reboots.contains(type) || PER_NODE.toBoolean()) {
                        def targetHosts = salt.getMinionsSorted(pepperEnv, target)
                        for (t in targetHosts) {
                            updatePkgs(pepperEnv, t, type)
                            highstate(pepperEnv, t, type)
                            verifyGalera(pepperEnv, t)
                        }
                    } else {
                        updatePkgs(pepperEnv, target, type)
                        highstate(pepperEnv, target, type)
                        verifyGalera(pepperEnv, target)
                    }
                }
            }

            if (updates.contains("ntw")) {
                def target = NTW_TARGET
                def type = 'ntw'
                if (salt.testTarget(pepperEnv, target)) {
                    backupContrail(pepperEnv)
                    if (!ROLLBACK_BY_REDEPLOY.toBoolean()) {
                        liveSnapshot(pepperEnv, target, type)
                    }
                    if (PER_NODE.toBoolean()) {
                        def targetHosts = salt.getMinionsSorted(pepperEnv, target)
                        for (t in targetHosts) {
                            updatePkgs(pepperEnv, t, type)
                            highstate(pepperEnv, t, type)
                            verifyContrail(pepperEnv, t)
                        }
                    } else {
                        updatePkgs(pepperEnv, target, type)
                        highstate(pepperEnv, target, type)
                        verifyContrail(pepperEnv, target)
                    }
                }
            }

            if (updates.contains("nal")) {
                def target = NAL_TARGET
                def type = 'nal'
                if (salt.testTarget(pepperEnv, target)) {
                    if (!ROLLBACK_BY_REDEPLOY.toBoolean()) {
                        liveSnapshot(pepperEnv, target, type)
                    }
                    if (PER_NODE.toBoolean()) {
                        def targetHosts = salt.getMinionsSorted(pepperEnv, target)
                        for (t in targetHosts) {
                            updatePkgs(pepperEnv, t, type)
                            highstate(pepperEnv, t, type)
                        }
                    } else {
                        updatePkgs(pepperEnv, target, type)
                        highstate(pepperEnv, target, type)
                    }
                    verifyContrail(pepperEnv, target)
                }
            }

            if (updates.contains("cmn")) {
                def target = CMN_TARGET
                def type = 'cmn'
                if (salt.testTarget(pepperEnv, target)) {
                    if (!ROLLBACK_BY_REDEPLOY.toBoolean()) {
                        liveSnapshot(pepperEnv, target, type)
                    } else {
                        backupCeph(pepperEnv, target)
                    }
                    if (PER_NODE.toBoolean()) {
                        def targetHosts = salt.getMinionsSorted(pepperEnv, target)
                        for (t in targetHosts) {
                            updatePkgs(pepperEnv, t, type)
                            highstate(pepperEnv, t, type)
                        }
                    } else {
                        updatePkgs(pepperEnv, target, type)
                        highstate(pepperEnv, target, type)
                    }
                    verifyCeph(pepperEnv, target, 'mon@')
                }
            }

            if (updates.contains("rgw")) {
                def target = RGW_TARGET
                def type = 'rgw'
                if (salt.testTarget(pepperEnv, target)) {
                    if (!ROLLBACK_BY_REDEPLOY.toBoolean()) {
                        liveSnapshot(pepperEnv, target, type)
                    }
                    if (PER_NODE.toBoolean()) {
                        def targetHosts = salt.getMinionsSorted(pepperEnv, target)
                        for (t in targetHosts) {
                            updatePkgs(pepperEnv, t, type)
                            highstate(pepperEnv, t, type)
                        }
                    } else {
                        updatePkgs(pepperEnv, target, type)
                        highstate(pepperEnv, target, type)
                    }
                    verifyCeph(pepperEnv, target, 'radosgw@rgw.')
                }
            }

            if (updates.contains("log")) {
                def target = LOG_TARGET
                def type = 'log'
                if (salt.testTarget(pepperEnv, target)) {
                    if (!ROLLBACK_BY_REDEPLOY.toBoolean()) {
                        liveSnapshot(pepperEnv, target, type)
                    }
                    if (PER_NODE.toBoolean()) {
                        def targetHosts = salt.getMinionsSorted(pepperEnv, target)
                        for (t in targetHosts) {
                            updatePkgs(pepperEnv, t, type)
                            highstate(pepperEnv, t, type)
                        }
                    } else {
                        updatePkgs(pepperEnv, target, type)
                        highstate(pepperEnv, target, type)
                    }
                }
            }

            if (updates.contains("mon")) {
                def target = MON_TARGET
                def type = 'mon'
                if (salt.testTarget(pepperEnv, target)) {
                    if (!ROLLBACK_BY_REDEPLOY.toBoolean()) {
                        liveSnapshot(pepperEnv, target, type)
                    }
                    if (PER_NODE.toBoolean()) {
                        def targetHosts = salt.getMinionsSorted(pepperEnv, target)
                        for (t in targetHosts) {
                            updatePkgs(pepperEnv, t, type)
                            highstate(pepperEnv, t, type)
                        }
                    } else {
                        updatePkgs(pepperEnv, target, type)
                        highstate(pepperEnv, target, type)
                    }
                }
            }

            if (updates.contains("mtr")) {
                def target = MTR_TARGET
                def type = 'mtr'
                if (salt.testTarget(pepperEnv, target)) {
                    if (!ROLLBACK_BY_REDEPLOY.toBoolean()) {
                        liveSnapshot(pepperEnv, target, type)
                    }
                    if (PER_NODE.toBoolean()) {
                        def targetHosts = salt.getMinionsSorted(pepperEnv, target)
                        for (t in targetHosts) {
                            updatePkgs(pepperEnv, t, type)
                            highstate(pepperEnv, t, type)
                        }
                    } else {
                        updatePkgs(pepperEnv, target, type)
                        highstate(pepperEnv, target, type)
                    }
                }
            }

            if (updates.contains("cid")) {
                def target = CID_TARGET
                def type = 'cid'
                if (salt.testTarget(pepperEnv, target)) {
                    if (!ROLLBACK_BY_REDEPLOY.toBoolean()) {
                        liveSnapshot(pepperEnv, target, type)
                    }
                    updatePkgs(pepperEnv, target, type)
                    highstate(pepperEnv, target, type)
                    verifyService(pepperEnv, target, 'docker')
                }
            }

            if (updates.contains("kvm")) {
                def target = KVM_TARGET
                def type = 'kvm'
                if (salt.testTarget(pepperEnv, target)) {
                    if (PER_NODE.toBoolean()) {
                        def targetHosts = salt.getMinionsSorted(pepperEnv, target)
                        for (t in targetHosts) {
                            updatePkgs(pepperEnv, t, type)
                            highstate(pepperEnv, t, type)
                        }
                    } else {
                        updatePkgs(pepperEnv, target, type)
                        highstate(pepperEnv, target, type)
                    }
                    verifyService(pepperEnv, target, 'libvirt-bin')
                }
            }

            if (updates.contains("osd")) {
                def target = CEPH_OSD_TARGET
                def type = 'osd'
                if (salt.testTarget(pepperEnv, target)) {
                    if (PER_NODE.toBoolean()) {
                        def targetHosts = salt.getMinionsSorted(pepperEnv, target)
                        for (t in targetHosts) {
                            updatePkgs(pepperEnv, t, type)
                            highstate(pepperEnv, t, type)
                        }
                    } else {
                        updatePkgs(pepperEnv, target, type)
                        highstate(pepperEnv, target, type)
                    }
                    verifyCephOsds(pepperEnv, target)
                }
            }

            /*
                * Rollback section
            */
          /*  if (rollbacks.contains("cfg")) {
                if (salt.testTarget(pepperEnv, 'I@salt:master')) {
                    stage('ROLLBACK_CFG') {
                        input message: "To rollback CFG nodes run the following commands on kvm nodes hosting the CFG nodes: virsh destroy cfg0X.domain; virsh define /var/lib/libvirt/images/cfg0X.domain.xml; virsh start cfg0X.domain; virsh snapshot-delete cfg0X.domain --metadata ${SNAPSHOT_NAME}; rm /var/lib/libvirt/images/cfg0X.domain.${SNAPSHOT_NAME}.qcow2; rm /var/lib/libvirt/images/cfg0X.domain.xml; At the end restart 'docker' service on all cicd nodes and run 'linux.system.repo' Salt states on cicd nodes. After running the previous commands current pipeline job will be killed."
                        //rollbackSaltMaster(pepperEnv, 'I@salt:master')
                        //finishSaltMasterRollback(pepperEnv, 'I@salt:master')
                    }
                }
            } */

            if (rollbacks.contains("msg")) {
                def target = MSG_TARGET
                if (salt.testTarget(pepperEnv, target)) {
                    if (!ROLLBACK_BY_REDEPLOY.toBoolean()) {
                        rollback(pepperEnv, target, 'msg')
                        salt.enforceState(pepperEnv, target, 'rabbitmq')
                        verifyService(pepperEnv, target, 'rabbitmq-server')
                    } else {
                        removeNode(pepperEnv, target, 'msg')
                    }
                }
            }

            if (rollbacks.contains("dbs")) {
                def target = DBS_TARGET
                if (salt.testTarget(pepperEnv, target)) {
                    if (!ROLLBACK_BY_REDEPLOY.toBoolean()) {
                        rollback(pepperEnv, target, 'dbs')
                        clusterGalera(pepperEnv)
                        verifyGalera(pepperEnv, target)
                    } else {
                        removeNode(pepperEnv, target, 'dbs')
                    }
                }
            }

            if (rollbacks.contains("ntw")) {
                def target = NTW_TARGET
                if (salt.testTarget(pepperEnv, target)) {
                    if (!ROLLBACK_BY_REDEPLOY.toBoolean()) {
                        rollback(pepperEnv, target, 'ntw')
                        verifyContrail(pepperEnv, target)
                    } else {
                        removeNode(pepperEnv, target, 'ntw')
                    }
                }
            }

            if (rollbacks.contains("nal")) {
                def target = NAL_TARGET
                if (salt.testTarget(pepperEnv, target)) {
                    if (!ROLLBACK_BY_REDEPLOY.toBoolean()) {
                        rollback(pepperEnv, target, 'nal')
                        verifyContrail(pepperEnv, target)
                    } else {
                        removeNode(pepperEnv, target, 'nal')
                    }
                }
            }

            if (rollbacks.contains("cmn")) {
                def target = CMN_TARGET
                if (salt.testTarget(pepperEnv, target)) {
                    if (!ROLLBACK_BY_REDEPLOY.toBoolean()) {
                        rollback(pepperEnv, target, 'cmn')
                        verifyCeph(pepperEnv, target, 'mon@')
                    } else {
                        removeNode(pepperEnv, target, 'cmn')
                    }
                }
            }

            if (rollbacks.contains("rgw")) {
                def target = RGW_TARGET
                if (salt.testTarget(pepperEnv, target)) {
                    if (!ROLLBACK_BY_REDEPLOY.toBoolean()) {
                        rollback(pepperEnv, target, 'rgw')
                        verifyCeph(pepperEnv, target, 'radosgw@rgw.')
                    } else {
                        removeNode(pepperEnv, target, 'rgw')
                    }
                }
            }

            if (rollbacks.contains("log")) {
                def target = LOG_TARGET
                if (salt.testTarget(pepperEnv, target)) {
                    if (!ROLLBACK_BY_REDEPLOY.toBoolean()) {
                        rollback(pepperEnv, target, 'log')
                    } else {
                        removeNode(pepperEnv, target, 'log')
                    }
                }
            }

            if (rollbacks.contains("mon")) {
                def target = MON_TARGET
                if (salt.testTarget(pepperEnv, target)) {
                    if (!ROLLBACK_BY_REDEPLOY.toBoolean()) {
                        rollback(pepperEnv, target, 'mon')
                    } else {
                        removeNode(pepperEnv, target, 'mon')
                    }
                }
            }

            if (rollbacks.contains("mtr")) {
                def target = MTR_TARGET
                if (salt.testTarget(pepperEnv, target)) {
                    if (!ROLLBACK_BY_REDEPLOY.toBoolean()) {
                        rollback(pepperEnv, target, 'mtr')
                    } else {
                        removeNode(pepperEnv, target, 'mtr')
                    }
                }
            }
            /*
            if (ROLLBACK_CID.toBoolean()) {
                def target = 'cid*'
                if (salt.testTarget(pepperEnv, target)) {
                    stage('ROLLBACK_CID') {
                        input message: "To rollback CICD nodes run the following commands on kvm nodes hosting the cicd nodes: virsh destroy cid0X.domain; virsh define /var/lib/libvirt/images/cid0X.domain.xml; virsh start cid0X.domain; virsh snapshot-delete cid0X.domain --metadata ${SNAPSHOT_NAME}; rm /var/lib/libvirt/images/cid0X.domain.${SNAPSHOT_NAME}.qcow2; rm /var/lib/libvirt/images/cid0X.domain.xml; At the end restart 'docker' service on all cicd nodes and run 'linux.system.repo' Salt states on cicd nodes. After running the previous commands current pipeline job will be killed."
                    }
                }
            } */

            if (rollbacks.contains("kvm")) {
                def target = KVM_TARGET
                def type = 'kvm'
                if (salt.testTarget(pepperEnv, target)) {
                    if (PER_NODE.toBoolean()) {
                        def targetHosts = salt.getMinionsSorted(pepperEnv, target)
                        for (t in targetHosts) {
                            rollbackPkgs(pepperEnv, t)
                            highstate(pepperEnv, t, type)
                        }
                    } else {
                        rollbackPkgs(pepperEnv, target, target)
                        highstate(pepperEnv, target, type)
                    }
                    verifyService(pepperEnv, target, 'libvirt-bin')
                }
            }

            if (rollbacks.contains("osd")) {
                def target = CEPH_OSD_TARGET
                def type = 'osd'
                if (salt.testTarget(pepperEnv, target)) {
                    if (PER_NODE.toBoolean()) {
                        def targetHosts = salt.getMinionsSorted(pepperEnv, target)
                        for (t in targetHosts) {
                            rollbackPkgs(pepperEnv, t)
                            highstate(pepperEnv, t, type)
                        }
                    } else {
                        rollbackPkgs(pepperEnv, target, target)
                        highstate(pepperEnv, target, type)
                    }
                    verifyCephOsds(pepperEnv, target)
                }
            }

            /*
                * Merge snapshots section
            */
            if (merges.contains("cfg")) {
                if (salt.testTarget(pepperEnv, 'I@salt:master')) {
                    mergeSnapshot(pepperEnv, 'I@salt:master')
                }
            }

            if (merges.contains("msg")) {
                if (salt.testTarget(pepperEnv, MSG_TARGET)) {
                    mergeSnapshot(pepperEnv, MSG_TARGET, 'msg')
                    verifyService(pepperEnv, MSG_TARGET, 'rabbitmq-server')
                }
            }

            if (merges.contains("dbs")) {
                if (salt.testTarget(pepperEnv, DBS_TARGET)) {
                    mergeSnapshot(pepperEnv, DBS_TARGET, 'dbs')
                    verifyGalera(pepperEnv, DBS_TARGET)
                    backupGalera(pepperEnv)
                }
            }

            if (merges.contains("ntw")) {
                if (salt.testTarget(pepperEnv, NTW_TARGET)) {
                    mergeSnapshot(pepperEnv, NTW_TARGET, 'ntw')
                    verifyContrail(pepperEnv, NTW_TARGET)
                    backupContrail(pepperEnv)
                }
            }

            if (merges.contains("nal")) {
                if (salt.testTarget(pepperEnv, NAL_TARGET)) {
                    mergeSnapshot(pepperEnv, NAL_TARGET, 'nal')
                    verifyContrail(pepperEnv, NAL_TARGET)
                }
            }

            if (merges.contains("cmn")) {
                if (salt.testTarget(pepperEnv, CMN_TARGET)) {
                    mergeSnapshot(pepperEnv, CMN_TARGET, 'cmn')
                    verifyCeph(pepperEnv, CMN_TARGET, 'mon@')
                    backupCeph(pepperEnv, CMN_TARGET)
                }
            }

            if (merges.contains("rgw")) {
                if (salt.testTarget(pepperEnv, RGW_TARGET)) {
                    mergeSnapshot(pepperEnv, RGW_TARGET, 'rgw')
                    verifyCeph(pepperEnv, RGW_TARGET, 'radosgw@rgw.')
                }
            }

            if (merges.contains("log")) {
                if (salt.testTarget(pepperEnv, LOG_TARGET)) {
                    mergeSnapshot(pepperEnv, LOG_TARGET, 'log')
                }
            }

            if (merges.contains("mon")) {
                if (salt.testTarget(pepperEnv, MON_TARGET)) {
                    mergeSnapshot(pepperEnv, MON_TARGET, 'mon')
                }
            }

            if (merges.contains("mtr")) {
                if (salt.testTarget(pepperEnv, MTR_TARGET)) {
                    mergeSnapshot(pepperEnv, MTR_TARGET, 'mtr')
                }
            }

            if (merges.contains("cid")) {
                if (salt.testTarget(pepperEnv, CID_TARGET)) {
                    mergeSnapshot(pepperEnv, CID_TARGET, 'cid')
                    verifyService(pepperEnv, CID_TARGET, 'docker')
                }
            }

            if (RESTORE_GALERA.toBoolean()) {
                restoreGalera(pepperEnv)
                verifyGalera(pepperEnv, DBS_TARGET)
            }

            if (RESTORE_CONTRAIL_DB.toBoolean()) {
                restoreContrailDb(pepperEnv)
                // verification is already present in restore pipelines
            }

            if(RUN_CVP_TESTS.toBoolean() == true){
                stage('Run CVP tests after upgrade.') {
                    build job: "cvp-sanity"
                    build job: "cvp-func"
                    build job: "cvp-ha"
                    build job: "cvp-perf"
                }
            }

        } catch (Throwable e) {
            // If there was an error or exception thrown, the build failed
            currentBuild.result = "FAILURE"
            currentBuild.description = currentBuild.description ? e.message + " " + currentBuild.description : e.message
            throw e
        }
    }
}
