/**
 *
 * Add Ceph node to existing cluster using upmap mechanism
 *
 * Requred parameters:
 *  SALT_MASTER_URL             URL of Salt master
 *  SALT_MASTER_CREDENTIALS     Credentials to the Salt API
 *  HOST                        Host (minion id) to be added
 *
 */

salt = new com.mirantis.mk.Salt()
def python = new com.mirantis.mk.Python()
def ceph = new com.mirantis.mk.Ceph()
orchestrate = new com.mirantis.mk.Orchestrate()
pepperEnv = "pepperEnv"
def flags = CLUSTER_FLAGS ? CLUSTER_FLAGS.tokenize(',') : []

def runCephCommand(cmd) {
    return salt.cmdRun(pepperEnv, "I@ceph:mon and I@ceph:common:keyring:admin", cmd, checkResponse = true, batch = null, output = false)
}

def getpgmap() {
    return runCephCommand('ceph pg ls remapped --format=json')['return'][0].values()[0]
}

def generatemapping(master,pgmap,map) {
    def pg_new
    def pg_old
    for (pg in pgmap) {
        pg_new = pg["up"].minus(pg["acting"])
        pg_old = pg["acting"].minus(pg["up"])
        for (i = 0; i < pg_new.size(); i++) {
            def string = "ceph osd pg-upmap-items " + pg["pgid"].toString() + " " + pg_new[i] + " " + pg_old[i] + ";"
            map.add(string)
        }
    }
}

timeout(time: 12, unit: 'HOURS') {
    node("python") {
        // create connection to salt master
        python.setupPepperVirtualenv(pepperEnv, SALT_MASTER_URL, SALT_MASTER_CREDENTIALS)

        stage("verify client versions") {
            def admin = salt.getMinions(pepperEnv, "I@ceph:mon and I@ceph:common:keyring:admin")[0]
            def versions = salt.cmdRun(pepperEnv, admin, "ceph features", checkResponse = true, batch = null, output = false).values()[0]

            if (versions[0][admin].contains('jewel')) {
                throw new Exception("Update all clients to luminous before using this pipeline")
            }
        }

        stage("enable luminous compat") {
            runCephCommand('ceph osd set-require-min-compat-client luminous')['return'][0].values()[0]
        }

        stage("enable upmap balancer") {
            runCephCommand('ceph balancer on')['return'][0].values()[0]
            runCephCommand('ceph balancer mode upmap')['return'][0].values()[0]
        }


        stage("set norebalance") {
            runCephCommand('ceph osd set norebalance')['return'][0].values()[0]
        }

        stage('Install Ceph OSD') {
            orchestrate.installCephOsd(pepperEnv, HOST)
        }

        def mapping = []

        stage("update mappings") {
            def pgmap
            for (int x = 1; x <= 3; x++) {
                pgmap = getpgmap()
                if (pgmap == '') {
                    return 1
                } else {
                    pgmap = new groovy.json.JsonSlurperClassic().parseText(pgmap)
                    generatemapping(pepperEnv, pgmap, mapping)
                    mapping.each(this.&runCephCommand)
                }
            }
        }

        stage("unset norebalance") {
            runCephCommand('ceph osd unset norebalance')['return'][0].values()[0]
        }

        stage("wait for healthy cluster") {
            ceph.waitForHealthy(pepperEnv, "I@ceph:mon and I@ceph:common:keyring:admin", flags)
        }
    }
}
