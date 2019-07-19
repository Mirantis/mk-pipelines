/**
 * Update packages
 *
 * Expected parameters:
 *   SALT_MASTER_CREDENTIALS    Credentials to the Salt API.
 *   SALT_MASTER_URL            Full Salt API address [https://10.10.10.1:8000].
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
def check_mon

def runCephCommand(master, target, cmd) {
    return salt.cmdRun(master, target, cmd)
}

def waitForHealthy(master, tgt, count = 0, attempts=100) {
    // wait for healthy cluster
    common = new com.mirantis.mk.Common()
    while (count<attempts) {
        def health = runCephCommand(master, tgt, 'ceph health')['return'][0].values()[0]
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

            def targets = ["common": "ceph-common", "osd": "ceph-osd", "mon": "ceph-mon",
                          "mgr":"ceph-mgr", "radosgw": "radosgw"]

            stage('Setup virtualenv for Pepper') {
                python.setupPepperVirtualenv(pepperEnv, SALT_MASTER_URL, SALT_MASTER_CREDENTIALS)
            }

            stage('Apply package upgrades on all nodes') {

                targets.each { key, value ->
                   // try {
                        command = "pkg.install"
                        packages = value
                        commandKwargs = ['only_upgrade': 'true','force_yes': 'true']
                        target = "I@ceph:${key}"
                        out = salt.runSaltCommand(pepperEnv, 'local', ['expression': target, 'type': 'compound'], command, true, packages, commandKwargs)
                        salt.printSaltCommandResult(out)
                }
            }

            stage("Restart MONs and RGWs") {
                selMinions = salt.getMinions(pepperEnv, "I@ceph:mon")
                for (tgt in selMinions) {
                    // runSaltProcessStep 'service.restart' don't work for this services
                    runCephCommand(pepperEnv, tgt, "systemctl restart ceph-mon.target")
                    waitForHealthy(pepperEnv, tgt)
                }
                selMinions = salt.getMinions(pepperEnv, "I@ceph:radosgw")
                for (tgt in selMinions) {
                    runCephCommand(pepperEnv, tgt, "systemctl restart ceph-radosgw.target")
                    waitForHealthy(pepperEnv, tgt)
                }
            }

            stage('Restart OSDs') {

                selMinions = salt.getMinions(pepperEnv, "I@ceph:osd")
                for (tgt in selMinions) {
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


        } catch (Throwable e) {
            // If there was an error or exception thrown, the build failed
            currentBuild.result = "FAILURE"
            currentBuild.description = currentBuild.description ? e.message + " " + currentBuild.description : e.message
            throw e
        }
    }
}
