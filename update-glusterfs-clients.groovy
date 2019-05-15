/**
 * Update packages on given server nodes
 *
 * Expected parameters:
 *   DRIVE_TRAIN_PARAMS         Yaml, DriveTrain releated params:
 *     SALT_MASTER_CREDENTIALS              Credentials to the Salt API
 *     SALT_MASTER_URL                      Full Salt API address [https://10.10.10.1:8000]
 *     IGNORE_SERVER_STATUS                 Does not validate server availability/status before update
 *     IGNORE_SERVER_VERSION                Does not validate that all servers have been updated
 *     TARGET_SERVERS                       Salt compound target to match nodes to be updated [*, G@osfamily:debian]
 */

// Convert parameters from yaml to env variables
params = readYaml text: env.DRIVE_TRAIN_PARAMS
for (key in params.keySet()) {
  value = params[key]
  env.setProperty(key, value)
}

@NonCPS
def getNextNode() {
  for (n in hudson.model.Hudson.instance.slaves) {
    node_name = n.getNodeName()
    if (node_name != env.SLAVE_NAME) {
      return node_name
    }
  }
}

def update() {
  def pEnv = "pepperEnv"
  def salt = new com.mirantis.mk.Salt()
  def common = new com.mirantis.mk.Common()
  def python = new com.mirantis.mk.Python()
  def pkg_name = 'glusterfs-client'

  /**
   * - choose only those hosts where update is available. Exclude minion on which job is running
   * - validate that all gluasterfs servers are in normal working state. Can be skipped with option
   * - validate that glusterfs on all servers has been updated, otherwise stop update. Can be skipped with option
   * - run update state on one client at a time
   */

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
        slave_container_id = salt.getReturnValues(salt.cmdRun(pEnv, minion, "which docker >/dev/null && docker ps --filter name=jenkins_${env.NODE_NAME} --filter status=running -q", false)).split('\n')[0]
        if (latest_version != current_version) {
          if (!slave_container_id.isEmpty() && !minion.startsWith('cfg')) {
            env.SLAVE_NAME = env.NODE_NAME
            env.SLAVE_MINION = minion
          } else {
            minions.add(minion)
          }
        } else {
          common.infoMsg("${pkg_name} has been already upgraded or newer version is not available on ${minion}. Skip upgrade")
        }
      }
    }
    if (!minions.isEmpty()) {
      if (!IGNORE_SERVER_STATUS.toBoolean()){
        stage('Validate servers availability') {
          salt.commandStatus(pEnv, 'I@glusterfs:server', "gluster pool list | fgrep localhost", 'Connected', true, true, null, true, 1)
          common.successMsg("All glusterfs servers are available")
        }
      } else {
        common.warningMsg("Check of glusterfs servers availability has been disabled")
      }
      if (!IGNORE_SERVER_VERSION.toBoolean()){
        stage('Check that all glusterfs servers have been updated') {
          latest_version = salt.getReturnValues(salt.runSaltProcessStep(pEnv, minions[0], 'pkg.latest_version', [pkg_name, 'show_installed=True'])).split('\n')[0].split('-')[0]
          salt.commandStatus(pEnv, 'I@glusterfs:server', "glusterfsd --version | head -n1 | awk '{print \$2}' | egrep '^${latest_version}' || echo none", latest_version, true, true, null, true, 1)
          common.successMsg('All glusterfs servers have been updated to desired version')
        }
      } else {
        common.warningMsg("Check of glusterfs servers' version has been disabled")
      }
      // Actual update
      for (tgt in minions) {
        stage("Update glusterfs on ${tgt}") {
          salt.runSaltProcessStep(pEnv, tgt, 'state.apply', ['glusterfs.update.client'])
        }
      }
    } else if (env.SLAVE_MINION == null) {
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
timeout(time: 12, unit: 'HOURS') {
  node() {
    update()
  }
  // Perform an update from another slave to finish update on previous slave host
  if (env.SLAVE_NAME != null && !env.SLAVE_NAME.isEmpty()) {
    node(getNextNode()) {
      update()
    }
  }
}
