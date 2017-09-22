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

        stage("Trusty workaround") {
            if(salt.getGrain(saltMaster, minions[0], "oscodename")['return'][0].values()[0]["oscodename"] == "trusty") {
                common.infoMsg("First node %nodename% has trusty")
                common.infoMsg("Assuming trusty on all cluster, running extra network states...")
                common.infoMsg("Network iteration #1. Bonding")
                salt.enforceState(saltMaster, targetLiveAll, 'linux.network', true)
                common.infoMsg("Network iteration #2. Vlan tagging and bridging")
                salt.enforceState(saltMaster, targetLiveAll, 'linux.network', true)
            }
        }

        stage("Setup repositories") {
            salt.enforceState(saltMaster, targetLiveAll, 'linux.system.repo', true)
        }

        stage("Upgrade packages") {
            salt.runSaltProcessStep(saltMaster, targetLiveAll, 'pkg.upgrade', [], null, true)
        }

        stage("Setup networking") {
            // Sync all of the modules from the salt master.
            salt.syncAll(saltMaster, targetLiveAll)

            // Apply state 'salt' to install python-psutil for network configuration without restarting salt-minion to avoid losing connection.
            salt.runSaltProcessStep(saltMaster, targetLiveAll, 'state.apply',  ['salt', 'exclude=[{\'id\': \'salt_minion_service\'}, {\'id\': \'salt_minion_service_restart\'}, {\'id\': \'salt_minion_sync_all\'}]'], null, true)

            // Restart salt-minion to take effect.
            salt.runSaltProcessStep(saltMaster, targetLiveAll, 'service.restart', ['salt-minion'], null, true, 10)

            // Configure networking excluding vhost0 interface.
            salt.runSaltProcessStep(saltMaster, targetLiveAll, 'state.apply',  ['linux.network', 'exclude=[{\'id\': \'linux_interface_vhost0\'}]'], null, true)

            // Kill unnecessary processes ifup/ifdown which is stuck from previous state linux.network.
            salt.runSaltProcessStep(saltMaster, targetLiveAll, 'ps.pkill', ['ifup'], null, false)
            salt.runSaltProcessStep(saltMaster, targetLiveAll, 'ps.pkill', ['ifdown'], null, false)

            // Restart networking to bring UP all interfaces.
            salt.runSaltProcessStep(saltMaster, targetLiveAll, 'service.restart', ['networking'], null, true, 300)
        }

        stage("Highstate compute") {
            // Execute highstate without state opencontrail.client.
            salt.runSaltProcessStep(saltMaster, targetLiveAll, 'state.highstate', ['exclude=opencontrail.client'], null, true)

            // Apply nova state to remove libvirt default bridge virbr0.
            salt.enforceState(saltMaster, targetLiveAll, 'nova', true)

            // Execute highstate.
            salt.enforceHighstate(saltMaster, targetLiveAll, true)

            // Restart supervisor-vrouter.
            salt.runSaltProcessStep(saltMaster, targetLiveAll, 'service.restart', ['supervisor-vrouter'], null, true, 300)

            // Apply salt,collectd to update information about current network interfaces.
            salt.enforceState(saltMaster, targetLiveAll, 'salt,collectd', true)
        }

    } catch (Throwable e) {
        // If there was an error or exception thrown, the build failed
        currentBuild.result = "FAILURE"
        currentBuild.description = currentBuild.description ? e.message + " " + currentBuild.description : e.message
        throw e
    }
}
