/**
 *
 * Enforce OSD weights from model
 *
 * Requred parameters:
 *  SALT_MASTER_URL             URL of Salt master
 *  SALT_MASTER_CREDENTIALS     Credentials to the Salt API
 *
 *  ADMIN_HOST                  Host (minion id) with admin keyring
 *
 */

common = new com.mirantis.mk.Common()
salt = new com.mirantis.mk.Salt()
def python = new com.mirantis.mk.Python()

def pepperEnv = "pepperEnv"

def runCephCommand(master, cmd) {
    return salt.cmdRun(master, ADMIN_HOST, cmd)
}

def grains

node("python") {

    stage('Load cluster information') {
        python.setupPepperVirtualenv(pepperEnv, SALT_MASTER_URL, SALT_MASTER_CREDENTIALS)

        // get list of disk from grains
        grains = salt.getGrain(pepperEnv, 'I@ceph:osd')['return'][0]
        common.prettyPrint(grains)

    }

    stage('Enforce weights on OSDs') {

        for (host in grains) {
            // parse grains
            def hostGrains = host.value
            common.prettyPrint(hostGrains)

            def hostname = hostGrains.host
            def salt_id = hostGrains.id
            def ceph_host_id = hostGrains.ceph_osd_host_id

            common.infoMsg("Setting weights on host ${hostname} (${salt_id}), ceph_id ${ceph_host_id}")
            for (disk in hostGrains.ceph_osd_disk) {
                def osd_id = ceph_host_id + disk.key
                print(osd_id)
                print(disk.value)
                print(disk.key)
                def cmd = "ceph osd crush set ${osd_id} ${disk.value.weight} host=${hostname}"
                print(runCephCommand(pepperEnv, cmd))
            }
        }

    }
}
