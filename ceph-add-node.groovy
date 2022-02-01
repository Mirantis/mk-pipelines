/**
 *
 * Add Ceph node to existing cluster
 *
 * Requred parameters:
 *  SALT_MASTER_URL             URL of Salt master
 *  SALT_MASTER_CREDENTIALS     Credentials to the Salt API
 *  HOST                        Host (minion id) to be added
 *  CLUSTER_FLAGS               Expected flags on the cluster during job run
 *  OSD_ONLY                    Add only new osds while keep rest intact
 *  USE_UPMAP                   Use upmap for rebalance the data after node was added
 *
 */

def common = new com.mirantis.mk.Common()
def salt = new com.mirantis.mk.Salt()
def ceph = new com.mirantis.mk.Ceph()
def orchestrate = new com.mirantis.mk.Orchestrate()
def python = new com.mirantis.mk.Python()

def pepperEnv = "pepperEnv"
def flags = CLUSTER_FLAGS.tokenize(',').toSet()
def osdOnly = OSD_ONLY.toBoolean()
def useUpmap = USE_UPMAP.toBoolean()

timeout(time: 12, unit: 'HOURS') {
    node("python") {

        // create connection to salt master
        python.setupPepperVirtualenv(pepperEnv, SALT_MASTER_URL, SALT_MASTER_CREDENTIALS)

        def target = salt.getMinions(pepperEnv, HOST)
        if(target.isEmpty()) {
            common.errorMsg("Host not found")
            throw new InterruptedException()
        }
        else if(target.size() > 1) {
            common.warningMsg("$HOST targeted more than one minion")
        }

        if(useUpmap) {
            stage('enable upmap balancer') {
                def features = ceph.cmdRun(pepperEnv, "ceph features --format json", false)
                features = common.parseJSON(features)
                for(group in features['client']) {
                    if(group instanceof java.util.HashMap$Node) { // Luminous
                        if(group.getValue()['release'] != 'luminous') {
                            throw new Exception("Some of installed clients does not support upmap. Update all clients to luminous or newer before using upmap")
                        }
                    }
                    else if(group['release'] != 'luminous') { // Nautilus
                        throw new Exception("Some of installed clients does not support upmap. Update all clients to luminous or newer before using upmap")
                    }
                }
                ceph.cmdRun(pepperEnv, 'ceph osd set-require-min-compat-client luminous')
                ceph.cmdRun(pepperEnv, 'ceph balancer on')
                ceph.cmdRun(pepperEnv, 'ceph balancer mode upmap')
            }
        }

        salt.fullRefresh(pepperEnv, HOST)

        stage("set flags") {
            if(useUpmap) {
                flags.add('norebalance')
            }
            ceph.setFlags(pepperEnv, flags)
        }

        try {
            stage('Launch VMs') {
                if(salt.testTarget(pepperEnv, "$HOST and not I@ceph:osd")) {
                    // launch VMs
                    salt.enforceState([saltId: pepperEnv, target: "I@salt:control", state: 'salt.control'])

                    // wait till the HOST appears in salt-key on salt-master
                    salt.minionPresent(pepperEnv, 'I@salt:master', HOST)
                }
                else {
                    common.infoMsg("No VM require for a osd node.")
                }
            }

            stage('Install infra') {
                if(!osdOnly) {
                    // run basic states
                    orchestrate.installFoundationInfraOnTarget(pepperEnv, HOST)
                }
                else {
                    common.infoMsg('Stage skipped due to OSD_ONLY.')
                }
            }

            stage('Install ceph components') {
                if(salt.testTarget(pepperEnv, "$HOST and I@ceph:mon")) {
                    ceph.installMon(pepperEnv, HOST)
                }
                if(salt.testTarget(pepperEnv, "$HOST and I@ceph:radosgw")) {
                    ceph.installRgw(pepperEnv, HOST)
                }
                if(salt.testTarget(pepperEnv, "$HOST and I@ceph:osd")) {
                    ceph.installOsd(pepperEnv, HOST, !osdOnly) //skip setup while osdOnly
                }
                else if(osdOnly) {
                    common.infoMsg('Stage skipped due to OSD_ONLY.')
                }
            }

            stage("Update/Install monitoring and hosts files") {
                if(!osdOnly) {
                    ceph.updateMonitoring(pepperEnv, HOST)
                    salt.enforceState([saltId: pepperEnv, target: "I@ceph:common", state: 'linux.network.host'])
                }
                else {
                    common.infoMsg('Stage skipped due to OSD_ONLY.')
                }
            }

            if(useUpmap) {
                stage("update mappings") {
                    def pgmap
                    for (int x = 1; x <= 3; x++) {
                        pgmap = ceph.cmdRun(pepperEnv, 'ceph pg ls remapped --format=json', false)
                        if (pgmap.trim()) {
                            pgmap = "{\"pgs\":$pgmap}" // common.parseJSON() can't parse a list of maps
                            pgmap = common.parseJSON(pgmap)['pgs']
                            if (pgmap instanceof java.util.Map && pgmap.get('pg_ready', false)) {
                                continue
                            }
                            def mapping = []
                            ceph.generateMapping(pgmap, mapping)
                            for(map in mapping) {
                                ceph.cmdRun(pepperEnv, map)
                            }
                            sleep(30)
                        }
                    }
                }

                stage('Unset norebalance') {
                    ceph.unsetFlags(pepperEnv, 'norebalance')
                    flags.removeElement('norebalance')
                }
            }
            stage('Wait for healthy cluster status') {
                ceph.waitForHealthy(pepperEnv, flags)
            }
        }
        finally {
            stage('Unset cluster flags') {
                ceph.unsetFlags(pepperEnv, flags)
            }
        }
    }
}
