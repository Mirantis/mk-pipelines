/**
 *
 * Cleanup OpenStack databases from stale records (archived records or records marked as deleted).
 * Cleanup OpenStack service databases.
 *
 * Expected parameters:
 *   SALT_MASTER_CREDENTIALS            Credentials to the Salt API.
 *   SALT_MASTER_URL                    Full Salt API address [http://10.10.10.15:6969].
 *
**/

def common = new com.mirantis.mk.Common()
def salt = new com.mirantis.mk.Salt()
def python = new com.mirantis.mk.Python()

def os_services = [ 'nova:controller', 'heat:server', 'cinder:controller', 'glance:server' ]

def slave_node = 'python'

if (common.validInputParam('SLAVE_NODE')) {
    slave_node = SLAVE_NODE
}

def env = "pepperEnv"
timeout(time: 12, unit: 'HOURS') {

    node(slave_node) {

        stage('Setup virtualenv for Pepper') {
            python.setupPepperVirtualenv(env, SALT_MASTER_URL, SALT_MASTER_CREDENTIALS)
        }

        stage('Databases cleanup') {

            for (os_service in os_services) {

                formula = os_service.split(":")[0]
                os_state = "${formula}.db.db_cleanup"
                os_file = "/usr/share/salt-formulas/env/${formula}/db/db_cleanup.sls"

                if (salt.runSaltProcessStep(env, 'I@salt:master', 'file.file_exists', [os_file], null, true, 5)['return'][0].values()[0].toBoolean()) {
                    salt.enforceStateWithTest([saltId: env, target: "I@${os_service}:role:primary", state: [os_state]])
                }

            }
        }
    }
}
