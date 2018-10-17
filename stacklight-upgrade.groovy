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

def common = new com.mirantis.mk.Common()
def salt = new com.mirantis.mk.Salt()
def python = new com.mirantis.mk.Python()

def pepperEnv = "pepperEnv"
def targetLiveSubset
def targetLiveAll
def minions
def result
def args
def commandKwargs
def probe = 1
def errorOccured = false

def upgrade(master, target, service, pckg, state) {
    def common = new com.mirantis.mk.Common()
    def salt = new com.mirantis.mk.Salt()
    def command = 'cmd.run'
    stage("Change ${target} repos") {
        salt.runSaltProcessStep(master, "${target}", 'saltutil.refresh_pillar', [], null, true, 5)
        salt.enforceState(master, "${target}", 'linux.system.repo', true)
    }
    stage("Update ${pckg} package") {
        common.infoMsg("Upgrade ${service} package")
        try {
            salt.runSaltProcessStep(master, "${target}", command, ["apt-get install --only-upgrade ${pckg}"], null, true)
        } catch (Exception er) {
            errorOccured = true
            common.errorMsg("${pckg} package is not upgraded.")
            return
        }
    }
    stage("Run ${state} state on ${target} nodes") {
        try {
            salt.enforceState(master, "${target}", ["${state}"], true)
        } catch (Exception er) {
            errorOccured = true
            common.errorMsg("${state} state was executed and failed. Please fix it manually.")
        }
    }
    out = salt.runSaltCommand(master, 'local', ['expression': "${target}", 'type': 'compound'], command, null, "systemctl status ${service}.service", null)
    salt.printSaltCommandResult(out)

    common.warningMsg("Please check \'systemctl status ${service}.service\' on ${target} nodes if ${service} is running.")
    return
}

def upgrade_es_kibana(master) {
    def common = new com.mirantis.mk.Common()
    def salt = new com.mirantis.mk.Salt()
    def command = 'cmd.run'
    stage('Elasticsearch upgrade') {
        try {
            salt.runSaltProcessStep(master, 'I@elasticsearch:server', command, ["systemctl stop elasticsearch"], null, true)
            salt.runSaltProcessStep(master, 'I@elasticsearch:server', command, ["apt-get --only-upgrade install elasticsearch"], null, true)
            salt.runSaltProcessStep(master, 'I@elasticsearch:server', command, ["systemctl daemon-reload"], null, true)
            salt.runSaltProcessStep(master, 'I@elasticsearch:server', command, ["systemctl start elasticsearch"], null, true)
            salt.runSaltProcessStep(master, '*', 'saltutil.sync_all', [], null, true)
        } catch (Exception er) {
            errorOccured = true
            common.errorMsg("Elasticsearch upgrade failed. Please fix it manually.")
            return
        }
    }
    stage('Verify that the Elasticsearch cluster status is green') {
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
            common.errorMsg("Elasticsearch cluster status is not \'green\'. Please fix it manually.")
            return
        }
    }
    stage('Kibana upgrade') {
        try {
            salt.runSaltProcessStep(master, 'I@kibana:server', command, ["systemctl stop kibana"], null, true)
            salt.runSaltProcessStep(master, 'I@kibana:server', command, ["apt-get --only-upgrade install kibana"], null, true)
            salt.runSaltProcessStep(master, 'I@kibana:server', command, ["systemctl start kibana"], null, true)
        } catch (Exception er) {
            errorOccured = true
            common.errorMsg("Kibana upgrade failed. Please fix it manually.")
            return
        }
        out = salt.runSaltCommand(master, 'local', ['expression': 'I@kibana:server', 'type': 'compound'], command, null, 'systemctl status kibana.service', null)
        salt.printSaltCommandResult(out)

        common.warningMsg('Please check if kibana service is running.')
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
            upgrade(pepperEnv, "I@fluentd:agent", "td-agent", "td-agent", "fluentd")
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

            stage('Docker components upgrade') {

                try {
                    salt.runSaltProcessStep(pepperEnv, 'I@docker:swarm:role:master and I@prometheus:server', 'cmd.run', ["docker stack rm monitoring"], null, true)
                    salt.enforceState(pepperEnv, 'I@docker:swarm and I@prometheus:server', 'prometheus')
                    salt.runSaltProcessStep(pepperEnv, 'I@docker:swarm:role:master and I@prometheus:server', 'cmd.run', ["docker stack rm dashboard"], null, true)
                    salt.enforceState(pepperEnv, 'I@docker:swarm:role:master and I@prometheus:server', 'docker')
                    salt.runSaltProcessStep(pepperEnv, '*', 'saltutil.sync_all', [], null, true)
                    salt.enforceState(pepperEnv, 'I@grafana:client', 'grafana.client')
                } catch (Exception er) {
                    errorOccured = true
                    common.errorMsg("Upgrade of docker components failed. Please fix it manually.")
                    return
                }
            }
        }
    }
}
