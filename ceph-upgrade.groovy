/**
 *
 * Upgrade Ceph mon/mgr/osd/rgw/client
 *
 * Requred parameters:
 *  SALT_MASTER_URL                 URL of Salt master
 *  SALT_MASTER_CREDENTIALS         Credentials to the Salt API
 *
 *  CLUSTER_FLAGS                   Comma separated list of tags to apply to cluster
 *  WAIT_FOR_HEALTHY                Wait for cluster rebalance before stoping daemons
 *  ASK_CONFIRMATION                Ask for manual confirmation
 *  ORIGIN_RELEASE                  Ceph release version before upgrade
 *  TARGET_RELEASE                  Ceph release version after upgrade
 *  STAGE_UPGRADE_MON               Set to True if Ceph mon nodes upgrade is desired
 *  STAGE_UPGRADE_MGR               Set to True if Ceph mgr nodes upgrade or new deploy is desired
 *  STAGE_UPGRADE_OSD               Set to True if Ceph osd nodes upgrade is desired
 *  STAGE_UPGRADE_RGW               Set to True if Ceph rgw nodes upgrade is desired
 *  STAGE_UPGRADE_CLIENT            Set to True if Ceph client nodes upgrade is desired (includes for example ctl/cmp nodes)
 *  STAGE_FINALIZE                  Set to True if configs recommended for TARGET_RELEASE should be set after upgrade is done
 *  BACKUP_ENABLED                  Select to copy the disks of Ceph VMs before upgrade and backup Ceph directories on OSD nodes
 *  BACKUP_DIR                      Select the target dir to backup to when BACKUP_ENABLED
 *
 */

common = new com.mirantis.mk.Common()
salt = new com.mirantis.mk.Salt()
def python = new com.mirantis.mk.Python()
ceph = new com.mirantis.mk.Ceph()
askConfirmation = (env.getProperty('ASK_CONFIRMATION') ?: true).toBoolean()

pepperEnv = "pepperEnv"
flags = CLUSTER_FLAGS.tokenize(',')
// sortbitwise is set by default on version >jewel.
// For jewel upgrade we will set and keep it while for other cases shouldn't be there
flags.removeElement('sortbitwise')

def backup(master, target) {
    stage("backup $target") {

        if (target == 'osd') {
            try {
                salt.enforceState(master, "I@ceph:${target}", "ceph.backup", true)
                ceph.cmdRunOnTarget(master, "I@ceph:${target}", "su root -c '/usr/local/bin/ceph-backup-runner-call.sh'")
            } catch (Exception e) {
                common.errorMsg(e)
                common.errorMsg("Make sure Ceph backup on OSD nodes is enabled")
                throw new InterruptedException()
            }
        } else {
            def _pillar = salt.getGrain(master, 'I@salt:master', 'domain')
            def domain = _pillar['return'][0].values()[0].values()[0]

            def kvm_pillar = salt.getGrain(master, 'I@salt:control', 'id')
            def kvm01 = kvm_pillar['return'][0].values()[0].values()[0]

            def target_pillar = salt.getGrain(master, "I@ceph:${target}", 'host')
            def minions = target_pillar['return'][0].values()
            for (minion in minions) {
                def minion_name = minion.values()[0]
                def provider_pillar = salt.getPillar(master, "${kvm01}", "salt:control:cluster:internal:node:${minion_name}:provider")
                def minionProvider = provider_pillar['return'][0].values()[0]

                ceph.waitForHealthy(master, flags)
                try {
                    ceph.cmdRunOnTarget(master, minionProvider, "[ ! -f ${BACKUP_DIR}/${minion_name}.${domain}.qcow2.bak ] && virsh destroy ${minion_name}.${domain}")
                } catch (Exception e) {
                    common.warningMsg('Backup already exists')
                }
                try {
                    ceph.cmdRunOnTarget(master, minionProvider, "[ ! -f ${BACKUP_DIR}/${minion_name}.${domain}.qcow2.bak ] && cp /var/lib/libvirt/images/${minion_name}.${domain}/system.qcow2 ${BACKUP_DIR}/${minion_name}.${domain}.qcow2.bak")
                } catch (Exception e) {
                    common.warningMsg('Backup already exists')
                }
                try {
                    ceph.cmdRunOnTarget(master, minionProvider, "virsh start ${minion_name}.${domain}")
                } catch (Exception e) {
                    common.warningMsg(e)
                }
                salt.minionsReachable(master, 'I@salt:master', "${minion_name}*")
                ceph.waitForHealthy(master, flags)
            }
        }
    }
    return
}

def upgrade(master, target) {

    stage("Change $target repos") {
        salt.runSaltProcessStep(master, "I@ceph:${target}", 'saltutil.refresh_pillar', [], null, true, 5)
        salt.enforceState(master, "I@ceph:${target}", 'linux.system.repo', true)
    }
    if (target == 'mgr') {
        stage('Run ceph mgr state') {
            salt.enforceState(master, "I@ceph:mgr", "ceph.mgr", true, failOnError=false, retries=3, retries_wait=10)
        }
    }
    if (target == 'common') {
        stage('Upgrade ceph-common pkgs') {
            salt.runSaltProcessStep(master, "I@ceph:common", 'pkg.install', ["ceph-common"], 'only_upgrade=True')
        }
    } else {
        minions = salt.getMinions(master, "I@ceph:${target}")

        def  ignoreDifferentSubversions = false
        for (minion in minions) {
            // upgrade pkgs
            if (target == 'radosgw') {
                stage("Upgrade radosgw pkgs on $minion") {
                    salt.runSaltProcessStep(master, minion, 'pkg.install', [target], 'only_upgrade=True')
                }
            } else {
                stage("Upgrade $target pkgs on $minion") {
                    salt.runSaltProcessStep(master, minion, 'pkg.install', ["ceph-${target}"], 'only_upgrade=True')
                }
            }
            // check for subversion difference before restart
            if (ignoreDifferentSubversions) {
                targetVersion = ceph.cmdRun(master, "ceph versions | grep $TARGET_RELEASE | awk '{print \$3}' | sort -V | tail -1")
                updatedVersion = ceph.cmdRunOnTarget(master, minion, "ceph version | awk '{print \$3}'")
                if (targetVersion != updatedVersion) {
                    stage('Version differnce warning') {
                        common.warningMsg("A potential problem has been spotted.")
                        common.warningMsg("Some components already have $targetVersion version while ceph-$target has just been updated to $updatedVersion")
                        input message: "Do you want to proceed with restarts and silence this warning?"
                        ignoreDifferentSubversions = true
                    }
                }
            }
            // restart services
            stage("Restart ${target} services on ${minion}") {
                if(target == 'osd') {
                    def ceph_disks = salt.getGrain(master, minion, 'ceph')['return'][0].values()[0].values()[0]['ceph_disk']
                    ceph_disks.each { osd, param ->
                        ceph.cmdRunOnTarget(master, minion, "systemctl restart ceph-${target}@${osd}")
                    }
                }
                else {
                    ceph.cmdRunOnTarget(master, minion, "systemctl restart ceph-${target}.target")
                }

                ceph.waitForHealthy(master, flags)
            }

            stage("Verify services for $minion") {
                sleep(10)
                ceph.cmdRunOnTarget(master, minion, "systemctl status ceph-${target}.target")
            }

            stage('Verify Ceph status') {
                ceph.cmdRun(master, "ceph -s", true, true)
                if (askConfirmation) {
                    input message: "From the verification command above, please check Ceph $target joined the cluster correctly. If so, Do you want to continue to upgrade next node?"
                }
            }
        }
    }
    ceph.cmdRun(master, "ceph versions")
    sleep(5)
    return
}

timeout(time: 12, unit: 'HOURS') {
    node("python") {

        // create connection to salt master
        python.setupPepperVirtualenv(pepperEnv, SALT_MASTER_URL, SALT_MASTER_CREDENTIALS)

        stage('Check user choices') {
            if (STAGE_UPGRADE_RGW.toBoolean()) {
                // if rgw, check if other stuff has required version
                def mon_ok = true
                if (!STAGE_UPGRADE_MON.toBoolean()) {
                    def mon_v = ceph.cmdRun(pepperEnv, "ceph mon versions")
                    mon_ok = mon_v.contains("${TARGET_RELEASE}") && !mon_v.contains("${ORIGIN_RELEASE}")
                }
                def mgr_ok = true
                if (!STAGE_UPGRADE_MGR.toBoolean()) {
                    def mgr_v = ceph.cmdRun(pepperEnv, "ceph mgr versions")
                    mgr_ok = mgr_v.contains("${TARGET_RELEASE}") && !mgr_v.contains("${ORIGIN_RELEASE}")
                }
                def osd_ok = true
                if (!STAGE_UPGRADE_OSD.toBoolean()) {
                    def osd_v = ceph.cmdRun(pepperEnv, "ceph osd versions")
                    osd_ok = osd_v.contains("${TARGET_RELEASE}") && !osd_v.contains("${ORIGIN_RELEASE}")
                }
                if (!mon_ok || !osd_ok || !mgr_ok) {
                    common.errorMsg('You may choose stages in any order, but RGW should be upgraded last')
                    throw new InterruptedException()
                }
            }
        }

        if (BACKUP_ENABLED.toBoolean() == true) {
            if (STAGE_UPGRADE_MON.toBoolean() == true) {
                backup(pepperEnv, 'mon')
            }
            if (STAGE_UPGRADE_RGW.toBoolean() == true) {
                backup(pepperEnv, 'radosgw')
            }
            if (STAGE_UPGRADE_OSD.toBoolean() == true) {
                backup(pepperEnv, 'osd')
            }
        }

        stage('Set cluster flags') {
            ceph.setFlags(pepperEnv, flags)
            if (ORIGIN_RELEASE == 'jewel') {
                ceph.setFlags('sortbitwise')
            }
        }

        if (STAGE_UPGRADE_MON.toBoolean() == true) {
            upgrade(pepperEnv, 'mon')
        }

        if (STAGE_UPGRADE_MGR.toBoolean() == true) {
            upgrade(pepperEnv, 'mgr')
        }

        if (STAGE_UPGRADE_OSD.toBoolean() == true) {
            upgrade(pepperEnv, 'osd')
        }

        if (STAGE_UPGRADE_RGW.toBoolean() == true) {
            upgrade(pepperEnv, 'radosgw')
        }

        if (STAGE_UPGRADE_CLIENT.toBoolean() == true) {
            upgrade(pepperEnv, 'common')
        }

        // remove cluster flags
        stage('Unset cluster flags') {
            ceph.unsetFlags(flags)
        }

        if (STAGE_FINALIZE.toBoolean() == true) {
            stage("Finalize ceph version upgrade") {
                ceph.cmdRun(pepperEnv, "ceph osd require-osd-release ${TARGET_RELEASE}")
                try {
                    ceph.cmdRun(pepperEnv, "ceph osd set-require-min-compat-client ${ORIGIN_RELEASE}")
                } catch (Exception e) {
                    common.warningMsg(e)
                }
                try {
                    ceph.cmdRun(pepperEnv, "ceph osd crush tunables optimal")
                } catch (Exception e) {
                    common.warningMsg(e)
                }
                if (TARGET_RELEASE == 'nautilus' ) {
                    ceph.cmdRun(pepperEnv, "ceph mon enable-msgr2")
                }
                salt.enforceState(pepperEnv, "I@ceph:common", "ceph.common")
            }
        }

        // wait for healthy cluster
        if (WAIT_FOR_HEALTHY.toBoolean()) {
            ceph.waitForHealthy(pepperEnv, flags)
        }
    }
}
