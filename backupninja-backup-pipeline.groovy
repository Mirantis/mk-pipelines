def common = new com.mirantis.mk.Common()
def salt = new com.mirantis.mk.Salt()
def python = new com.mirantis.mk.Python()

timeout(time: 12, unit: 'HOURS') {
    node() {
        stage('Setup virtualenv for Pepper') {
            python.setupPepperVirtualenv(pepperEnv, SALT_MASTER_URL, SALT_MASTER_CREDENTIALS)
        }
        stage('Check backup location') {
            try{
              backupNode = salt.getMinions(pepperEnv, "I@backupninja:client")[0]
              salt.minionsReachable(pepperEnv, "I@salt:master", backupNode)
            }
            catch (Exception e) {
                common.errorMsg(e.getMessage())
                common.errorMsg("Pipeline wasn't able to detect backupninja:client pillar or the minion is not reachable")
                currentBuild.result = "FAILURE"
                return
            }
            try{
              backupServer = salt.getMinions(pepperEnv, "I@backupninja:server")[0]
              salt.minionsReachable(pepperEnv, "I@salt:master", backupServer)
            }
            catch (Exception e) {
                common.errorMsg(e.getMessage())
                common.errorMsg("Pipeline wasn't able to detect backupninja:server pillar or the minion is not reachable")
                currentBuild.result = "FAILURE"
                return
            }
        }
        stage ('Prepare for backup') {
                salt.enforceState(['saltId': pepperEnv, 'target': 'I@backupninja:server', 'state': 'backupninja'])
                salt.enforceState(['saltId': pepperEnv, 'target': 'I@backupninja:client', 'state': 'backupninja'])
        }
        stage('Backup') {
            output = salt.getReturnValues(salt.cmdRun(pepperEnv, backupNode, "su root -c 'backupninja --now -d'")).readLines()[-2]
            def outputPattern = java.util.regex.Pattern.compile("\\d+")
            def outputMatcher = outputPattern.matcher(output)
              if (outputMatcher.find()) {
                  try{
                  result = outputMatcher.getAt([0,1,2,3])
                  }
                  catch (Exception e){
                    common.errorMsg(e.getMessage())
                    common.errorMsg("Parsing failed.")
                    currentBuild.result = "FAILURE"
                    return
                  }
            }
            if (result[1] == 0 || result == ""){
                common.errorMsg("Backup failed.")
                currentBuild.result = "FAILURE"
                return
            }
            else {
              common.successMsg("Backup successfully finished " + result[1] + " fatals, " + result[2] + " errors " + result[3] +" warnings")
            }
        }
    }
}
