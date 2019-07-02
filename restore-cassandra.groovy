/**
 * Update packages on given nodes
 *
 * Expected parameters:
 *   SALT_MASTER_CREDENTIALS    Credentials to the Salt API.
 *   SALT_MASTER_URL            Full Salt API address [http://10.10.10.1:8000].
 *
**/

common = new com.mirantis.mk.Common()
salt = new com.mirantis.mk.Salt()
python = new com.mirantis.mk.Python()

def pepperEnv = "pepperEnv"

def getValueForPillarKey(pepperEnv, target, pillarKey) {
    def out = salt.getReturnValues(salt.getPillar(pepperEnv, target, pillarKey))
    if (out == '') {
        throw new Exception("Cannot get value for ${pillarKey} key on ${target} target")
    }
    return out.toString()
}

timeout(time: 12, unit: 'HOURS') {
    node() {

        stage('Setup virtualenv for Pepper') {
            python.setupPepperVirtualenv(pepperEnv, SALT_MASTER_URL, SALT_MASTER_CREDENTIALS)
        }

        stage('Opencontrail controllers health check') {
            try {
                salt.enforceState(pepperEnv, 'I@opencontrail:control or I@opencontrail:collector', 'opencontrail.upgrade.verify', true, true)
            } catch (Exception er) {
                common.errorMsg("Opencontrail controllers health check stage found issues with services. Please take a look at the logs above.")
                throw er
            }
        }

        stage('Backup') {
            salt.cmdRun(pepperEnv, 'I@cassandra:backup:client', 'bash /usr/local/bin/cassandra-backup-runner-call.sh')
        }

        stage('Restore') {
            // get opencontrail version
            def contrailVersion = getValueForPillarKey(pepperEnv, "I@opencontrail:control:role:primary", "_param:opencontrail_version")
            common.infoMsg("OpenContrail version is ${contrailVersion}")
            if (contrailVersion.startsWith('4')) {
                controllerImage = getValueForPillarKey(pepperEnv, "I@opencontrail:control:role:primary",
                        "docker:client:compose:opencontrail:service:controller:container_name")
                common.infoMsg("Applying db restore procedure for OpenContrail 4.X version")
                try {
                    salt.cmdRun(pepperEnv, 'I@opencontrail:control', 'doctrail controller systemctl stop contrail-database' )
                } catch (Exception err) {
                    common.errorMsg('An error has been occurred during cassandra db shutdown: ' + err.getMessage())
                    throw err
                }
                try {
                    salt.cmdRun(pepperEnv, 'I@opencontrail:control', "docker exec ${controllerImage} bash -c 'for f in \$(ls /var/lib/cassandra/); do rm -r /var/lib/cassandra/\$f; done'")
                } catch (Exception err) {
                    common.errorMsg('Cannot cleanup cassandra data: ' + err.getMessage())
                    throw err
                }
                try {
                    salt.cmdRun(pepperEnv, 'I@cassandra:backup:client', 'doctrail controller systemctl start contrail-database' )
                } catch (Exception err) {
                    common.errorMsg('An error has been occurred during cassandra db startup: ' + err.getMessage())
                    throw err
                }
                // remove restore-already-happenned file if any is present
                try {
                    salt.cmdRun(pepperEnv, 'I@cassandra:backup:client', 'rm /var/backups/cassandra/dbrestored')
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
                // another mantra, wait till all services are up
                sleep(60)
            } else {
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

                def backupDir = getValueForPillarKey(pepperEnv, "I@cassandra:backup:client", "cassandra:backup:backup_dir")
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

        stage('Opencontrail controllers health check') {
            common.retry(3, 20){
                salt.enforceState(pepperEnv, 'I@opencontrail:control or I@opencontrail:collector', 'opencontrail.upgrade.verify', true, true)
            }
        }
    }
}
