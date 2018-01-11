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
timeout(time: 12, unit: 'HOURS') {
    node() {

        stage('Setup virtualenv for Pepper') {
            python.setupPepperVirtualenv(pepperEnv, SALT_MASTER_URL, SALT_MASTER_CREDENTIALS)
        }

        stage('Start restore') {
            // # actual upgrade

            stage('Ask for manual confirmation') {
                input message: "Are you sure you have the correct backups ready? Do you really want to continue to restore Zookeeper?"
            }
            // Zookeeper restore section
            try {
                salt.runSaltProcessStep(pepperEnv, 'I@opencontrail:control', 'service.stop', ['supervisor-config'], null, true)
            } catch (Exception er) {
                common.warningMsg('Supervisor-config service already stopped')
            }
            try {
                salt.runSaltProcessStep(pepperEnv, 'I@opencontrail:control', 'service.stop', ['supervisor-control'], null, true)
            } catch (Exception er) {
                common.warningMsg('Supervisor-control service already stopped')
            }
            try {
                salt.runSaltProcessStep(pepperEnv, 'I@opencontrail:control', 'service.stop', ['zookeeper'], null, true)
            } catch (Exception er) {
                common.warningMsg('Zookeeper service already stopped')
            }
            //sleep(5)
            // wait until zookeeper service is down
            salt.commandStatus(pepperEnv, 'I@opencontrail:control', 'service zookeeper status', 'stop')

            try {
                salt.cmdRun(pepperEnv, 'I@opencontrail:control', "mkdir -p /root/zookeeper/zookeeper.bak")
            } catch (Exception er) {
                common.warningMsg('Directory already exists')
            }

            try {
                salt.cmdRun(pepperEnv, 'I@opencontrail:control', "mv /var/lib/zookeeper/version-2/* /root/zookeeper/zookeeper.bak")
            } catch (Exception er) {
                common.warningMsg('Files were already moved')
            }
            try {
                salt.cmdRun(pepperEnv, 'I@opencontrail:control', "rm -rf /var/lib/zookeeper/version-2/*")
            } catch (Exception er) {
                common.warningMsg('Directory already empty')
            }

            _pillar = salt.getPillar(pepperEnv, "I@opencontrail:control", 'zookeeper:backup:backup_dir')
            backup_dir = _pillar['return'][0].values()[0]
            if(backup_dir == null || backup_dir.isEmpty()) { backup_dir='/var/backups/zookeeper' }
            print(backup_dir)
            salt.runSaltProcessStep(pepperEnv, 'I@opencontrail:control', 'file.remove', ["${backup_dir}/dbrestored"], null, true)

            // performs restore
            salt.cmdRun(pepperEnv, 'I@opencontrail:control', "su root -c 'salt-call state.sls zookeeper'")

            salt.runSaltProcessStep(pepperEnv, 'I@opencontrail:control', 'service.start', ['zookeeper'], null, true)
            salt.runSaltProcessStep(pepperEnv, 'I@opencontrail:control', 'service.start', ['supervisor-config'], null, true)
            salt.runSaltProcessStep(pepperEnv, 'I@opencontrail:control', 'service.start', ['supervisor-control'], null, true)

            // wait until contrail-status is up
            salt.commandStatus(pepperEnv, 'I@opencontrail:control', "contrail-status | grep -v == | grep -v \'disabled on boot\' | grep -v nodemgr | grep -v active | grep -v backup", null, false)

            salt.cmdRun(pepperEnv, 'I@opencontrail:control', "ls /var/lib/zookeeper/version-2")
            try {
                salt.cmdRun(pepperEnv, 'I@opencontrail:control', "echo stat | nc localhost 2181")
            } catch (Exception er) {
                common.warningMsg('Check which node is zookeeper leader')
            }
            salt.cmdRun(pepperEnv, 'I@opencontrail:control', "contrail-status")
        }
    }
}
