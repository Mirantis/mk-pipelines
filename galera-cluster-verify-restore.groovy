/**
 * Verify and restore Galera cluster
 *
 * Expected parameters:
 *   SALT_MASTER_CREDENTIALS    Credentials to the Salt API.
 *   SALT_MASTER_URL            Full Salt API address [http://10.10.10.1:8000].
 *   ASK_CONFIRMATION           Ask confirmation for restore
 *   VERIFICATION_RETRIES       Number of restries to verify the restoration.
 *   CHECK_TIME_SYNC            Set to true to check time synchronization accross selected nodes.
 *   RESTORE_TYPE               Sets restoration method
 *
**/

def common = new com.mirantis.mk.Common()
def salt = new com.mirantis.mk.Salt()
def galera = new com.mirantis.mk.Galera()
def python = new com.mirantis.mk.Python()
def pepperEnv = "pepperEnv"
def resultCode = 99
def restoreType = env.RESTORE_TYPE
def runRestoreDb = false
def runBackupDb = false
def restartCluster = false

askConfirmation = (env.getProperty('ASK_CONFIRMATION') ?: true).toBoolean()
checkTimeSync = (env.getProperty('CHECK_TIME_SYNC') ?: true).toBoolean()

if (common.validInputParam(VERIFICATION_RETRIES) && VERIFICATION_RETRIES.isInteger()) {
    verificationRetries = VERIFICATION_RETRIES.toInteger()
} else {
    verificationRetries = 5
}
if (restoreType.equals("BACKUP_AND_RESTORE") || restoreType.equals("ONLY_RESTORE")) {
    runRestoreDb = true
}
if (restoreType.equals("BACKUP_AND_RESTORE")) {
    runBackupDb = true
}
if (restoreType.equals("RESTART_CLUSTER")) {
    restartCluster = true
}

timeout(time: 12, unit: 'HOURS') {
    node() {
        stage('Setup virtualenv for Pepper') {
            python.setupPepperVirtualenv(pepperEnv, SALT_MASTER_URL, SALT_MASTER_CREDENTIALS)
        }

        def galeraStatus = [:]
        stage('Verify status') {
            def sysstatTargets = 'I@xtrabackup:client or I@xtrabackup:server'
            def sysstatTargetsNodes = salt.getMinions(pepperEnv, sysstatTargets)
            try {
                if (!salt.isPackageInstalled(['saltId': pepperEnv, 'target': sysstatTargets, 'packageName': 'sysstat', 'output': false])) {
                    if (askConfirmation) {
                        input message: "Do you want to install 'sysstat' package on targeted nodes: ${sysstatTargetsNodes}? Click to confirm"
                    }
                    salt.runSaltProcessStep(pepperEnv, sysstatTargets, 'pkg.install', ['sysstat'])
                }
            } catch (Exception e) {
                common.errorMsg("Unable to determine status of sysstat package on target nodes: ${sysstatTargetsNodes}.")
                common.errorMsg(e.getMessage())
                if (askConfirmation) {
                    input message: "Do you want to continue? Click to confirm"
                }
            }
            try {
                common.infoMsg('Checking required xtrabackup pillars...')
                def xtrabackupRestoreFrom = salt.getPillar(pepperEnv, 'I@galera:master or I@galera:slave', 'xtrabackup:client:restore_from')
                def xtrabackupRestoreLatest = salt.getPillar(pepperEnv, 'I@galera:master or I@galera:slave', 'xtrabackup:client:restore_full_latest')
                if ('' in xtrabackupRestoreFrom['return'][0].values() || '' in xtrabackupRestoreLatest['return'][0].values()) {
                    throw new Exception('Pillars xtrabackup:client:restore_from or xtrabackup:client:restore_full_latest are missed for \'I@galera:master or I@galera:slave\' nodes.')
                }
            } catch (Exception e) {
                common.errorMsg(e.getMessage())
                common.errorMsg('Please fix your pillar data. For more information check docs: https://docs.mirantis.com/mcp/latest/mcp-operations-guide/backup-restore/openstack/database/xtrabackup-restore-database.html')
                return
            }
            galeraStatus = galera.verifyGaleraStatus(pepperEnv, checkTimeSync)

            switch (galeraStatus.error) {
                case 128:
                    common.errorMsg("Unable to obtain Galera members minions list. Without fixing this issue, pipeline cannot continue in verification, backup and restoration. This may be caused by wrong Galera configuration or corrupted pillar data.")
                    currentBuild.result = "FAILURE"
                    return
                case 130:
                    common.errorMsg("Neither master or slaves are reachable. Without fixing this issue, pipeline cannot continue in verification, backup and restoration. Is at least one member of the Galera cluster up and running?")
                    currentBuild.result = "FAILURE"
                    return
                case 131:
                    common.errorMsg("Time desynced - Please fix this issue and rerun the pipeline.")
                    currentBuild.result = "FAILURE"
                    return
                case 140..141:
                    common.errorMsg("Disk utilization check failed - Please fix this issue and rerun the pipeline.")
                    currentBuild.result = "FAILURE"
                    return
                case 1:
                    if (askConfirmation) {
                        input message: "There was a problem with parsing the status output or with determining it. Do you want to run a next action: ${restoreType}?"
                    } else {
                        common.warningMsg("There was a problem with parsing the status output or with determining it. Trying to perform action: ${restoreType}.")
                    }
                    break
                case 0:
                    if (askConfirmation) {
                        input message: "There seems to be everything alright with the cluster, do you still want to continue with next action: ${restoreType}?"
                        break
                    } else {
                        common.warningMsg("There seems to be everything alright with the cluster, no backup and no restoration will be done.")
                        currentBuild.result = "SUCCESS"
                        return
                    }
                default:
                    if (askConfirmation) {
                        input message: "There's something wrong with the cluster, do you want to continue with action: ${restoreType}?"
                    } else {
                        common.warningMsg("There's something wrong with the cluster, trying to perform action: ${restoreType}")
                    }
                    break
            }
        }
        if (runBackupDb) {
            if (askConfirmation) {
                input message: "Are you sure you want to run a backup? Click to confirm"
            }
            stage('Backup') {
                deployBuild = build(job: 'galera_backup_database', parameters: [
                        [$class: 'StringParameterValue', name: 'SALT_MASTER_URL', value: SALT_MASTER_URL],
                        [$class: 'StringParameterValue', name: 'SALT_MASTER_CREDENTIALS', value: SALT_MASTER_CREDENTIALS],
                        [$class: 'StringParameterValue', name: 'OVERRIDE_BACKUP_NODE', value: "none"],
                ]
                )
            }
        }
        if (runRestoreDb || restartCluster) {
            if (runRestoreDb) {
                stage('Restore') {
                    if (askConfirmation) {
                        input message: "Are you sure you want to run a restore? Click to confirm"
                    }
                    try {
                        if ((!askConfirmation && resultCode > 0) || askConfirmation) {
                            galera.restoreGaleraCluster(pepperEnv, galeraStatus)
                        }
                    } catch (Exception e) {
                        common.errorMsg("Restoration process has failed.")
                        common.errorMsg(e.getMessage())
                    }
                }
            }
            if (restartCluster) {
                stage('Restart cluster') {
                    if (askConfirmation) {
                        input message: "Are you sure you want to run a restart? Click to confirm"
                    }
                    try {
                        if ((!askConfirmation && resultCode > 0) || askConfirmation) {
                            galera.restoreGaleraCluster(pepperEnv, galeraStatus, false)
                        }
                    } catch (Exception e) {
                        common.errorMsg("Restart process has failed.")
                        common.errorMsg(e.getMessage())
                    }
                }
            }
            stage('Verify restoration result') {
                common.retry(verificationRetries, 15) {
                    def status = galera.verifyGaleraStatus(pepperEnv, false)
                    if (status.error >= 1) {
                        error("Verification attempt finished with an error. This may be caused by cluster not having enough time to come up or to sync. Next verification attempt in 5 seconds.")
                    } else {
                        common.infoMsg("Restoration procedure seems to be successful. See verification report to be sure.")
                        currentBuild.result = "SUCCESS"
                    }
                }
            }
        }
    }
}
