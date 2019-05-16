/**
 * Update packages on given nodes
 *
 * Expected parameters:
 *   SALT_MASTER_CREDENTIALS    Credentials to the Salt API.
 *   SALT_MASTER_URL            Full Salt API address [https://10.10.10.1:8000].
 *   TARGET_SERVERS             Salt compound target to match nodes to be updated [*, G@osfamily:debian].
 */

pepperEnv = "pepperEnv"
salt = new com.mirantis.mk.Salt()
def common = new com.mirantis.mk.Common()
def python = new com.mirantis.mk.Python()
def targetLiveSubset
def targetLiveAll
def minions
def result
def packages
def command
def commandKwargs
def selMinions = []

def runCephCommand(master, target, cmd) {
    return salt.cmdRun(master, target, cmd)
}

def waitForHealthy(master, tgt, attempts=100, timeout=10) {
    // wait for healthy cluster
    common = new com.mirantis.mk.Common()
    while (count<attempts) {
        def health = runCephCommand(master, ADMIN_HOST, 'ceph health')['return'][0].values()[0]
        if (health.contains('HEALTH_OK') || health.contains('HEALTH_WARN noout flag(s) set\n')) {
            common.infoMsg('Cluster is healthy')
            break;
        }
        count++
        sleep(10)
    }
}

timeout(time: 12, unit: 'HOURS') {
    node() {
        try {

            stage('Setup virtualenv for Pepper') {
                python.setupPepperVirtualenv(pepperEnv, SALT_MASTER_URL, SALT_MASTER_CREDENTIALS)
            }

            stage('List target servers') {
                minions = salt.getMinions(pepperEnv, TARGET_SERVERS)

                if (minions.isEmpty()) {
                    throw new Exception("No minion was targeted")
                }

                for (m  in minions) {
                    if (m.startsWith("osd") || m.startsWith("cmn") || m.startsWith("rgw")) {
                        selMinions.add(m)
                    }
                }
            }



            stage('Apply package upgrades on all nodes') {

                for (tgt in selMinions) {
                    try {
                        if (tgt.startsWith("osd")) {
                            out = runCephCommand(pepperEnv, tgt, "apt install --only-upgrade ceph-osd -y")
                            salt.printSaltCommandResult(out)
                        } else if (tgt.startsWith("cmn")) {
                            out = runCephCommand(pepperEnv, tgt, "apt install --only-upgrade ceph-mon -y")
                            salt.printSaltCommandResult(out)
                        } else if (tgt.startsWith("rgw")) {
                            out = runCephCommand(pepperEnv, tgt, "apt install --only-upgrade radosgw -y")
                            salt.printSaltCommandResult(out)
                        }
                    } catch (Throwable e) {
                        if (e.message.contains("Unmet dependencies")) {
                            out = runCephCommand(pepperEnv, tgt, "apt -f install -y")
                            salt.printSaltCommandResult(out)
                        } else {
                            throw (e)
                        }
                    }
                }
            }

            stage("Restart MONs and RGWs") {
                for (tgt in selMinions) {
                    if (tgt.contains("cmn")) {
                        runCephCommand(pepperEnv, tgt, "systemctl restart ceph-mon.target")
                        waitForHealthy(pepperEnv, tgt)
                    } else if (tgt.contains("rgw")) {
                        runCephCommand(pepperEnv, tgt, "systemctl restart ceph-radosgw.target")
                        waitForHealthy(pepperEnv, tgt)
                    }
                }
            }

            stage('Restart OSDs') {

                for (tgt in selMinions) {
                    if (tgt.contains("osd")) {
                        salt.runSaltProcessStep(pepperEnv, tgt, 'saltutil.sync_grains', [], null, true, 5)
                        def ceph_disks = salt.getGrain(pepperEnv, tgt, 'ceph')['return'][0].values()[0].values()[0]['ceph_disk']

                        def osd_ids = []
                        for (i in ceph_disks) {
                            def osd_id = i.getKey().toString()
                            osd_ids.add('osd.' + osd_id)
                        }

                        runCephCommand(pepperEnv, tgt, 'ceph osd set noout')

                        for (i in osd_ids) {

                            salt.runSaltProcessStep(pepperEnv, tgt, 'service.restart', ['ceph-osd@' + i.replaceAll('osd.', '')],  null, true)
                            // wait for healthy cluster
                            waitForHealthy(pepperEnv, tgt)
                        }

                        runCephCommand(pepperEnv, tgt, 'ceph osd unset noout')
                    }
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