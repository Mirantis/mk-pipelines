/**
 * Update pipeline for OpenContrail 4X versions
 *
 * Expected parameters:
 *   SALT_MASTER_CREDENTIALS        Credentials to the Salt API.
 *   SALT_MASTER_URL                Full Salt API address [http://10.10.10.1:8000].
 *   STAGE_CONTROLLERS_UPDATE       Run update on OpenContrail controller and analytic nodes (bool)
 *   STAGE_COMPUTES_UPDATE          Run update OpenContrail components on compute nodes (bool)
 *
 **/

common = new com.mirantis.mk.Common()
salt = new com.mirantis.mk.Salt()
python = new com.mirantis.mk.Python()

def pepperEnv = "pepperEnv"
def supportedOcTargetVersions = ['4.0', '4.1']
def neutronServerPkgs = 'neutron-plugin-contrail,contrail-heat,python-contrail'
def config4Services = ['zookeeper', 'contrail-webui-middleware', 'contrail-webui', 'contrail-api', 'contrail-schema', 'contrail-svc-monitor', 'contrail-device-manager', 'contrail-config-nodemgr', 'contrail-database']
def dashboardPanelPkg = 'openstack-dashboard-contrail-panels'
def targetOcVersion

def cmpMinions
def cmpMinionsFirstSubset
def cmpMinionsSecondSubset
def cmpTargetAll
def cmpTargetFirstSubset
def cmpTargetSecondSubset

def checkContrailServices(pepperEnv, oc_version, target) {

    def checkCmd

    if (oc_version.startsWith('4')) {

        checkCmd = "doctrail all contrail-status | grep -v == | grep -v FOR | grep -v \\* | grep -v \'disabled on boot\' | grep -v nodemgr | grep -v active | grep -v backup | grep -v -F /var/crashes/"

        if (oc_version == '4.1') {
            def targetMinions = salt.getMinions(pepperEnv, target)
            def collectorMinionsInTarget = targetMinions.intersect(salt.getMinions(pepperEnv, 'I@opencontrail:collector'))

            if (collectorMinionsInTarget.size() != 0) {
                def cassandraConfigYaml = readYaml text: salt.getFileContent(pepperEnv, 'I@opencontrail:control:role:primary', '/etc/cassandra/cassandra.yaml')

                def currentCassandraNativeTransportPort = cassandraConfigYaml['native_transport_port'] ?: "9042"
                def currentCassandraRpcPort = cassandraConfigYaml['rpc_port'] ?: "9160"

                def cassandraNativeTransportPort = getValueForPillarKey(pepperEnv, "I@opencontrail:control:role:primary", "opencontrail:database:bind:port_configdb")
                def cassandraCassandraRpcPort = getValueForPillarKey(pepperEnv, "I@opencontrail:control:role:primary", "opencontrail:database:bind:rpc_port_configdb")

                if (currentCassandraNativeTransportPort != cassandraNativeTransportPort) {
                    checkCmd += ' | grep -v \'contrail-collector.*(Database:Cassandra connection down)\''
                }

                if (currentCassandraRpcPort != cassandraCassandraRpcPort) {
                    checkCmd += ' | grep -v \'contrail-alarm-gen.*(Database:Cassandra\\[\\] connection down)\''
                }
            }
        }

    } else {
        checkCmd = "contrail-status | grep -v == | grep -v FOR | grep -v \'disabled on boot\' | grep -v nodemgr | grep -v active | grep -v backup | grep -v -F /var/crashes/"
    }

    salt.commandStatus(pepperEnv, target, checkCmd, null, false, true, null, true, 500)
}

def getValueForPillarKey(pepperEnv, target, pillarKey) {
    def out = salt.getReturnValues(salt.getPillar(pepperEnv, target, pillarKey))
    if (out == '') {
        throw new Exception("Cannot get value for ${pillarKey} key on ${target} target")
    }
    return out.toString()
}

def cmpNodesUpdate(pepperEnv, target) {

    def cmpPkgs = 'contrail-lib contrail-nodemgr contrail-utils contrail-vrouter-agent contrail-vrouter-utils python-contrail python-contrail-vrouter-api python-opencontrail-vrouter-netns contrail-vrouter-dkms'
    def aptCmd = "export DEBIAN_FRONTEND=noninteractive; apt install -o Dpkg::Options::=\"--force-confold\" ${cmpPkgs} -y;"
    def kernelModuleReloadCmd = 'service contrail-vrouter-agent stop; service contrail-vrouter-nodemgr stop; rmmod vrouter; sync && echo 3 > /proc/sys/vm/drop_caches && echo 1 > /proc/sys/vm/compact_memory; service contrail-vrouter-agent start; service contrail-vrouter-nodemgr start'
    def out

    try {
        salt.runSaltProcessStep(pepperEnv, target, 'saltutil.refresh_pillar', [], null, true)
        salt.runSaltProcessStep(pepperEnv, target, 'saltutil.sync_all', [], null, true)
        salt.runSaltProcessStep(pepperEnv, target, 'file.remove', ["/etc/apt/sources.list.d/mcp_opencontrail.list"], null, true)
        salt.enforceState(pepperEnv, target, 'linux.system.repo')
    } catch (Exception er) {
        common.errorMsg("Opencontrail component on ${target} probably failed to be replaced. Please check availability of contrail packages before continuing.")
        throw er
    }

    out = salt.runSaltCommand(pepperEnv, 'local', ['expression': target, 'type': 'compound'], 'cmd.shell', null, aptCmd, null)
    salt.printSaltCommandResult(out)

    try {
        salt.enforceState(pepperEnv, target, 'opencontrail')
    } catch (Exception er) {
        common.errorMsg("Opencontrail state was executed on ${target} and failed please fix it manually.")
    }

    salt.runSaltProcessStep(pepperEnv, target, 'cmd.shell', [kernelModuleReloadCmd], null, true)
    salt.commandStatus(pepperEnv, target, 'contrail-status | grep -v == | grep -v active | grep -v -F /var/crashes/', null, false)
    out = salt.runSaltCommand(pepperEnv, 'local', ['expression': target, 'type': 'compound'], 'cmd.shell', null, "contrail-status", null)
    salt.printSaltCommandResult(out)
}

timeout(time: 12, unit: 'HOURS') {
    node() {

        stage('Setup virtualenv for Pepper') {
            python.setupPepperVirtualenv(pepperEnv, SALT_MASTER_URL, SALT_MASTER_CREDENTIALS)
        }

        if (STAGE_CONTROLLERS_UPDATE.toBoolean() == true) {

            stage('Sync Salt data') {

                // Sync data on minions
                salt.runSaltProcessStep(pepperEnv, 'I@keystone:server:role:primary or I@opencontrail:database or I@neutron:server or I@horizon:server', 'saltutil.refresh_pillar', [], null, true)
                salt.runSaltProcessStep(pepperEnv, 'I@keystone:server:role:primary or I@opencontrail:database or I@neutron:server or I@horizon:server', 'saltutil.sync_all', [], null, true)
            }

            stage('Verify OpenContrail version compatibility') {

                // Verify specified target OpenContrail version before update
                targetOcVersion = getValueForPillarKey(pepperEnv, "I@opencontrail:control:role:primary", "_param:opencontrail_version")
                if (!supportedOcTargetVersions.contains(targetOcVersion)) {
                    throw new Exception("Specified OpenContrail version ${targetOcVersion} is not supported by update pipeline. Supported versions: ${supportedOcTargetVersions}")
                }
            }

            stage('Opencontrail controllers health check') {
                try {
                    salt.enforceState(pepperEnv, 'I@opencontrail:control or I@opencontrail:collector', 'opencontrail.upgrade.verify', true, true)
                } catch (Exception er) {
                    common.errorMsg("OpenContrail controllers health check stage found issues with services. Please take a look at the logs above.")
                    throw er
                }
            }

            stage('Update system repositories') {
                try {
                    salt.runSaltProcessStep(pepperEnv, 'I@opencontrail:control or I@opencontrail:collector', 'file.remove', ["/etc/apt/sources.list.d/mcp_opencontrail.list"], null, true)
                    salt.enforceState(pepperEnv, 'I@opencontrail:control or I@opencontrail:collector or I@neutron:server or I@horizon:server', 'linux.system.repo')

                } catch (Exception er) {
                    common.errorMsg("System repositories failed to be updated on I@opencontrail:control, I@opencontrail:collector, I@neutron:server or I@horizon:server nodes.")
                    throw er
                }
            }

            stage('OpenContrail controllers update') {

                // Make sure that dedicated opencontrail user is created
                salt.enforceState(pepperEnv, 'I@keystone:server:role:primary', 'keystone.client.server')

                // Stop neutron-server to prevent creation of new objects in contrail
                salt.runSaltProcessStep(pepperEnv, 'I@neutron:server', 'service.stop', ['neutron-server'])

                // Backup Zookeeper data
                salt.enforceState(pepperEnv, 'I@zookeeper:backup:server', 'zookeeper.backup')
                salt.enforceState(pepperEnv, 'I@zookeeper:backup:client', 'zookeeper.backup')

                try {
                    salt.cmdRun(pepperEnv, 'I@opencontrail:control', "su root -c '/usr/local/bin/zookeeper-backup-runner.sh'")
                } catch (Exception er) {
                    common.errorMsg('Zookeeper failed to backup. Please fix it before continuing.')
                    throw er
                }

                // Backup Cassandra DB
                salt.enforceState(pepperEnv, 'I@cassandra:backup:server', 'cassandra.backup')
                salt.enforceState(pepperEnv, 'I@cassandra:backup:client', 'cassandra.backup')

                try {
                    salt.cmdRun(pepperEnv, 'I@cassandra:backup:client', "su root -c '/usr/local/bin/cassandra-backup-runner-call.sh'")
                } catch (Exception er) {
                    common.errorMsg('Cassandra failed to backup. Please fix it before continuing.')
                    throw er
                }

                try {
                    // Get docker images info
                    controllerImage = getValueForPillarKey(pepperEnv, "I@opencontrail:control:role:primary", "docker:client:compose:opencontrail:service:controller:image")
                    analyticsImage = getValueForPillarKey(pepperEnv, "I@opencontrail:collector:role:primary", "docker:client:compose:opencontrail:service:analytics:image")
                    analyticsdbImage = getValueForPillarKey(pepperEnv, "I@opencontrail:collector:role:primary", "docker:client:compose:opencontrail:service:analyticsdb:image")

                    // Pull new docker images
                    salt.runSaltProcessStep(pepperEnv, 'I@opencontrail:control', 'dockerng.pull', [controllerImage])
                    salt.runSaltProcessStep(pepperEnv, 'I@opencontrail:collector', 'dockerng.pull', [analyticsImage])
                    salt.runSaltProcessStep(pepperEnv, 'I@opencontrail:collector', 'dockerng.pull', [analyticsdbImage])

                } catch (Exception er) {
                    common.errorMsg("OpenContrail docker images failed be upgraded.")
                    throw er
                }

                try {
                    salt.runSaltProcessStep(pepperEnv, 'I@opencontrail:collector', 'cmd.shell', ['cd /etc/docker/compose/opencontrail/; docker-compose down'], null, true)

                    salt.runSaltProcessStep(pepperEnv, 'I@opencontrail:collector', 'state.sls', ['opencontrail', 'exclude=opencontrail.client'])
                    salt.runSaltProcessStep(pepperEnv, 'I@opencontrail:collector', 'state.sls', ['opencontrail.client'])

                    salt.enforceState(pepperEnv, 'I@opencontrail:collector', 'docker.client')
                    if (targetOcVersion == '4.1') {
                        sleep(15)
                        salt.runSaltProcessStep(pepperEnv, 'I@opencontrail:collector', 'cmd.shell', ["doctrail analyticsdb systemctl restart confluent-kafka"], null, true)
                    }
                    checkContrailServices(pepperEnv, targetOcVersion, 'I@opencontrail:collector')
                } catch (Exception er) {
                    common.errorMsg("OpenContrail Analytic nodes failed to be upgraded.")
                    throw er
                }

                try {
                    salt.runSaltProcessStep(pepperEnv, 'I@opencontrail:control:role:secondary', 'cmd.shell', ['cd /etc/docker/compose/opencontrail/; docker-compose down'], null, true)
                    for (service in config4Services) {
                        salt.runSaltProcessStep(pepperEnv, 'I@opencontrail:control:role:primary', 'cmd.shell', ["doctrail controller systemctl stop ${service}"], null, true)
                    }
                    salt.runSaltProcessStep(pepperEnv, 'I@opencontrail:control:role:secondary', 'state.sls', ['opencontrail', 'exclude=opencontrail.client'])

                    salt.enforceState(pepperEnv, 'I@opencontrail:control:role:secondary', 'docker.client')
                    checkContrailServices(pepperEnv, targetOcVersion, 'I@opencontrail:control:role:secondary')

                    sleep(120)

                    salt.runSaltProcessStep(pepperEnv, 'I@opencontrail:control:role:primary', 'cmd.shell', ['cd /etc/docker/compose/opencontrail/; docker-compose down'], null, true)
                    salt.runSaltProcessStep(pepperEnv, 'I@opencontrail:control:role:primary', 'state.sls', ['opencontrail', 'exclude=opencontrail.client'])

                    salt.enforceState(pepperEnv, 'I@opencontrail:control:role:primary', 'docker.client')
                    checkContrailServices(pepperEnv, targetOcVersion, 'I@opencontrail:control:role:primary')
                } catch (Exception er) {
                    common.errorMsg("OpenContrail Controller nodes failed to be upgraded.")
                    throw er
                }

                // Run opencontrail.client state once contrail-api is ready to service requests from clients
                salt.runSaltProcessStep(pepperEnv, 'I@opencontrail:control or I@opencontrail:collector', 'state.sls', ['opencontrail.client'])

                try {
                    salt.runSaltProcessStep(pepperEnv, 'I@neutron:server', 'pkg.install', [neutronServerPkgs])
                    salt.runSaltProcessStep(pepperEnv, 'I@horizon:server', 'pkg.install', [dashboardPanelPkg])
                    salt.runSaltProcessStep(pepperEnv, 'I@neutron:server', 'service.start', ['neutron-server'])
                    salt.enforceState(pepperEnv, 'I@horizon:server', 'horizon')
                } catch (Exception er) {
                    common.errorMsg("Update of packages on neutron and horizon nodes has been failed")
                    throw er
                }
            }
        }

        if (STAGE_COMPUTES_UPDATE.toBoolean() == true) {

            try {
                stage('List targeted compute servers') {
                    cmpMinions = salt.getMinions(pepperEnv, COMPUTE_TARGET_SERVERS)
                    cmpMinionsFirstSubset = cmpMinions[0..<Integer.valueOf(COMPUTE_TARGET_SUBSET_LIVE)]
                    cmpMinionsSecondSubset = cmpMinions - cmpMinionsFirstSubset

                    if (cmpMinions.isEmpty()) {
                        throw new Exception("No minions were found by specified target")
                    }

                    common.infoMsg("Found nodes: ${cmpMinions}")
                    common.infoMsg("Selected sample nodes: ${cmpMinionsFirstSubset}")

                    cmpTargetAll = cmpMinions.join(' or ')
                    cmpTargetFirstSubset = cmpMinionsFirstSubset.join(' or ')
                    cmpTargetSecondSubset = cmpMinionsSecondSubset.join(' or ')
                }

                stage('Compute nodes health check') {
                    try {
                        salt.enforceState(pepperEnv, cmpTargetAll, 'opencontrail.upgrade.verify', true, true)
                    } catch (Exception er) {
                        common.errorMsg("Opencontrail compute nodes health check stage found issues with services. Please take a look at the logs above.")
                        throw er
                    }
                }

                stage('Confirm update on sample nodes') {
                    input message: "Do you want to continue with the Opencontrail components update on compute sample nodes? ${cmpTargetFirstSubset}"
                }

                stage("Opencontrail compute update on sample nodes") {

                    cmpNodesUpdate(pepperEnv, cmpTargetFirstSubset)
                }

                stage('Confirm update on all remaining target nodes') {

                    input message: "Do you want to continue with the Opencontrail components update on all targeted compute nodes? Node list: ${cmpTargetSecondSubset}"
                }

                stage("Opencontrail compute update on all targeted nodes") {

                    cmpNodesUpdate(pepperEnv, cmpTargetSecondSubset)
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
