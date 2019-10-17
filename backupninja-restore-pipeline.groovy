def common = new com.mirantis.mk.Common()
def salt = new com.mirantis.mk.Salt()
def python = new com.mirantis.mk.Python()
def pepperEnv = "pepperEnv"
def maasNodes = []
def restoreSaltMasterAndMaas = (env.getProperty('RESTORE_SALTMASTER_AND_MAAS') ?: true).toBoolean()
def restoreDogtag = (env.getProperty('RESTORE_DOGTAG') ?: true).toBoolean()

timeout(time: 12, unit: 'HOURS') {
    node() {
        stage('Setup virtualenv for Pepper') {
            python.setupPepperVirtualenv(pepperEnv, SALT_MASTER_URL, SALT_MASTER_CREDENTIALS)
        }
        stage('Verify pillar for restore') {
            if (restoreSaltMasterAndMaas) {
                try {
                    def masterPillar = salt.getPillar(pepperEnv, "I@salt:master", 'salt:master:initial_data')
                    if(masterPillar['return'].isEmpty()) {
                        throw new Exception("Problem with salt-master pillar on 'I@salt:master' node.")
                    }
                    def minionPillar = salt.getPillar(pepperEnv, "I@salt:master", 'salt:minion:initial_data')
                    if(minionPillar['return'].isEmpty()) {
                        throw new Exception("Problem with salt-minion pillar on 'I@salt:master' node.")
                    }
                }
                catch (Exception e){
                    common.errorMsg(e.getMessage())
                    common.errorMsg('Please fix your pillar. For more information check docs: https://docs.mirantis.com/mcp/latest/mcp-operations-guide/backup-restore/salt-master/salt-master-restore.html')
                    return
                }
                maasNodes = salt.getMinions(pepperEnv, 'I@maas:region')
            }
            if (!maasNodes.isEmpty()) {
                try {
                    def maaSPillar = salt.getPillar(pepperEnv, "I@maas:region", 'maas:region:database:initial_data')
                    if (maaSPillar['return'].isEmpty()) {
                        throw new Exception("Problem with MaaS pillar on 'I@maas:region' node.")
                    }
                }
                catch (Exception e) {
                    common.errorMsg(e.getMessage())
                    common.errorMsg('Please fix your pillar. For more information check docs: https://docs.mirantis.com/mcp/latest/mcp-operations-guide/backup-restore/maas-postgresql/backupninja-postgresql-restore.html')
                    return
                }
            } else {
                common.warningMsg("No MaaS Pillar was found. You can ignore this if it's expected. Otherwise you should fix you pillar. Check: https://docs.mirantis.com/mcp/latest/mcp-operations-guide/backup-restore/maas-postgresql/backupninja-postgresql-restore.html")
            }
            if (restoreDogtag) {
                try {
                    def dogtagPillar = salt.getPillar(pepperEnv, "I@dogtag:server:role:master", 'dogtag:server:initial_data')
                    if (dogtagPillar['return'].isEmpty()) {
                        throw new Exception("Problem with Dogtag pillar on 'I@dogtag:server:role:master' node.")
                    }
                }
                catch (Exception e) {
                    common.errorMsg(e.getMessage())
                    common.errorMsg('Please fix your pillar. For more information check docs: https://docs.mirantis.com/mcp/latest/mcp-operations-guide/backup-restore/dogtag/restore-dogtag.html')
                    return
                }
            }
        }
        stage('Restore') {
            if (restoreSaltMasterAndMaas) {
                common.infoMsg('Starting salt-master restore')
                salt.enforceState(['saltId': pepperEnv, 'target': 'I@salt:master', 'state': 'salt.master.restore'])
                salt.enforceState(['saltId': pepperEnv, 'target': 'I@salt:master', 'state': 'salt.minion.restore'])
                salt.fullRefresh(pepperEnv, '*')
                common.infoMsg('Validating output')
                common.infoMsg('Salt-Keys')
                salt.cmdRun(pepperEnv, 'I@salt:master', "salt-key")
                common.infoMsg('Salt-master CA')
                salt.cmdRun(pepperEnv, 'I@salt:master', "ls -la /etc/pki/ca/salt_master_ca/")
                if (!maasNodes.isEmpty()) {
                    common.infoMsg('Starting MaaS restore')
                    salt.enforceState(['saltId': pepperEnv, 'target': 'I@maas:region', 'state': 'maas.region'])
                }
            }
            if (restoreDogtag) {
                salt.runSaltProcessStep(pepperEnv, 'I@dogtag:server:role:slave', 'service.stop', ['dirsrv@pki-tomcat.service'])
                salt.enforceState(['saltId': pepperEnv, 'target': 'I@dogtag:server:role:master', 'state': 'dogtag.server.restore'])
                salt.runSaltProcessStep(pepperEnv, 'I@dogtag:server:role:slave', 'service.start', ['dirsrv@pki-tomcat.service'])
            }
        }
        stage('After restore steps') {
            if (restoreSaltMasterAndMaas) {
                common.infoMsg("No more steps for Salt Master and MaaS restore are required.")
            }
            if (restoreDogtag) {
                salt.enforceState(['saltId': pepperEnv, 'target': 'I@salt:master', 'state': ['salt', 'reclass']])
                salt.enforceState(['saltId': pepperEnv, 'target': 'I@dogtag:server:role:master', 'state': 'dogtag.server'])
                salt.enforceState(['saltId': pepperEnv, 'target': 'I@dogtag:server', 'state': 'dogtag.server'])
                salt.enforceState(['saltId': pepperEnv, 'target': 'I@haproxy:proxy', 'state': 'haproxy'])
                salt.enforceState(['saltId': pepperEnv, 'target': 'I@barbican:server:role:primary', 'state': 'barbican.server'])
                salt.enforceState(['saltId': pepperEnv, 'target': 'I@barbican:server', 'state': 'barbican.server'])
                salt.cmdRun(pepperEnv, 'I@barbican:server', 'rm /etc/barbican/alias/*')
                salt.runSaltProcessStep(pepperEnv, 'I@barbican:server', 'service.restart', 'apache2')
            }
        }
    }
}
