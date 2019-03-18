/**
 *
 * Performs MCP component packages updates
 *
 * Expected parameters:
 *   SALT_MASTER_URL               Url to Salt API
 *   SALT_MASTER_CREDENTIALS       Credentials to the Salt API
 *   TARGET_SERVERS                (Optional) String containing list of Salt targets split by comma.
 *                                 NOTE: For if this parameter is set, it will be used to run packages updates on specified targets
 *                                 If it isn't set targets will be detected automatically.
 *   COMPONENTS                    String containing comma-separated list of supported for update components
 *                                 Currently only nova and ceph are supported.
 *
 */

common = new com.mirantis.mk.Common()
def python = new com.mirantis.mk.Python()
salt = new com.mirantis.mk.Salt()

pepperEnv = "pepperEnv"

/**
 * Execute shell command using salt
 *
 * @param saltMaster Object pointing to salt master
 * @param target Minion to execute on
 * @param cmd Command to execute
 * @return string with shell output
 */

def runShCommand(saltMaster, target, cmd) {
    return salt.cmdRun(saltMaster, target, cmd)
}

/**
 * Installs packages updates by running apt install with
 * flag --only-upgrade on target
 *
 * @param saltMaster Object pointing to salt master
 * @param target Minion to execute on
 * @param pkgs List of packages to update e.g nova*, salt*, ceph*
 */

def installPkgUpdate(saltMaster, target, pkgs) {
    common.infoMsg("Installing ${pkgs} updates on ${target}")
    runShCommand(saltMaster, target, "apt install --only-upgrade ${pkgs.join(' ')} -y")
}

/**
 * Returns string with values from pillar
 *
 * @param saltMaster Object pointing to salt master
 * @param target Minion to execute on
 * @param pillar Pillar path to get values (nova:controller)
 * @return string with Pillar values
 */
def getPillarValues(saltMaster, target, pillar) {
    return salt.getReturnValues(salt.getPillar(saltMaster, target, pillar))
}

/**
 * Returns pillar value converted to boolean
 *
 * @param saltMaster Object pointing to salt master
 * @param target Minion to execute on
 * @param pillar Pillar path to get values (nova:controller:enabled)
 * @return Boolean as result of Pillar output string
 */

def getPillarBoolValues(saltMaster, target, pillar){
    return getPillarValues(saltMaster, target, pillar).toBoolean()
}

/**
 * Returns first minion from sorted in alphsberical
 * order list of minions
 *
 * @param saltMaster Object pointing to salt master
 * @param target Criteria by which to choose minions
 * @return string with minion id
 */

def getFirstMinion(saltMaster, target) {
    def minionsSorted = salt.getMinionsSorted(saltMaster, target)
    return minionsSorted[0]
}

/**
 * Stops list of services one by one on target
 *
 * @param saltMaster Object pointing to salt master
 * @param target Criteria by which to choose minions
 *
 */

def stopServices(saltMaster, target, services) {
    common.infoMsg("Stopping ${services} on ${target}")
    for (s in services){
        runShCommand(saltMaster, target, "systemctl stop ${s}")
    }
}

def waitForHealthy(saltMaster, count=0, attempts=100) {
    // wait for healthy cluster
    while (count<attempts) {
        def health = runShCommand(saltMaster, "I@ceph:mon and I@ceph:common:keyring:admin", 'ceph health')['return'][0].values()[0]
        if (health.contains('HEALTH_OK')) {
            common.infoMsg('Cluster is healthy')
            break;
        }
        count++
        sleep(10)
    }
}

/**
 * Returns nova service status in as list of hashes e.g.
 *  [
 * {
 *   "Status": "enabled",
 *   "Binary": "nova-conductor",
 *   "Zone": "internal",
 *   "State": "up",
 *   "Host": "ctl01",
 *   "Updated At": "2019-03-22T17:39:02.000000",
 *   "ID": 7
 *  }
 * ]
 *
 *
 * @param saltMaster Object pointing to salt master
 * @param target on which to run openstack client command
 * @param host for which to get service status e.g. cmp1
 * @param service name to check e.g. nova-compute
 * @return List of hashes with service status data
 *
 */

def getServiceStatus(saltMaster, target, host, service){
    def cmd = ". /root/keystonercv3; openstack compute service list --host ${host} --service ${service} -f json"
    common.retry(3, 10) {
        res = readJSON text: salt.cmdRun(saltMaster, target, cmd)['return'][0].values()[0].replaceAll('Salt command execution success','')
    }
    return res
}

/**
 * Waits while services are back to up state in Nova api output, if state
 * doesn't change to 'up' raises error
 *
 * @param saltMaster Object pointing to salt master
 * @param target Criteria by which to choose hosts where to check services states
 * @param clientTarget Criteria by which to choose minion where to run openstack commands
 * @param binaries lsit of services to wait for
 * @param retries number of tries to to get service status
 * @param timeout number of seconds to wait between tries
 *
 */

def waitForServices(saltMaster, target, clientTarget, binaries, retries=18, timeout=10) {
    for (host in salt.getMinionsSorted(saltMaster, target)) {
        for (b in binaries) {
            common.retry(retries, timeout) {
                def status = getServiceStatus(saltMaster, clientTarget, host.tokenize('.')[0], b)[0]
                if (status['State'] == 'up') {
                    common.infoMsg("Service ${b} on host ${host} is UP and Running")
                } else {
                    error("Service ${b} status check failed or service isn't running on host ${host}")
                }
            }
        }
    }
}

node(){
    try {
        stage('Setup virtualenv for Pepper') {
            python.setupPepperVirtualenv(pepperEnv, SALT_MASTER_URL, SALT_MASTER_CREDENTIALS)
        }

        def components = COMPONENTS.tokenize(',')

        if ('ceph' in components) {
            def monPillar = 'ceph:mon:enabled'
            def commonPillar = 'ceph:common:enabled'
            def osdPillar = 'ceph:osd:enabled'
            def rgwPillar = 'ceph:radosgw:enabled'

            def monTarget = "I@${monPillar}:true"
            def commonTarget = "I@${commonPillar}:true"
            def osdTarget = "I@${osdPillar}:true"
            def rgwTarget = "I@${rgwPillar}:true"
            def targets = TARGET_SERVERS.tokenize(',')

            // If TARGET_SERVERS is empty
            if (!targets) {
                targets = salt.getMinionsSorted(pepperEnv, commonTarget) + salt.getMinionsSorted(pepperEnv, monTarget) + salt.getMinionsSorted(pepperEnv, rgwTarget) + salt.getMinionsSorted(pepperEnv, osdTarget)
            }
            // Ceph common and other roles can be combined, so making host list elements to be unique
            targets = targets.toSet()

            stage('Update Ceph configuration using new defaults') {
                for (t in targets) {
                    if (getPillarBoolValues(pepperEnv, t, commonPillar)) {
                        salt.enforceState(pepperEnv, t, 'ceph.common', true)
                    }
                }
            }

            stage('Restart Ceph services') {
                for (t in targets) {
                    if (getPillarBoolValues(pepperEnv, t, monPillar)) {
                        def monitors = salt.getMinions(pepperEnv, t)
                        for (tgt in monitors) {
                            runShCommand(pepperEnv, tgt, "systemctl restart ceph-mon.target")
                            runShCommand(pepperEnv, tgt, "systemctl restart ceph-mgr.target")
                            waitForHealthy(pepperEnv)
                        }
                    }
                }
                for (t in targets) {
                    if (getPillarBoolValues(pepperEnv, t, rgwPillar)) {
                        runShCommand(pepperEnv, t, "systemctl restart ceph-radosgw.target")
                        waitForHealthy(pepperEnv)
                    }
                }
                for (t in targets) {
                    if (getPillarBoolValues(pepperEnv, t, osdPillar)) {
                        def nodes = salt.getMinions(pepperEnv, t)
                        for (tgt in nodes) {
                            salt.runSaltProcessStep(pepperEnv, tgt, 'saltutil.sync_grains', [], null, true, 5)
                            def ceph_disks = salt.getGrain(pepperEnv, tgt, 'ceph')['return'][0].values()[0].values()[0]['ceph_disk']

                            def osd_ids = []
                            for (i in ceph_disks) {
                                def osd_id = i.getKey().toString()
                                osd_ids.add('osd.' + osd_id)
                            }

                            for (i in osd_ids) {
                                runShCommand(pepperEnv, tgt, 'ceph osd set noout')
                                salt.runSaltProcessStep(pepperEnv, tgt, 'service.restart', ['ceph-osd@' + i.replaceAll('osd.', '')],  null, true)
                                sleep(60)
                                runShCommand(pepperEnv, tgt, 'ceph osd unset noout')
                                // wait for healthy cluster
                                waitForHealthy(pepperEnv)
                            }
                        }
                    }
                }
            }
        }

        if ('nova' in components) {
            def ctlPillar = 'nova:controller:enabled'
            def cmpPillar = 'nova:compute:enabled'

            def cmpTarget = "I@${cmpPillar}:true"
            def ctlTarget = "I@${ctlPillar}:true"
            // Target for colling openstack client containing keystonercv3
            def clientTarget = getFirstMinion(pepperEnv, 'I@keystone:client:enabled:true')
            def targets = TARGET_SERVERS.tokenize(',')
            // If TARGET_SERVERS is empty
            if (!targets) {
                targets = salt.getMinionsSorted(pepperEnv, ctlTarget) + salt.getMinionsSorted(pepperEnv, cmpTarget)
            }

            for (t in targets){
                if (getPillarBoolValues(pepperEnv, t, ctlPillar) || getPillarBoolValues(pepperEnv, t, cmpPillar)) {
                    def tservices = ['nova*']
                    def tbinaries = []
                    if (getPillarBoolValues(pepperEnv, t, ctlPillar)) {
                        tservices += ['apache2']
                        tbinaries += ['nova-consoleauth', 'nova-scheduler', 'nova-conductor']
                    }
                    if (getPillarBoolValues(pepperEnv, t, cmpPillar)) {
                        tbinaries += ['nova-compute']
                    }
                    // Stop component services to ensure that updated code is running
                    stopServices(pepperEnv, t, tservices)
                    // Update all installed nova packages
                    installPkgUpdate(pepperEnv, t, ['nova*', 'python-nova*'])
                    common.infoMsg("Applying component states on ${t}")
                    salt.enforceState(pepperEnv, t, 'nova')
                    waitForServices(pepperEnv, t, clientTarget, tbinaries)
                } else {
                    // If no compute or controller pillar is detected just packages will be updated
                    installPkgUpdate(pepperEnv, t, ['nova*', 'python-nova*'])
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