/**
 *
 * Remove OSD from existing cluster
 *
 * Requred parameters:
 *  SALT_MASTER_URL             URL of Salt master
 *  SALT_MASTER_CREDENTIALS     Credentials to the Salt API
 *
 *  HOST                        Host (minion id) to be removed
 *  ADMIN_HOST                  Host (minion id) with admin keyring
 *  CLUSTER_FLAGS               Comma separated list of tags to apply to cluster
 *  WAIT_FOR_HEALTHY            Wait for cluster rebalance before stoping daemons
 *
 */

common = new com.mirantis.mk.Common()
salt = new com.mirantis.mk.Salt()
def python = new com.mirantis.mk.Python()

def pepperEnv = "pepperEnv"
def flags = CLUSTER_FLAGS.tokenize(',')
def osds = OSD.tokenize(',')

def runCephCommand(master, cmd) {
    return salt.cmdRun(master, ADMIN_HOST, cmd)
}

node("python") {

    // create connection to salt master
    python.setupPepperVirtualenv(pepperEnv, SALT_MASTER_URL, SALT_MASTER_CREDENTIALS)

    if (flags.size() > 0) {
        stage('Set cluster flags') {
            for (flag in flags) {
                runCephCommand(pepperEnv, 'ceph osd set ' + flag)
            }
        }
    }

    def osd_ids = []

    print("osds:")
    print(osds)

    // get list of osd disks of the host
    def ceph_disks = salt.getGrain(pepperEnv, HOST, 'ceph')['return'][0].values()[0].values()[0]['ceph_disk']
    common.prettyPrint(ceph_disks)

    for (i in ceph_disks) {
        def osd_id = i.getKey().toString()
        if (osd_id in osds || OSD == '*') {
            osd_ids.add('osd.' + osd_id)
            print("Will delete " + osd_id)
        } else {
            print("Skipping " + osd_id)
        }
    }

    // `ceph osd out <id> <id>`
    stage('Set OSDs out') {
            runCephCommand(pepperEnv, 'ceph osd out ' + osd_ids.join(' '))
    }

    // wait for healthy cluster
    if (common.validInputParam('WAIT_FOR_HEALTHY') && WAIT_FOR_HEALTHY.toBoolean()) {
        stage('Waiting for healthy cluster') {
            while (true) {
                def health = runCephCommand(pepperEnv, 'ceph health')['return'][0].values()[0]
                if (health.contains('HEALTH OK')) {
                    common.infoMsg('Cluster is healthy')
                    break;
                }
                sleep(60)
            }
        }
    }

    // stop osd daemons
    stage('Stop OSD daemons') {
        for (i in osd_ids) {
            salt.runSaltProcessStep(pepperEnv, HOST, 'service.stop', ['ceph-osd@' + i.replaceAll('osd.', '')],  null, true)
        }
    }

    // `ceph osd crush remove osd.2`
    stage('Remove OSDs from CRUSH') {
        for (i in osd_ids) {
            runCephCommand(pepperEnv, 'ceph osd crush remove ' + i)
        }
    }

    // remove keyring `ceph auth del osd.3`
    stage('Remove OSD keyrings from auth') {
        for (i in osd_ids) {
            runCephCommand(pepperEnv, 'ceph auth del ' + i)
        }
    }

    // remove osd `ceph osd rm osd.3`
    stage('Remove OSDs') {
        for (i in osd_ids) {
            runCephCommand(pepperEnv, 'ceph osd rm ' + i)
        }
    }

    // remove cluster flags
    if (flags.size() > 0) {
        stage('Unset cluster flags') {
            for (flag in flags) {
                common.infoMsg('Removing flag ' + flag)
                runCephCommand(pepperEnv, 'ceph osd unset ' + flag)
            }
        }
    }
}
