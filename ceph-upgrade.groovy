/**
 *
 * Upgrade Ceph mon/mgr/osd/rgw/client
 *
 * Requred parameters:
 *  SALT_MASTER_URL                 URL of Salt master
 *  SALT_MASTER_CREDENTIALS         Credentials to the Salt API
 *
 *  ADMIN_HOST                      Host (minion id) with admin keyring and /etc/crushmap file present
 *  CLUSTER_FLAGS                   Comma separated list of tags to apply to cluster
 *  WAIT_FOR_HEALTHY                Wait for cluster rebalance before stoping daemons
 *  ORIGIN_RELEASE                  Ceph release version before upgrade
 *  TARGET_RELEASE                  Ceph release version after upgrade
 *  STAGE_UPGRADE_MON               Set to True if Ceph mon nodes upgrade is desired
 *  STAGE_UPGRADE_MGR               Set to True if Ceph mgr nodes upgrade or new deploy is desired
 *  STAGE_UPGRADE_OSD               Set to True if Ceph osd nodes upgrade is desired
 *  STAGE_UPGRADE_RGW               Set to True if Ceph rgw nodes upgrade is desired
 *  STAGE_UPGRADE_CLIENT            Set to True if Ceph client nodes upgrade is desired (includes for example ctl/cmp nodes)
 *
 */

common = new com.mirantis.mk.Common()
salt = new com.mirantis.mk.Salt()
def python = new com.mirantis.mk.Python()

def pepperEnv = "pepperEnv"
def flags = CLUSTER_FLAGS.tokenize(',')

def runCephCommand(master, target, cmd) {
    return salt.cmdRun(master, target, cmd)
}

def waitForHealthy(master, count=0, attempts=300) {
    // wait for healthy cluster
    while (count<attempts) {
        def health = runCephCommand(master, ADMIN_HOST, 'ceph health')['return'][0].values()[0]
        if (health.contains('HEALTH_OK')) {
            common.infoMsg('Cluster is healthy')
            break;
        }
        count++
        sleep(10)
    }
}

def backup(master, target) {
    stage("backup ${target}") {

        if (target == 'osd') {
            try {
                salt.enforceState(master, "I@ceph:${target}", "ceph.backup", true)
                runCephCommand(master, "I@ceph:${target}", "su root -c '/usr/local/bin/ceph-backup-runner-call.sh'")
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

                waitForHealthy(master)
                try {
                    salt.cmdRun(master, "${minionProvider}", "[ ! -f /root/${minion_name}.${domain}.qcow2.bak ] && virsh destroy ${minion_name}.${domain}")
                } catch (Exception e) {
                    common.warningMsg('Backup already exists')
                }
                try {
                    salt.cmdRun(master, "${minionProvider}", "[ ! -f /root/${minion_name}.${domain}.qcow2.bak ] && cp /var/lib/libvirt/images/${minion_name}.${domain}/system.qcow2 /root/${minion_name}.${domain}.qcow2.bak")
                } catch (Exception e) {
                    common.warningMsg('Backup already exists')
                }
                try {
                    salt.cmdRun(master, "${minionProvider}", "virsh start ${minion_name}.${domain}")
                } catch (Exception e) {
                    common.warningMsg(e)
                }
                salt.minionsReachable(master, 'I@salt:master', "${minion_name}*")
                waitForHealthy(master)
            }
        }
    }
    return
}

def upgrade(master, target) {

    stage("Change ${target} repos") {
        salt.runSaltProcessStep(master, "I@ceph:${target}", 'saltutil.refresh_pillar', [], null, true, 5)
        salt.enforceState(master, "I@ceph:${target}", 'linux.system.repo', true)
    }
    if (target == 'mgr') {
        stage('Run ceph mgr state') {
            salt.enforceState(master, "I@ceph:mgr", "ceph.mgr", true)
        }
    }
    if (target == 'common') {
        stage('Upgrade ceph-common pkgs') {
            runCephCommand(master, "I@ceph:${target}", "apt install ceph-${target} -y")
        }
    } else {
        minions = salt.getMinions(master, "I@ceph:${target}")

        for (minion in minions) {
            // upgrade pkgs
            if (target == 'radosgw') {
                stage('Upgrade radosgw pkgs') {
                    runCephCommand(master, "I@ceph:${target}", "apt install ${target} -y ")
                }
            } else {
                stage("Upgrade ${target} pkgs on ${minion}") {
                    runCephCommand(master, "${minion}", "apt install ceph-${target} -y")
                }
            }
            // restart services
            stage("Restart ${target} services on ${minion}") {
                runCephCommand(master, "${minion}", "systemctl restart ceph-${target}.target")
            }

            stage("Verify services for ${minion}") {
                sleep(10)
                runCephCommand(master, ADMIN_HOST, "ceph -s")
            }

            stage('Ask for manual confirmation') {
                input message: "From the verification command above, please check Ceph ${target} joined the cluster correctly. If so, Do you want to continue to upgrade next node?"
            }
        }
    }
    runCephCommand(master, ADMIN_HOST, "ceph versions")
    sleep(5)
    return
}
timeout(time: 12, unit: 'HOURS') {
    node("python") {

        // create connection to salt master
        python.setupPepperVirtualenv(pepperEnv, SALT_MASTER_URL, SALT_MASTER_CREDENTIALS)

        stage ('Check user choices') {
            if (STAGE_UPGRADE_RGW.toBoolean() == true) {
                // if rgw, check if other stuff has required version
                def mon_ok = true
                if (STAGE_UPGRADE_MON.toBoolean() == false) {
                    def mon_v = runCephCommand(pepperEnv, ADMIN_HOST, "ceph mon versions")['return'][0].values()[0]
                    mon_ok = mon_v.contains("${TARGET_RELEASE}") && !mon_v.contains("${ORIGIN_RELEASE}")
                }
                def mgr_ok = true
                if (STAGE_UPGRADE_MGR.toBoolean() == false) {
                    def mgr_v = runCephCommand(pepperEnv, ADMIN_HOST, "ceph mgr versions")['return'][0].values()[0]
                    mgr_ok = mgr_v.contains("${TARGET_RELEASE}") && !mgr_v.contains("${ORIGIN_RELEASE}")
                }
                def osd_ok = true
                if (STAGE_UPGRADE_OSD.toBoolean() == false) {
                    def osd_v = runCephCommand(pepperEnv, ADMIN_HOST, "ceph osd versions")['return'][0].values()[0]
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

        if (flags.size() > 0) {
            stage('Set cluster flags') {
                for (flag in flags) {
                    runCephCommand(pepperEnv, ADMIN_HOST, 'ceph osd set ' + flag)
                }
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
        if (flags.size() > 0) {
            stage('Unset cluster flags') {
                for (flag in flags) {
                    if (!flag.contains('sortbitwise')) {
                        common.infoMsg('Removing flag ' + flag)
                        runCephCommand(pepperEnv, ADMIN_HOST, 'ceph osd unset ' + flag)
                    }

                }
            }
        }

        if (STAGE_FINALIZE.toBoolean() == true) {
            stage("Finalize ceph version upgrade") {
                runCephCommand(pepperEnv, ADMIN_HOST, "ceph osd require-osd-release ${TARGET_RELEASE}")
                try {
                    runCephCommand(pepperEnv, ADMIN_HOST, "ceph osd set-require-min-compat-client ${ORIGIN_RELEASE}")
                } catch (Exception e) {
                    common.warningMsg(e)
                }
                try {
                    runCephCommand(pepperEnv, ADMIN_HOST, "ceph osd crush tunables optimal")
                } catch (Exception e) {
                    common.warningMsg(e)
                }
            }
        }

        // wait for healthy cluster
        if (WAIT_FOR_HEALTHY.toBoolean() == true) {
            waitForHealthy(pepperEnv)
        }
    }
}
