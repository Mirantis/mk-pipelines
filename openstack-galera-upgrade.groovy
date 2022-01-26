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
      salt.enforceState(env, target, ['linux.system.repo'])
      common.stageWrapper(upgradeStageMap, "Pre upgrade", target, interactive) {
        openstack.runOpenStackUpgradePhase(env, target, 'pre')
        openstack.runOpenStackUpgradePhase(env, target, 'verify')
      }
    }

    if (shutdownCluster){
      for (target in stopTargets) {
        common.stageWrapper(upgradeStageMap, "Stop MySQL service", target, interactive) {
          openstack.runOpenStackUpgradePhase(env, target, 'service_stopped')
        }
      }
    }

    for (target in upgradeTargets) {
         out = salt.cmdRun(env, target,  'cat /var/lib/mysql/grastate.dat | grep "seqno" | cut -d ":" -f2', true, null, false).get('return')[0].values()[0].replaceAll('Salt command execution success', '').trim()
         common.infoMsg("Get seqno: ${out} for node ${target}")
         if (!out.isNumber()){
             out = -2
         }
        targetSecMapping[out.toInteger()] = target
        secNoList.add(out.toInteger())
    }

    def masterNode = targetSecMapping[secNoList.max()]
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
          openstack.runOpenStackUpgradePhase(env, target, 'pkgs_latest')
          openstack.runOpenStackUpgradePhase(env, target, 'render_config')
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
