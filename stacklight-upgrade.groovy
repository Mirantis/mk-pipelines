/**
 *
 * Upgrade Stacklight packages and components
 *
 * Requred parameters:
 *  SALT_MASTER_URL                 URL of Salt master
 *  SALT_MASTER_CREDENTIALS         Credentials to the Salt API
 *
 *  STAGE_UPGRADE_SYSTEM_PART           Set to True if upgrade of system part (telegraf, fluentd, prometheus-relay) is desired
 *  STAGE_UPGRADE_ES_KIBANA             Set to True if Elasticsearch and Kibana upgrade is desired
 *  STAGE_UPGRADE_DOCKER_COMPONENTS     Set to True if upgrade for components running in Docker Swarm is desired
 *
 */

common = new com.mirantis.mk.Common()
salt = new com.mirantis.mk.Salt()
python = new com.mirantis.mk.Python()
command = 'cmd.run'
pepperEnv = "pepperEnv"
errorOccured = false

def upgrade(master, target, service, pckg, state) {
    stage("Upgrade ${service}") {
        salt.runSaltProcessStep(master, "${target}", 'saltutil.refresh_pillar', [], null, true)
        salt.enforceState([saltId: master, target: "${target}", state: 'linux.system.repo', output: true, failOnError: true])
        common.infoMsg("Upgrade ${service} package(s)")
        try {
            salt.runSaltProcessStep(master, "${target}", command, ["apt-get install -y -o Dpkg::Options::=\"--force-confold\" ${pckg}"], null, true)
        } catch (Exception er) {
            errorOccured = true
            common.errorMsg("[ERROR] ${pckg} package(s) was not upgraded.")
            throw er
        }
        common.infoMsg("Run ${state} state on ${target} nodes")
        try {
            salt.enforceState([saltId: master, target: "${target}", state: ["${state}"], output: true, failOnError: true])
        } catch (Exception er) {
            errorOccured = true
            common.errorMsg("[ERROR] ${state} state was executed and failed. Please fix it manually.")
            throw er
        }
        common.infoMsg("Check ${service} service(s) status on the target nodes")
        for (s in service.split(" ")){
            salt.runSaltProcessStep(master, "${target}", "service.status", "${s}", null, true)
        }
    }
}

def verify_es_is_green(master) {
    common.infoMsg('Verify that the Elasticsearch cluster status is green')
    try {
        def retries_wait = 20
        def retries = 15

        def elasticsearch_vip
        def pillar = salt.getReturnValues(salt.getPillar(master, "I@elasticsearch:client", 'elasticsearch:client:server:host'))
        if(pillar) {
            elasticsearch_vip = pillar
        } else {
            errorOccured = true
            common.errorMsg('[ERROR] Elasticsearch VIP address could not be retrieved')
        }

        pillar = salt.getReturnValues(salt.getPillar(master, "I@elasticsearch:client", 'elasticsearch:client:server:port'))
        def elasticsearch_port
        if(pillar) {
            elasticsearch_port = pillar
        } else {
            errorOccured = true
            common.errorMsg('[ERROR] Elasticsearch VIP port could not be retrieved')
        }

        pillar = salt.getReturnValues(salt.getPillar(master, "I@elasticsearch:client", 'elasticsearch:client:server:scheme'))
        def elasticsearch_scheme
        if(pillar) {
            elasticsearch_scheme = pillar
            common.infoMsg("[INFO] Using elasticsearch scheme: ${elasticsearch_scheme}")
        } else {
            common.infoMsg('[INFO] No pillar with Elasticsearch server scheme, using scheme: http')
            elasticsearch_scheme = "http"
        }

        common.retry(retries,retries_wait) {
            common.infoMsg('Waiting for Elasticsearch to become green..')
            salt.cmdRun(master, "I@elasticsearch:client", "curl -sf ${elasticsearch_vip}:${elasticsearch_port}/_cat/health | awk '{print \$4}' | grep green")
        }
    } catch (Exception er) {
        errorOccured = true
        common.errorMsg("[ERROR] Elasticsearch cluster status is not \'green\'. Please fix it manually.")
        throw er
    }
}

def upgrade_es_kibana(master) {
    def elasticsearch_version
    def es_pillar = salt.getPillar(master, "I@elasticsearch:client", '_param:elasticsearch_version')
    if(!es_pillar['return'].isEmpty()) {
        elasticsearch_version = es_pillar['return'][0].values()[0]
    }
    stage('Upgrade elasticsearch') {
        if (elasticsearch_version == '5') {
            try {
                common.infoMsg('Upgrade the Elasticsearch package')
                salt.runSaltProcessStep(master, 'I@elasticsearch:server', command, ["systemctl stop elasticsearch"], null, true)
                salt.runSaltProcessStep(master, 'I@elasticsearch:server', command, ["apt-get --only-upgrade install elasticsearch"], null, true)
                salt.runSaltProcessStep(master, 'I@elasticsearch:server', command, ["systemctl daemon-reload"], null, true)
                salt.runSaltProcessStep(master, 'I@elasticsearch:server', command, ["systemctl start elasticsearch"], null, true)
                salt.runSaltProcessStep(master, '*', 'saltutil.sync_all', [], null, true)
                verify_es_is_green(master)
            } catch (Exception er) {
                errorOccured = true
                common.errorMsg("[ERROR] Elasticsearch upgrade failed. Please fix it manually.")
                throw er
            }
        } else {
            try {
                salt.runSaltProcessStep(master, "*", 'saltutil.refresh_pillar', [], null, true)
                salt.enforceState([saltId: master, target: "I@elasticsearch:server", state: 'linux.system.repo', output: true, failOnError: true])
                salt.runSaltProcessStep(master, 'I@elasticsearch:client', command, ["apt-get install -y -o Dpkg::Options::=\"--force-confold\" python-elasticsearch"], null, true)
                salt.enforceState([saltId: master, target: "I@elasticsearch:server", state: 'salt.minion', output: true, failOnError: true])
                salt.runSaltProcessStep(master, 'I@elasticsearch:server', command, ["systemctl stop elasticsearch"], null, true)
                salt.runSaltProcessStep(master, 'I@elasticsearch:server', command, ["export ES_PATH_CONF=/etc/elasticsearch; apt-get install -y -o Dpkg::Options::=\"--force-confold\" elasticsearch"], null, true)
                salt.enforceState([saltId: master, target: "I@elasticsearch:server", state: 'elasticsearch.server', output: true, failOnError: true])
                verify_es_is_green(master)
                salt.enforceState([saltId: master, target: "I@elasticsearch:client", state: 'elasticsearch.client.update_index_templates', output: true, failOnError: true])
                salt.enforceState([saltId: master, target: "I@elasticsearch:client", state: 'elasticsearch.client', output: true, failOnError: true])
            } catch (Exception er) {
                errorOccured = true
                common.errorMsg("[ERROR] Elasticsearch upgrade failed. Please fix it manually.")
                throw er
            }
        }
    }
    stage('Upgrade kibana') {
        def kibana_version
        def kibana_pillar = salt.getPillar(master, "I@kibana:client", '_param:kibana_version')
        if(!kibana_pillar['return'].isEmpty()) {
            kibana_version = kibana_pillar['return'][0].values()[0]
        }
        if (kibana_version == '5') {
            try {
                common.infoMsg('Upgrade the Kibana package')
                salt.runSaltProcessStep(master, 'I@kibana:server', command, ["systemctl stop kibana"], null, true)
                salt.runSaltProcessStep(master, 'I@kibana:server', command, ["apt-get --only-upgrade install kibana"], null, true)
                salt.runSaltProcessStep(master, 'I@kibana:server', command, ["systemctl start kibana"], null, true)
            } catch (Exception er) {
                errorOccured = true
                common.errorMsg("[ERROR] Kibana upgrade failed. Please fix it manually.")
                throw er
            }
        } else {
            try {
                salt.runSaltProcessStep(master, 'I@kibana:server', command, ["systemctl stop kibana"], null, true)
                salt.enforceStateWithExclude([saltId: master, target: "I@kibana:server", state: "kibana.server", excludedStates: "[{'id': 'kibana_service'}]"])
                salt.runSaltProcessStep(master, 'I@kibana:server', command, ["apt-get install -y -o Dpkg::Options::=\"--force-confold\" kibana"], null, true)
                salt.enforceState([saltId: master, target: "I@kibana:server", state: 'kibana.server', output: true, failOnError: true])
                salt.enforceState([saltId: master, target: "I@kibana:client", state: 'kibana.client', output: true, failOnError: true])
            } catch (Exception er) {
                errorOccured = true
                common.errorMsg("[ERROR] Kibana upgrade failed. Please fix it manually.")
                throw er
            }
        }

        common.infoMsg("Check kibana status on the target nodes")
        salt.runSaltProcessStep(master, "I@kibana:server", "service.status", ["kibana"], null, true)
    }
}
timeout(time: 12, unit: 'HOURS') {
    node("python") {

        stage('Setup virtualenv for Pepper') {
            python.setupPepperVirtualenv(pepperEnv, SALT_MASTER_URL, SALT_MASTER_CREDENTIALS)
        }

        stage('Update grains and mine') {
            salt.enforceState([saltId: pepperEnv, target: '*', state: 'salt.minion.grains'])
            salt.runSaltProcessStep(pepperEnv, '*', 'saltutil.refresh_modules')
            salt.runSaltProcessStep(pepperEnv, '*', 'mine.update')
            sleep(30)
        }

        if (salt.testTarget(pepperEnv, "I@ceph:mon")) {
            stage('Enable Ceph prometheus plugin') {
                salt.enforceState([saltId: pepperEnv, target: 'I@ceph:mon', state: "ceph.mgr", output: true, failOnError: true])
            }
        }

        if (STAGE_UPGRADE_SYSTEM_PART.toBoolean() == true && !errorOccured) {
            upgrade(pepperEnv, "I@telegraf:agent or I@telegraf:remote_agent", "telegraf", "telegraf", "telegraf")
            upgrade(pepperEnv, "I@fluentd:agent", "td-agent", "td-agent td-agent-additional-plugins", "fluentd")
            if (salt.testTarget(pepperEnv, "I@prometheus:relay")) {
                upgrade(pepperEnv, "I@prometheus:relay", "prometheus prometheus-relay", "prometheus-bin prometheus-relay", "prometheus")
                salt.runSaltProcessStep(pepperEnv, "I@prometheus:relay", "service.restart", "prometheus", null, true)
            }
            if (salt.testTarget(pepperEnv, "I@prometheus:exporters:libvirt")) {
                upgrade(pepperEnv, "I@prometheus:exporters:libvirt", "libvirt-exporter", "libvirt-exporter", "prometheus")
            }
            if (salt.testTarget(pepperEnv, "I@prometheus:exporters:jmx")) {
                upgrade(pepperEnv, "I@prometheus:exporters:jmx", "jmx-exporter", "jmx-exporter", "prometheus")
            }
        }

        if (STAGE_UPGRADE_ES_KIBANA.toBoolean() == true && !errorOccured) {
            upgrade_es_kibana(pepperEnv)
        }

        if (STAGE_UPGRADE_DOCKER_COMPONENTS.toBoolean() == true && !errorOccured) {
            stage('Upgrade docker components') {
                try {
                    common.infoMsg('Disable and remove the previous versions of monitoring services')
                    salt.runSaltProcessStep(pepperEnv, 'I@docker:swarm:role:master and I@prometheus:server', command, ["docker stack rm monitoring"], null, true)
                    common.infoMsg('Rebuild the Prometheus configuration')
                    salt.enforceState([saltId: pepperEnv, target: 'I@docker:swarm and I@prometheus:server', state: 'prometheus'])
                    common.infoMsg('Disable and remove the previous version of Grafana')
                    salt.runSaltProcessStep(pepperEnv, 'I@docker:swarm:role:master and I@prometheus:server', command, ["docker stack rm dashboard"], null, true)
                    common.infoMsg('Start the monitoring services')
                    salt.enforceState([saltId: pepperEnv, target: 'I@docker:swarm:role:master and I@prometheus:server', state: 'docker'])
                    salt.runSaltProcessStep(pepperEnv, '*', 'saltutil.sync_all', [], null, true)
                    common.infoMsg("Waiting grafana service to start")
                    sleep(120)

                    common.infoMsg('Refresh the Grafana dashboards')
                    salt.enforceState([saltId: pepperEnv, target: 'I@grafana:client', state: 'grafana.client', retries: 10, retries_wait: 30])
                } catch (Exception er) {
                    errorOccured = true
                    common.errorMsg("[ERROR] Upgrade of docker components failed. Please fix it manually.")
                    throw er
                }
            }
        }
    }
}
