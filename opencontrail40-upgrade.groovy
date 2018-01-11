/**
 * Update packages on given nodes
 *
 * Expected parameters:
 *   SALT_MASTER_CREDENTIALS        Credentials to the Salt API.
 *   SALT_MASTER_URL                Full Salt API address [http://10.10.10.1:8000].
 *   STAGE_CONTROLLERS_UPGRADE      Run upgrade on Opencontrail controllers and analytics (bool)
 *   STAGE_COMPUTES_UPGRADE         Run upgrade on Opencontrail compute nodes  (bool)
 *   COMPUTE_TARGET_SERVERS         Salt compound target to match nodes to be updated [*, G@osfamily:debian].
 *   COMPUTE_TARGET_SUBSET_LIVE     Number of selected nodes to live apply selected package update.
 *   STAGE_CONTROLLERS_ROLLBACK     Run rollback on Opencontrail controllers  (bool)
 *   STAGE_COMPUTES_ROLLBACK        Run rollback on Opencontrail compute nodes  (bool)
 *
**/

def common = new com.mirantis.mk.Common()
def salt = new com.mirantis.mk.Salt()
def python = new com.mirantis.mk.Python()

def pepperEnv = "pepperEnv"
def targetLiveSubset
def targetLiveAll
def minions
def args
def probe = 1
def command = 'cmd.shell'

def controlPkgs = 'contrail-config,contrail-config-openstack,contrail-control,contrail-dns,contrail-lib,contrail-nodemgr,contrail-utils,contrail-web-controller,contrail-web-core,neutron-plugin-contrail,python-contrail,contrail-database'
def analyticsPkgs = 'contrail-analytics,contrail-lib,contrail-nodemgr,contrail-utils,python-contrail,contrail-database'
//def cmpPkgs = ['contrail-lib', 'contrail-nodemgr', 'contrail-utils', 'contrail-vrouter-agent', 'contrail-vrouter-utils', 'python-contrail', 'python-contrail-vrouter-api', 'python-opencontrail-vrouter-netns', 'contrail-vrouter-dkms']
def CMP_PKGS = 'contrail-lib contrail-nodemgr contrail-utils contrail-vrouter-agent contrail-vrouter-utils python-contrail python-contrail-vrouter-api python-opencontrail-vrouter-netns contrail-vrouter-dkms'
def KERNEL_MODULE_RELOAD = 'service supervisor-vrouter stop;ifdown vhost0;rmmod vrouter;modprobe vrouter;ifup vhost0;service supervisor-vrouter start;'
def analyticsServices = ['supervisor-analytics', 'supervisor-database', 'zookeeper']
def configServices = ['contrail-webui-jobserver', 'contrail-webui-webserver', 'supervisor-config', 'supervisor-database', 'zookeeper']
def controlServices = ['ifmap-server', 'supervisor-control']
def config4Services = ['zookeeper', 'contrail-webui-middleware', 'contrail-webui', 'contrail-api', 'contrail-schema', 'contrail-svc-monitor', 'contrail-device-manager', 'contrail-config-nodemgr', 'contrail-database']

def void runCommonCommands(target, command, args, check, salt, pepperEnv, common) {

    out = salt.runSaltCommand(pepperEnv, 'local', ['expression': target, 'type': 'compound'], command, null, args, null)
    salt.printSaltCommandResult(out)
    // wait until $check is in correct state
    if ( check == "nodetool status" ) {
        salt.commandStatus(pepperEnv, target, check, 'Status=Up')
    } else if ( check == "doctrail all contrail-status" ) {
        salt.commandStatus(pepperEnv, target, "${check} | grep -v == | grep -v FOR | grep -v \\* | grep -v \'disabled on boot\' | grep -v nodemgr | grep -v active | grep -v backup | grep -v -F /var/crashes/", null, false, true, null, true, 500)
    } else if ( check == "contrail-status" ) {
        salt.commandStatus(pepperEnv, target, "${check} | grep -v == | grep -v FOR | grep -v \'disabled on boot\' | grep -v nodemgr | grep -v active | grep -v backup | grep -v -F /var/crashes/", null, false, true, null, true, 500)
    }
    //out = salt.runSaltCommand(pepperEnv, 'local', ['expression': target, 'type': 'compound'], command, null, check, null)
    //salt.printSaltCommandResult(out)
    //input message: "Please check the output of \'${check}\' and continue if it is correct."
}
timeout(time: 12, unit: 'HOURS') {
    node() {

        stage('Setup virtualenv for Pepper') {
            python.setupPepperVirtualenv(pepperEnv, SALT_MASTER_URL, SALT_MASTER_CREDENTIALS)
        }

        if (STAGE_CONTROLLERS_UPGRADE.toBoolean() == true) {

            stage('Opencontrail controllers upgrade') {
                try {
                    salt.runSaltProcessStep(pepperEnv, 'I@opencontrail:database or I@neutron:server', 'saltutil.refresh_pillar', [], null, true)
                    salt.runSaltProcessStep(pepperEnv, 'I@opencontrail:database or I@neutron:server', 'saltutil.sync_all', [], null, true)
                    salt.runSaltProcessStep(pepperEnv, 'I@opencontrail:database', 'file.remove', ["/etc/apt/sources.list.d/mcp_opencontrail.list"], null, true)
                    salt.runSaltProcessStep(pepperEnv, 'I@opencontrail:database', 'file.remove', ["/etc/apt/sources.list.d/cassandra.list"], null, true)
                    salt.enforceState(pepperEnv, 'I@opencontrail:database or I@neutron:server', 'linux.system.repo')

                } catch (Exception er) {
                    common.errorMsg("Opencontrail component on I@opencontrail:control or I@opencontrail:collector or I@neutron:server probably failed to be replaced.")
                    throw er
                }

                try {
                    controllerImage = salt.getPillar(pepperEnv, "I@opencontrail:control and *01*", "docker:client:compose:opencontrail_api:service:controller:image")
                    analyticsImage = salt.getPillar(pepperEnv, "I@opencontrail:collector and *01*", "docker:client:compose:opencontrail_api:service:analytics:image")
                    analyticsdbImage = salt.getPillar(pepperEnv, "I@opencontrail:collector and *01*", "docker:client:compose:opencontrail_api:service:analyticsdb:image")
                    salt.enforceState(pepperEnv, 'I@opencontrail:database', 'docker.host')
                    salt.runSaltProcessStep(pepperEnv, 'I@opencontrail:database', 'state.sls', ['opencontrail', 'exclude=opencontrail.client'])
                    salt.runSaltProcessStep(pepperEnv, 'I@opencontrail:control', 'dockerng.pull', [controllerImage])
                    salt.runSaltProcessStep(pepperEnv, 'I@opencontrail:collector', 'dockerng.pull', [analyticsImage])
                    salt.runSaltProcessStep(pepperEnv, 'I@opencontrail:collector', 'dockerng.pull', [analyticsdbImage])

                } catch (Exception er) {
                    common.errorMsg("Docker images on I@opencontrail:control or I@opencontrail:collector probably failed to be downloaded.")
                    throw er
                }

                salt.enforceState(pepperEnv, 'I@zookeeper:backup:server', 'zookeeper.backup')
                salt.enforceState(pepperEnv, 'I@zookeeper:backup:client', 'zookeeper.backup')

                try {
                    salt.cmdRun(pepperEnv, 'I@opencontrail:control', "su root -c '/usr/local/bin/zookeeper-backup-runner.sh'")
                } catch (Exception er) {
                    common.errorMsg('Zookeeper failed to backup. Please fix it before continuing.')
                    throw er
                }

                salt.enforceState(pepperEnv, 'I@cassandra:backup:server', 'cassandra.backup')
                salt.enforceState(pepperEnv, 'I@cassandra:backup:client', 'cassandra.backup')

                try {
                    salt.cmdRun(pepperEnv, 'I@cassandra:backup:client', "su root -c '/usr/local/bin/cassandra-backup-runner-call.sh'")
                } catch (Exception er) {
                    common.errorMsg('Cassandra failed to backup. Please fix it before continuing.')
                    throw er
                }
                
                salt.runSaltProcessStep(pepperEnv, 'I@neutron:server', 'service.stop', ['neutron-server'])

                try {
                    for (service in analyticsServices) {
                        salt.runSaltProcessStep(pepperEnv, 'I@opencontrail:collector', 'service.stop', [service])
                    }
                    result = salt.runSaltProcessStep(pepperEnv, 'I@opencontrail:collector', 'file.directory_exists', ['/var/lib/analyticsdb/data'])['return'][0].values()[0]
                    if (result == false) {
                        salt.runSaltProcessStep(pepperEnv, 'I@opencontrail:collector', 'file.move', ['/var/lib/cassandra', '/var/lib/analyticsdb'])
                        salt.runSaltProcessStep(pepperEnv, 'I@opencontrail:collector', 'file.copy', ['/var/lib/zookeeper', '/var/lib/analyticsdb_zookeeper_data','recurse=True'])
                    }
                    check = 'doctrail all contrail-status'
                    salt.enforceState(pepperEnv, 'I@opencontrail:collector', 'docker.client')
                    runCommonCommands('I@opencontrail:collector and *01*', command, args, check, salt, pepperEnv, common)
                } catch (Exception er) {
                    common.errorMsg("Opencontrail Analytics failed to be upgraded.")
                    throw er
                }
                try {
                    check = 'doctrail all contrail-status'

                    for (service in configServices) {
                        salt.runSaltProcessStep(pepperEnv, 'I@opencontrail:control', 'service.stop', [service])
                    }

                    result = salt.runSaltProcessStep(pepperEnv, 'I@opencontrail:control', 'file.directory_exists', ['/var/lib/configdb/data'])['return'][0].values()[0]
                    if (result == false) {
                        salt.runSaltProcessStep(pepperEnv, 'I@opencontrail:control', 'file.copy', ['/var/lib/cassandra', '/var/lib/configdb', 'recurse=True'])
                        salt.runSaltProcessStep(pepperEnv, 'I@opencontrail:control', 'file.copy', ['/var/lib/zookeeper', '/var/lib/config_zookeeper_data', 'recurse=True'])
                    }

                    for (service in controlServices) {
                        salt.runSaltProcessStep(pepperEnv, 'I@opencontrail:control and *0[23]*', 'service.stop', [service])
                    }

                    salt.enforceState(pepperEnv, 'I@opencontrail:control and *0[23]*', 'docker.client')

                    runCommonCommands('I@opencontrail:control and *02*', command, args, check, salt, pepperEnv, common)

                    sleep(120)

                    for (service in controlServices) {
                        salt.runSaltProcessStep(pepperEnv, 'I@opencontrail:control and *01*', 'service.stop', [service])
                    }

                    salt.enforceState(pepperEnv, 'I@opencontrail:control and *01*', 'docker.client')

                    salt.runSaltProcessStep(pepperEnv, 'I@neutron:server', 'pkg.install', ['neutron-plugin-contrail,contrail-heat,python-contrail'])
                    salt.runSaltProcessStep(pepperEnv, 'I@neutron:server', 'service.start', ['neutron-server'])
                } catch (Exception er) {
                    common.errorMsg("Opencontrail Controller failed to be upgraded.")
                    throw er
                }

            }
            salt.runSaltProcessStep(pepperEnv, 'I@opencontrail:control', 'archive.tar', ['zcvf', '/root/contrail-database.tgz', '/var/lib/cassandra'])
            salt.runSaltProcessStep(pepperEnv, 'I@opencontrail:control', 'archive.tar', ['zcvf', '/root/contrail-zookeeper.tgz', '/var/lib/zoopeeker'])
            salt.runSaltProcessStep(pepperEnv, 'I@opencontrail:collector', 'archive.tar', ['zcvf', '/root/contrail-analytics-database.tgz', '/var/lib/cassandra'])
            salt.runSaltProcessStep(pepperEnv, 'I@opencontrail:collector', 'archive.tar', ['zcvf', '/root/contrail-analytics-zookeeper.tgz', '/var/lib/zookeeper'])
            //salt.runSaltProcessStep(pepperEnv, 'I@opencontrail:control', 'pkg.remove', [controlPkgs])
            //salt.runSaltProcessStep(pepperEnv, 'I@opencontrail:collector', 'pkg.remove', [analyticsPkgs])
            for (service in controlServices) {
                salt.runSaltProcessStep(pepperEnv, 'I@opencontrail:control', 'service.disable', [service])
            }
            for (service in analyticsServices) {
                salt.runSaltProcessStep(pepperEnv, 'I@opencontrail:collector', 'service.disable', [service])
                }
        }


        if (STAGE_COMPUTES_UPGRADE.toBoolean() == true) {

            try {

                stage('List targeted compute servers') {
                    minions = salt.getMinions(pepperEnv, COMPUTE_TARGET_SERVERS)

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

                    try {
                        salt.runSaltProcessStep(pepperEnv, targetLiveSubset, 'saltutil.refresh_pillar', [], null, true)
                        salt.runSaltProcessStep(pepperEnv, targetLiveSubset, 'saltutil.sync_all', [], null, true)
                        salt.runSaltProcessStep(pepperEnv, targetLiveSubset, 'file.remove', ["/etc/apt/sources.list.d/mcp_opencontrail.list"], null, true)
                        salt.enforceState(pepperEnv, targetLiveSubset, 'linux.system.repo')
                    } catch (Exception er) {
                        common.errorMsg("Opencontrail component on ${targetLiveSubset} probably failed to be replaced. Please check it in ${oc_component_repo} before continuing.")
                        throw er
                    }

                    args = "export DEBIAN_FRONTEND=noninteractive; apt install -o Dpkg::Options::=\"--force-confold\" -o Dpkg::Options::=\"--force-confdef\" ${CMP_PKGS}  -y;"
                    check = 'contrail-status'

                    out = salt.runSaltCommand(pepperEnv, 'local', ['expression': targetLiveSubset, 'type': 'compound'], command, null, args, null)
                    salt.printSaltCommandResult(out)

                    try {
                        salt.enforceState(pepperEnv, targetLiveSubset, 'opencontrail')
                    } catch (Exception er) {
                        common.errorMsg("Opencontrail state was executed on ${targetLiveSubset} and failed please fix it manually.")
                    }

                    salt.runSaltProcessStep(pepperEnv, targetLiveSubset, 'cmd.shell', ["${KERNEL_MODULE_RELOAD}"], null, true)

                    //sleep(10)
                    salt.commandStatus(pepperEnv, targetLiveSubset, "${check} | grep -v == | grep -v active | grep -v -F /var/crashes/", null, false)

                    out = salt.runSaltCommand(pepperEnv, 'local', ['expression': targetLiveSubset, 'type': 'compound'], command, null, check, null)
                    salt.printSaltCommandResult(out)
                }

                stage('Confirm upgrade on all targeted nodes') {
                    input message: "Do you want to continue with the Opencontrail compute upgrade on all the targeted nodes? ${targetLiveAll} nodes?"
                }
                stage("Opencontrail compute upgrade on all targeted nodes") {

                    try {
                        salt.runSaltProcessStep(pepperEnv, targetLiveAll, 'saltutil.refresh_pillar', [], null, true)
                        salt.runSaltProcessStep(pepperEnv, targetLiveAll, 'saltutil.sync_all', [], null, true)
                        salt.runSaltProcessStep(pepperEnv, targetLiveAll, 'file.remove', ["/etc/apt/sources.list.d/mcp_opencontrail.list"], null, true)
                        salt.enforceState(pepperEnv, targetLiveAll, 'linux.system.repo')
                    } catch (Exception er) {
                        common.errorMsg("Opencontrail component on ${targetLiveAll} probably failed to be replaced. Please check it in ${oc_component_repo} before continuing.")
                        throw er
                    }

                    args = "export DEBIAN_FRONTEND=noninteractive; apt install -o Dpkg::Options::=\"--force-confold\" -o Dpkg::Options::=\"--force-confdef\" ${CMP_PKGS}  -y;"
                    check = 'contrail-status'

                    out = salt.runSaltCommand(pepperEnv, 'local', ['expression': targetLiveAll, 'type': 'compound'], command, null, args, null)
                    salt.printSaltCommandResult(out)

                    try {
                        salt.enforceState(pepperEnv, targetLiveAll, 'opencontrail')
                    } catch (Exception er) {
                        common.errorMsg("Opencontrail state was executed on ${targetLiveAll} and failed please fix it manually.")
                    }

                    salt.runSaltProcessStep(pepperEnv, targetLiveAll, 'cmd.shell', ["${KERNEL_MODULE_RELOAD}"], null, true)
                    //sleep(10)
                    salt.commandStatus(pepperEnv, targetLiveAll, "${check} | grep -v == | grep -v active | grep -v -F /var/crashes/", null, false)

                    out = salt.runSaltCommand(pepperEnv, 'local', ['expression': targetLiveAll, 'type': 'compound'], command, null, check, null)
                    salt.printSaltCommandResult(out)
                }

            } catch (Throwable e) {
                // If there was an error or exception thrown, the build failed
                currentBuild.result = "FAILURE"
                currentBuild.description = currentBuild.description ? e.message + " " + currentBuild.description : e.message
                throw e
            }
        }


        if (STAGE_CONTROLLERS_ROLLBACK.toBoolean() == true) {

            stage('Ask for manual confirmation') {
                input message: "Do you want to continue with the Opencontrail nodes rollback?"
            }

           stage('Opencontrail controllers rollback') {

                salt.runSaltProcessStep(pepperEnv, 'I@opencontrail:database', 'saltutil.refresh_pillar', [], null, true)
                salt.enforceState(pepperEnv, 'I@opencontrail:database', 'linux.system.repo')
                salt.runSaltProcessStep(pepperEnv, 'I@opencontrail:collector', 'cmd.shell', ['cd /etc/docker/compose/opencontrail/; docker-compose down'], null, true)

                salt.runSaltProcessStep(pepperEnv, 'I@opencontrail:collector', 'state.sls', ['opencontrail', 'exclude=opencontrail.client'])

                salt.runSaltProcessStep(pepperEnv, 'I@opencontrail:control and *0[23]*', 'cmd.shell', ['cd /etc/docker/compose/opencontrail/; docker-compose down'], null, true)
                for (service in config4Services) {
                    salt.runSaltProcessStep(pepperEnv, 'I@opencontrail:control and *01*', 'cmd.shell', ["doctrail controller systemctl stop ${service}"], null, true)
                }
                salt.runSaltProcessStep(pepperEnv, 'I@opencontrail:control and *0[23]*', 'state.sls', ['opencontrail', 'exclude=opencontrail.client'])

                check = 'contrail-status'
                runCommonCommands('I@opencontrail:control and *02*', command, args, check, salt, pepperEnv, common)

                sleep(120)

                salt.runSaltProcessStep(pepperEnv, 'I@opencontrail:control and *01*', 'cmd.shell', ['cd /etc/docker/compose/opencontrail/; docker-compose down'], null, true)
                salt.runSaltProcessStep(pepperEnv, 'I@opencontrail:control and *01*', 'state.sls', ['opencontrail', 'exclude=opencontrail.client'])
            }
        }

        if (STAGE_COMPUTES_ROLLBACK.toBoolean() == true) {

            try {

                stage('List targeted compute servers') {
                    minions = salt.getMinions(pepperEnv, COMPUTE_TARGET_SERVERS)

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

                    try {
                        salt.runSaltProcessStep(pepperEnv, targetLiveSubset, 'file.remove', ["/etc/apt/sources.list.d/mcp_opencontrail.list"], null, true)
                        salt.runSaltProcessStep(pepperEnv, targetLiveSubset, 'saltutil.refresh_pillar', [], null, true)
                        salt.enforceState(pepperEnv, targetLiveSubset, 'linux.system.repo')
                    } catch (Exception er) {
                        common.errorMsg("Opencontrail component on ${targetLiveSubset} probably failed to be replaced. Please check it in ${oc_component_repo} before continuing.")
                        throw er
                    }

                    args = "export DEBIAN_FRONTEND=noninteractive; apt install --allow-downgrades -o Dpkg::Options::=\"--force-confold\" -o Dpkg::Options::=\"--force-confdef\" ${CMP_PKGS}  -y;"
                    check = 'contrail-status'

                    out = salt.runSaltCommand(pepperEnv, 'local', ['expression': targetLiveSubset, 'type': 'compound'], command, null, args, null)
                    salt.printSaltCommandResult(out)

                    try {
                        salt.enforceState(pepperEnv, targetLiveSubset, 'opencontrail')
                    } catch (Exception er) {
                        common.errorMsg("Opencontrail state was executed on ${targetLiveSubset} and failed please fix it manually.")
                    }

                    salt.runSaltProcessStep(pepperEnv, targetLiveSubset, 'cmd.shell', ["${KERNEL_MODULE_RELOAD}"], null, true)
                    //sleep(10)
                    salt.commandStatus(pepperEnv, targetLiveSubset, "${check} | grep -v == | grep -v active | grep -v -F /var/crashes/", null, false)

                    out = salt.runSaltCommand(pepperEnv, 'local', ['expression': targetLiveSubset, 'type': 'compound'], command, null, check, null)
                    salt.printSaltCommandResult(out)
                }

                stage('Confirm rollback on all targeted nodes') {
                    input message: "Do you want to continue with the Opencontrail compute upgrade on all the targeted nodes? ${targetLiveAll} nodes?"
                }

                stage("Opencontrail compute upgrade on all targeted nodes") {

                    try {
                        salt.runSaltProcessStep(pepperEnv, targetLiveSubset, 'file.remove', ["/etc/apt/sources.list.d/mcp_opencontrail.list"], null, true)
                        salt.runSaltProcessStep(pepperEnv, targetLiveAll, 'saltutil.refresh_pillar', [], null, true)
                        salt.enforceState(pepperEnv, targetLiveAll, 'linux.system.repo')
                    } catch (Exception er) {
                        common.errorMsg("Opencontrail component on ${targetLiveAll} probably failed to be replaced. Please check it in ${oc_component_repo} before continuing.")
                        throw er
                    }

                    args = "export DEBIAN_FRONTEND=noninteractive; apt install --allow-downgrades -o Dpkg::Options::=\"--force-confold\" -o Dpkg::Options::=\"--force-confdef\" ${CMP_PKGS}  -y;"
                    check = 'contrail-status'

                    out = salt.runSaltCommand(pepperEnv, 'local', ['expression': targetLiveAll, 'type': 'compound'], command, null, args, null)
                    salt.printSaltCommandResult(out)

                    try {
                        salt.enforceState(pepperEnv, targetLiveAll, 'opencontrail')
                    } catch (Exception er) {
                        common.errorMsg("Opencontrail state was executed on ${targetLiveAll} and failed please fix it manually.")
                    }

                    salt.runSaltProcessStep(pepperEnv, targetLiveAll, 'cmd.shell', ["${KERNEL_MODULE_RELOAD}"], null, true)
                    //sleep(10)
                    salt.commandStatus(pepperEnv, targetLiveAll, "${check} | grep -v == | grep -v active | grep -v -F /var/crashes/", null, false)

                    out = salt.runSaltCommand(pepperEnv, 'local', ['expression': targetLiveAll, 'type': 'compound'], command, null, check, null)
                    salt.printSaltCommandResult(out)
                }

            } catch (Throwable e) {
                // If there was an error or exception thrown, the build failed
                currentBuild.result = "FAILURE"
                currentBuild.description = currentBuild.description ? e.message + " " + currentBuild.description : e.message
                throw e
            }
        }
    }
}
