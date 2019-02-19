/**
 * Update packages on given nodes
 *
 * Expected parameters:
 *   SALT_MASTER_CREDENTIALS    Credentials to the Salt API.
 *   SALT_MASTER_URL            Full Salt API address [http://10.10.10.1:8000].
 *
**/

def common = new com.mirantis.mk.Common()
def salt = new com.mirantis.mk.Salt()
def python = new com.mirantis.mk.Python()

def pepperEnv = "pepperEnv"

def oc3SupervisorServices = ["supervisor-config", "supervisor-control"]
def oc4ConfigServices = ["contrail-api", "contrail-schema", "contrail-svc-monitor", "contrail-device-manager", "contrail-config-nodemgr"]
def oc4ControlServices = ["contrail-control", "contrail-named", "contrail-dns", "contrail-control-nodemgr"]
def zkService = "zookeeper"
def contrailStatusCheckCmd = "contrail-status | grep -v == | grep -v \'disabled on boot\' | grep -v nodemgr | grep -v active | grep -v backup"
def zkDbPath = "/var/lib/zookeeper/version-2"

timeout(time: 12, unit: 'HOURS') {
    node() {

        stage('Setup virtualenv for Pepper') {
            python.setupPepperVirtualenv(pepperEnv, SALT_MASTER_URL, SALT_MASTER_CREDENTIALS)
        }

        stage('Restore') {

            def ocVersionPillarKey = salt.getReturnValues(salt.getPillar(pepperEnv, "I@opencontrail:control:role:primary", "_param:opencontrail_version"))

            if (ocVersionPillarKey == '') {
                throw new Exception("Cannot get value for _param:opencontrail_version key on I@opencontrail:control:role:primary target")
            }

            def ocVersion = ocVersionPillarKey.toString()

            if (ocVersion >= "4.0") {

                contrailStatusCheckCmd = "doctrail controller ${contrailStatusCheckCmd}"
                zkDbPath = "/var/lib/config_zookeeper_data/version-2"

                for (service in (oc4ConfigServices + oc4ControlServices + [zkService])) {
                    try {
                        salt.runSaltProcessStep(pepperEnv, 'I@opencontrail:control', 'cmd.run', ["doctrail controller systemctl stop ${service}"])
                    } catch (Exception er) {
                        common.warningMsg("${service} cannot be stopped inside controller container")
                    }
                }
                // wait until zookeeper service is down
                salt.commandStatus(pepperEnv, 'I@opencontrail:control', "doctrail controller service ${zkService} status", 'Active: inactive')
            } else {
                for (service in (oc3SupervisorServices + [zkService])) {
                    try {
                        salt.runSaltProcessStep(pepperEnv, 'I@opencontrail:control', 'service.stop', ["${service}"])
                    } catch (Exception er) {
                        common.warningMsg("${service} service cannot be stopped. It may be already stopped before.")
                    }
                }
                // wait until zookeeper service is down
                salt.commandStatus(pepperEnv, 'I@opencontrail:control', "service ${zkService} status", "stop")
            }

            try {
                salt.cmdRun(pepperEnv, 'I@opencontrail:control', "mkdir -p /root/zookeeper/zookeeper.bak")
            } catch (Exception er) {
                common.warningMsg('/root/zookeeper/zookeeper.bak directory already exists')
            }

            try {
                salt.cmdRun(pepperEnv, 'I@opencontrail:control', "mv ${zkDbPath}/* /root/zookeeper/zookeeper.bak")
            } catch (Exception er) {
                common.warningMsg('Files were already moved')
            }
            try {
                salt.cmdRun(pepperEnv, 'I@opencontrail:control', "rm -rf ${zkDbPath}/*")
            } catch (Exception er) {
                common.warningMsg('Directory already empty')
            }

            backupDirPillarKey = salt.getPillar(pepperEnv, "I@opencontrail:control", 'zookeeper:backup:backup_dir')
            backupDir = backupDirPillarKey['return'][0].values()[0]
            if (backupDir == null || backupDir.isEmpty()) { backupDir='/var/backups/zookeeper' }
            print(backupDir)
            salt.runSaltProcessStep(pepperEnv, 'I@opencontrail:control', 'file.remove', ["${backupDir}/dbrestored"])

            // performs restore
            salt.enforceState(pepperEnv, 'I@opencontrail:control', "zookeeper.backup")

            if (ocVersion >= "4.0") {
                for (service in ([zkService] + oc4ConfigServices + oc4ControlServices)) {
                    salt.runSaltProcessStep(pepperEnv, 'I@opencontrail:control', 'cmd.run', ["doctrail controller systemctl start ${service}"])
                }
            } else {
                for (service in ([zkService] + oc3SupervisorServices)) {
                    salt.runSaltProcessStep(pepperEnv, 'I@opencontrail:control', 'service.start', ["${service}"])
                }
            }

            // wait until contrail-status is up
            salt.commandStatus(pepperEnv, 'I@opencontrail:control', contrailStatusCheckCmd, null, false)

            salt.cmdRun(pepperEnv, 'I@opencontrail:control', "ls ${zkDbPath}")
            try {
                salt.cmdRun(pepperEnv, 'I@opencontrail:control', "echo stat | nc localhost 2181")
            } catch (Exception er) {
                common.warningMsg('Check which node is zookeeper leader')
            }
        }
    }
}
