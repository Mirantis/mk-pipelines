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
            runCephCommand(master, "I@ceph:${target}", "apt install ceph-${target} -y ")
        }
    } else if (target == 'radosgw') {
        stage('Upgrade radosgw pkgs') {
            runCephCommand(master, "I@ceph:${target}", "apt install ${target} -y ")
        }
        // restart services
        stage("Restart ${target} services") {
            runCephCommand(master, "I@ceph:${target}", "systemctl restart ceph-${target}.target")
        }
    } else {

        // upgrade pkgs
        stage("Upgrade ${target} pkgs") {
            runCephCommand(master, "I@ceph:${target}", "apt install ceph-${target} -y ")
        }
        // restart services
        stage("Restart ${target} services") {
            runCephCommand(master, "I@ceph:${target}", "systemctl restart ceph-${target}.target")
        }
    }
    runCephCommand(master, ADMIN_HOST, "ceph versions")
    sleep(5)
    return
}

node("python") {

    // create connection to salt master
    python.setupPepperVirtualenv(pepperEnv, SALT_MASTER_URL, SALT_MASTER_CREDENTIALS)

    if (flags.size() > 0) {
        stage('Set cluster flags') {
            for (flag in flags) {
                runCephCommand(pepperEnv, ADMIN_HOST, 'ceph osd set ' + flag)
            }
        }
    }

    if (STAGE_UPGRADE_MON.toBoolean() == true) {
        upgrade(pepperEnv, 'mon')
        stage("Verify mon services") {
            runCephCommand(pepperEnv, ADMIN_HOST, "ceph mon stat")
        }
        stage('Ask for manual confirmation') {
            input message: "From the verification command above, please check Ceph mons joined the cluster. If so, Do you want to continue?"
        }
    }

    if (STAGE_UPGRADE_MGR.toBoolean() == true) {
        upgrade(pepperEnv, 'mgr')
        stage("Verify mgr services") {
            runCephCommand(pepperEnv, ADMIN_HOST, "ceph -s")
        }
        stage('Ask for manual confirmation') {
            input message: "From the verification command above, please check Ceph mgr joined the cluster. If so, Do you want to continue?"
        }
    }

    if (STAGE_UPGRADE_OSD.toBoolean() == true) {
        upgrade(pepperEnv, 'osd')
        stage("Verify osd services") {
            runCephCommand(pepperEnv, ADMIN_HOST, "ceph osd stat")
        }
        stage('Ask for manual confirmation') {
            input message: "From the verification command above, please check Ceph osds joined the cluster. If so, Do you want to continue?"
        }
    }

    if (STAGE_UPGRADE_RGW.toBoolean() == true) {
        upgrade(pepperEnv, 'radosgw')
        stage("Verify rgw services") {
            runCephCommand(pepperEnv, ADMIN_HOST, "ceph -s")
        }
        stage('Ask for manual confirmation') {
            input message: "From the verification command above, please check Ceph rgw joined the cluster. If so, Do you want to continue?"
        }
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

    stage("Finalize ceph version upgrade") {
        runCephCommand(pepperEnv, ADMIN_HOST, "ceph osd require-osd-release ${TARGET_RELEASE}")
        try {
            runCephCommand(pepperEnv, ADMIN_HOST, "ceph osd set-require-min-compat-client ${ORIGIN_RELEASE}")
        } catch (Exception e) {
            common.warningMsg(e)
        }
        runCephCommand(pepperEnv, ADMIN_HOST, "ceph osd crush tunables optimal")
    }

    // wait for healthy cluster
    if (WAIT_FOR_HEALTHY.toBoolean() == true) {
        stage('Waiting for healthy cluster') {
            while (true) {
                def health = runCephCommand(pepperEnv, ADMIN_HOST, 'ceph -s')['return'][0].values()[0]
                if (health.contains('HEALTH_OK')) {
                    common.infoMsg('Cluster is healthy')
                    break;
                }
                sleep(10)
            }
        }
    }
}
