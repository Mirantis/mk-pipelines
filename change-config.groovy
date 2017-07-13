/**
 * Make change to the node(s) configuration
 *
 * Expected parameters:
 *   SALT_MASTER_CREDENTIALS    Credentials to the Salt API.
 *   SALT_MASTER_URL            Full Salt API address [https://10.10.10.1:8000].
 *   TARGET_SERVERS             Salt compound target to match nodes to be updated [*, G@osfamily:debian].
 *   TARGET_STATES              States to be applied, empty string means running highstate [linux, linux,openssh, salt.minion.grains].
 *   TARGET_SUBSET_TEST         Number of nodes to test config changes, empty string means all targetted nodes.
 *   TARGET_SUBSET_LIVE         Number of selected noded to live apply selected config changes.
 *   TARGET_BATCH_LIVE          Batch size for the complete live config changes on all nodes, empty string means apply to all targetted nodes.
 *
**/

def common = new com.mirantis.mk.Common()
def salt = new com.mirantis.mk.Salt()

def saltMaster
def targetTestSubset
def targetLiveSubset
def targetLiveAll
def minions
def result
def states

node() {
    try {

        if (TARGET_STATES != "") {
            states = TARGET_STATES
        }
        else {
            states = null
        }

        stage('Connect to Salt master') {
            saltMaster = salt.connection(SALT_MASTER_URL, SALT_MASTER_CREDENTIALS)
        }

        stage('List target servers') {
            minions = salt.getMinions(saltMaster, TARGET_SERVERS)
            if (minions.isEmpty()) {
                throw new Exception("No minion was targeted")
            }
            if (TARGET_SUBSET_TEST != "") {
                targetTestSubset = ['expression': minions.subList(0, Integer.valueOf(TARGET_SUBSET_TEST)).join(' or '), 'type': 'compound']
            }
            else {
                targetTestSubset = ['expression': minions.join(' or '), 'type': 'compound']
            }
            targetLiveSubset = ['expression': minions.subList(0, Integer.valueOf(TARGET_SUBSET_LIVE)).join(' or '), 'type': 'compound']
            targetLiveAll = ['expression': minions.join(' or '), 'type': 'compound']
            common.infoMsg("Found nodes: ${targetLiveAll.expression}")
            common.infoMsg("Selected test nodes: ${targetTestSubset.expression}")
            common.infoMsg("Selected sample nodes: ${targetLiveSubset.expression}")
        }

        stage('Test config changes') {
            def kwargs = [
                'test': true
            ]
            result = salt.runSaltCommand(saltMaster, 'local', targetTestSubset, 'state.apply', null, states, kwargs)
            salt.checkResult(result)
        }

        stage('Confirm live changes on sample') {
            timeout(time: 2, unit: 'HOURS') {
               input message: "Approve live config change on ${targetLiveSubset.expression} nodes?"
            }
        }

        stage('Apply config changes on sample') {
            result = salt.runSaltCommand(saltMaster, 'local', targetLiveSubset, 'state.apply', null, states)
            salt.checkResult(result)
        }

        stage('Confirm live changes on all nodes') {
            timeout(time: 2, unit: 'HOURS') {
               input message: "Approve live config change on ${targetLiveAll.expression} nodes?"
            }
        }

        stage('Apply config changes on all nodes') {
            result = salt.runSaltCommand(saltMaster, 'local', targetLiveAll, 'state.apply', null, states)
            salt.checkResult(result)
        }

    } catch (Throwable e) {
        currentBuild.result = 'FAILURE'
        throw e
    }
}
