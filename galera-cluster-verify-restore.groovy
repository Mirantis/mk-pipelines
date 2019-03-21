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

timeout(time: 12, unit: 'HOURS') {
    node() {
        stage('Setup virtualenv for Pepper') {
            python.setupPepperVirtualenv(pepperEnv, SALT_MASTER_URL, SALT_MASTER_CREDENTIALS)
        }
        stage('Verify status') {
            resultCode = galera.verifyGaleraStatus(pepperEnv, false, checkTimeSync)
            if (resultCode == 128) {
                common.errorMsg("Unable to connect to Galera Master. Trying slaves...")
                resultCode = galera.verifyGaleraStatus(pepperEnv, true, checkTimeSync)
                if (resultCode == 129) {
                    common.errorMsg("Unable to obtain Galera slave minions list". "Without fixing this issue, pipeline cannot continue in verification, backup and restoration.")
                    currentBuild.result = "FAILURE"
                    return
                } else if (resultCode == 130) {
                    common.errorMsg("Neither master or slaves are reachable. Without fixing this issue, pipeline cannot continue in verification, backup and restoration.")
                    currentBuild.result = "FAILURE"
                    return
                }
            }
            if (resultCode == 131) {
                common.errorMsg("Time desynced - Click proceed when the issue is fixed or abort.")
                currentBuild.result = "FAILURE"
            }
            if (resultCode == 1) {
                if(askConfirmation){
                    common.warningMsg("There was a problem with parsing the status output or with determining it. Do you want to run a restore?")
                } else {
                    common.warningMsg("There was a problem with parsing the status output or with determining it. Try to restore.")
                }
            } else if (resultCode > 1) {
                if(askConfirmation){
                    common.warningMsg("There's something wrong with the cluster, do you want to continue with backup and/or restore?")
                } else {
                    common.warningMsg("There's something wrong with the cluster, try to backup and/or restore.")
                }
            } else {
                if(askConfirmation){
                  common.warningMsg("There seems to be everything alright with the cluster, do you still want to continue with backup and/or restore?")
                } else {
                  common.warningMsg("There seems to be everything alright with the cluster, no backup and no restoration will be done.")
                  currentBuild.result = "SUCCESS"
                  return
                }
            }
        }
        if (runBackupDb) {
            stage('Backup') {
                deployBuild = build( job: "galera-database-backup-pipeline", parameters: [
                    [$class: 'StringParameterValue', name: 'SALT_MASTER_URL', value: SALT_MASTER_URL],
                    [$class: 'StringParameterValue', name: 'SALT_MASTER_CREDENTIALS', value: SALT_MASTER_CREDENTIALS],
                    [$class: 'StringParameterValue', name: 'OVERRIDE_BACKUP_NODE', value: "none"],
                    ]
                )
            }
        }
        stage('Restore') {
            if(askConfirmation){
              input message: "Are you sure you want to run a restore? Click to confirm"
            }
            try {
                if((!askConfirmation && resultCode > 0) || askConfirmation){
                  galera.restoreGaleraCluster(pepperEnv, runRestoreDb)
                }
            } catch (Exception e) {
                common.errorMsg("Restoration process has failed.")
            }
        }
        stage('Verify restoration result') {
            common.retry(verificationRetries, 15) {
                exitCode = galera.verifyGaleraStatus(pepperEnv, false, false)
                if (exitCode >= 1) {
                    error("Verification attempt finished with an error. This may be caused by cluster not having enough time to come up or to sync. Next verification attempt in 5 seconds.")
                } else {
                    common.infoMsg("Restoration procedure seems to be successful. See verification report to be sure.")
                    currentBuild.result = "SUCCESS"
                }
            }
        }
    }
}
