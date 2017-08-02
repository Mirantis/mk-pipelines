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
            salt.cmdRun(saltMaster, 'I@opencontrail:control', "mkdir -p /root/cassandra/cassandra.bak")
        } catch (Exception er) {
            common.warningMsg('Directory already exists')
        }

        try {
            salt.cmdRun(saltMaster, 'I@opencontrail:control', "mv /var/lib/cassandra/* /root/cassandra/cassandra.bak")
        } catch (Exception er) {
            common.warningMsg('Files were already moved')
        }
        try {
            salt.cmdRun(saltMaster, 'I@opencontrail:control', "rm -rf /var/lib/cassandra/*")
        } catch (Exception er) {
            common.warningMsg('Directory already empty')
        }

        _pillar = salt.getPillar(saltMaster, "I@cassandra:backup:client", 'cassandra:backup:backup_dir')
        backup_dir = _pillar['return'][0].values()[0]
        if(backup_dir == null || backup_dir.isEmpty()) { backup_dir='/var/backups/cassandra' }
        print(backup_dir)
        salt.runSaltProcessStep(saltMaster, 'I@cassandra:backup:client', 'file.remove', ["${backup_dir}/dbrestored"], null, true)

        salt.runSaltProcessStep(saltMaster, 'I@cassandra:backup:client', 'service.start', ['supervisor-database'], null, true)

        // wait until supervisor-database service is up
        salt.commandStatus(saltMaster, 'I@cassandra:backup:client', 'service supervisor-database status', 'running')

        // performs restore
        salt.cmdRun(saltMaster, 'I@cassandra:backup:client', "su root -c 'salt-call state.sls cassandra'")
        salt.runSaltProcessStep(saltMaster, 'I@cassandra:backup:client', 'system.reboot', null, null, true, 5)
        salt.runSaltProcessStep(saltMaster, 'I@opencontrail:control and not I@cassandra:backup:client', 'system.reboot', null, null, true, 5)

        // wait until supervisor-database service is up
        salt.commandStatus(saltMaster, 'I@cassandra:backup:client', 'service supervisor-database status', 'running')
        salt.commandStatus(saltMaster, 'I@opencontrail:control and not I@cassandra:backup:client', 'service supervisor-database status', 'running')
        sleep(5)

        salt.runSaltProcessStep(saltMaster, 'I@opencontrail:control', 'service.restart', ['supervisor-database'], null, true)

        // wait until contrail-status is up
        salt.commandStatus(saltMaster, 'I@opencontrail:control', "contrail-status | grep -v == | grep -v \'disabled on boot\' | grep -v nodemgr | grep -v active | grep -v backup", null, false)

        salt.cmdRun(saltMaster, 'I@opencontrail:control', "nodetool status")
        salt.cmdRun(saltMaster, 'I@opencontrail:control', "contrail-status")
    }
}
