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

def saltMaster
def target = ['expression': TARGET_SERVERS, 'type': 'compound']
def result

node("python") {
    try {

        stage('Connect to Salt master') {
            saltMaster = salt.connection(SALT_MASTER_URL, SALT_MASTER_CREDENTIALS)
        }

        stage('Update Salt formulas') {
            result = salt.runSaltCommand(saltMaster, 'local', target, 'state.apply', null, 'salt.master.env')
            salt.checkResult(result)
        }

    } catch (Throwable e) {
        currentBuild.result = 'FAILURE'
        throw e
    }
}
