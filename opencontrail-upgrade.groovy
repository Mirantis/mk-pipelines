/**
 * Update packages on given nodes
 *
 * Expected parameters:
 *   SALT_MASTER_CREDENTIALS        Credentials to the Salt API.
 *   SALT_MASTER_URL                Full Salt API address [http://10.10.10.1:8000].
 *   STAGE_CONTROLLERS_UPGRADE      Run upgrade on Opencontrail controllers  (bool)
 *   STAGE_ANALYTICS_UPGRADE        Run upgrade on Opencontrail analytics  (bool)
 *   STAGE_COMPUTES_UPGRADE         Run upgrade on Opencontrail compute nodes  (bool)
 *   COMPUTE_TARGET_SERVERS         Salt compound target to match nodes to be updated [*, G@osfamily:debian].
 *   COMPUTE_TARGET_SUBSET_LIVE     Number of selected nodes to live apply selected package update.
 *   STAGE_CONTROLLERS_ROLLBACK     Run rollback on Opencontrail controllers  (bool)
 *   STAGE_ANALYTICS_ROLLBACK       Run rollback on Opencontrail analytics  (bool)
 *   STAGE_COMPUTES_ROLLBACK        Run rollback on Opencontrail compute nodes  (bool)
 *
**/

def common = new com.mirantis.mk.Common()
def salt = new com.mirantis.mk.Salt()

def saltMaster
def targetLiveSubset
def targetLiveAll
def minions
def result
def args
def commandKwargs
def probe = 1
def errorOccured = false
def command = 'cmd.shell'

def CONTROL_PKGS = 'contrail-config contrail-config-openstack contrail-control contrail-dns contrail-lib contrail-nodemgr contrail-utils contrail-web-controller contrail-web-core neutron-plugin-contrail python-contrail'
def ANALYTIC_PKGS = 'contrail-analytics contrail-lib contrail-nodemgr contrail-utils python-contrail'
def CMP_PKGS = 'contrail-lib contrail-nodemgr contrail-utils contrail-vrouter-agent contrail-vrouter-utils python-contrail python-contrail-vrouter-api python-opencontrail-vrouter-netns contrail-vrouter-dkms'
def KERNEL_MODULE_RELOAD = 'service supervisor-vrouter stop;ifdown vhost0;rmmod vrouter;modprobe vrouter;ifup vhost0;service supervisor-vrouter start;'

def void runCommonCommands(target, command, args, check, salt, saltMaster, common) {

    out = salt.runSaltCommand(saltMaster, 'local', ['expression': target, 'type': 'compound'], command, null, args, null)
    salt.printSaltCommandResult(out)
    // wait until $check is in correct state
    if ( check == "nodetool status" ) {
        salt.commandStatus(saltMaster, target, check, 'Status=Up')  
    } else if ( check == "contrail-status" ) {
        salt.commandStatus(saltMaster, target, "${check} | grep -v == | grep -v \'disabled on boot\' | grep -v nodemgr | grep -v active | grep -v backup", null, false)  
    }

    //out = salt.runSaltCommand(saltMaster, 'local', ['expression': target, 'type': 'compound'], command, null, check, null)
    //salt.printSaltCommandResult(out)
    //input message: "Please check the output of \'${check}\' and continue if it is correct."
}

timestamps {
    node() {

        stage('Connect to Salt API') {
            saltMaster = salt.connection(SALT_MASTER_URL, SALT_MASTER_CREDENTIALS)
        }

        if (STAGE_CONTROLLERS_UPGRADE.toBoolean() == true && !errorOccured) {

            stage('Opencontrail controllers upgrade') {

                oc_component_repo = salt.runSaltProcessStep(saltMaster, 'I@opencontrail:control and *01*', 'cmd.shell', ['grep -RE \'oc[0-9]{2,3}\' /etc/apt/sources.list* | awk \'{print $1}\' | sed \'s/ *:.*//\''], null, true)

                oc_component_repo = oc_component_repo['return'][0].values()[0]

                try {
                    salt.runSaltProcessStep(saltMaster, 'I@opencontrail:control', 'cmd.shell', ["rm ${oc_component_repo}"], null, true)
                    salt.runSaltProcessStep(saltMaster, 'I@opencontrail:control', 'saltutil.refresh_pillar', [], null, true)
                    salt.enforceState(saltMaster, 'I@opencontrail:control', 'linux.system.repo')
                } catch (Exception er) {
                    errorOccured = true
                    common.errorMsg("Opencontrail component on I@opencontrail:control probably failed to be replaced. Please check it in ${oc_component_repo} before continuing.")
                    return
                }

                try {
                    salt.cmdRun(saltMaster, 'I@opencontrail:control', "su root -c '/usr/local/bin/zookeeper-backup-runner.sh'")
                } catch (Exception er) {
                    common.errorMsg('Zookeeper failed to backup. Please fix it before continuing.')
                    return
                }

                try {
                    salt.cmdRun(saltMaster, 'I@cassandra:backup:client', "su root -c '/usr/local/bin/cassandra-backup-runner-call.sh'")
                } catch (Exception er) {
                    common.errorMsg('Cassandra failed to backup. Please fix it before continuing.')
                    return
                }

                args = 'apt install contrail-database -y;'
                check = 'nodetool status'

                // ntw01
                runCommonCommands('I@opencontrail:control and *01*', command, args, check, salt, saltMaster, common)
                // ntw02
                runCommonCommands('I@opencontrail:control and *02*', command, args, check, salt, saltMaster, common)
                // ntw03
                runCommonCommands('I@opencontrail:control and *03*', command, args, check, salt, saltMaster, common)

                args = "apt install -o Dpkg::Options::=\"--force-confold\" ${CONTROL_PKGS} -y --force-yes;"
                check = 'contrail-status'

                // ntw01
                runCommonCommands('I@opencontrail:control and *01*', command, args, check, salt, saltMaster, common)
                // ntw02
                runCommonCommands('I@opencontrail:control and *02*', command, args, check, salt, saltMaster, common)
                // ntw03
                runCommonCommands('I@opencontrail:control and *03*', command, args, check, salt, saltMaster, common)

                try {
                    salt.enforceState(saltMaster, 'I@opencontrail:control', 'opencontrail')
                } catch (Exception er) {
                    common.errorMsg('Opencontrail state was executed on I@opencontrail:control and failed please fix it manually.')
                }

                out = salt.runSaltCommand(saltMaster, 'local', ['expression': 'I@opencontrail:control', 'type': 'compound'], command, null, check, null)
                salt.printSaltCommandResult(out)

                common.warningMsg('Please check \'show bgp summary\' on your bgp router if all bgp peers are in healthy state.')
            }
        }

        if (STAGE_ANALYTICS_UPGRADE.toBoolean() == true && !errorOccured) {

            stage('Ask for manual confirmation') {
                input message: "Do you want to continue with the Opencontrail analytic nodes upgrade?"
            }

            stage('Opencontrail analytics upgrade') {

                oc_component_repo = salt.runSaltProcessStep(saltMaster, 'I@opencontrail:collector and *01*', 'cmd.shell', ['grep -RE \'oc[0-9]{2,3}\' /etc/apt/sources.list* | awk \'{print $1}\' | sed \'s/ *:.*//\''], null, true)

                oc_component_repo = oc_component_repo['return'][0].values()[0]

                try {
                    salt.runSaltProcessStep(saltMaster, 'I@opencontrail:collector', 'cmd.shell', ["rm ${oc_component_repo}"], null, true)
                    salt.runSaltProcessStep(saltMaster, 'I@opencontrail:collector', 'saltutil.refresh_pillar', [], null, true)
                    salt.enforceState(saltMaster, 'I@opencontrail:collector', 'linux.system.repo')
                } catch (Exception er) {
                    errorOccured = true
                    common.errorMsg("Opencontrail component on I@opencontrail:collector probably failed to be replaced. Please check it in ${oc_component_repo} before continuing.")
                    return
                }

                args = 'apt install contrail-database -y;'
                check = 'nodetool status'

                // nal01
                runCommonCommands('I@opencontrail:collector and *01*', command, args, check, salt, saltMaster, common)
                // nal02
                runCommonCommands('I@opencontrail:collector and *02*', command, args, check, salt, saltMaster, common)
                // nal03
                runCommonCommands('I@opencontrail:collector and *03*', command, args, check, salt, saltMaster, common)

                args = "apt install -o Dpkg::Options::=\"--force-confold\" ${ANALYTIC_PKGS} -y --force-yes;"
                check = 'contrail-status'

                // nal01
                runCommonCommands('I@opencontrail:collector and *01*', command, args, check, salt, saltMaster, common)
                // nal02
                runCommonCommands('I@opencontrail:collector and *02*', command, args, check, salt, saltMaster, common)
                // nal03
                runCommonCommands('I@opencontrail:collector and *03*', command, args, check, salt, saltMaster, common)

                try {
                    salt.enforceState(saltMaster, 'I@opencontrail:collector', 'opencontrail')
                } catch (Exception er) {
                    common.errorMsg('Opencontrail state was executed on I@opencontrail:collector and failed please fix it manually.')
                }

                out = salt.runSaltCommand(saltMaster, 'local', ['expression': 'I@opencontrail:collector', 'type': 'compound'], command, null, check, null)
                salt.printSaltCommandResult(out)
            }
        }

        if (STAGE_COMPUTES_UPGRADE.toBoolean() == true && !errorOccured) {

            try {

                stage('List targeted compute servers') {
                    minions = salt.getMinions(saltMaster, COMPUTE_TARGET_SERVERS)

                    if (minions.isEmpty()) {
                        throw new Exception("No minion was targeted")
                    }

                    targetLiveSubset = minions.subList(0, Integer.valueOf(COMPUTE_TARGET_SUBSET_LIVE)).join(' or ')
                    targetLiveSubsetProbe = minions.subList(0, probe).join(' or ')

                    targetLiveAll = minions.join(' or ')
                    common.infoMsg("Found nodes: ${targetLiveAll}")
                    common.infoMsg("Selected sample nodes: ${targetLiveSubset}")
                }

                stage('Confirm upgrade on sample nodes') {
                    input message: "Do you want to continue with the Opencontrail compute upgrade on the following sample nodes? ${targetLiveSubset}"
                }

                stage("Opencontrail compute upgrade on sample nodes") {

                    oc_component_repo = salt.runSaltProcessStep(saltMaster, targetLiveSubset, 'cmd.shell', ['grep -RE \'oc[0-9]{2,3}\' /etc/apt/sources.list* | awk \'{print $1}\' | sed \'s/ *:.*//\''], null, true)
                    oc_component_repo = oc_component_repo['return'][0].values()[0]

                    try {
                        salt.runSaltProcessStep(saltMaster, targetLiveSubset, 'cmd.shell', ["rm ${oc_component_repo}"], null, true)
                        salt.runSaltProcessStep(saltMaster, targetLiveSubset, 'saltutil.refresh_pillar', [], null, true)
                        salt.enforceState(saltMaster, targetLiveSubset, 'linux.system.repo')
                    } catch (Exception er) {
                        errorOccured = true
                        common.errorMsg("Opencontrail component on ${targetLiveSubset} probably failed to be replaced. Please check it in ${oc_component_repo} before continuing.")
                        return
                    }

                    args = "export DEBIAN_FRONTEND=noninteractive; apt install -o Dpkg::Options::=\"--force-confold\" -o Dpkg::Options::=\"--force-confdef\" ${CMP_PKGS}  -y;"
                    check = 'contrail-status'

                    out = salt.runSaltCommand(saltMaster, 'local', ['expression': targetLiveSubset, 'type': 'compound'], command, null, args, null)
                    salt.printSaltCommandResult(out)

                    try {
                        salt.enforceState(saltMaster, targetLiveSubset, 'opencontrail')
                    } catch (Exception er) {
                        common.errorMsg("Opencontrail state was executed on ${targetLiveSubset} and failed please fix it manually.")
                    }

                    salt.runSaltProcessStep(saltMaster, targetLiveSubset, 'cmd.shell', ["${KERNEL_MODULE_RELOAD}"], null, true)

                    //sleep(10)
                    salt.commandStatus(saltMaster, targetLiveSubset, "${check} | grep -v == | grep -v active", null, false)

                    out = salt.runSaltCommand(saltMaster, 'local', ['expression': targetLiveSubset, 'type': 'compound'], command, null, check, null)
                    salt.printSaltCommandResult(out)
                }

                stage('Confirm upgrade on all targeted nodes') {
                    input message: "Do you want to continue with the Opencontrail compute upgrade on all the targeted nodes? ${targetLiveAll} nodes?"
                }
                stage("Opencontrail compute upgrade on all targeted nodes") {

                    oc_component_repo = salt.runSaltProcessStep(saltMaster, targetLiveAll, 'cmd.shell', ['grep -RE \'oc[0-9]{2,3}\' /etc/apt/sources.list* | awk \'{print $1}\' | sed \'s/ *:.*//\''], null, true)
                    oc_component_repo = oc_component_repo['return'][0].values()[0]

                    try {
                        salt.runSaltProcessStep(saltMaster, targetLiveAll, 'cmd.shell', ["rm ${oc_component_repo}"], null, true)
                        salt.runSaltProcessStep(saltMaster, targetLiveAll, 'saltutil.refresh_pillar', [], null, true)
                        salt.enforceState(saltMaster, targetLiveAll, 'linux.system.repo')
                    } catch (Exception er) {
                        common.errorMsg("Opencontrail component on ${targetLiveAll} probably failed to be replaced. Please check it in ${oc_component_repo} before continuing.")
                        return
                    }

                    args = "export DEBIAN_FRONTEND=noninteractive; apt install -o Dpkg::Options::=\"--force-confold\" -o Dpkg::Options::=\"--force-confdef\" ${CMP_PKGS}  -y;"
                    check = 'contrail-status'

                    out = salt.runSaltCommand(saltMaster, 'local', ['expression': targetLiveAll, 'type': 'compound'], command, null, args, null)
                    salt.printSaltCommandResult(out)

                    try {
                        salt.enforceState(saltMaster, targetLiveAll, 'opencontrail')
                    } catch (Exception er) {
                        common.errorMsg("Opencontrail state was executed on ${targetLiveAll} and failed please fix it manually.")
                    }

                    salt.runSaltProcessStep(saltMaster, targetLiveAll, 'cmd.shell', ["${KERNEL_MODULE_RELOAD}"], null, true)
                    //sleep(10)
                    salt.commandStatus(saltMaster, targetLiveAll, "${check} | grep -v == | grep -v active", null, false)

                    out = salt.runSaltCommand(saltMaster, 'local', ['expression': targetLiveAll, 'type': 'compound'], command, null, check, null)
                    salt.printSaltCommandResult(out)
                }

            } catch (Throwable e) {
                // If there was an error or exception thrown, the build failed
                currentBuild.result = "FAILURE"
                throw e
            }
        }


        if (STAGE_CONTROLLERS_ROLLBACK.toBoolean() == true && !errorOccured) {

            stage('Ask for manual confirmation') {
                input message: "Do you want to continue with the Opencontrail control nodes rollback?"
            }

           stage('Opencontrail controllers rollback') {

                oc_component_repo = salt.runSaltProcessStep(saltMaster, 'I@opencontrail:control and *01*', 'cmd.shell', ['grep -RE \'oc[0-9]{2,3}\' /etc/apt/sources.list* | awk \'{print $1}\' | sed \'s/ *:.*//\''], null, true)
                oc_component_repo = oc_component_repo['return'][0].values()[0]

                try {
                    salt.runSaltProcessStep(saltMaster, 'I@opencontrail:control', 'cmd.shell', ["rm ${oc_component_repo}"], null, true)
                    salt.runSaltProcessStep(saltMaster, 'I@opencontrail:control', 'saltutil.refresh_pillar', [], null, true)
                    salt.enforceState(saltMaster, 'I@opencontrail:control', 'linux.system.repo')
                } catch (Exception er) {
                    errorOccured = true
                    common.errorMsg("Opencontrail component on I@opencontrail:control probably failed to be replaced. Please check it in ${oc_component_repo} before continuing.")
                    return
                }

                args = 'apt install contrail-database -y --force-yes;'
                check = 'nodetool status'

                // ntw01
                runCommonCommands('I@opencontrail:control and *01*', command, args, check, salt, saltMaster, common)
                // ntw02
                runCommonCommands('I@opencontrail:control and *02*', command, args, check, salt, saltMaster, common)
                // ntw03
                runCommonCommands('I@opencontrail:control and *03*', command, args, check, salt, saltMaster, common)

                args = "apt install -o Dpkg::Options::=\"--force-confold\" ${CONTROL_PKGS} -y --force-yes;"
                check = 'contrail-status'

                // ntw01
                runCommonCommands('I@opencontrail:control and *01*', command, args, check, salt, saltMaster, common)
                // ntw02
                runCommonCommands('I@opencontrail:control and *02*', command, args, check, salt, saltMaster, common)
                // ntw03
                runCommonCommands('I@opencontrail:control and *03*', command, args, check, salt, saltMaster, common)

                try {
                    salt.enforceState(saltMaster, 'I@opencontrail:control', 'opencontrail')
                } catch (Exception er) {
                    common.errorMsg('Opencontrail state was executed on I@opencontrail:control and failed please fix it manually.')
                }

                out = salt.runSaltCommand(saltMaster, 'local', ['expression': 'I@opencontrail:control', 'type': 'compound'], command, null, check, null)
                salt.printSaltCommandResult(out)

                common.warningMsg('Please check \'show bgp summary\' on your bgp router if all bgp peers are in healthy state.')
            }
        }

        if (STAGE_ANALYTICS_ROLLBACK.toBoolean() == true && !errorOccured) {

            stage('Ask for manual confirmation') {
                input message: "Do you want to continue with the Opencontrail analytic nodes rollback?"
            }

            stage('Opencontrail analytics rollback') {

                oc_component_repo = salt.runSaltProcessStep(saltMaster, 'I@opencontrail:collector and *01*', 'cmd.shell', ['grep -RE \'oc[0-9]{2,3}\' /etc/apt/sources.list* | awk \'{print $1}\' | sed \'s/ *:.*//\''], null, true)
                oc_component_repo = oc_component_repo['return'][0].values()[0]

                try {
                    salt.runSaltProcessStep(saltMaster, 'I@opencontrail:collector', 'cmd.shell', ["rm ${oc_component_repo}"], null, true)
                    salt.runSaltProcessStep(saltMaster, 'I@opencontrail:collector', 'saltutil.refresh_pillar', [], null, true)
                    salt.enforceState(saltMaster, 'I@opencontrail:collector', 'linux.system.repo')
                } catch (Exception er) {
                    errorOccured = true
                    common.errorMsg("Opencontrail component on I@opencontrail:collector probably failed to be replaced. Please check it in ${oc_component_repo} before continuing.")
                    return
                }

                args = 'apt install contrail-database -y --force-yes;'
                check = 'nodetool status'

                // nal01
                runCommonCommands('I@opencontrail:collector and *01*', command, args, check, salt, saltMaster, common)
                // nal02
                runCommonCommands('I@opencontrail:collector and *02*', command, args, check, salt, saltMaster, common)
                // nal03
                runCommonCommands('I@opencontrail:collector and *03*', command, args, check, salt, saltMaster, common)

                args = "apt install -o Dpkg::Options::=\"--force-confold\" ${ANALYTIC_PKGS} -y --force-yes;"
                check = 'contrail-status'

                // nal01
                runCommonCommands('I@opencontrail:collector and *01*', command, args, check, salt, saltMaster, common)
                // nal02
                runCommonCommands('I@opencontrail:collector and *02*', command, args, check, salt, saltMaster, common)
                // nal03
                runCommonCommands('I@opencontrail:collector and *03*', command, args, check, salt, saltMaster, common)

                try {
                    salt.enforceState(saltMaster, 'I@opencontrail:collector', 'opencontrail')
                } catch (Exception er) {
                    common.errorMsg('Opencontrail state was executed on I@opencontrail:collector and failed please fix it manually.')
                }

                out = salt.runSaltCommand(saltMaster, 'local', ['expression': 'I@opencontrail:collector', 'type': 'compound'], command, null, check, null)
                salt.printSaltCommandResult(out)
            }
        }

        if (STAGE_COMPUTES_ROLLBACK.toBoolean() == true && !errorOccured) {

            try {

                stage('List targeted compute servers') {
                    minions = salt.getMinions(saltMaster, COMPUTE_TARGET_SERVERS)

                    if (minions.isEmpty()) {
                        throw new Exception("No minion was targeted")
                    }

                    targetLiveSubset = minions.subList(0, Integer.valueOf(COMPUTE_TARGET_SUBSET_LIVE)).join(' or ')
                    targetLiveSubsetProbe = minions.subList(0, probe).join(' or ')

                    targetLiveAll = minions.join(' or ')
                    common.infoMsg("Found nodes: ${targetLiveAll}")
                    common.infoMsg("Selected sample nodes: ${targetLiveSubset}")
                }

                stage('Confirm rollback on sample nodes') {
                    input message: "Do you want to continue with the Opencontrail compute rollback on the following sample nodes? ${targetLiveSubset}"
                }

                stage("Opencontrail compute rollback on sample nodes") {

                    oc_component_repo = salt.runSaltProcessStep(saltMaster, targetLiveSubset, 'cmd.shell', ['grep -RE \'oc[0-9]{2,3}\' /etc/apt/sources.list* | awk \'{print $1}\' | sed \'s/ *:.*//\''], null, true)
                    oc_component_repo = oc_component_repo['return'][0].values()[0]

                    try {
                        salt.runSaltProcessStep(saltMaster, targetLiveSubset, 'cmd.shell', ["rm ${oc_component_repo}"], null, true)
                        salt.runSaltProcessStep(saltMaster, targetLiveSubset, 'saltutil.refresh_pillar', [], null, true)
                        salt.enforceState(saltMaster, targetLiveSubset, 'linux.system.repo')
                    } catch (Exception er) {
                        errorOccured = true
                        common.errorMsg("Opencontrail component on ${targetLiveSubset} probably failed to be replaced. Please check it in ${oc_component_repo} before continuing.")
                        return
                    }

                    args = "export DEBIAN_FRONTEND=noninteractive; apt install --allow-downgrades -o Dpkg::Options::=\"--force-confold\" -o Dpkg::Options::=\"--force-confdef\" ${CMP_PKGS}  -y;"
                    check = 'contrail-status'

                    out = salt.runSaltCommand(saltMaster, 'local', ['expression': targetLiveSubset, 'type': 'compound'], command, null, args, null)
                    salt.printSaltCommandResult(out)

                    try {
                        salt.enforceState(saltMaster, targetLiveSubset, 'opencontrail')
                    } catch (Exception er) {
                        common.errorMsg("Opencontrail state was executed on ${targetLiveSubset} and failed please fix it manually.")
                    }

                    salt.runSaltProcessStep(saltMaster, targetLiveSubset, 'cmd.shell', ["${KERNEL_MODULE_RELOAD}"], null, true)
                    //sleep(10)
                    salt.commandStatus(saltMaster, targetLiveSubset, "${check} | grep -v == | grep -v active", null, false)

                    out = salt.runSaltCommand(saltMaster, 'local', ['expression': targetLiveSubset, 'type': 'compound'], command, null, check, null)
                    salt.printSaltCommandResult(out)
                }

                stage('Confirm rollback on all targeted nodes') {
                    input message: "Do you want to continue with the Opencontrail compute upgrade on all the targeted nodes? ${targetLiveAll} nodes?"
                }

                stage("Opencontrail compute upgrade on all targeted nodes") {

                    oc_component_repo = salt.runSaltProcessStep(saltMaster, targetLiveAll, 'cmd.shell', ['grep -RE \'oc[0-9]{2,3}\' /etc/apt/sources.list* | awk \'{print $1}\' | sed \'s/ *:.*//\''], null, true)
                    oc_component_repo = oc_component_repo['return'][0].values()[0]

                    try {
                        salt.runSaltProcessStep(saltMaster, targetLiveAll, 'cmd.shell', ["rm ${oc_component_repo}"], null, true)
                        salt.runSaltProcessStep(saltMaster, targetLiveAll, 'saltutil.refresh_pillar', [], null, true)
                        salt.enforceState(saltMaster, targetLiveAll, 'linux.system.repo')
                    } catch (Exception er) {
                        common.errorMsg("Opencontrail component on ${targetLiveAll} probably failed to be replaced. Please check it in ${oc_component_repo} before continuing.")
                        return
                    }

                    args = "export DEBIAN_FRONTEND=noninteractive; apt install --allow-downgrades -o Dpkg::Options::=\"--force-confold\" -o Dpkg::Options::=\"--force-confdef\" ${CMP_PKGS} -y;"
                    check = 'contrail-status'

                    out = salt.runSaltCommand(saltMaster, 'local', ['expression': targetLiveAll, 'type': 'compound'], command, null, args, null)
                    salt.printSaltCommandResult(out)

                    try {
                        salt.enforceState(saltMaster, targetLiveAll, 'opencontrail')
                    } catch (Exception er) {
                        common.errorMsg("Opencontrail state was executed on ${targetLiveAll} and failed please fix it manually.")
                    }

                    salt.runSaltProcessStep(saltMaster, targetLiveAll, 'cmd.shell', ["${KERNEL_MODULE_RELOAD}"], null, true)

                    //sleep(10)
                    salt.commandStatus(saltMaster, targetLiveAll, "${check} | grep -v == | grep -v active", null, false)

                    out = salt.runSaltCommand(saltMaster, 'local', ['expression': targetLiveAll, 'type': 'compound'], command, null, check, null)
                    salt.printSaltCommandResult(out)
                }

            } catch (Throwable e) {
                // If there was an error or exception thrown, the build failed
                currentBuild.result = "FAILURE"
                throw e
            }
        }
    }
}
