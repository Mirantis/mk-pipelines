/**
 * Upgrade RabbitMQ packages on msg nodes.
 * Update packages on given nodes
 *
 * Expected parameters:
 *   SALT_MASTER_CREDENTIALS            Credentials to the Salt API.
 *   SALT_MASTER_URL                    Full Salt API address [http://10.10.10.15:6969].
 *   OS_DIST_UPGRADE                    Upgrade system packages including kernel (apt-get dist-upgrade)
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
 * Refresh pillars on the target nodes.
 * Apply the 'linux.system.repo' state on the target nodes.
 * Verify API, perform basic CRUD operations for services.
 * Verify rabbitmq is running and operational.''',
    'State result': 'Basic checks around services API are passed.'
  ])

upgradeStageMap.put('Stop RabbitMQ service',
  [
    'Description': 'All rabbitmq services will be stopped on All TARGET_SERVERS nodes.',
    'Status': 'NOT_LAUNCHED',
    'Expected behaviors': '''
 * RabbitMQ services are stopped.
 * OpenStack APIs are not accessible from this point.
 * No workload downtime''',
    'Launched actions': '''
 * Stop RabbitMQ services''',
    'State result': 'RabbitMQ service is stopped',
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

upgradeStageMap.put('Upgrade RabbitMQ server',
   [
    'Description': 'RabbitMQ and Erlang code will be upgraded during this stage. No workload downtime is expected.',
    'Status': 'NOT_LAUNCHED',
    'Expected behaviors': '''
 * OpenStack services loose connection to rabbitmq-server
 * No workload downtime''',
    'Launched actions': '''
 * Install new version of RabbitMQ and Erlang packages
 * Render version of configs''',
    'State result': '''
 * RabbitMQ packages are upgraded''',
  ])

upgradeStageMap.put('Start RabbitMQ service',
   [
    'Description': 'All rabbitmq services will be running on All TARGET_SERVERS nodes.',
    'Status': 'NOT_LAUNCHED',
    'Expected behaviors': '''
 * RabbitMQ service is running.
 * OpenStack API are accessible from this point.
 * No workload downtime''',
    'Launched actions': '''
 * Start RabbitMQ service''',
    'State result': 'RabbitMQ service is running',
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

    def stopTargets = upgradeTargets.reverse()

    common.printStageMap(upgradeStageMap)
    if (interactive){
      input message: common.getColorizedString(
        "Above you can find detailed info this pipeline will execute.\nThe info provides brief description of each stage, actions that will be performed and service/workload impact during each stage.\nPlease read it carefully.", "yellow")
    }

    for (target in upgradeTargets){
      common.stageWrapper(upgradeStageMap, "Pre upgrade", target, interactive) {
        openstack.runOpenStackUpgradePhase(env, target, 'pre')
        salt.runSaltProcessStep(env, target, 'saltutil.refresh_pillar', [], null, true)
        salt.enforceState(env, target, 'linux.system.repo')
        openstack.runOpenStackUpgradePhase(env, target, 'verify')
      }
    }

    for (target in stopTargets) {
      common.stageWrapper(upgradeStageMap, "Stop RabbitMQ service", target, interactive) {
        openstack.runOpenStackUpgradePhase(env, target, 'service_stopped')
      }
    }

    for (target in upgradeTargets) {
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
    }

    for (target in upgradeTargets) {
      common.stageWrapper(upgradeStageMap, "Upgrade RabbitMQ server", target, interactive) {
        openstack.runOpenStackUpgradePhase(env, target, 'pkgs_latest')
        openstack.runOpenStackUpgradePhase(env, target, 'render_config')
      }
    }

    for (target in upgradeTargets) {
      common.stageWrapper(upgradeStageMap, "Start RabbitMQ service", target, interactive) {
        openstack.runOpenStackUpgradePhase(env, target, 'service_running')
        openstack.applyOpenstackAppsStates(env, target)
        openstack.runOpenStackUpgradePhase(env, target, 'verify')
      }
    }
  }
}
