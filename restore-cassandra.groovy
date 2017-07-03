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


def saltMaster

timestamps {
    node() {

        stage('Connect to Salt API') {
            saltMaster = salt.connection(SALT_MASTER_URL, SALT_MASTER_CREDENTIALS)
        }

        stage('Start restore') {
            // # actual upgrade

            stage('Ask for manual confirmation') {
                input message: "Are you sure you have the correct backups ready? Do you really want to continue to restore Cassandra?"
            }
            // Cassandra restore section
            try {
                salt.runSaltProcessStep(saltMaster, 'I@opencontrail:control', 'service.stop', ['supervisor-database'], null, true)
            } catch (Exception er) {
                common.warningMsg('Supervisor-database service already stopped')
            }
            try {
                salt.cmdRun(saltMaster, 'I@opencontrail:control', "mkdir /root/cassandra/cassandra.bak")
            } catch (Exception er) {
                common.warningMsg('Directory already exists')
            }

            // add check if empty dir ?
            try {
                salt.cmdRun(saltMaster, 'I@opencontrail:control', "mv /var/lib/cassandra/* /root/cassandra/cassandra.bak")
            } catch (Exception er) {
                common.warningMsg('Files were already moved')
            }

            _pillar = salt.getPillar(saltMaster, "ntw01*", 'cassandra:backup:backup_dir')
            backup_dir = _pillar['return'][0].values()[0]
            if(backup_dir == null || backup_dir.isEmpty()) { backup_dir='/var/backups/cassandra' }
            print(backup_dir)
            salt.runSaltProcessStep(saltMaster, 'I@galera:master', 'file.remove', ["${backup_dir}/dbrestored"], null, true)

            salt.runSaltProcessStep(saltMaster, 'ntw01*', 'service.start', ['supervisor-database'], null, true)

            sleep(10)

            // performs restore
            salt.cmdRun(saltMaster, 'ntw01*', "su root -c 'salt-call state.sls cassandra'")
            salt.runSaltProcessStep(saltMaster, 'ntw01*', 'system.reboot', null, null, true, 5)
            salt.runSaltProcessStep(saltMaster, 'ntw02*', 'system.reboot', null, null, true, 5)
            salt.runSaltProcessStep(saltMaster, 'ntw03*', 'system.reboot', null, null, true, 5)

            sleep(15)
            salt.runSaltProcessStep(saltMaster, 'I@opencontrail:control', 'service.restart', ['supervisor-database'], null, true)

            sleep(10)
            salt.cmdRun(saltMaster, 'I@opencontrail:control', "nodetool status")
            salt.cmdRun(saltMaster, 'I@opencontrail:control', "contrail-status")
        }
    }
}



