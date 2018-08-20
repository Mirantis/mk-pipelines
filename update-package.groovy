/**
 * Update packages on given nodes
 *
 * Expected parameters:
 *   SALT_MASTER_CREDENTIALS    Credentials to the Salt API.
 *   SALT_MASTER_URL            Full Salt API address [https://10.10.10.1:8000].
 *   TARGET_SERVERS             Salt compound target to match nodes to be updated [*, G@osfamily:debian].
 *   TARGET_PACKAGES            Space delimited list of packages to be updates [package1=version package2=version], empty string means all updating all packages to the latest version.
 *   TARGET_SUBSET_TEST         Number of nodes to list package updates, empty string means all targetted nodes.
 *   TARGET_SUBSET_LIVE         Number of selected nodes to live apply selected package update.
 *   TARGET_BATCH_LIVE          Batch size for the complete live package update on all nodes, empty string means apply to all targetted nodes.
 *
**/
pepperEnv = "pepperEnv"
salt = new com.mirantis.mk.Salt()
def common = new com.mirantis.mk.Common()
def python = new com.mirantis.mk.Python()
def targetTestSubset
def targetLiveSubset
def targetLiveAll
def minions
def result
def packages
def command
def commandKwargs
def installSaltStack(target, pkgs){
    salt.runSaltProcessStep(pepperEnv, target, 'pkg.install', ["force_yes=True", "pkgs='$pkgs'"], null, true, 30)
}

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

                targetLiveAll = minions.join(' or ')
                common.infoMsg("Found nodes: ${targetLiveAll}")
                common.infoMsg("Selected test nodes: ${targetTestSubset}")
                common.infoMsg("Selected sample nodes: ${targetLiveSubset}")
            }

            stage("List package upgrades") {
                common.infoMsg("Listing all the packages that have a new update available on test nodes: ${targetTestSubset}")
                salt.runSaltProcessStep(pepperEnv, targetTestSubset, 'pkg.list_upgrades', [], null, true)
                if(TARGET_PACKAGES != "" && TARGET_PACKAGES != "*"){
                    common.infoMsg("Note that only the ${TARGET_PACKAGES} would be installed from the above list of available updates on the ${targetTestSubset}")
                }
            }

            stage('Confirm live package upgrades on sample') {
                if(TARGET_PACKAGES==""){
                    timeout(time: 2, unit: 'HOURS') {
                        def userInput = input(
                         id: 'userInput', message: 'Insert package names for update', parameters: [
                         [$class: 'TextParameterDefinition', defaultValue: '', description: 'Package names (or *)', name: 'packages']
                        ])
                        if(userInput!= "" && userInput!= "*"){
                            TARGET_PACKAGES = userInput
                        }
                    }
                }else{
                    timeout(time: 2, unit: 'HOURS') {
                       input message: "Approve live package upgrades on ${targetLiveSubset} nodes?"
                    }
                }
            }

            if (TARGET_PACKAGES != "") {
                command = "pkg.install"
                packages = TARGET_PACKAGES.tokenize(' ')
                commandKwargs = ['only_upgrade': 'true']
            }else {
                command = "pkg.upgrade"
                packages = null
            }

            stage('Apply package upgrades on sample') {
                if(packages == null || packages.contains("salt-master") || packages.contains("salt-common") || packages.contains("salt-minion") || packages.contains("salt-api")){
                    def saltTargets = (targetLiveSubset.split(' or ').collect{it as String})
                    for(int i = 0; i < saltTargets.size(); i++ ){
                        common.infoMsg("During salt-minion upgrade on cfg node, pipeline lose connectivy to salt-master for 2 min. If pipeline ended with error rerun pipeline again.")
                        common.retry(10, 5) {
                            if(salt.minionsReachable(pepperEnv, 'I@salt:master', "I@salt:master and ${saltTargets[i]}")){
                                installSaltStack("I@salt:master and ${saltTargets[i]}", '["salt-master", "salt-common", "salt-api", "salt-minion"]')
                            }
                            if(salt.minionsReachable(pepperEnv, 'I@salt:master', "I@salt:minion and not I@salt:master and ${saltTargets[i]}")){
                                installSaltStack("I@salt:minion and not I@salt:master and ${saltTargets[i]}", '["salt-minion"]')
                            }
                        }
                    }
                }
                out = salt.runSaltCommand(pepperEnv, 'local', ['expression': targetLiveSubset, 'type': 'compound'], command, null, packages, commandKwargs)
                salt.printSaltCommandResult(out)
                for(value in out.get("return")[0].values()){
                    if (value.containsKey('result') && value.result == false) {
                        throw new Exception("The package upgrade on sample node has failed. Please check the Salt run result above for more information.")
                    }
                }
            }

            stage('Confirm package upgrades on all nodes') {
                timeout(time: 2, unit: 'HOURS') {
                   input message: "Approve live package upgrades on ${targetLiveAll} nodes?"
                }
            }

            stage('Apply package upgrades on all nodes') {

                if(packages == null || packages.contains("salt-master") || packages.contains("salt-common") || packages.contains("salt-minion") || packages.contains("salt-api")){
                    def saltTargets = (targetLiveAll.split(' or ').collect{it as String})
                    for(int i = 0; i < saltTargets.size(); i++ ){
                        common.infoMsg("During salt-minion upgrade on cfg node, pipeline lose connectivy to salt-master for 2 min. If pipeline ended with error rerun pipeline again.")
                        common.retry(10, 5) {
                            if(salt.minionsReachable(pepperEnv, 'I@salt:master', "I@salt:master and ${saltTargets[i]}")){
                                installSaltStack("I@salt:master and ${saltTargets[i]}", '["salt-master", "salt-common", "salt-api", "salt-minion"]')
                            }
                            if(salt.minionsReachable(pepperEnv, 'I@salt:master', "I@salt:minion and not I@salt:master and ${saltTargets[i]}")){
                                installSaltStack("I@salt:minion and not I@salt:master and ${saltTargets[i]}", '["salt-minion"]')
                            }
                        }
                    }
                }

                out = salt.runSaltCommand(pepperEnv, 'local', ['expression': targetLiveAll, 'type': 'compound'], command, null, packages, commandKwargs)
                salt.printSaltCommandResult(out)
                for(value in out.get("return")[0].values()){
                    if (value.containsKey('result') && value.result == false) {
                        throw new Exception("The package upgrade on sample node has failed. Please check the Salt run result above for more information.")
                    }
                }
                common.warningMsg("Pipeline has finished successfully, but please, check if any packages have been kept back.")
            }

        } catch (Throwable e) {
            // If there was an error or exception thrown, the build failed
            currentBuild.result = "FAILURE"
            currentBuild.description = currentBuild.description ? e.message + " " + currentBuild.description : e.message
            throw e
        }
    }
}
