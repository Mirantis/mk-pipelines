def common = new com.mirantis.mk.Common()
def salt = new com.mirantis.mk.Salt()
def galera = new com.mirantis.mk.Galera()
def python = new com.mirantis.mk.Python()
def pepperEnv = "pepperEnv"

backupNode = "none"
primaryNodes = []
syncedNodes = []
galeraMembers = []
maxValue = 0
masterNode = "none"

if (common.validInputParam('OVERRIDE_BACKUP_NODE')) {
    backupNode = OVERRIDE_BACKUP_NODE
}

timeout(time: 12, unit: 'HOURS') {
    node() {
        stage('Setup virtualenv for Pepper') {
            python.setupPepperVirtualenv(pepperEnv, SALT_MASTER_URL, SALT_MASTER_CREDENTIALS)
        }
        if (backupNode.equals("none")) {
            stage('Locate Primary component') {
                galeraMembers = salt.getMinions(pepperEnv, "I@galera:master or I@galera:slave")
                for (member in galeraMembers) {          // STEP 1 - Locate all nodes that belong to Primary component
                    try {
                        salt.minionsReachable(pepperEnv, "I@salt:master", member)
                        memberStatus = galera.getWsrepParameters(pepperEnv, member, "wsrep_cluster_status", false)
                        if (memberStatus.get('wsrep_cluster_status').equals("Primary")) {
                            primaryNodes.add(member)
                            common.infoMsg("Adding ${member} as a member of a Primary component.")
                        } else {
                            common.warningMsg("Ignoring ${member} node, because it's not part of a Primary component.")
                        }
                    } catch (Exception e) {
                        common.warningMsg("Minion '${member}' is not reachable or is not possible to determine its status.")
                    }
                }
            }
            stage('Choose backup node') {
                primaryNodes = primaryNodes.sort()
                for (node in primaryNodes.reverse()) {       // Looking for the node with the highest mysql_queries value
                    try {
                        queriesValue = galera.getWsrepParameters(pepperEnv, node, "Queries", false).get('Queries')
                        common.infoMsg("Queries for ${node} - ${queriesValue}")
                        if (queriesValue >= maxValue) {
                            maxValue = queriesValue
                            masterNode = node
                            common.infoMsg("Current main node in galera cluster: ${masterNode}")
                        }
                    } catch (Exception e) {
                        common.warningMsg("Minion '${node}' unavailable.")
                    }
                }
                if (masterNode.equals("none")) {
                    backupNode = primaryNodes[-1]                      // STEP 2 - Use node with highest hostname number (last option if everything previous fails)
                } else {
                    common.infoMsg("Master node is ${masterNode}.")
                    primaryNodes = primaryNodes.minus(masterNode)
                    backupNode = primaryNodes[-1]
                }
                common.infoMsg("Choose backup node as ${backupNode}.")
            }            
        } else {
            stage('Choose backup node') {
                common.infoMsg("Backup node backup was overriden to ${backupNode}.")
            }
        }
        stage ('Prepare for backup') {
                salt.enforceState(pepperEnv, 'I@xtrabackup:server', ['linux.system.repo', 'xtrabackup'])
                salt.enforceState(pepperEnv, 'I@xtrabackup:client', ['linux.system.repo', 'openssh.client'])
        }
        stage('Backup') {
            common.infoMsg("Node ${backupNode} was selected as a backup node.")
            input: "Please check selected backup node and confirm to run the backup procedure."
            salt.cmdRun(pepperEnv, backupNode, "su root -c 'salt-call state.sls xtrabackup'")
            salt.cmdRun(pepperEnv, backupNode, "su root -c '/usr/local/bin/innobackupex-runner.sh -s'")
        }
        stage('Clean-up') {
            salt.cmdRun(pepperEnv, backupNode, "su root -c '/usr/local/bin/innobackupex-runner.sh -c'")
        }
        stage('Backup Dogtag') {
            if (!salt.getMinions(pepperEnv, "I@dogtag:server:enabled").isEmpty()) {
                dogtagBackupBuild = build(job: 'backupninja_backup', parameters: [
                        [$class: 'StringParameterValue', name: 'SALT_MASTER_URL', value: SALT_MASTER_URL],
                        [$class: 'StringParameterValue', name: 'SALT_MASTER_CREDENTIALS', value: SALT_MASTER_CREDENTIALS],
                        [$class: 'BooleanParameterValue', name: 'ASK_CONFIRMATION', value: "false"],
                        [$class: 'BooleanParameterValue', name: 'BACKUP_SALTMASTER_AND_MAAS', value: "false"],
                        [$class: 'BooleanParameterValue', name: 'BACKUP_DOGTAG', value: "true"],
                        [$class: 'BooleanParameterValue', name: 'BACKUP_KEYSTONE_CREDENTIAL_KEYS', value: "false"]
                ]
                )
            } else {
                common.warningMsg("Dogtag pillar not found. This is fine if you are using different Barbican backend.")
            }
        }
    }
}
