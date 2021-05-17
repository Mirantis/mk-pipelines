/**
 *
 * Remove Ceph node from existing cluster
 *
 * Requred parameters:
 *  SALT_MASTER_URL             URL of Salt master
 *  SALT_MASTER_CREDENTIALS     Credentials to the Salt API
 *  HOST                        Host (minion id) to be removed
 *  WAIT_FOR_HEALTHY            Wait for cluster rebalance after a osd was removed
 *  CLUSTER_FLAGS               Expected flags on the cluster during job run
 *  FAST_WIPE                   Clean only partition table insted of full wipe
 *  CLEAN_ORPHANS               Clean ceph partition which are no longer part of the cluster
 *  OSD                         Coma separated list of OSDs to remove while keep the rest intact
 *  OSD_NODE_IS_DOWN            Remove unavailable (offline) osd node from cluster, provided in HOST parameter
 *  GENERATE_CRUSHMAP           Generate new crush map. Excludes OSD
 *
 */

def common = new com.mirantis.mk.Common()
def salt = new com.mirantis.mk.Salt()
def ceph = new com.mirantis.mk.Ceph()
def python = new com.mirantis.mk.Python()

def pepperEnv = "pepperEnv"

def osds = OSD.tokenize(',').toSet()
def flags = CLUSTER_FLAGS.tokenize(',').toSet()
def cleanOrphans = CLEAN_ORPHANS.toBoolean()
def fullWipe = !FAST_WIPE.toBoolean()
def safeRemove = WAIT_FOR_HEALTHY.toBoolean()

def osdOnly = OSD.trim() as Boolean
def generateCrushmap = osdOnly ? false : GENERATE_CRUSHMAP.toBoolean()
def osdNodeUnavailable = OSD_NODE_IS_DOWN.toBoolean()

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
            common.errorMsg("$HOST targeted more than one minion")
            throw new InterruptedException()
        }

        if (osdNodeUnavailable) {
            stage('Remove unavailable OSD node') {
                osdHostName = salt.stripDomainName("${target[0]}")
                osdTreeString = ceph.cmdRun(pepperEnv, "ceph osd tree --format json-pretty")
                osdTree = common.parseJSON(osdTreeString)
                osdIDs = []
                for(osd in osdTree["nodes"]) {
                    if (osd["type"] == "host" && osd["name"] == osdHostName) {
                        osdIDs = osd["children"]
                        break
                    }
                }
                if (osdIDs.size() == 0) {
                    common.warningMsg("Can't find any OSDs placed on host ${HOST} (${osdHostName}). Is it correct name?")
                    currentBuild.result = "UNSTABLE"
                } else {
                    common.infoMsg("Found next OSDs for host ${HOST} (${osdHostName}): ${osdIDs}")
                    input message: "Do you want to continue node remove?"
                    for (osdId in osdIDs) {
                        ceph.cmdRun(pepperEnv, "ceph osd purge ${osdId} --yes-i-really-mean-it", true, true)
                    }
                    salt.cmdRun(pepperEnv, "I@salt:master", "salt-key -d ${HOST} --include-all -y")

                    if(safeRemove) {
                        ceph.waitForHealthy(pepperEnv, flags)
                    }
                }
            }
            return
        }

        salt.fullRefresh(pepperEnv, HOST)

        stage('Set flags') {
            ceph.setFlags(pepperEnv, flags)
        }

        try {
            stage('Remove OSDs') {
                if(salt.testTarget(pepperEnv, "$HOST and I@ceph:osd")) {
                    // get list of osd disks of the host
                    def cephGrain = ceph.getGrain(pepperEnv, HOST, 'ceph')
                    def cephDisks = cephGrain.get('ceph_disk',[:]).keySet()
                    if (cephGrain.isEmpty()) {
                        throw new Exception("Ceph salt grains cannot be found on $HOST")
                    }

                    // glob for OSD input or whole node is going to be removed
                    if(OSD == '*' || !osdOnly) {
                        osds = cephDisks
                    }

                    // discard all osds which aren't deployed on target HOST
                    osds = osds.intersect(cephDisks)

                    if(!osds.isEmpty()) {
                        common.infoMsg("The following osds will be removed: ${osds.join(', ')}")
                    }
                    if(osds != cephDisks) {
                        common.infoMsg("The following osds will be skiped: ${cephDisks.removeAll(osds).join(', ')}")
                    }

                    ceph.removeOsd(pepperEnv, HOST, osds, flags, safeRemove, fullWipe)

                    if(cleanOrphans) {
                        ceph.removeOrphans(pepperEnv, HOST, fullWipe)
                    }
                }
                else {
                    common.infoMsg('Stage skipped.')
                }
            }

            stage('Remove keyring') {
                // only non-osd nodes as keyrings for osds was removed already in previous step
                if(salt.testTarget(pepperEnv, "$HOST and not I@ceph:osd")) {
                    ceph.deleteKeyrings(pepperEnv, HOST)
                }
                else {
                    common.infoMsg('Stage skipped.')
                }
            }

            stage('Update monmap') {
                if(salt.testTarget(pepperEnv, "$HOST and I@ceph:mon")) {
                    def hostname = ceph.getGrain(pepperEnv, HOST, 'host')
                    ceph.cmdRun(pepperEnv, 'ceph mon getmap -o monmap.backup')
                    ceph.cmdRun(pepperEnv, "ceph mon remove $hostname")
                }
                else {
                    common.infoMsg('Stage skipped.')
                }
            }

            stage('Update Ceph configs/crushmap') {
                //TODO: it won't remove removed mon from config
                if(salt.testTarget(pepperEnv, "$HOST and I@ceph:mon")) {
                    salt.enforceState(pepperEnv, 'I@ceph:common', 'ceph.common', true)
                }
                else if (salt.testTarget(pepperEnv, "$HOST and I@ceph:osd") && salt.testTarget(pepperEnv, "I@ceph:setup:crush and not $HOST") && generateCrushmap) {
                    salt.enforceState(pepperEnv, 'I@ceph:setup:crush', 'ceph.setup.crush', true)
                }
                else {
                    common.infoMsg('Stage skipped.')
                }
            }

            stage('Purge Ceph components') {
                Set pkgs = ['ceph-base','ceph-common']
                if(salt.testTarget(pepperEnv, "$HOST and I@ceph:osd")) {
                    pkgs.addAll(['ceph-osd','ceph-fuse','ceph-mds','python-cephfs','librados2','python-rados','python-rbd','python-rgw'])
                }
                //TODO: why removed pkgs on vm which will be remved as whole in next stage
                if(salt.testTarget(pepperEnv, "$HOST and I@ceph:radosgw")) {
                    ceph.removeRgw(pepperEnv, HOST)
                    pkgs.addAll(['radosgw','libcephfs2','python-cephfs','python-rados','python-rbd','python-rgw'])
                }
                if(salt.testTarget(pepperEnv, "$HOST and I@ceph:mon")) {
                    pkgs.addAll(['ceph-mon','ceph-mgr','libcephfs2','python-cephfs','python-rbd','python-rgw'])
                }

                if(!osdOnly) {
                    salt.runSaltProcessStep(pepperEnv, HOST, 'pkg.purge', "pkgs='$pkgs'")
                }
                else {
                    common.infoMsg('Stage skipped.')
                }
            }

            stage('Remove salt minion and destroy VM') {
                if(!osdOnly) {
                    if(salt.testTarget(pepperEnv, "$HOST and I@ceph:osd")) {
                        ceph.removeSalt(pepperEnv, HOST)
                    }
                    else {
                        ceph.removeVm(pepperEnv, HOST)
                    }
                }
                else {
                    common.infoMsg('Stage skipped.')
                }
            }
        }
        finally {
            stage('Unset cluster flags') {
                ceph.unsetFlags(pepperEnv, flags)
            }
        }
    }
}
