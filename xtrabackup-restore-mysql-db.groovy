/**
 * Update packages on given nodes
 *
 * Expected parameters:
 *   SALT_MASTER_CREDENTIALS    Credentials to the Salt API.
 *   SALT_MASTER_URL            Full Salt API address [http://10.10.10.1:8000].
 *
**/

def common = new com.mirantis.mk.Common()
def salt = new com.mirantis.mk.Salt()
def python = new com.mirantis.mk.Python()

def pepperEnv = "pepperEnv"
timeout(time: 12, unit: 'HOURS') {
    node() {

        stage('Setup virtualenv for Pepper') {
            python.setupPepperVirtualenv(pepperEnv, SALT_MASTER_URL, SALT_MASTER_CREDENTIALS)
        }

        stage('Start restore') {
            // # actual upgrade

            stage('Ask for manual confirmation') {
                input message: "Are you sure you have the correct backups ready? Do you really want to continue to restore mysql db?"
            }
            // database restore section
            try {
                salt.runSaltProcessStep(pepperEnv, 'I@galera:slave', 'service.stop', ['mysql'], null, true)
            } catch (Exception er) {
                common.warningMsg('Mysql service already stopped')
            }
            try {
                salt.runSaltProcessStep(pepperEnv, 'I@galera:master', 'service.stop', ['mysql'], null, true)
            } catch (Exception er) {
                common.warningMsg('Mysql service already stopped')
            }
            try {
                salt.cmdRun(pepperEnv, 'I@galera:slave', "rm /var/lib/mysql/ib_logfile*")
            } catch (Exception er) {
                common.warningMsg('Files are not present')
            }
            try {
                salt.cmdRun(pepperEnv, 'I@galera:master', "mkdir -p /root/mysql/mysql.bak")
            } catch (Exception er) {
                common.warningMsg('Directory already exists')
            }
            try {
                salt.cmdRun(pepperEnv, 'I@galera:master', "mv /var/lib/mysql/* /root/mysql/mysql.bak")
            } catch (Exception er) {
                common.warningMsg('Files were already moved')
            }
            try {
                salt.cmdRun(pepperEnv, 'I@galera:master', "rm -rf /var/lib/mysql/*")
            } catch (Exception er) {
                common.warningMsg('Directory already empty')
            }
            try {
                salt.runSaltProcessStep(pepperEnv, 'I@galera:master', 'file.remove', ["/var/lib/mysql/.galera_bootstrap"], null, true)
            } catch (Exception er) {
                common.warningMsg('File is not present')
            }
            salt.cmdRun(pepperEnv, 'I@galera:master', "sed -i '/gcomm/c\\wsrep_cluster_address=\"gcomm://\"' /etc/mysql/my.cnf")
            _pillar = salt.getPillar(pepperEnv, "I@galera:master", 'xtrabackup:client:backup_dir')
            backup_dir = _pillar['return'][0].values()[0]
            if(backup_dir == null || backup_dir.isEmpty()) { backup_dir='/var/backups/mysql/xtrabackup' }
            print(backup_dir)
            salt.runSaltProcessStep(pepperEnv, 'I@galera:master', 'file.remove', ["${backup_dir}/dbrestored"], null, true)
            salt.cmdRun(pepperEnv, 'I@xtrabackup:client', "su root -c 'salt-call state.sls xtrabackup'")
            salt.runSaltProcessStep(pepperEnv, 'I@galera:master', 'service.start', ['mysql'], null, true)

            // wait until mysql service on galera master is up
            salt.commandStatus(pepperEnv, 'I@galera:master', 'service mysql status', 'running')

            salt.runSaltProcessStep(pepperEnv, 'I@galera:slave', 'service.start', ['mysql'], null, true)
            try {
                salt.commandStatus(pepperEnv, 'I@galera:slave', 'service mysql status', 'running')
            } catch (Exception er) {
                common.warningMsg('Either there are no galera slaves or something failed when starting mysql on galera slaves')
            }
            sleep(5)
            salt.cmdRun(pepperEnv, 'I@galera:master', "su root -c 'salt-call mysql.status | grep -A1 wsrep_cluster_size'")

            try {
                salt.runSaltProcessStep(pepperEnv, 'I@galera:master or I@galera:slave', 'file.touch', ["/var/lib/mysql/.galera_bootstrap"], null, true)
            } catch (Exception er) {
                common.warningMsg('File is already present')
            }
        }
    }
}
