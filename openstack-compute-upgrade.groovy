/**
 * Update packages on given nodes
 *
 * Expected parameters:
 *   SALT_MASTER_CREDENTIALS    Credentials to the Salt API.
 *   SALT_MASTER_URL            Full Salt API address [https://10.10.10.1:8000].
 *   TARGET_SERVERS             Salt compound target to match nodes to be updated [*, G@osfamily:debian].
 *   TARGET_SUBSET_TEST         Number of nodes to list package updates, empty string means all targetted nodes.
 *   TARGET_SUBSET_LIVE         Number of selected nodes to live apply selected package update.
 *   INTERACTIVE                Ask interactive questions during pipeline run (bool).
 *
**/

def common = new com.mirantis.mk.Common()
def salt = new com.mirantis.mk.Salt()
def python = new com.mirantis.mk.Python()

def pepperEnv = "pepperEnv"
def targetTestSubset
def targetLiveSubset
def targetLiveAll
def minions
def result
def args
def command
def commandKwargs
def probe = 1

timeout(time: 12, unit: 'HOURS') {
    node() {
        try {

            stage('Setup virtualenv for Pepper') {
                python.setupPepperVirtualenv(pepperEnv, SALT_MASTER_URL, SALT_MASTER_CREDENTIALS)
            }

            stage('List target servers') {
                minions = salt.getMinions(pepperEnv, TARGET_SERVERS)

                if (minions.isEmpty()) {
                    throw new Exception("No minion was targeted")
                }

                if (TARGET_SUBSET_TEST != "") {
                    targetTestSubset = minions.subList(0, Integer.valueOf(TARGET_SUBSET_TEST)).join(' or ')
                } else {
                    targetTestSubset = minions.join(' or ')
                }
                targetLiveSubset = minions.subList(0, Integer.valueOf(TARGET_SUBSET_LIVE)).join(' or ')
                targetTestSubsetProbe = minions.subList(0, probe).join(' or ')
                targetLiveSubsetProbe = minions.subList(0, probe).join(' or ')

                targetLiveAll = minions.join(' or ')
                common.infoMsg("Found nodes: ${targetLiveAll}")
                common.infoMsg("Selected test nodes: ${targetTestSubset}")
                common.infoMsg("Selected sample nodes: ${targetLiveSubset}")
            }


            stage("Add new repos on test nodes") {
                salt.enforceState(pepperEnv, targetTestSubset, 'linux.system.repo')
            }


            opencontrail = null

            try {
                opencontrail = salt.cmdRun(pepperEnv, targetTestSubsetProbe, "salt-call grains.item roles | grep opencontrail.compute")
                print(opencontrail)
            } catch (Exception er) {
                common.infoMsg("opencontrail is not used")
            }

            if(opencontrail != null) {
                stage('Remove OC component from repos on test nodes') {
                    def contrail_repo_file1 = ''
                    def contrail_repo_file2 = ''
                    try {
                        contrail_repo_file1 = salt.cmdRun(pepperEnv, targetTestSubset, "grep -Eorl \\ oc\\([0-9]*\$\\) /etc/apt/sources.list*")['return'][0].values()[0].split("\n")[0]
                        contrail_repo_file2 = salt.cmdRun(pepperEnv, targetTestSubset, "grep -Eorl \\ oc\\([0-9]*\\ \\) /etc/apt/sources.list*")['return'][0].values()[0].split("\n")[0]
                    } catch (Exception er) {
                        common.warningMsg(er)
                    }
                    salt.cmdRun(pepperEnv, targetTestSubset, "find /etc/apt/sources.list* -type f -print0 | xargs -0 sed -i -r -e 's/ oc([0-9]*) / /g;s/ oc([0-9]*\$)//g'")
                    try {
                        salt.cmdRun(pepperEnv, targetTestSubset, "salt-call pkg.refresh_db")
                    } catch (Exception er) {
                        common.warningMsg(er)
                        // remove the malformed repo entry
                        salt.cmdRun(pepperEnv, targetTestSubset, "rm ${contrail_repo_file1} ${contrail_repo_file2}")
                        salt.runSaltProcessStep(pepperEnv, targetTestSubset, 'pkg.refresh_db', [], null, true)
                    }
                }
            }

            stage("List package upgrades") {
                salt.runSaltProcessStep(pepperEnv, targetTestSubset, 'pkg.list_upgrades', [], null, true)
            }

            if (INTERACTIVE.toBoolean()){
              stage('Confirm upgrade on sample nodes') {
                input message: "Please verify the list of packages that you want to be upgraded. Do you want to continue with upgrade?"
              }
            }

            stage("Add new repos on sample nodes") {
                salt.enforceState(pepperEnv, targetLiveSubset, 'linux.system.repo')
            }

            if(opencontrail != null) {
                stage('Remove OC component from repos on sample nodes') {
                    def contrail_repo_file1 = ''
                    def contrail_repo_file2 = ''
                    try {
                        contrail_repo_file1 = salt.cmdRun(pepperEnv, targetLiveSubset, "grep -Eorl \\ oc\\([0-9]*\$\\) /etc/apt/sources.list*")['return'][0].values()[0].split("\n")[0]
                        contrail_repo_file2 = salt.cmdRun(pepperEnv, targetLiveSubset, "grep -Eorl \\ oc\\([0-9]*\\ \\) /etc/apt/sources.list*")['return'][0].values()[0].split("\n")[0]
                    } catch (Exception er) {
                        common.warningMsg(er)
                    }
                    salt.cmdRun(pepperEnv, targetLiveSubset, "find /etc/apt/sources.list* -type f -print0 | xargs -0 sed -i -r -e 's/ oc([0-9]*) / /g;s/ oc([0-9]*\$)//g'")
                    try {
                        salt.cmdRun(pepperEnv, targetLiveSubset, "salt-call pkg.refresh_db")
                    } catch (Exception er) {
                        common.warningMsg(er)
                        // remove the malformed repo entry
                        salt.cmdRun(pepperEnv, targetLiveSubset, "rm ${contrail_repo_file1} ${contrail_repo_file2}")
                        salt.runSaltProcessStep(pepperEnv, targetLiveSubset, 'pkg.refresh_db', [], null, true)
                    }
                }
            }

            args = "apt-get -y -s -o Dpkg::Options::=\"--force-confdef\" -o Dpkg::Options::=\"--force-confold\" dist-upgrade"

            stage('Test upgrade on sample') {
                try {
                    salt.cmdRun(pepperEnv, targetLiveSubset, args)
                } catch (Exception er) {
                    print(er)
                }
            }

            if (INTERACTIVE.toBoolean()){
              stage('Confirm upgrade on sample') {
                input message: "Please verify if there are packages that it wants to downgrade. If so, execute apt-cache policy on them and verify if everything is fine. Do you want to continue with upgrade?"
              }
            }

            command = "cmd.run"
            args = 'export DEBIAN_FRONTEND=noninteractive; apt-get -y -q --allow-downgrades -o Dpkg::Options::=\"--force-confdef\" -o Dpkg::Options::=\"--force-confold\" dist-upgrade;'

            stage('Apply package upgrades on sample') {
                out = salt.runSaltCommand(pepperEnv, 'local', ['expression': targetLiveSubset, 'type': 'compound'], command, null, args, commandKwargs)
                salt.printSaltCommandResult(out)
            }

            openvswitch = null

            try {
                openvswitch = salt.cmdRun(pepperEnv, targetLiveSubsetProbe, "salt-call grains.item roles | grep neutron.compute")
            } catch (Exception er) {
                common.infoMsg("openvswitch is not used")
            }

            if(openvswitch != null) {
                args = "sudo /usr/share/openvswitch/scripts/ovs-ctl start"

                stage('Start ovs on sample nodes') {
                    out = salt.runSaltCommand(pepperEnv, 'local', ['expression': targetLiveSubset, 'type': 'compound'], command, null, args, commandKwargs)
                    salt.printSaltCommandResult(out)
                }
                stage("Run salt states on sample nodes") {
                    salt.enforceState(pepperEnv, targetLiveSubset, ['nova', 'neutron'])
                }
            } else {
                stage("Run salt states on sample nodes") {
                    salt.enforceState(pepperEnv, targetLiveSubset, ['nova', 'linux.system.repo'])
                }
            }

            stage("Run Highstate on sample nodes") {
                try {
                    salt.enforceHighstate(pepperEnv, targetLiveSubset)
                } catch (Exception er) {
                    common.errorMsg("Highstate was executed on ${targetLiveSubset} but something failed. Please check it and fix it accordingly.")
                }
            }

            if (INTERACTIVE.toBoolean()){
              stage('Confirm upgrade on all targeted nodes') {
                timeout(time: 2, unit: 'HOURS') {
                   input message: "Verify that the upgraded sample nodes are working correctly. If so, do you want to approve live upgrade on ${targetLiveAll} nodes?"
                }
              }
            }

            stage("Add new repos on all targeted nodes") {
                salt.enforceState(pepperEnv, targetLiveAll, 'linux.system.repo')
            }

            if(opencontrail != null) { 
                stage('Remove OC component from repos on all targeted nodes') {
                    def contrail_repo_file1 = ''
                    def contrail_repo_file2 = ''
                    try {
                        contrail_repo_file1 = salt.cmdRun(pepperEnv, targetLiveAll, "grep -Eorl \\ oc\\([0-9]*\$\\) /etc/apt/sources.list*")['return'][0].values()[0].split("\n")[0]
                        contrail_repo_file2 = salt.cmdRun(pepperEnv, targetLiveAll, "grep -Eorl \\ oc\\([0-9]*\\ \\) /etc/apt/sources.list*")['return'][0].values()[0].split("\n")[0]
                    } catch (Exception er) {
                        common.warningMsg(er)
                    }
                    salt.cmdRun(pepperEnv, targetLiveAll, "find /etc/apt/sources.list* -type f -print0 | xargs -0 sed -i -r -e 's/ oc([0-9]*) / /g;s/ oc([0-9]*\$)//g'")
                    try {
                        salt.cmdRun(pepperEnv, targetLiveAll, "salt-call pkg.refresh_db")
                    } catch (Exception er) {
                        common.warningMsg(er)
                        // remove the malformed repo entry
                        salt.cmdRun(pepperEnv, targetLiveAll, "rm ${contrail_repo_file1} ${contrail_repo_file2}")
                        salt.runSaltProcessStep(pepperEnv, targetLiveAll, 'pkg.refresh_db', [], null, true)
                    }
                }
            }

            args = 'export DEBIAN_FRONTEND=noninteractive; apt-get -y -q --allow-downgrades -o Dpkg::Options::=\"--force-confdef\" -o Dpkg::Options::=\"--force-confold\" dist-upgrade;'

            stage('Apply package upgrades on all targeted nodes') {
                out = salt.runSaltCommand(pepperEnv, 'local', ['expression': targetLiveAll, 'type': 'compound'], command, null, args, commandKwargs)
                salt.printSaltCommandResult(out)
            }

            if(openvswitch != null) {
                args = "sudo /usr/share/openvswitch/scripts/ovs-ctl start"

                stage('Start ovs on all targeted nodes') {
                    out = salt.runSaltCommand(pepperEnv, 'local', ['expression': targetLiveAll, 'type': 'compound'], command, null, args, commandKwargs)
                    salt.printSaltCommandResult(out)
                }
                stage("Run salt states on all targeted nodes") {
                    salt.enforceState(pepperEnv, targetLiveAll, ['nova', 'neutron'])
                }
            } else {
                stage("Run salt states on all targeted nodes") {
                    salt.enforceState(pepperEnv, targetLiveAll, ['nova', 'linux.system.repo'])
                }
            }

            stage("Run Highstate on all targeted nodes") {
                try {
                    salt.enforceHighstate(pepperEnv, targetLiveAll)
                } catch (Exception er) {
                    common.errorMsg("Highstate was executed ${targetLiveAll} but something failed. Please check it and fix it accordingly.")
                }
            }

        } catch (Throwable e) {
            // If there was an error or exception thrown, the build failed
            currentBuild.result = "FAILURE"
            currentBuild.description = currentBuild.description ? e.message + " " + currentBuild.description : e.message
            throw e
        }
    }
}

