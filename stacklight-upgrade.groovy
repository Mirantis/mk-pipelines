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
        salt.runSaltProcessStep(master, "${target}", 'saltutil.refresh_pillar', [], null, true, 5)
        salt.enforceState(master, "${target}", 'linux.system.repo', true)
        common.infoMsg("Upgrade ${service} package")
        try {
            salt.runSaltProcessStep(master, "${target}", command, ["apt-get install --only-upgrade ${pckg}"], null, true)
        } catch (Exception er) {
            errorOccured = true
            common.errorMsg("[ERROR] ${pckg} package was not upgraded.")
            return
        }
        common.infoMsg("Run ${state} state on ${target} nodes")
        try {
            salt.enforceState(master, "${target}", ["${state}"], true)
        } catch (Exception er) {
            errorOccured = true
            common.errorMsg("[ERROR] ${state} state was executed and failed. Please fix it manually.")
        }
        common.infoMsg("Check ${service} service status on the target nodes")
        salt.runSaltProcessStep(master, "${target}", "service.status", ["${service}"], null, true)
        return
    }
}

def upgrade_es_kibana(master) {
    stage('Upgrade elasticsearch') {
        try {
            common.infoMsg('Upgrade the Elasticsearch package')
            salt.runSaltProcessStep(master, 'I@elasticsearch:server', command, ["systemctl stop elasticsearch"], null, true)
            salt.runSaltProcessStep(master, 'I@elasticsearch:server', command, ["apt-get --only-upgrade install elasticsearch"], null, true)
            salt.runSaltProcessStep(master, 'I@elasticsearch:server', command, ["systemctl daemon-reload"], null, true)
            salt.runSaltProcessStep(master, 'I@elasticsearch:server', command, ["systemctl start elasticsearch"], null, true)
            salt.runSaltProcessStep(master, '*', 'saltutil.sync_all', [], null, true)
        } catch (Exception er) {
            errorOccured = true
            common.errorMsg("[ERROR] Elasticsearch upgrade failed. Please fix it manually.")
            return
        }
        common.infoMsg('Verify that the Elasticsearch cluster status is green')
        try {
            def retries_wait = 20
            def retries = 15
            def elasticsearch_vip
            def pillar = salt.getPillar(master, "I@elasticsearch:client", 'elasticsearch:client:server:host')
            if(!pillar['return'].isEmpty()) {
                elasticsearch_vip = pillar['return'][0].values()[0]
            } else {
                errorOccured = true
                common.errorMsg('[ERROR] Elasticsearch VIP address could not be retrieved')
            }
            pillar = salt.getPillar(master, "I@elasticsearch:client", 'elasticsearch:client:server:port')
            def elasticsearch_port
            if(!pillar['return'].isEmpty()) {
                elasticsearch_port = pillar['return'][0].values()[0]
            } else {
                errorOccured = true
                common.errorMsg('[ERROR] Elasticsearch VIP port could not be retrieved')
            }
            common.retry(retries,retries_wait) {
                common.infoMsg('Waiting for Elasticsearch to become green..')
                salt.cmdRun(master, "I@elasticsearch:client", "curl -sf ${elasticsearch_vip}:${elasticsearch_port}/_cat/health | awk '{print \$4}' | grep green")
            }
        } catch (Exception er) {
            errorOccured = true
            common.errorMsg("[ERROR] Elasticsearch cluster status is not \'green\'. Please fix it manually.")
            return
        }
    }
    stage('Upgrade kibana') {
        try {
            common.infoMsg('Upgrade the Kibana package')
            salt.runSaltProcessStep(master, 'I@kibana:server', command, ["systemctl stop kibana"], null, true)
            salt.runSaltProcessStep(master, 'I@kibana:server', command, ["apt-get --only-upgrade install kibana"], null, true)
            salt.runSaltProcessStep(master, 'I@kibana:server', command, ["systemctl start kibana"], null, true)
        } catch (Exception er) {
            errorOccured = true
            common.errorMsg("[ERROR] Kibana upgrade failed. Please fix it manually.")
            return
        }

        common.infoMsg("Check kibana status on the target nodes")
        salt.runSaltProcessStep(master, "I@kibana:server", "service.status", ["kibana"], null, true)
        return
    }
}
timeout(time: 12, unit: 'HOURS') {
    node("python") {

        stage('Setup virtualenv for Pepper') {
            python.setupPepperVirtualenv(pepperEnv, SALT_MASTER_URL, SALT_MASTER_CREDENTIALS)
        }

        if (STAGE_UPGRADE_SYSTEM_PART.toBoolean() == true && !errorOccured) {
            upgrade(pepperEnv, "I@telegraf:agent or I@telegraf:remote_agent", "telegraf", "telegraf", "telegraf")
            upgrade(pepperEnv, "I@fluentd:agent", "td-agent", "td-agent td-agent-additional-plugins", "fluentd")
            if (salt.testTarget(pepperEnv, "I@prometheus:relay")) {
                upgrade(pepperEnv, "I@prometheus:relay", "prometheus-relay", "prometheus-relay", "prometheus")
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
                    salt.enforceState(pepperEnv, 'I@docker:swarm and I@prometheus:server', 'prometheus')
                    common.infoMsg('Disable and remove the previous version of Grafana')
                    salt.runSaltProcessStep(pepperEnv, 'I@docker:swarm:role:master and I@prometheus:server', command, ["docker stack rm dashboard"], null, true)
                    common.infoMsg('Start the monitoring services')
                    salt.enforceState(pepperEnv, 'I@docker:swarm:role:master and I@prometheus:server', 'docker')
                    salt.runSaltProcessStep(pepperEnv, '*', 'saltutil.sync_all', [], null, true)
                    common.infoMsg('Refresh the Grafana dashboards')
                    salt.enforceState(pepperEnv, 'I@grafana:client', 'grafana.client')
                } catch (Exception er) {
                    errorOccured = true
                    common.errorMsg("[ERROR] Upgrade of docker components failed. Please fix it manually.")
                    return
                }
            }
        }
    }
}
