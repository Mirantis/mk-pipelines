/**
 * Verify and restore Galera cluster
 *
 * Expected parameters:
 *   SALT_MASTER_CREDENTIALS    Credentials to the Salt API.
 *   SALT_MASTER_URL            Full Salt API address [http://10.10.10.1:8000].
 *   ASK_CONFIRMATION           Ask confirmation for restore
 *   CHECK_TIME_SYNC            Set to true to check time synchronization accross selected nodes.
 *
**/

def common = new com.mirantis.mk.Common()
def salt = new com.mirantis.mk.Salt()
def openstack = new com.mirantis.mk.Openstack()
def python = new com.mirantis.mk.Python()
def pepperEnv = "pepperEnv"
def resultCode = 99

askConfirmation = (env.getProperty('ASK_CONFIRMATION') ?: true).toBoolean()
checkTimeSync = (env.getProperty('CHECK_TIME_SYNC') ?: true).toBoolean()

timeout(time: 12, unit: 'HOURS') {
    node() {
        stage('Setup virtualenv for Pepper') {
            python.setupPepperVirtualenv(pepperEnv, SALT_MASTER_URL, SALT_MASTER_CREDENTIALS)
        }
        stage('Verify status')
            resultCode = openstack.verifyGaleraStatus(pepperEnv, false, checkTimeSync)
        stage('Restore') {
            if (resultCode == 128) {
                common.errorMsg("Unable to connect to Galera Master. Trying slaves...")
                resultCode = openstack.verifyGaleraStatus(pepperEnv, true, checkTimeSync)
                if (resultCode == 129) {
                    common.errorMsg("Unable to obtain Galera slave minions list". "Without fixing this issue, pipeline cannot continue in verification and restoration.")
                    currentBuild.result = "FAILURE"
                } else if (resultCode == 130) {
                    common.errorMsg("Neither master or slaves are reachable. Without fixing this issue, pipeline cannot continue in verification and restoration.")
                    currentBuild.result = "FAILURE"
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
                    common.warningMsg("There's something wrong with the cluster, do you want to run a restore?")
                } else {
                    common.warningMsg("There's something wrong with the cluster, try to restore.")
                }
            } else {
                if(askConfirmation){
                  common.warningMsg("There seems to be everything alright with the cluster, do you still want to run a restore?")
                } else {
                  common.warningMsg("There seems to be everything alright with the cluster, do nothing")
                }
            }
            if(askConfirmation){
              input message: "Are you sure you want to run a restore? Click to confirm"
            }
            try {
                if((!askConfirmation && resultCode > 0) || askConfirmation){
                  openstack.restoreGaleraDb(pepperEnv)
                }
            } catch (Exception e) {
                common.errorMsg("Restoration process has failed.")
            }
        }
        stage('Verify restoration result') {
            exitCode = openstack.verifyGaleraStatus(pepperEnv, false, false)
            if (exitCode >= 1) {
                common.errorMsg("Restoration procedure was probably not successful. See verification report for more information.")
                currentBuild.result = "FAILURE"
            } else {
                common.infoMsg("Restoration procedure seems to be successful. See verification report to be sure.")
                currentBuild.result = "SUCCESS"
            }
        }
    }
}
