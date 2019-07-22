def common = new com.mirantis.mk.Common()
def salt = new com.mirantis.mk.Salt()
def python = new com.mirantis.mk.Python()
def pepperEnv = "pepperEnv"
def maasNodes

timeout(time: 12, unit: 'HOURS') {
    node() {
        stage('Setup virtualenv for Pepper') {
            python.setupPepperVirtualenv(pepperEnv, SALT_MASTER_URL, SALT_MASTER_CREDENTIALS)
        }
        stage('Salt-Master restore') {
            common.infoMsg('Verify pillar for salt-master backups')
            try {
                def masterPillar = salt.getPillar(pepperEnv, "I@salt:master", 'salt:master:initial_data')
                if(masterPillar['return'].isEmpty()) {
                    throw new Exception('Problem with salt-master pillar.')
                }
                def minionPillar = salt.getPillar(pepperEnv, "I@salt:master", 'salt:minion:initial_data')
                if(minionPillar['return'].isEmpty()) {
                    throw new Exception('Problem with salt-minion pillar.')
                }
            }
            catch (Exception e){
                common.errorMsg(e.getMessage())
                common.errorMsg('Please fix your pillar. For more information check docs: https://docs.mirantis.com/mcp/latest/mcp-operations-guide/backup-restore/salt-master/salt-master-restore.html')
                return
            }
            maasNodes = salt.getMinions(pepperEnv, 'I@maas:server')
            common.infoMsg('Performing restore')
            salt.enforceState(['saltId': pepperEnv, 'target': 'I@salt:master', 'state': 'salt.master.restore'])
            salt.enforceState(['saltId': pepperEnv, 'target': 'I@salt:master', 'state': 'salt.minion.restore'])
            salt.fullRefresh(pepperEnv, '*')

            common.infoMsg('Validating output')
            common.infoMsg('Salt-Keys')
            salt.cmdRun(pepperEnv, 'I@salt:master', "salt-key")
            common.infoMsg('Salt-master CA')
            salt.cmdRun(pepperEnv, 'I@salt:master', "ls -la /etc/pki/ca/salt_master_ca/")
        }
        if (!maasNodes.isEmpty()) {
            stage('MAAS Restore') {
                common.infoMsg('Verify pillar for MaaS backup')
                try {
                    def maaSPillar = salt.getPillar(pepperEnv, "I@maas:server", 'maas:region:database:initial_data')
                    if (maaSPillar['return'].isEmpty()) {
                        throw new Exception('Problem with MaaS pillar.')
                    }
                }
                catch (Exception e) {
                    common.errorMsg(e.getMessage())
                    common.errorMsg('Please fix your pillar. For more information check docs: https://docs.mirantis.com/mcp/latest/mcp-operations-guide/backup-restore/backupninja-postgresql/backupninja-postgresql-restore.html')
                    return
                }
                salt.enforceState(['saltId': pepperEnv, 'target': 'I@maas:region', 'state': 'maas.region'])
            }
        }
    }
}
