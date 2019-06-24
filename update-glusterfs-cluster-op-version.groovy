/**
 * Update packages on given server nodes
 *
 * Expected parameters:
 *   DRIVE_TRAIN_PARAMS         Yaml, DriveTrain releated params:
 *     SALT_MASTER_CREDENTIALS              Credentials to the Salt API
 *     SALT_MASTER_URL                      Full Salt API address [https://10.10.10.1:8000]
 *     IGNORE_CLIENT_VERSION                Does not validate that all clients have been updated
 *     IGNORE_SERVER_VERSION                Does not validate that all servers have been updated
 *     CLUSTER_OP_VERSION                   GlusterFS cluster.op-verion option to set. Default is to be set to current cluster.max-op-version if available.
 */

def pEnv = "pepperEnv"
def salt = new com.mirantis.mk.Salt()
def common = new com.mirantis.mk.Common()
def python = new com.mirantis.mk.Python()

// Convert parameters from yaml to env variables
params = readYaml text: env.DRIVE_TRAIN_PARAMS
for (key in params.keySet()) {
  value = params[key]
  env.setProperty(key, value)
}

/**
 * - ensure that cluster.op-version can be updated
 * - check that all servers have been updated to version no less then CLUSTER_OP_VERSION or cluster.max-op-version
 * - check that all clients have been updated to version no less then CLUSTER_OP_VERSION or cluster.max-op-version
 * - set cluster.op-version
 */

/**
 * Convert glusterfs' cluster.op-version to regular version string
 *
 * @param version string representing cluster.op-version, i.e. 50400
 * @return string version number, i.e. 5.4.0
 */
def convertVersion(version) {
    new_version = version[0]
    for (i=1;i<version.length();i++) {
        if (i%2 == 0) {
            new_version += version[i]
        } else if (version[i] == '0') {
            new_version += '.'
        } else {
            new_version += '.' + version[i]
        }
    }
    return new_version
}

timeout(time: 12, unit: 'HOURS') {
  node() {
    try {

      stage('Setup virtualenv for Pepper') {
        python.setupPepperVirtualenv(pEnv, SALT_MASTER_URL, SALT_MASTER_CREDENTIALS)
      }
      stage('Get current cluster.op-version') {
        volume = salt.getReturnValues(salt.cmdRun(pEnv, 'I@glusterfs:server:role:primary', "gluster volume list")).split('\n')[0]
        currentOpVersion = salt.getReturnValues(salt.cmdRun(pEnv, 'I@glusterfs:server:role:primary', "gluster volume get ${volume} cluster.op-version | grep cluster.op-version | awk '{print \$2}'")).split('\n')[0]
      }
      if (CLUSTER_OP_VERSION.isEmpty()) {
        stage('Get cluster.max-op-version') {
          CLUSTER_OP_VERSION = salt.getReturnValues(salt.cmdRun(pEnv, 'I@glusterfs:server:role:primary', "gluster volume get all cluster.max-op-version 2>/dev/null | grep cluster.max-op-version | awk '{print \$2}'")).split('\n')[0]
        }
      }
      if (CLUSTER_OP_VERSION.isEmpty() || CLUSTER_OP_VERSION.length() != 5) {
        msg = 'No cluster.op-version specified to set'
        common.errorMsg(msg)
        currentBuild.result = "FAILURE"
        currentBuild.description = msg
      } else if (currentOpVersion == CLUSTER_OP_VERSION) {
        common.warningMsg("cluster.op-version is already set to ${currentOpVersion}")
      } else {
        version = convertVersion(CLUSTER_OP_VERSION)
        if (!IGNORE_SERVER_VERSION.toBoolean()){
          stage('Check that all servers have been updated') {
            salt.commandStatus(pEnv, 'I@glusterfs:server', "dpkg --compare-versions \$(glusterfsd --version | head -n1| awk '{print \$2}') gt ${version} && echo good", 'good', true, true, null, true, 1)
            common.successMsg('All servers have been updated to desired version')
          }
        } else {
          common.warningMsg("Check of servers' version has been disabled")
        }
        if (!IGNORE_CLIENT_VERSION.toBoolean()){
          stage('Check that all clients have been updated') {
            salt.commandStatus(pEnv, 'I@glusterfs:client', "dpkg --compare-versions \$(glusterfsd --version | head -n1| awk '{print \$2}') gt ${version} && echo good", 'good', true, true, null, true, 1)
            common.successMsg('All clients have been updated to desired version')
          }
        } else {
          common.warningMsg("Check of clients' version has been disabled")
        }
        stage("Update cluster.op-version") {
          salt.cmdRun(pEnv, 'I@glusterfs:server:role:primary', "gluster volume set all cluster.op-version ${CLUSTER_OP_VERSION}")
        }
        stage("Validate cluster.op-version") {
          newOpVersion = salt.getReturnValues(salt.cmdRun(pEnv, 'I@glusterfs:server:role:primary', "gluster volume get ${volume} cluster.op-version | grep cluster.op-version | awk '{print \$2}'")).split('\n')[0]
          if (newOpVersion != CLUSTER_OP_VERSION) {
            throw new Exception("cluster.op-version was not set to ${CLUSTER_OP_VERSION}")
          }
        }
      }
    } catch (Throwable e) {
      // If there was an error or exception thrown, the build failed
      currentBuild.result = "FAILURE"
      currentBuild.description = currentBuild.description ? e.message + " " + currentBuild.description : e.message
      throw e
    }
  }
}
