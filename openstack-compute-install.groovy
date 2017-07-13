/**
 * Update packages on given nodes
 *
 * Expected parameters:
 *   SALT_MASTER_CREDENTIALS    Credentials to the Salt API.
 *   SALT_MASTER_URL            Full Salt API address [https://10.10.10.1:8000].
 *   TARGET_SERVERS             Salt compound target to match nodes to be updated [*, G@osfamily:debian].
 *
**/

def common = new com.mirantis.mk.Common()
def salt = new com.mirantis.mk.Salt()

def saltMaster
def minions
def result
def command
def commandKwargs


node() {
    try {

        stage('Connect to Salt master') {
            saltMaster = salt.connection(SALT_MASTER_URL, SALT_MASTER_CREDENTIALS)
        }

        stage('List target servers') {
            minions = salt.getMinions(saltMaster, TARGET_SERVERS)

            if (minions.isEmpty()) {
                throw new Exception("No minion was targeted")
            }

            targetLiveAll = minions.join(' or ')
            common.infoMsg("Found nodes: ${targetLiveAll}")
            common.infoMsg("Selected nodes: ${targetLiveAll}")
        }

        stage("Setup network for compute") {
            common.infoMsg("Now all network configuration will be enforced, which caused reboot of nodes: ${targetLiveAll}")
            try {
                salt.cmdRun(saltMaster, targetLiveAll, 'salt-call state.sls linux.system.user,openssh,linux.network;reboot')
            } catch(e) {
                common.infoMsg("no respond from nodes due reboot")
            }
            common.infoMsg("Now pipeline is waiting until node reconnect to salt master")
            timeout(800) {
                retry(666) {
                    try {
                        salt.runSaltCommand(saltMaster, 'local', ['expression': targetLiveAll, 'type': 'compound'], 'test.ping')
                    } catch(e) {
                        common.infoMsg("Still waiting for node to come up")
                        sleep(10)
                    }
                }
            }
        }

        stage("Deploy Compute") {
            common.infoMsg("Lets run rest of the states to finish deployment")
            salt.enforceState(saltMaster, targetLiveAll, 'linux,openssh,ntp,salt', true)
            retry(2) {
                salt.runSaltCommand(saltMaster, 'local', ['expression': targetLiveAll, 'type': 'compound'], 'state.apply')
            }
        }

    } catch (Throwable e) {
        // If there was an error or exception thrown, the build failed
        currentBuild.result = "FAILURE"
        throw e
    }
}
