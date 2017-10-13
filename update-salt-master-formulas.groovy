/**
 * Update formulas on salt master
 *
 * Expected parameters:
 *   SALT_MASTER_CREDENTIALS    Credentials to the Salt API.
 *   SALT_MASTER_URL            Full Salt API address [https://10.10.10.1:8000].
 *   TARGET_SERVERS             Server to update
 *
**/

def common = new com.mirantis.mk.Common()
def salt = new com.mirantis.mk.Salt()
def python = new com.mirantis.mk.Python()

def pepperEnv = "pepperEnv"
def target = ['expression': TARGET_SERVERS, 'type': 'compound']
def result

node("python") {
    try {

        stage('Setup virtualenv for Pepper') {
            python.setupPepperVirtualenv(pepperEnv, SALT_MASTER_URL, SALT_MASTER_CREDENTIALS)
        }

        stage('Update Salt formulas') {
            result = salt.runSaltCommand(pepperEnv, 'local', target, 'state.apply', null, 'salt.master.env')
            salt.checkResult(result)
        }

    } catch (Throwable e) {
        currentBuild.result = 'FAILURE'
        throw e
    }
}
