/**
 *
 * Add OSD host to existing cluster
 *
 * Requred parameters:
 *  SALT_MASTER_URL             URL of Salt master
 *  SALT_MASTER_CREDENTIALS     Credentials to the Salt API
 *  HOST                        Host (minion id) to be added
 *
 */

common = new com.mirantis.mk.Common()
salt = new com.mirantis.mk.Salt()
orchestrate = new com.mirantis.mk.Orchestrate()
def python = new com.mirantis.mk.Python()

def pepperEnv = "pepperEnv"

node("python") {

    // create connection to salt master
    python.setupPepperVirtualenv(pepperEnv, SALT_MASTER_URL, SALT_MASTER_CREDENTIALS)

    // run basic states
    stage('Install infra') {
        orchestrate.installFoundationInfraOnTarget(pepperEnv, HOST)
    }
    // Install Ceph
    stage('Install Ceph') {
        orchestrate.installCephOsd(pepperEnv, HOST)
    }
}
