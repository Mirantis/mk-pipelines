/**
 *
 * Add Ceph node to existing cluster
 *
 * Requred parameters:
 *  SALT_MASTER_URL             URL of Salt master
 *  SALT_MASTER_CREDENTIALS     Credentials to the Salt API
 *  HOST                        Host (minion id) to be added
 *  HOST_TYPE                   Type of Ceph node to be added. Valid values are mon/osd/rgw
 *
 */

common = new com.mirantis.mk.Common()
salt = new com.mirantis.mk.Salt()
orchestrate = new com.mirantis.mk.Orchestrate()
def python = new com.mirantis.mk.Python()

def pepperEnv = "pepperEnv"
timeout(time: 12, unit: 'HOURS') {
    node("python") {

        // create connection to salt master
        python.setupPepperVirtualenv(pepperEnv, SALT_MASTER_URL, SALT_MASTER_CREDENTIALS)

        matches = ["osd", "mon", "rgw"]
        def found = false
        for (s in matches) {
            if (HOST_TYPE.toLowerCase() == s) {
                found = true
            }
        }

        if (!found) {
            common.errorMsg("No such HOST_TYPE was found. Please insert one of the following types: mon/osd/rgw")
            throw new InterruptedException()
        }

        def checknode = salt.runSaltProcessStep(pepperEnv, HOST, 'test.ping')
        if (checknode['return'][0].values().isEmpty()) {
            common.errorMsg("Host not found")
            throw new InterruptedException()
        }

        if (HOST_TYPE.toLowerCase() != 'osd') {

            // launch VMs
            stage('Launch VMs') {
                salt.enforceState(pepperEnv, 'I@salt:control', 'salt.control', true)

                // wait till the HOST appears in salt-key on salt-master
                salt.minionPresent(pepperEnv, 'I@salt:master', HOST)
            }
        }

        // run basic states
        stage('Install infra') {
            orchestrate.installFoundationInfraOnTarget(pepperEnv, HOST)
        }

        if (HOST_TYPE.toLowerCase() == 'osd') {

            // Install Ceph osd
            stage('Install Ceph OSD') {
                orchestrate.installCephOsd(pepperEnv, HOST)
            }
        } else if (HOST_TYPE.toLowerCase() == 'mon') {
            // Install Ceph mon
            stage('Install Ceph MON') {
                salt.enforceState(pepperEnv, 'I@ceph:common', 'ceph.common', true)
                // install Ceph Mons
                salt.enforceState(pepperEnv, 'I@ceph:mon', 'ceph.mon', true)
                if (salt.testTarget(pepperEnv, 'I@ceph:mgr')) {
                    salt.enforceState(pepperEnv, 'I@ceph:mgr', 'ceph.mgr', true)
                }
            }
        } else if (HOST_TYPE.toLowerCase() == 'rgw') {
            // Install Ceph rgw
            stage('Install Ceph RGW') {
                salt.enforceState(pepperEnv, 'I@ceph:radosgw', ['keepalived', 'haproxy', 'ceph.radosgw'], true)
            }
        }

        stage("Update/Install monitoring") {
            def prometheusNodes = salt.getMinions(pepperEnv, 'I@prometheus:server')
            if (!prometheusNodes.isEmpty()) {
                //Collect Grains
                salt.enforceState(pepperEnv, HOST, 'salt.minion.grains')
                salt.runSaltProcessStep(pepperEnv, HOST, 'saltutil.refresh_modules')
                salt.runSaltProcessStep(pepperEnv, HOST, 'mine.update')
                sleep(5)
                salt.enforceState(pepperEnv, HOST, 'prometheus')
                salt.enforceState(pepperEnv, 'I@prometheus:server', 'prometheus')
            } else {
                common.infoMsg('No Prometheus nodes in cluster. Nothing to do')
            }
        }
    }
}
