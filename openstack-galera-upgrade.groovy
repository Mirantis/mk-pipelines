/**
 * Upgrade MySQL and Galera packages on dbs nodes.
 * Update packages on given nodes
 *
 * Expected parameters:
 *   SALT_MASTER_CREDENTIALS            Credentials to the Salt API.
 *   SALT_MASTER_URL                    Full Salt API address [http://10.10.10.15:6969].
 *   SHUTDOWN_CLUSTER                   Shutdown all mysql instances on target nodes at the same time.
 *   OS_DIST_UPGRADE                    Upgrade system packages including kernel (apt-get dist-upgrade).
 *   OS_UPGRADE                         Upgrade all installed applications (apt-get upgrade)
 *   TARGET_SERVERS                     Comma separated list of salt compound definitions to upgrade.
 *   INTERACTIVE                        Ask interactive questions during pipeline run (bool).
 *   UPDATE_TO_MYSQL57                  Set this flag if you are updating MySQL from 5.6 to 5.7
 *
**/

def common = new com.mirantis.mk.Common()
def salt = new com.mirantis.mk.Salt()
def python = new com.mirantis.mk.Python()
def debian = new com.mirantis.mk.Debian()
def openstack = new com.mirantis.mk.Openstack()
def galera = new com.mirantis.mk.Galera()
def shutdownCluster = SHUTDOWN_CLUSTER.toBoolean()
def interactive = INTERACTIVE.toBoolean()
def LinkedHashMap upgradeStageMap = [:]
def updateToMysql57 = UPDATE_TO_MYSQL57.toBoolean()

upgradeStageMap.put('Pre upgrade',
  [
    'Description': 'Only non destructive actions will be applied during this phase. Basic service verification will be performed.',
    'Status': 'NOT_LAUNCHED',
    'Expected behaviors': '''
 * No service downtime
 * No workload downtime''',
    'Launched actions': '''
 * Verify API, perform basic CRUD operations for services.
 * Verify MySQL is running and Galera cluster is operational.''',
    'State result': 'Basic checks around wsrep Galera status are passed.'
  ])

upgradeStageMap.put('Stop MySQL service',
  [
    'Description': 'All MySQL services will be stopped on All TARGET_SERVERS nodes.',
    'Status': 'NOT_LAUNCHED',
    'Expected behaviors': '''
 * MySQL services are stopped.
 * OpenStack APIs are not accessible from this point.
 * No workload downtime''',
    'Launched actions': '''
 * Stop MySQL services''',
    'State result': 'MySQL service is stopped',
  ])

upgradeStageMap.put('Upgrade OS',
  [
    'Description': 'Optional step. OS packages will be upgraded during this phase, depending on the job parameters dist-upgrade might be called. And reboot of node executed.',
    'Status': 'NOT_LAUNCHED',
    'Expected behaviors': '''
 * No workload downtime
 * The nodes might be rebooted''',
    'Launched actions': '''
 * Install new version of system packages
 * If doing dist-upgrade new kernel might be installed and node rebooted
 * System packages are updated
 * Node might be rebooted
'''
  ])

upgradeStageMap.put('Upgrade MySQL server',
   [
    'Description': 'MySQL and Erlang code will be upgraded during this stage. No workload downtime is expected.',
    'Status': 'NOT_LAUNCHED',
    'Expected behaviors': '''
 * OpenStack services loose connection to MySQL server
 * No workload downtime''',
    'Launched actions': '''
 * Install new version of MySQL and Galera packages
 * Render version of configs''',
    'State result': '''
 * MySQL packages are upgraded''',
  ])

upgradeStageMap.put('Start MySQL service',
   [
    'Description': 'All MySQL services will be running on All TARGET_SERVERS nodes.',
    'Status': 'NOT_LAUNCHED',
    'Expected behaviors': '''
 * MySQL service is running.
 * OpenStack API are accessible from this point.
 * No workload downtime''',
    'Launched actions': '''
 * Start MySQL service''',
    'State result': 'MySQL service is running',
  ])

def env = "env"
timeout(time: 12, unit: 'HOURS') {
  node() {

    stage('Setup virtualenv for Pepper') {
      python.setupPepperVirtualenv(env, SALT_MASTER_URL, SALT_MASTER_CREDENTIALS)
    }

    def upgradeTargets = salt.getMinionsSorted(env, TARGET_SERVERS)

    if (upgradeTargets.isEmpty()) {
      error("No servers for upgrade matched by ${TARGET_SERVERS}")
    }

    def targetSecMapping = [:]
    def secNoList = []
    def out
    def stopTargets = upgradeTargets.reverse()
    common.printStageMap(upgradeStageMap)

    if (interactive){
      input message: common.getColorizedString(
        "Above you can find detailed info this pipeline will execute.\nThe info provides brief description of each stage, actions that will be performed and service/workload impact during each stage.\nPlease read it carefully.", "yellow")
    }

    for (target in upgradeTargets) {
      salt.runSaltProcessStep(env, target, 'saltutil.refresh_pillar', [], null, true)
      mysqlPillarVersion = salt.getPillar(env, target, "galera:version:mysql").get("return")[0].values()[0].toString().toLowerCase()
      mysql56InstalledVersion = salt.getReturnValues(salt.runSaltProcessStep(env, target, 'pkg.version', 'mysql-wsrep-server-5.6', null, true)).toString().toLowerCase().take(3)
      mysql57InstalledVersion = salt.getReturnValues(salt.runSaltProcessStep(env, target, 'pkg.version', 'mysql-wsrep-server-5.7', null, true)).toString().toLowerCase().take(3)

      if (mysqlPillarVersion == '5.6' && mysql57InstalledVersion == '5.7') {
        error("""Pre upgrade check failed. You are trying to downgrade MySQL package from 5.7 version to 5.6.
                 Check value for galera_mysql_version variable in your model.""")
      }
      if (mysqlPillarVersion == '5.7' && mysql56InstalledVersion == '5.6' && updateToMysql57 != true) {
        error("""Pre upgrade check failed. You are trying to update MySQL package from version 5.6 to version 5.7 the wrong way.
                  If you want to update from 5.6 version to 5.7 set flag UPDATE_TO_MYSQL57 in the current job.
                  If you don't want to update from 5.6 version to 5.7 you need to change the value for galera_mysql_version to 5.6 in your model.""")
      }
      if (mysqlPillarVersion == '5.6' && updateToMysql57 == true) {
        error("""Pre upgrade check failed. You are trying to update MySQL package from version 5.6 to version 5.7 the wrong way.
                  If you want to update from 5.6 version to 5.7 you need to set galera_mysql_version variable in your model to 5.7 value.
                  If you don't want to update from 5.6 version to 5.7 you need to unset flag UPDATE_TO_MYSQL57""")
      }

      salt.enforceState(env, target, ['linux.system.repo'])
      common.stageWrapper(upgradeStageMap, "Pre upgrade", target, interactive) {
        openstack.runOpenStackUpgradePhase(env, target, 'pre')
        openstack.runOpenStackUpgradePhase(env, target, 'verify')
      }
    }

    if (updateToMysql57 == true) {
      shutdownCluster = true
    }

    if (shutdownCluster){
      for (target in stopTargets) {
        common.stageWrapper(upgradeStageMap, "Stop MySQL service", target, interactive) {
          openstack.runOpenStackUpgradePhase(env, target, 'service_stopped')
        }
      }
    }

    def masterNode = salt.getMinionsSorted(env, galera.getGaleraLastShutdownNode(env))[0]
    common.infoMsg("Master node is: ${masterNode}")

    // Make sure we start upgrade always from master node
    upgradeTargets.remove(masterNode)
    upgradeTargets = [masterNode] + upgradeTargets
    common.infoMsg("Upgrade targets are: ${upgradeTargets}")

    for (target in upgradeTargets) {

        common.stageWrapper(upgradeStageMap, "Stop MySQL service", target, interactive) {
          openstack.runOpenStackUpgradePhase(env, target, 'service_stopped')
        }

        common.stageWrapper(upgradeStageMap, "Upgrade OS", target, interactive) {
          if (OS_DIST_UPGRADE.toBoolean() == true){
            upgrade_mode = 'dist-upgrade'
          } else if (OS_UPGRADE.toBoolean() == true){
            upgrade_mode = 'upgrade'
          }
          if (OS_DIST_UPGRADE.toBoolean() == true || OS_UPGRADE.toBoolean() == true) {
            debian.osUpgradeNode(env, target, upgrade_mode, false)
          }
        }

        common.stageWrapper(upgradeStageMap, "Upgrade MySQL server", target, interactive) {
          if (updateToMysql57 == true) {
            if (target == masterNode) {
              openstack.runOpenStackUpgradePhase(env, target, 'update_master')
            }
            else {
              openstack.runOpenStackUpgradePhase(env, target, 'update_slave')
            }
          }
          else {
            openstack.runOpenStackUpgradePhase(env, target, 'pkgs_latest')
            openstack.runOpenStackUpgradePhase(env, target, 'render_config')
          }
        }

        if (shutdownCluster && target == masterNode){
          //Start first node.
          common.stageWrapper(upgradeStageMap, "Start MySQL service", target, interactive) {
            galera.startFirstNode(env, target)
          }
        }

        common.stageWrapper(upgradeStageMap, "Start MySQL service", target, interactive) {
          openstack.runOpenStackUpgradePhase(env, target, 'service_running')
          openstack.runOpenStackUpgradePhase(env, target, 'verify')
        }
    }

    // restart first node by applying state.

    if (shutdownCluster) {
      openstack.runOpenStackUpgradePhase(env, masterNode, 'render_config')
      salt.cmdRun(env, masterNode, "systemctl restart mysql")
      openstack.runOpenStackUpgradePhase(env, masterNode, 'verify')
    }

    for (target in upgradeTargets) {
      ensureClusterState = galera.getWsrepParameters(env, target, 'wsrep_evs_state')
      if (ensureClusterState['wsrep_evs_state'] == 'OPERATIONAL') {
        common.infoMsg('Node is in OPERATIONAL state.')
      } else {
        throw new Exception("Node is NOT in OPERATIONAL state.")
      }
    }
  }
}
