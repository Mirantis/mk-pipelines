/**
 * Update packages
 *
 * Expected parameters:
 *   SALT_MASTER_CREDENTIALS    Credentials to the Salt API.
 *   SALT_MASTER_URL            Full Salt API address [https://10.10.10.1:8000].
 */

pepperEnv = "pepperEnv"
def common = new com.mirantis.mk.Common()
def salt = new com.mirantis.mk.Salt()
def ceph = new com.mirantis.mk.Ceph()
def python = new com.mirantis.mk.Python()
def packages
def command
def commandKwargs
def selMinions = []
def flags = CLUSTER_FLAGS ? CLUSTER_FLAGS.tokenize(',') : []
def runHighState = RUNHIGHSTATE

def collectPkgVersion(target, packageName) {
    def salt = new com.mirantis.mk.Salt()
    return salt.runSaltCommand(pepperEnv, 'local', ['expression': target, 'type': 'compound'], "pkg.version", true, packageName)
}

def getChangedPkgs(oldVersions, newVersions) {
    def common = new com.mirantis.mk.Common()
    def changedPkgs = [:]
    def updated = false
    newVersions.each { k, v ->
        changedPkgs[k] = [:]
        if (v == null || !v['return'] || oldVersions[k] == null || !oldVersions[k]['return']) {
            common.warningMsg("Can't detect package version changes for ceph-${k} packages")
            changedPkgs[k]["*"] = true
            updated = true
            return
        }

        // since run was not in batch mode, get only 0 element which contains all output
        v['return'][0].each { tgt, newPgkVersion ->
            oldVersion = oldVersions[k]['return'][0].get(tgt, "")
            if (oldVersion == newPgkVersion) {
                changedPkgs[k][tgt] = false
                return
            }
            common.infoMsg("${tgt} has updated ceph ${k} packages ${oldVersion} -> ${newPgkVersion}")
            updated = true
            changedPkgs[k][tgt] = true
        }
    }
    return ["updated": updated, "changed": changedPkgs]
}

// if some map contains tgt and has true value - restart needed
def needToRestart(infoPkgs, daemon, tgt) {
    if (infoPkgs[daemon].get("*", false) || infoPkgs[daemon].get(tgt, false) || infoPkgs["common"].get(tgt, false)) {
        return true
    }
    return false
}

timeout(time: 12, unit: 'HOURS') {
    node() {
        try {
            def targets = ["common": "ceph-common", "osd": "ceph-osd", "mon": "ceph-mon",
                           "mgr"   : "ceph-mgr", "radosgw": "radosgw"]

            def oldPackageVersions = [:]
            def newPackageVersions = [:]

            stage('Setup virtualenv for Pepper') {
                python.setupPepperVirtualenv(pepperEnv, SALT_MASTER_URL, SALT_MASTER_CREDENTIALS)
            }

            stage('Apply package upgrades on all nodes') {
                targets.each { key, value ->
                    target = "I@ceph:${key}"
                    packages = value
                    // check package versions before upgrade to compare it after update run
                    oldPackageVersions[key] = collectPkgVersion(target, packages)
                    salt.enforceState(pepperEnv, target, 'linux.system.repo', true)
                    command = "pkg.install"
                    commandKwargs = ['only_upgrade': 'true', 'force_yes': 'true']
                    out = salt.runSaltCommand(pepperEnv, 'local', ['expression': target, 'type': 'compound'], command, true, packages, commandKwargs)
                    salt.printSaltCommandResult(out)
                    // check package version after update
                    newPackageVersions[key] = collectPkgVersion(target, packages)
                }
            }

            def packageChanges = getChangedPkgs(oldPackageVersions, newPackageVersions)

            if (!packageChanges["updated"]) {
                common.infoMsg("Ceph packages were not updated, skipping service restart")
                return
            }

            stage('Set cluster flags') {
                common.infoMsg("Ceph packages update detected, setting cluster noout flag")
                if (flags.size() > 0) {
                    stage('Set cluster flags') {
                        for (flag in flags) {
                            salt.cmdRun(pepperEnv, "I@ceph:mon and I@ceph:common:keyring:admin", 'ceph osd set ' + flag)
                        }
                    }
                }
            }

            stage("Restart MONs/MGRs") {
                ["mon", "mgr"].each {daemon ->
                    selMinions = salt.getMinions(pepperEnv, "I@ceph:${daemon}")
                    for (tgt in selMinions) {
                        if (!needToRestart(packageChanges["changed"], daemon, tgt)) {
                            common.infoMsg("Node ${tgt} has no updated ceph packages, skipping service restart on it")
                            continue
                        }
                        // runSaltProcessStep 'service.restart' don't work for this services
                        salt.cmdRun(pepperEnv, tgt, "systemctl restart ceph-${daemon}.target")
                        ceph.waitForHealthy(pepperEnv, tgt, flags)
                        if (runHighState) {
                            salt.enforceHighstate(pepperEnv, tgt)
                        }
                    }
                }
            }

            stage('Restart OSDs') {
                def device_grain_name =  "ceph_disk"
                selMinions = salt.getMinions(pepperEnv, "I@ceph:osd")
                for (tgt in selMinions) {
                    if (!needToRestart(packageChanges["changed"], "osd", tgt)) {
                        common.infoMsg("Node ${tgt} has no updated ceph packages, skipping service restart on it")
                        continue
                    }
                    salt.runSaltProcessStep(pepperEnv, tgt, 'saltutil.sync_grains', [], null, true, 5)
                    def ceph_disks = salt.getGrain(pepperEnv, tgt, 'ceph')['return'][0].values()[0].values()[0][device_grain_name]

                    def osd_ids = []
                    for (i in ceph_disks) {
                        def osd_id = i.getKey().toString()
                        osd_ids.add('osd.' + osd_id)
                    }

                    salt.cmdRun(pepperEnv, tgt, 'ceph osd set noout')
                    flags = 'noout' in flags ? flags : flags + ['noout']

                    for (i in osd_ids) {
                        salt.runSaltProcessStep(pepperEnv, tgt, 'service.restart', ['ceph-osd@' + i.replaceAll('osd.', '')], null, true)
                        // wait for healthy cluster
                        ceph.waitForHealthy(pepperEnv, tgt, flags, 100)
                    }

                    if (runHighState) {
                        salt.enforceHighstate(pepperEnv, tgt)
                    }

                    salt.cmdRun(pepperEnv, tgt, 'ceph osd unset noout')
                }
            }

            stage('Restart RGWs') {
                selMinions = salt.getMinions(pepperEnv, "I@ceph:radosgw")
                for (tgt in selMinions) {
                    if (!needToRestart(packageChanges["changed"], "radosgw", tgt)) {
                        common.infoMsg("Node ${tgt} has no updated ceph packages, skipping service restart on it")
                        continue
                    }
                    salt.cmdRun(pepperEnv, tgt, "systemctl restart ceph-radosgw.target")
                    ceph.waitForHealthy(pepperEnv, tgt, flags)
                    if (runHighState) {
                        salt.enforceHighstate(pepperEnv, tgt)
                    }
                }
            }
        } catch (Throwable e) {
            // If there was an error or exception thrown, the build failed
            if (flags.size() > 0) {
                stage('Unset cluster flags') {
                    for (flag in flags) {
                        salt.cmdRun(pepperEnv, "I@ceph:mon and I@ceph:common:keyring:admin", 'ceph osd unset ' + flag)
                    }
                }
            }
            currentBuild.result = "FAILURE"
            currentBuild.description = currentBuild.description ? e.message + " " + currentBuild.description : e.message
            throw e
        }
    }
}
