def common = new com.mirantis.mk.Common()
def salt = new com.mirantis.mk.Salt()
def python = new com.mirantis.mk.Python()
def pepperEnv = "pepperEnv"
def askConfirmation = (env.getProperty('ASK_CONFIRMATION') ?: true).toBoolean()
def backupSaltMasterAndMaas = (env.getProperty('BACKUP_SALTMASTER_AND_MAAS') ?: true).toBoolean()
def backupDogtag = (env.getProperty('BACKUP_DOGTAG') ?: true).toBoolean()
def saltMasterTargetMatcher = "I@backupninja:client and I@salt:master"
def dogtagTagetMatcher = "I@backupninja:client and I@dogtag:server"
logBackupSuccess = []
logBackupFailure = []

def checkBackupninjaLog(output, backupName='', terminateOnFailure=true) {
    def common = new com.mirantis.mk.Common()
    def outputPattern = java.util.regex.Pattern.compile("\\d+")
    def outputMatcher = outputPattern.matcher(output)
    if (outputMatcher.find()) {
        try {
            result = outputMatcher.getAt([0, 1, 2, 3])
            if (result[1] != null && result[1] instanceof String && result[1].isInteger() && (result[1].toInteger() < 1)) {
                common.successMsg("[${backupName}] - Backup successfully finished " + result[1] + " fatals, " + result[2] + " errors " + result[3] + " warnings.")
                logBackupSuccess.add(backupName)
            } else {
                common.errorMsg("[${backupName}] - Backup failed. Found " + result[1] + " fatals, " + result[2] + " errors " + result[3] + " warnings.")
                logBackupFailure.add(backupName)
            }
        }
        catch (Exception e) {
            common.errorMsg(e.getMessage())
            common.errorMsg("[${backupName}] - Backupninja log parsing failed.")
            logBackupFailure.add(backupName)
        }
    }
}

timeout(time: 12, unit: 'HOURS') {
    node() {
        def saltMasterBackupNode = ''
        def dogtagBackupNode = ''
        def backupServer = ''
        stage('Setup virtualenv for Pepper') {
            python.setupPepperVirtualenv(pepperEnv, SALT_MASTER_URL, SALT_MASTER_CREDENTIALS)
        }
        stage('Verify pillar for backups') {
            if (backupSaltMasterAndMaas) {
                try {
                    def masterPillar = salt.getPillar(pepperEnv, "I@salt:master", 'salt:master:initial_data')
                    if (masterPillar['return'].isEmpty()) {
                        throw new Exception("Problem with salt-master pillar on 'I@salt:master' node.")
                    }
                    def minionPillar = salt.getPillar(pepperEnv, "I@salt:master", 'salt:minion:initial_data')
                    if (minionPillar['return'].isEmpty()) {
                        throw new Exception("Problem with salt-minion pillar on I@salt:master node.")
                    }
                }
                catch (Exception e) {
                    common.errorMsg(e.getMessage())
                    common.errorMsg('Please fix your pillar. For more information check docs: https://docs.mirantis.com/mcp/latest/mcp-operations-guide/backup-restore/salt-master.html')
                    return
                }
            }
            if (backupDogtag) {
                try {
                    def dogtagPillar = salt.getPillar(pepperEnv, "I@salt:master", "dogtag:server")
                    if (dogtagPillar['return'].isEmpty()) {
                        throw new Exception("Problem with dogtag pillar on I@dogtag:server node.")
                    }
                }
                catch (Exception e) {
                    common.errorMsg(e.getMessage())
                    common.errorMsg("Looks like dogtag pillar is not defined. Fix your pillar or disable dogtag backup by setting the BACKUP_DOGTAG parameter to False if you're using different barbican backend.")
                    return
                }
            }
        }
        stage('Check backup location') {
            if (backupSaltMasterAndMaas) {
                try {
                    saltMasterBackupNode = salt.getMinionsSorted(pepperEnv, saltMasterTargetMatcher)[0]
                    salt.minionsReachable(pepperEnv, "I@salt:master", saltMasterBackupNode)
                }
                catch (Exception e) {
                    common.errorMsg(e.getMessage())
                    common.errorMsg("Pipeline wasn't able to detect backupninja:client pillar on Salt master node or the minion is not reachable")
                    currentBuild.result = "FAILURE"
                    return
                }

                def maasNodes = salt.getMinions(pepperEnv, 'I@maas:server')
                if (!maasNodes.isEmpty()) {
                    def postgresqlMajorVersion = salt.getPillar(pepperEnv, 'I@salt:master', '_param:postgresql_major_version').get('return')[0].values()[0]
                    if (! postgresqlMajorVersion) {
                            common.errorMsg("Can't get _param:postgresql_major_version parameter, which is required to determine postgresql-client version. Is it defined in pillar?")
                            if (askConfirmation) {
                                input message: "Confirm to proceed anyway."
                            }
                    } else {
                        def postgresqlClientPackage = "postgresql-client-${postgresqlMajorVersion}"
                        try {
                            if (!salt.isPackageInstalled(['saltId': pepperEnv, 'target': saltMasterBackupNode, 'packageName': postgresqlClientPackage, 'output': false])) {
                                if (askConfirmation) {
                                    input message: "Do you want to install ${postgresqlClientPackages} package on targeted nodes: ${saltMasterBackupNode}? It's required to make backup. Click to confirm."
                                } else {
                                    common.infoMsg("Package ${postgresqlClientPackages} will be installed. It's required to make backup.")
                                }
                                // update also common fake package
                                salt.runSaltProcessStep(pepperEnv, saltMasterBackupNode, 'pkg.install', ["postgresql-client,${postgresqlClientPackage}"])
                            }
                        } catch (Exception e) {
                            common.errorMsg("Unable to determine status of ${postgresqlClientPackages} packages on target nodes: ${saltMasterBackupNode}.")
                            if (askConfirmation) {
                                input message: "Do you want to continue? Click to confirm"
                            }
                        }
                    }
                }
            }
            if (backupDogtag) {
                try {
                    dogtagBackupNode = salt.getMinionsSorted(pepperEnv, dogtagTagetMatcher)[0]
                    salt.minionsReachable(pepperEnv, "I@salt:master", dogtagBackupNode)
                }
                catch (Exception e) {
                    common.errorMsg(e.getMessage())
                    common.errorMsg("Pipeline wasn't able to detect node with backupninja:client and dogtag:server pillars defined or the minion is not reachable")
                    currentBuild.result = "FAILURE"
                    return
                }
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
            if (backupSaltMasterAndMaas) {
                salt.enforceState(['saltId': pepperEnv, 'target': 'I@backupninja:server', 'state': 'backupninja'])
                salt.enforceState(['saltId': pepperEnv, 'target': saltMasterTargetMatcher, 'state': 'backupninja'])
                def backupMasterSource = salt.getReturnValues(salt.getPillar(pepperEnv, saltMasterBackupNode, 'salt:master:initial_data:source'))
                def backupMinionSource = salt.getReturnValues(salt.getPillar(pepperEnv, saltMasterBackupNode, 'salt:minion:initial_data:source'))
                // TODO: Remove ssh-keyscan once we have openssh meta for backupninja implemented
                [backupServer, backupMasterSource, backupMinionSource].unique().each {
                    salt.cmdRun(pepperEnv, saltMasterBackupNode, "ssh-keygen -F ${it} || ssh-keyscan -H ${it} >> /root/.ssh/known_hosts")
                }
                def maasNodes = salt.getMinions(pepperEnv, 'I@maas:region')
                if (!maasNodes.isEmpty()) {
                    common.infoMsg("Trying to save maas file permissions on ${maasNodes} if possible")
                    salt.cmdRun(pepperEnv, 'I@maas:region', 'which getfacl && getfacl -pR /var/lib/maas/ > /var/lib/maas/file_permissions.txt || true')
                }
            }
            if (backupDogtag) {
                salt.enforceState(['saltId': pepperEnv, 'target': 'I@backupninja:server', 'state': 'backupninja'])
                salt.enforceState(['saltId': pepperEnv, 'target': dogtagTagetMatcher, 'state': 'backupninja'])
            }
        }
        stage('Backup') {
            if (backupSaltMasterAndMaas) {
                def output = salt.getReturnValues(salt.cmdRun(pepperEnv, saltMasterBackupNode, "su root -c 'backupninja --now -d'")).readLines()[-2]
                checkBackupninjaLog(output, "Salt Master/MAAS")
            }
            if (backupDogtag) {
                def output = salt.getReturnValues(salt.cmdRun(pepperEnv, dogtagBackupNode, "su root -c 'backupninja --now -d'")).readLines()[-2]
                checkBackupninjaLog(output, "Dogtag")
            }
        }
        stage('Results') {
            if (logBackupSuccess.size() > 0) {
                common.infoMsg("Following backups finished successfully: ${logBackupSuccess.join(",")}")
            }
            if (logBackupFailure.size() > 0) {
                common.errorMsg("Following backups has failed: ${logBackupFailure.join(",")}. Make sure to check the logs.")
                currentBuild.result = "FAILURE"
            }
        }
    }
}
