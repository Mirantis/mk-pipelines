/**
 * Upgrade OpenStack packages on control plane nodes.
 * There are no silver boollet in uprading cloud.
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
def debian = new com.mirantis.mk.Debian()
def openstack = new com.mirantis.mk.Openstack()

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
upgradeStageMap.put('Stop OpenStack services',
  [
    'Description': 'All OpenStack python services will be stopped on All control nodes. This does not affect data plane services such as openvswitch or qemu.',
    'Status': 'NOT_LAUNCHED',
    'Expected behaviors': '''
 * OpenStack python services are stopped.
 * OpenStack API are not accessible from this point.
 * No workload downtime''',
    'Launched actions': '''
 * Stop OpenStack python services''',
    'State result': 'OpenStack python services are stopped',
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

def stopOpenStackServices(env, target) {
    def salt = new com.mirantis.mk.Salt()
    def openstack = new com.mirantis.mk.Openstack()
    def common = new com.mirantis.mk.Common()

    def services = openstack.getOpenStackUpgradeServices(env, target)
    def st
    for (service in services){
        st = "${service}.upgrade.service_stopped".trim()
        common.infoMsg("Stopping ${st} services on ${target}")
        salt.enforceState(env, target, st)
    }
}

def snapshotVM(env, domain, snapshotName) {
  def common = new com.mirantis.mk.Common()
  def salt = new com.mirantis.mk.Salt()

  def target =  salt.getNodeProvider(env, domain)

  // TODO: gracefully migrate all workloads from VM, and stop it
  salt.runSaltProcessStep(env, target, 'virt.shutdown', [domain], null, true, 3600)

  //TODO: wait while VM is powered off

  common.infoMsg("Creating snapshot ${snapshotName} for VM ${domain} on node ${target}")
  salt.runSaltProcessStep(env, target, 'virt.snapshot', [domain, snapshotName], null, true, 3600)
}

def revertSnapshotVM(env, domain, snapshotName, ensureUp=true) {
  def common = new com.mirantis.mk.Common()
  def salt = new com.mirantis.mk.Salt()

  def target =  salt.getNodeProvider(env, domain)

  common.infoMsg("Reverting snapshot ${snapshotName} for VM ${domain} on node ${target}")
  salt.runSaltProcessStep(env, target, 'virt.revert_snapshot', [snapshotName, domain], null, true, 3600)

  if (ensureUp){
    salt.runSaltProcessStep(env, target, 'virt.start', [domain], null, true, 300)
  }
}

def restartDesignate(env, target) {
  def common = new com.mirantis.mk.Common()
  def salt = new com.mirantis.mk.Salt()
  // Workaround for PROD-33592, restart designate-central services if enabled
  designate_enabled = salt.getPillar(env, target, "designate:server:enabled").get("return")[0].values()[0]
  if (designate_enabled == '' || designate_enabled == 'false' || designate_enabled == null) {
    common.infoMsg('Designate is disabled, nothing to do')
  } else {
    try {
       salt.runSaltProcessStep(env, target, "service.restart", "designate-central", null, true)
    }
    catch (Exception ex) {
       common.infoMsg(ex)
       error('Designate service is broken, please check logs')
    }
  }
}

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

    common.printStageMap(upgradeStageMap)
    if (interactive){
      input message: common.getColorizedString(
        "Above you can find detailed info this pipeline will execute.\nThe info provides brief description of each stage, actions that will be performed and service/workload impact during each stage.\nPlease read it carefully.", "yellow")
    }

    common.infoMsg("Refreshing haproxy config for mysql proxies")
    salt.enforceState(env, 'I@haproxy:proxy:listen:mysql_cluster', ['haproxy.proxy'])

    for (target in upgradeTargets){
      common.stageWrapper(upgradeStageMap, "Pre upgrade", target, interactive) {
        openstack.runOpenStackUpgradePhase(env, target, 'pre')
        salt.runSaltProcessStep(env, target, 'saltutil.refresh_pillar', [], null, true)
        salt.enforceState(env, target, 'linux.system.repo')
        restartDesignate(env, target)
        openstack.runOpenStackUpgradePhase(env, target, 'verify')
      }
    }

    for (target in upgradeTargets) {
      common.stageWrapper(upgradeStageMap, "Stop OpenStack services", target, interactive) {
        stopOpenStackServices(env, target)
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
        // Workaround for PROD-31413, install python-tornado from latest release if available and
        // restart minion to apply new code.
        salt.upgradePackageAndRestartSaltMinion(env, target, 'python-tornado')
      }

      common.stageWrapper(upgradeStageMap, "Upgrade OpenStack", target, interactive) {
        openstack.runOpenStackUpgradePhase(env, target, 'upgrade')
        openstack.applyOpenstackAppsStates(env, target)
        openstack.runOpenStackUpgradePhase(env, target, 'verify')
      }
    }
  }
}
