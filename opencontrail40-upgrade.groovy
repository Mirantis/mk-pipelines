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
def thirdPartyControlPkgsToRemove = 'zookeeper,libzookeeper-java,kafka,cassandra,redis-server,ifmap-server,supervisor'
def analyticsPkgs = 'contrail-analytics,contrail-lib,contrail-nodemgr,contrail-utils,python-contrail,contrail-database'
def thirdPartyAnalyticsPkgsToRemove = 'zookeeper,libzookeeper-java,kafka,cassandra,python-cassandra,cassandra-cpp-driver,redis-server,supervisor'
def cmpPkgs = 'contrail-lib contrail-nodemgr contrail-utils contrail-vrouter-agent contrail-vrouter-utils python-contrail python-contrail-vrouter-api python-opencontrail-vrouter-netns contrail-vrouter-dkms'
def neutronServerPkgs = 'neutron-plugin-contrail,contrail-heat,python-contrail'
def dashboardPanelPkg = 'openstack-dashboard-contrail-panels'
def kernelModuleReloadCmd = 'service supervisor-vrouter stop; rmmod vrouter; sync && echo 3 > /proc/sys/vm/drop_caches && echo 1 > /proc/sys/vm/compact_memory; service contrail-vrouter-agent start; service contrail-vrouter-nodemgr start'
def analyticsServices = ['supervisor-analytics', 'supervisor-database', 'zookeeper', 'redis-server']
def configServices = ['contrail-webui-jobserver', 'contrail-webui-webserver', 'supervisor-config', 'supervisor-database', 'zookeeper']
def controlServices = ['ifmap-server', 'supervisor-control', 'redis-server']
def thirdPartyServicesToDisable = ['kafka', 'zookeeper', 'cassandra']
def config4Services = ['zookeeper', 'contrail-webui-middleware', 'contrail-webui', 'contrail-api', 'contrail-schema', 'contrail-svc-monitor', 'contrail-device-manager', 'contrail-config-nodemgr', 'contrail-database']

def runCommonCommands(target, command, args, check, salt, pepperEnv) {

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
}
timeout(time: 12, unit: 'HOURS') {
    node() {

        stage('Setup virtualenv for Pepper') {
            python.setupPepperVirtualenv(pepperEnv, SALT_MASTER_URL, SALT_MASTER_CREDENTIALS)
        }

        if (STAGE_CONTROLLERS_UPGRADE.toBoolean() == true) {

            stage('Opencontrail controllers health check') {
                try {
                    salt.enforceState(pepperEnv, 'I@opencontrail:control or I@opencontrail:collector', 'opencontrail.upgrade.verify', true, true)
                } catch (Exception er) {
                    common.errorMsg("Opencontrail controllers health check stage found issues with services. Please take a look at the logs above.")
                    throw er
                }
            }

            stage('Opencontrail controllers upgrade') {
                try {
                    salt.runSaltProcessStep(pepperEnv, 'I@opencontrail:database or I@neutron:server or I@horizon:server', 'saltutil.refresh_pillar', [], null, true)
                    salt.runSaltProcessStep(pepperEnv, 'I@opencontrail:database or I@neutron:server or I@horizon:server', 'saltutil.sync_all', [], null, true)
                    salt.runSaltProcessStep(pepperEnv, 'I@opencontrail:database', 'file.remove', ["/etc/apt/sources.list.d/mcp_opencontrail.list"], null, true)
                    salt.runSaltProcessStep(pepperEnv, 'I@opencontrail:database', 'file.remove', ["/etc/apt/sources.list.d/cassandra.list"], null, true)
                    salt.enforceState(pepperEnv, 'I@opencontrail:database or I@neutron:server or I@horizon:server', 'linux.system.repo')

                } catch (Exception er) {
                    common.errorMsg("Opencontrail component on I@opencontrail:control, I@opencontrail:collector, I@neutron:server or I@horizon:server probably failed to be replaced.")
                    throw er
                }

                try {
                    controllerImage = salt.getPillar(pepperEnv, "I@opencontrail:control:role:primary", "docker:client:compose:opencontrail_api:service:controller:image")
                    analyticsImage = salt.getPillar(pepperEnv, "I@opencontrail:collector:role:primary", "docker:client:compose:opencontrail_api:service:analytics:image")
                    analyticsdbImage = salt.getPillar(pepperEnv, "I@opencontrail:collector:role:primary", "docker:client:compose:opencontrail_api:service:analyticsdb:image")
                    salt.enforceState(pepperEnv, 'I@opencontrail:database', 'docker.host')
                    salt.runSaltProcessStep(pepperEnv, 'I@opencontrail:database', 'state.sls', ['opencontrail', 'exclude=opencontrail.client'])
                    salt.runSaltProcessStep(pepperEnv, 'I@opencontrail:collector', 'state.sls', ['opencontrail.client'])
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
                    runCommonCommands('I@opencontrail:collector:role:primary', command, args, check, salt, pepperEnv)
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
                        salt.runSaltProcessStep(pepperEnv, 'I@opencontrail:control:role:secondary', 'service.stop', [service])
                    }

                    salt.enforceState(pepperEnv, 'I@opencontrail:control:role:secondary', 'docker.client')

                    runCommonCommands('I@opencontrail:control:role:secondary', command, args, check, salt, pepperEnv)

                    sleep(120)

                    for (service in controlServices) {
                        salt.runSaltProcessStep(pepperEnv, 'I@opencontrail:control:role:primary', 'service.stop', [service])
                    }

                    salt.enforceState(pepperEnv, 'I@opencontrail:control:role:primary', 'docker.client')

                    salt.runSaltProcessStep(pepperEnv, 'I@neutron:server', 'pkg.install', [neutronServerPkgs])
                    salt.runSaltProcessStep(pepperEnv, 'I@horizon:server', 'pkg.install', [dashboardPanelPkg])
                    salt.runSaltProcessStep(pepperEnv, 'I@neutron:server', 'service.start', ['neutron-server'])
                    salt.enforceState(pepperEnv, 'I@horizon:server', 'horizon')
                } catch (Exception er) {
                    common.errorMsg("Opencontrail Controller failed to be upgraded.")
                    throw er
                }
            }

            stage('Opencontrail controllers backup and cleanup') {
                try {
                    salt.runSaltProcessStep(pepperEnv, 'I@opencontrail:control', 'archive.tar', ['zcvf', '/root/contrail-database.tgz', '/var/lib/cassandra'])
                    salt.runSaltProcessStep(pepperEnv, 'I@opencontrail:control', 'archive.tar', ['zcvf', '/root/contrail-zookeeper.tgz', '/var/lib/zoopeeker'])
                    salt.runSaltProcessStep(pepperEnv, 'I@opencontrail:collector', 'archive.tar', ['zcvf', '/root/contrail-analytics-database.tgz', '/var/lib/cassandra'])
                    salt.runSaltProcessStep(pepperEnv, 'I@opencontrail:collector', 'archive.tar', ['zcvf', '/root/contrail-analytics-zookeeper.tgz', '/var/lib/zookeeper'])

                    for (service in (controlServices + thirdPartyServicesToDisable)) {
                        salt.runSaltProcessStep(pepperEnv, 'I@opencontrail:control', 'service.disable', [service])
                    }
                    for (service in (analyticsServices + thirdPartyServicesToDisable)) {
                        salt.runSaltProcessStep(pepperEnv, 'I@opencontrail:collector', 'service.disable', [service])
                    }

                    def tmpCfgBackupDir = '/tmp/cfg_backup'
                    def thirdPartyCfgFilesToBackup = ['/var/lib/zookeeper/myid', '/etc/zookeeper/conf/', '/usr/share/kafka/config/']

                    salt.runSaltProcessStep(pepperEnv, 'I@opencontrail:control or I@opencontrail:collector', 'file.makedirs', [tmpCfgBackupDir])

                    for (cfgFilePath in thirdPartyCfgFilesToBackup) {
                        salt.runSaltProcessStep(pepperEnv, 'I@opencontrail:control or I@opencontrail:collector', 'file.makedirs', [tmpCfgBackupDir + cfgFilePath])
                        salt.runSaltProcessStep(pepperEnv, 'I@opencontrail:control or I@opencontrail:collector', 'file.copy', [cfgFilePath, tmpCfgBackupDir + cfgFilePath, 'recurse=True'])
                    }

                    salt.runSaltProcessStep(pepperEnv, 'I@opencontrail:control', 'pkg.remove', [controlPkgs + ',' + thirdPartyControlPkgsToRemove])
                    salt.runSaltProcessStep(pepperEnv, 'I@opencontrail:collector', 'pkg.remove', [analyticsPkgs + ',' + thirdPartyAnalyticsPkgsToRemove])

                    for (cfgFilePath in thirdPartyCfgFilesToBackup) {
                        salt.runSaltProcessStep(pepperEnv, 'I@opencontrail:control or I@opencontrail:collector', 'file.makedirs', [cfgFilePath])
                        salt.runSaltProcessStep(pepperEnv, 'I@opencontrail:control or I@opencontrail:collector', 'file.copy', [tmpCfgBackupDir + cfgFilePath, cfgFilePath, 'recurse=True'])
                    }
                } catch (Exception er) {
                    common.errorMsg("Opencontrail Controllers backup and cleanup stage has failed.")
                    throw er
                }
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

                stage('Compute nodes health check') {
                    try {
                        salt.enforceState(pepperEnv, targetLiveAll, 'opencontrail.upgrade.verify', true, true)
                    } catch (Exception er) {
                        common.errorMsg("Opencontrail compute nodes health check stage found issues with services. Please take a look at the logs above.")
                        throw er
                    }
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
                        common.errorMsg("Opencontrail component on ${targetLiveSubset} probably failed to be replaced. Please check availability of contrail packages before continuing.")
                        throw er
                    }

                    args = "export DEBIAN_FRONTEND=noninteractive; apt install -o Dpkg::Options::=\"--force-confold\" -o Dpkg::Options::=\"--force-confdef\" ${cmpPkgs}  -y;"
                    check = 'contrail-status'

                    out = salt.runSaltCommand(pepperEnv, 'local', ['expression': targetLiveSubset, 'type': 'compound'], command, null, args, null)
                    salt.printSaltCommandResult(out)

                    try {
                        salt.enforceState(pepperEnv, targetLiveSubset, 'opencontrail')
                    } catch (Exception er) {
                        common.errorMsg("Opencontrail state was executed on ${targetLiveSubset} and failed please fix it manually.")
                    }

                    salt.runSaltProcessStep(pepperEnv, targetLiveSubset, 'cmd.shell', [kernelModuleReloadCmd], null, true)

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
                        common.errorMsg("Opencontrail component on ${targetLiveAll} probably failed to be replaced. Please check availability of contrail packages before continuing.")
                        throw er
                    }

                    args = "export DEBIAN_FRONTEND=noninteractive; apt install -o Dpkg::Options::=\"--force-confold\" -o Dpkg::Options::=\"--force-confdef\" ${cmpPkgs}  -y;"
                    check = 'contrail-status'

                    out = salt.runSaltCommand(pepperEnv, 'local', ['expression': targetLiveAll, 'type': 'compound'], command, null, args, null)
                    salt.printSaltCommandResult(out)

                    try {
                        salt.enforceState(pepperEnv, targetLiveAll, 'opencontrail')
                    } catch (Exception er) {
                        common.errorMsg("Opencontrail state was executed on ${targetLiveAll} and failed please fix it manually.")
                    }

                    salt.runSaltProcessStep(pepperEnv, targetLiveAll, 'cmd.shell', [kernelModuleReloadCmd], null, true)
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

                salt.runSaltProcessStep(pepperEnv, 'I@opencontrail:database or I@neutron:server or I@horizon:server', 'saltutil.refresh_pillar', [], null, true)
                salt.enforceState(pepperEnv, 'I@opencontrail:database or I@neutron:server or I@horizon:server', 'linux.system.repo')
                salt.runSaltProcessStep(pepperEnv, 'I@opencontrail:collector', 'cmd.shell', ['cd /etc/docker/compose/opencontrail/; docker-compose down'], null, true)

                salt.runSaltProcessStep(pepperEnv, 'I@opencontrail:collector', 'state.sls', ['opencontrail', 'exclude=opencontrail.client'])
                salt.runSaltProcessStep(pepperEnv, 'I@opencontrail:collector', 'state.sls', ['opencontrail.client'])

                salt.runSaltProcessStep(pepperEnv, 'I@opencontrail:control:role:secondary', 'cmd.shell', ['cd /etc/docker/compose/opencontrail/; docker-compose down'], null, true)
                for (service in config4Services) {
                    salt.runSaltProcessStep(pepperEnv, 'I@opencontrail:control:role:primary', 'cmd.shell', ["doctrail controller systemctl stop ${service}"], null, true)
                }
                salt.runSaltProcessStep(pepperEnv, 'I@opencontrail:control:role:secondary', 'state.sls', ['opencontrail', 'exclude=opencontrail.client'])

                check = 'contrail-status'
                runCommonCommands('I@opencontrail:control:role:secondary', command, args, check, salt, pepperEnv)

                sleep(120)

                salt.runSaltProcessStep(pepperEnv, 'I@opencontrail:control:role:primary', 'cmd.shell', ['cd /etc/docker/compose/opencontrail/; docker-compose down'], null, true)
                salt.runSaltProcessStep(pepperEnv, 'I@opencontrail:control:role:primary', 'state.sls', ['opencontrail', 'exclude=opencontrail.client'])

                salt.runSaltProcessStep(pepperEnv, 'I@neutron:server', 'service.stop', ['neutron-server'])
                salt.runSaltProcessStep(pepperEnv, 'I@neutron:server', 'pkg.remove', [neutronServerPkgs])
                salt.runSaltProcessStep(pepperEnv, 'I@neutron:server', 'pkg.install', [neutronServerPkgs])
                salt.runSaltProcessStep(pepperEnv, 'I@horizon:server', 'pkg.remove', [dashboardPanelPkg])
                salt.runSaltProcessStep(pepperEnv, 'I@horizon:server', 'pkg.install', [dashboardPanelPkg])
                salt.runSaltProcessStep(pepperEnv, 'I@neutron:server', 'service.start', ['neutron-server'])

                salt.enforceState(pepperEnv, 'I@horizon:server', 'horizon')
                for (service in (controlServices + thirdPartyServicesToDisable)) {
                    salt.runSaltProcessStep(pepperEnv, 'I@opencontrail:control', 'service.enable', [service])
                }
                for (service in (analyticsServices + thirdPartyServicesToDisable)) {
                    salt.runSaltProcessStep(pepperEnv, 'I@opencontrail:collector', 'service.enable', [service])
                }
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
                        common.errorMsg("Opencontrail component on ${targetLiveSubset} probably failed to be replaced. Please check availability of contrail packages before continuing.")
                        throw er
                    }

                    args = "export DEBIAN_FRONTEND=noninteractive; apt install --allow-downgrades -o Dpkg::Options::=\"--force-confold\" -o Dpkg::Options::=\"--force-confdef\" ${cmpPkgs}  -y;"
                    check = 'contrail-status'

                    out = salt.runSaltCommand(pepperEnv, 'local', ['expression': targetLiveSubset, 'type': 'compound'], command, null, args, null)
                    salt.printSaltCommandResult(out)

                    try {
                        salt.enforceState(pepperEnv, targetLiveSubset, 'opencontrail')
                    } catch (Exception er) {
                        common.errorMsg("Opencontrail state was executed on ${targetLiveSubset} and failed please fix it manually.")
                    }

                    salt.runSaltProcessStep(pepperEnv, targetLiveSubset, 'cmd.shell', [kernelModuleReloadCmd], null, true)
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
                        common.errorMsg("Opencontrail component on ${targetLiveAll} probably failed to be replaced. Please check availability of contrail packages before continuing.")
                        throw er
                    }

                    args = "export DEBIAN_FRONTEND=noninteractive; apt install --allow-downgrades -o Dpkg::Options::=\"--force-confold\" -o Dpkg::Options::=\"--force-confdef\" ${cmpPkgs}  -y;"
                    check = 'contrail-status'

                    out = salt.runSaltCommand(pepperEnv, 'local', ['expression': targetLiveAll, 'type': 'compound'], command, null, args, null)
                    salt.printSaltCommandResult(out)

                    try {
                        salt.enforceState(pepperEnv, targetLiveAll, 'opencontrail')
                    } catch (Exception er) {
                        common.errorMsg("Opencontrail state was executed on ${targetLiveAll} and failed please fix it manually.")
                    }

                    salt.runSaltProcessStep(pepperEnv, targetLiveAll, 'cmd.shell', [kernelModuleReloadCmd], null, true)
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
