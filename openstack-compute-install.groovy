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
def python = new com.mirantis.mk.Python()

def pepperEnv = "pepperEnv"
def minions
def result
def command
def commandKwargs

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

                targetLiveAll = minions.join(' or ')
                common.infoMsg("Found nodes: ${targetLiveAll}")
                common.infoMsg("Selected nodes: ${targetLiveAll}")
            }

            stage("Trusty workaround") {
                if(salt.getGrain(pepperEnv, minions[0], "oscodename")['return'][0].values()[0]["oscodename"] == "trusty") {
                    common.infoMsg("First node %nodename% has trusty")
                    common.infoMsg("Assuming trusty on all cluster, running extra network states...")
                    common.infoMsg("Network iteration #1. Bonding")
                    salt.enforceState(pepperEnv, targetLiveAll, 'linux.network', true)
                    common.infoMsg("Network iteration #2. Vlan tagging and bridging")
                    salt.enforceState(pepperEnv, targetLiveAll, 'linux.network', true)
                }
            }

            stage("Setup repositories") {
                salt.enforceState(pepperEnv, targetLiveAll, 'linux.system.repo', true)
            }

            stage("Upgrade packages") {
                salt.runSaltProcessStep(pepperEnv, targetLiveAll, 'pkg.upgrade', [], null, true)
            }

            stage("Setup networking") {
                // Sync all of the modules from the salt master.
                salt.syncAll(pepperEnv, targetLiveAll)

                // Apply state 'salt' to install python-psutil for network configuration without restarting salt-minion to avoid losing connection.
                salt.runSaltProcessStep(pepperEnv, targetLiveAll, 'state.apply',  ['salt', 'exclude=[{\'id\': \'salt_minion_service\'}, {\'id\': \'salt_minion_service_restart\'}, {\'id\': \'salt_minion_sync_all\'}]'], null, true)

                // Restart salt-minion to take effect.
                salt.runSaltProcessStep(pepperEnv, targetLiveAll, 'service.restart', ['salt-minion'], null, true, 10)

                // Configure networking excluding vhost0 interface.
                salt.runSaltProcessStep(pepperEnv, targetLiveAll, 'state.apply',  ['linux.network', 'exclude=[{\'id\': \'linux_interface_vhost0\'}]'], null, true)

                // Kill unnecessary processes ifup/ifdown which is stuck from previous state linux.network.
                salt.runSaltProcessStep(pepperEnv, targetLiveAll, 'ps.pkill', ['ifup'], null, false)
                salt.runSaltProcessStep(pepperEnv, targetLiveAll, 'ps.pkill', ['ifdown'], null, false)

                // Restart networking to bring UP all interfaces.
                salt.runSaltProcessStep(pepperEnv, targetLiveAll, 'service.restart', ['networking'], null, true, 300)
            }

            stage("Highstate compute") {
                // Execute highstate without state opencontrail.client.
                salt.runSaltProcessStep(pepperEnv, targetLiveAll, 'state.highstate', ['exclude=opencontrail.client'], null, true)

                // Apply nova state to remove libvirt default bridge virbr0.
                salt.enforceState(pepperEnv, targetLiveAll, 'nova', true)

                // Execute highstate.
                salt.enforceHighstate(pepperEnv, targetLiveAll, true)

                // Restart supervisor-vrouter.
                salt.runSaltProcessStep(pepperEnv, targetLiveAll, 'service.restart', ['supervisor-vrouter'], null, true, 300)

                // Apply salt and collectd if is present to update information about current network interfaces.
                salt.enforceState(pepperEnv, targetLiveAll, 'salt', true)
                if(!salt.getPillar(pepperEnv, minions[0], "collectd")['return'][0].values()[0].isEmpty()) {
                    salt.enforceState(pepperEnv, targetLiveAll, 'collectd', true)
                }
            }

        stage("Install monitoring") {
            salt.enforceState(pepperEnv, targetLiveAll, 'prometheus')
            salt.enforceState(pepperEnv, 'I@prometheus', 'prometheus')
        }

        } catch (Throwable e) {
            // If there was an error or exception thrown, the build failed
            currentBuild.result = "FAILURE"
            currentBuild.description = currentBuild.description ? e.message + " " + currentBuild.description : e.message
            throw e
        }
    }
}
