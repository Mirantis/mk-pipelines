/**
 * Upgrade OpenStack packages on gateway nodes.
 * Update packages on given nodes
 *
 * Expected parameters:
 *   SALT_MASTER_CREDENTIALS            Credentials to the Salt API.
 *   SALT_MASTER_URL                    Full Salt API address [http://10.10.10.1:8000].
 *   OS_DIST_UPGRADE                    Upgrade system packages including kernel (apt-get dist-upgrade)
 *   OS_UPGRADE                         Upgrade all installed applications (apt-get upgrade)
 *   TARGET_SERVERS                     Comma separated list of salt compound definitions to upgrade.
 *   INTERACTIVE                        Ask interactive questions during pipeline run (bool).
 *
 * TODO:
 *   * Add OS_RELEASE_UPGRADE
**/

def common = new com.mirantis.mk.Common()
def salt = new com.mirantis.mk.Salt()
def python = new com.mirantis.mk.Python()
def openstack = new com.mirantis.mk.Openstack()
def debian = new com.mirantis.mk.Debian()

def interactive = INTERACTIVE.toBoolean()
def LinkedHashMap upgradeStageMap = [:]

upgradeStageMap.put('Pre upgrade',
  [
    'Description': 'Only non destructive actions will be applied during this phase. Basic api, service verification will be performed.',
    'Status': 'NOT_LAUNCHED',
    'Expected behaviors': '''
 * No service downtime
 * No workload downtime''',
    'Launched actions': '''
 * Refresh pillars on the target nodes.
 * Apply the 'linux.system.repo' state on the target nodes.
 * Verify API, perform basic CRUD operations for services.
 * Verify that compute/neutron agents on hosts are up.
 * Run some service built in checkers like keystone-manage doctor or nova-status upgrade.''',
    'State result': 'Basic checks around services API are passed.'
  ])
upgradeStageMap.put('Upgrade pre: migrate resources',
  [
    'Description': 'In order to minimize workload downtime smooth resource migration is happening during this phase. Neutron agents on node are set to admin_disabled state, to make sure they are quickly migrated to new node (1-2 ping loss). Instances might be live-migrated from host (this stage is optional) and configured from pillar.',
    'Status': 'NOT_LAUNCHED',
    'Expected behaviors': '''
 * No service downtime
 * Small workload downtime''',
    'Launched actions': '''
 * Set neutron agents to admin disabled sate
 * Migrate instances if allowed (optional).''',
    'State result': '''
 * Hosts are being removed from scheduling to host new resources.
 * If instance migration was performed no instances should be present.'''
  ])
upgradeStageMap.put('Upgrade OS',
  [
    'Description': 'Optional step. OS packages will be upgraded during this phase, depending on the job parameters dist-upgrade might be called. And reboot of node executed.',
    'Status': 'NOT_LAUNCHED',
    'Expected behaviors': '''
 * OpenStack services might flap
 * No workload downtime
 * The nodes might be rebooted''',
    'Launched actions': '''
 * Install new version of system packages
 * If doing dist-upgrade new kernel might be installed and node rebooted
 * System packages are updated
 * Node might be rebooted
'''
  ])
upgradeStageMap.put('Upgrade OpenStack',
   [
    'Description': 'OpenStack python code will be upgraded during this stage. No workload downtime is expected.',
    'Status': 'NOT_LAUNCHED',
    'Expected behaviors': '''
 * OpenStack services might flap
 * No workload downtime''',
    'Launched actions': '''
 * Install new version of OpenStack packages
 * Render version of configs
 * Apply offline dbsync
 * Start OpenStack services
 * Verify agents are alive/connected
 * Run basic API validation''',
    'State result': '''
 * OpenStack packages are upgraded
 * Services are running
 * Basic checks around services API are passed
 * Verified that agents/services on data plane nodes are connected to new control plane
'''
  ])
upgradeStageMap.put('Upgrade post: enable resources',
  [
    'Description': 'Verify that agents/services on node are up, add them back to scheduling.',
    'Status': 'NOT_LAUNCHED',
    'Expected behaviors': '''
 * No service downtime
 * No workload downtime''',
    'Launched actions': '''
 * Set neutron agents to admin sate enabled
 * Enable nova-compute services''',
    'State result': 'Hosts are being added to scheduling to host new resources',
  ])
upgradeStageMap.put('Post upgrade',
  [
    'Description': 'Only non destructive actions will be applied during this phase. Like cleanup old configs, cleanup temporary files. Online dbsyncs.',
    'Status': 'NOT_LAUNCHED',
    'Expected behaviors': '''
 * No service downtime
 * No workload downtime''',
    'Launched actions': '''
 * Cleanup os client configs''',
    'State result': 'Temporary resources are being cleaned.'
  ])


def env = "env"
timeout(time: 24, unit: 'HOURS') {
  node() {

    stage('Setup virtualenv for Pepper') {
      python.setupPepperVirtualenv(env, SALT_MASTER_URL, SALT_MASTER_CREDENTIALS)
    }

    def targetNodes = salt.getMinionsSorted(env, TARGET_SERVERS)
    def migrateResources = true

    if (targetNodes.isEmpty()) {
      error("No servers for upgrade matched by ${TARGET_SERVERS}")
    }
    if (targetNodes.size() == 1 ){
      migrateResources = false
    }

    common.printStageMap(upgradeStageMap)
    if (interactive){
      input message: common.getColorizedString(
        "Above you can find detailed info this pipeline will execute.\nThe info provides brief description of each stage, actions that will be performed and service/workload impact during each stage.\nPlease read it carefully.", "yellow")
    }

    for (target in targetNodes){
      common.stageWrapper(upgradeStageMap, "Pre upgrade", target, interactive) {
        openstack.runOpenStackUpgradePhase(env, target, 'pre')
        salt.runSaltProcessStep(env, target, 'saltutil.refresh_pillar', [], null, true)
        salt.enforceState(env, target, 'linux.system.repo')
        openstack.runOpenStackUpgradePhase(env, target, 'verify')
      }

      common.stageWrapper(upgradeStageMap, "Upgrade pre: migrate resources", target, interactive) {
        if (migrateResources) {
          common.infoMsg("Migrating neutron resources from ${target}")
          openstack.runOpenStackUpgradePhase(env, target, 'upgrade.pre')
          // Start upgrade only when resources were successfully migrated
        }
      }

      common.stageWrapper(upgradeStageMap, "Upgrade OS", target, interactive) {
        if (OS_DIST_UPGRADE.toBoolean() == true){
          upgrade_mode = 'dist-upgrade'
        } else if (OS_UPGRADE.toBoolean() == true){
          upgrade_mode = 'upgrade'
        }
        if (OS_DIST_UPGRADE.toBoolean() == true || OS_UPGRADE.toBoolean() == true) {
          debian.osUpgradeNode(env, target, upgrade_mode, false, 60, 10)
        }
        salt.checkTargetMinionsReady(['saltId': env, 'target': target, wait: 60, timeout: 10])
        // Workaround for PROD-31413, install python-tornado from latest release if available and
        // restart minion to apply new code.
        salt.upgradePackageAndRestartSaltMinion(env, target, 'python-tornado')
      }

      common.stageWrapper(upgradeStageMap, "Upgrade OpenStack", target, interactive) {
        // Stop services on node. //Do actual step by step orch here.
        openstack.runOpenStackUpgradePhase(env, target, 'service_stopped')
        openstack.runOpenStackUpgradePhase(env, target, 'pkgs_latest')
        openstack.runOpenStackUpgradePhase(env, target, 'render_config')
        openstack.runOpenStackUpgradePhase(env, target, 'service_running')
        openstack.applyOpenstackAppsStates(env, target)
        openstack.runOpenStackUpgradePhase(env, target, 'verify')
      }

      common.stageWrapper(upgradeStageMap, "Upgrade post: enable resources", target, interactive) {
        openstack.runOpenStackUpgradePhase(env, target, 'upgrade.post')
      }
    }
  }
}
