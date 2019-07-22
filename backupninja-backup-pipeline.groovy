def common = new com.mirantis.mk.Common()
def salt = new com.mirantis.mk.Salt()
def python = new com.mirantis.mk.Python()
def pepperEnv = "pepperEnv"

timeout(time: 12, unit: 'HOURS') {
    node() {
        stage('Setup virtualenv for Pepper') {
            python.setupPepperVirtualenv(pepperEnv, SALT_MASTER_URL, SALT_MASTER_CREDENTIALS)
        }
        stage('Verify pillar for backups') {
            try {
                def masterPillar = salt.getPillar(pepperEnv, "I@salt:master", 'salt:master:initial_data')
                if (masterPillar['return'].isEmpty()) {
                    throw new Exception('Problem with salt-master pillar.')
                }
                def minionPillar = salt.getPillar(pepperEnv, "I@salt:master", 'salt:minion:initial_data')
                if (minionPillar['return'].isEmpty()) {
                    throw new Exception('Problem with salt-minion pillar.')
                }
            }
            catch (Exception e) {
                common.errorMsg(e.getMessage())
                common.errorMsg('Please fix your pillar. For more information check docs: https://docs.mirantis.com/mcp/latest/mcp-operations-guide/backup-restore/salt-master.html')
                return
            }
        }
        stage('Check backup location') {
            try {
                backupNode = salt.getMinions(pepperEnv, "I@backupninja:client")[0]
                salt.minionsReachable(pepperEnv, "I@salt:master", backupNode)
            }
            catch (Exception e) {
                common.errorMsg(e.getMessage())
                common.errorMsg("Pipeline wasn't able to detect backupninja:client pillar or the minion is not reachable")
                currentBuild.result = "FAILURE"
                return
            }
            try {
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
        stage('Prepare for backup') {
            salt.enforceState(['saltId': pepperEnv, 'target': 'I@backupninja:server', 'state': 'backupninja'])
            salt.enforceState(['saltId': pepperEnv, 'target': 'I@backupninja:client', 'state': 'backupninja'])
            def backupMasterSource = salt.getReturnValues(salt.getPillar(pepperEnv, backupNode, 'salt:master:initial_data:source'))
            def backupMinionSource = salt.getReturnValues(salt.getPillar(pepperEnv, backupNode, 'salt:minion:initial_data:source'))
            [backupServer, backupMasterSource, backupMinionSource].unique().each {
                salt.cmdRun(pepperEnv, backupNode, "ssh-keygen -F ${it} || ssh-keyscan -H ${it} >> /root/.ssh/known_hosts")
            }
        }
        stage('Backup') {
            def output = salt.getReturnValues(salt.cmdRun(pepperEnv, backupNode, "su root -c 'backupninja --now -d'")).readLines()[-2]
            def outputPattern = java.util.regex.Pattern.compile("\\d+")
            def outputMatcher = outputPattern.matcher(output)
            if (outputMatcher.find()) {
                try {
                    result = outputMatcher.getAt([0, 1, 2, 3])
                }
                catch (Exception e) {
                    common.errorMsg(e.getMessage())
                    common.errorMsg("Parsing failed.")
                    currentBuild.result = "FAILURE"
                    return
                }
            }
            if (result[1] != null && result[1] instanceof String && result[1].isInteger() && (result[1].toInteger() < 1)) {
                common.successMsg("Backup successfully finished " + result[1] + " fatals, " + result[2] + " errors " + result[3] + " warnings.")
            } else {
                common.errorMsg("Backup failed. Found " + result[1] + " fatals, " + result[2] + " errors " + result[3] + " warnings.")
                currentBuild.result = "FAILURE"
                return
            }
        }
    }
}
