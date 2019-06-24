/**
 * Update packages on given server nodes
 *
 * Expected parameters:
 *   DRIVE_TRAIN_PARAMS         Yaml, DriveTrain releated params:
 *     SALT_MASTER_CREDENTIALS              Credentials to the Salt API
 *     SALT_MASTER_URL                      Full Salt API address [https://10.10.10.1:8000]
 *     IGNORE_SERVER_STATUS                 Does not validate server availability/status before update
 *     IGNORE_NON_REPLICATED_VOLUMES        Update GlusterFS even there is a non-replicated volume(s)
 *     TARGET_SERVERS                       Salt compound target to match nodes to be updated [*, G@osfamily:debian]
 */

def pEnv = "pepperEnv"
def salt = new com.mirantis.mk.Salt()
def common = new com.mirantis.mk.Common()
def python = new com.mirantis.mk.Python()
def pkg_name = 'glusterfs-server'

// Convert parameters from yaml to env variables
params = readYaml text: env.DRIVE_TRAIN_PARAMS
for (key in params.keySet()) {
  value = params[key]
  env.setProperty(key, value)
}

/**
 * - choose only those hosts where update is available
 * - validate that all servers are in normal working state. Can be skipped with option
 * - validate all volumes are replicated. If there is a non-replicated volume stop update. Can be skipped with option
 * - run update state on one server at a time
 */

timeout(time: 12, unit: 'HOURS') {
  node() {
    try {

      stage('Setup virtualenv for Pepper') {
        python.setupPepperVirtualenv(pEnv, SALT_MASTER_URL, SALT_MASTER_CREDENTIALS)
      }

      stage('List target servers') {
        all_minions = salt.getMinions(pEnv, TARGET_SERVERS)

        if (all_minions.isEmpty()) {
          throw new Exception("No minion was targeted")
        }
        minions = []
        for (minion in all_minions) {
          latest_version = salt.getReturnValues(salt.runSaltProcessStep(pEnv, minion, 'pkg.latest_version', [pkg_name, 'show_installed=True'])).split('\n')[0]
          current_version = salt.getReturnValues(salt.runSaltProcessStep(pEnv, minion, 'pkg.version', [pkg_name])).split('\n')[0]
          if (latest_version != current_version) {
            minions.add(minion)
          } else {
            common.infoMsg("${pkg_name} has been already upgraded or newer version is not available on ${minion}. Skip upgrade")
          }
        }
      }
      if (!minions.isEmpty()) {
        if (!IGNORE_SERVER_STATUS.toBoolean()){
          stage('Validate servers availability') {
            salt.commandStatus(pEnv, TARGET_SERVERS, "gluster pool list | fgrep localhost", 'Connected', true, true, null, true, 1)
            common.successMsg("All servers are available")
          }
        } else {
          common.warningMsg("Check of servers availability has been disabled")
        }
        if (!IGNORE_NON_REPLICATED_VOLUMES.toBoolean()){
          stage('Check that all volumes are replicated') {
            salt.commandStatus(pEnv, TARGET_SERVERS, "gluster volume info | fgrep 'Type:' | fgrep -v Replicate", null, false, true, null, true, 1)
            common.successMsg("All volumes are replicated")
          }
        } else {
          common.warningMsg("Check of volumes' replication has been disabled. Be aware, you may lost data during update!")
        }
        // Actual update
        for (tgt in minions) {
          stage("Update glusterfs on ${tgt}") {
            salt.runSaltProcessStep(pEnv, tgt, 'state.apply', ['glusterfs.update.server'])
          }
        }
      } else {
        common.warningMsg("No hosts to update glusterfs on")
      }
    } catch (Throwable e) {
      // If there was an error or exception thrown, the build failed
      currentBuild.result = "FAILURE"
      currentBuild.description = currentBuild.description ? e.message + " " + currentBuild.description : e.message
      salt.runSaltProcessStep(pEnv, TARGET_SERVERS, 'state.apply', ['glusterfs'])
      throw e
    }
  }
}
