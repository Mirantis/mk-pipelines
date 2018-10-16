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

        stage('Restore') {
            // get opencontrail version
            def _pillar = salt.getPillar(pepperEnv, "I@opencontrail:control", '_param:opencontrail_version')
            def contrailVersion = _pillar['return'][0].values()[0]
            common.infoMsg("Contrail version is ${contrailVersion}")
            if (contrailVersion >= 4) {
                common.infoMsg("There will be steps for OC4.0 restore")
                try {
                    salt.cmdRun(pepperEnv, 'I@opencontrail:control', 'doctrail controller systemctl stop contrail-database' )
                } catch (Exception err) {
                    common.warningMsg('contrail-database already stopped? ' + err.getMessage())
                }
                try {
                    salt.cmdRun(pepperEnv, 'I@opencontrail:control', 'doctrail controller bash -c "for f in $(ls /var/lib/cassandra/); do rm -r /var/lib/cassandra/$f; done"')
                } catch (Exception err) {
                    common.warningMsg('cassandra data already removed? ' + err.getMessage())
                }
                try {
                    salt.cmdRun(pepperEnv, 'I@cassandra:backup:client', 'doctrail controller systemctl start contrail-database' )
                } catch (Exception err) {
                    common.warningMsg('contrail-database already started? ' + err.getMessage())
                }
                // remove restore-already-happenned file if any is present
                try {
                    salt.cmdRun(pepperEnv, 'I@cassandra:backup:client', 'rm  /var/backups/cassandra/dbrestored')
                } catch (Exception err) {
                    common.warningMsg('/var/backups/cassandra/dbrestored not present? ' + err.getMessage())
                }
                // perform actual backup
                salt.enforceState(pepperEnv, 'I@cassandra:backup:client', "cassandra")
                salt.runSaltProcessStep(pepperEnv, 'I@cassandra:backup:client', 'system.reboot', null, [], true, 5)
                sleep(5)
                salt.runSaltProcessStep(pepperEnv, 'I@opencontrail:control and not I@cassandra:backup:client', 'system.reboot', null, [], true, 5)
                // the lovely wait-60-seconds mantra before restarting supervisor-database service
                sleep(60)
                salt.cmdRun(pepperEnv, 'I@opencontrail:control', "doctrail controller systemctl restart contrail-database")
                // another mantra
                sleep(60)
                salt.cmdRun(pepperEnv, 'I@opencontrail:control', "doctrail controller contrail-status")
            }
            else {
                try {
                    salt.runSaltProcessStep(pepperEnv, 'I@neutron:server', 'service.stop', ['neutron-server'], null, true)
                } catch (Exception er) {
                    common.warningMsg('neutron-server service already stopped')
                }
                try {
                    salt.runSaltProcessStep(pepperEnv, 'I@opencontrail:control', 'service.stop', ['supervisor-config'], null, true)
                } catch (Exception er) {
                    common.warningMsg('Supervisor-config service already stopped')
                }
                // Cassandra restore section
                try {
                    salt.runSaltProcessStep(pepperEnv, 'I@opencontrail:control', 'service.stop', ['supervisor-database'], null, true)
                } catch (Exception er) {
                    common.warningMsg('Supervisor-database service already stopped')
                }
                try {
                    salt.cmdRun(pepperEnv, 'I@opencontrail:control', "mkdir -p /root/cassandra/cassandra.bak")
                } catch (Exception er) {
                    common.warningMsg('Directory already exists')
                }

                try {
                    salt.cmdRun(pepperEnv, 'I@opencontrail:control', "mv /var/lib/cassandra/* /root/cassandra/cassandra.bak")
                } catch (Exception er) {
                    common.warningMsg('Files were already moved')
                }
                try {
                    salt.cmdRun(pepperEnv, 'I@opencontrail:control', "rm -rf /var/lib/cassandra/*")
                } catch (Exception er) {
                    common.warningMsg('Directory already empty')
                }

                _pillar = salt.getPillar(pepperEnv, "I@cassandra:backup:client", 'cassandra:backup:backup_dir')
                def backupDir = _pillar['return'][0].values()[0] ?: '/var/backups/cassandra'
                common.infoMsg("Backup directory is ${backupDir}")
                salt.runSaltProcessStep(pepperEnv, 'I@cassandra:backup:client', 'file.remove', ["${backupDir}/dbrestored"], null, true)

                salt.runSaltProcessStep(pepperEnv, 'I@cassandra:backup:client', 'service.start', ['supervisor-database'], null, true)

                // wait until supervisor-database service is up
                salt.commandStatus(pepperEnv, 'I@cassandra:backup:client', 'service supervisor-database status', 'running')
                sleep(60)

                // performs restore
                salt.enforceState(pepperEnv, 'I@cassandra:backup:client', "cassandra.backup")
                salt.runSaltProcessStep(pepperEnv, 'I@cassandra:backup:client', 'system.reboot', null, null, true, 5)
                sleep(5)
                salt.runSaltProcessStep(pepperEnv, 'I@opencontrail:control and not I@cassandra:backup:client', 'system.reboot', null, null, true, 5)

                // wait until supervisor-database service is up
                salt.commandStatus(pepperEnv, 'I@cassandra:backup:client', 'service supervisor-database status', 'running')
                salt.commandStatus(pepperEnv, 'I@opencontrail:control and not I@cassandra:backup:client', 'service supervisor-database status', 'running')
                sleep(5)

                salt.runSaltProcessStep(pepperEnv, 'I@opencontrail:control', 'service.restart', ['supervisor-database'], null, true)
                salt.runSaltProcessStep(pepperEnv, 'I@neutron:server', 'service.start', ['neutron-server'], null, true)

                // wait until contrail-status is up
                salt.commandStatus(pepperEnv, 'I@opencontrail:control', "contrail-status | grep -v == | grep -v \'disabled on boot\' | grep -v nodemgr | grep -v active | grep -v backup", null, false)

                salt.cmdRun(pepperEnv, 'I@opencontrail:control', "nodetool status")
                salt.cmdRun(pepperEnv, 'I@opencontrail:control', "contrail-status")
            }
        }
    }
}
