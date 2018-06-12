/**
 *
 * Update Salt environment pipeline
 *
 * Expected parameters:
 *   SALT_MASTER_URL            Salt API server location
 *   SALT_MASTER_CREDENTIALS    Credentials to the Salt API
 *   MCP_VERSION                Version of MCP to upgrade to
 *   UPDATE_CLUSTER_MODEL       Update MCP version parameter in cluster model
 *   UPDATE_PIPELINES           Update pipeline repositories on Gerrit
 *   UPDATE_LOCAL_REPOS         Update local repositories
 */

import hudson.model.*

// Load shared libs
salt = new com.mirantis.mk.Salt()
common = new com.mirantis.mk.Common()
python = new com.mirantis.mk.Python()
jenkinsUtils = new com.mirantis.mk.JenkinsUtils()
venvPepper = "venvPepper"

def triggerMirrorJob(jobName){
    params = jenkinsUtils.getJobParameters(jobName)
    build job: jobName, parameters: [
        [$class: 'StringParameterValue', name: 'BRANCHES', value: params.get("BRANCHES")],
        [$class: 'StringParameterValue', name: 'CREDENTIALS_ID', value: params.get("CREDENTIALS_ID")],
        [$class: 'StringParameterValue', name: 'SOURCE_URL', value: params.get("SOURCE_URL")],
        [$class: 'StringParameterValue', name: 'TARGET_URL', value: params.get("TARGET_URL")]
    ]
}

timeout(time: 12, unit: 'HOURS') {
    node("python") {
        try {
            python.setupPepperVirtualenv(venvPepper, SALT_MASTER_URL, SALT_MASTER_CREDENTIALS)

            if(MCP_VERSION == ""){
                error("You must specify MCP version")
            }

            stage("Update Reclass"){
                def cluster_name = salt.getPillar(venvPepper, 'I@salt:master', "_param:cluster_name").get("return")[0].values()[0]
                if(UPDATE_CLUSTER_MODEL.toBoolean()){
                    try{
                        salt.cmdRun(venvPepper, 'I@salt:master', "cd /srv/salt/reclass/ && git diff-index --quiet HEAD --")
                    }
                    catch(Exception ex){
                        error("You have uncommited changes in your Reclass cluster model repository. Please commit or reset them and rerun the pipeline.")
                    }
                    salt.cmdRun(venvPepper, 'I@salt:master', "cd /srv/salt/reclass/classes/cluster/$cluster_name && grep -r --exclude-dir=aptly -l 'apt_mk_version: .*' * | xargs sed -i 's/apt_mk_version: .*/apt_mk_version: \"$MCP_VERSION\"/g'")
                }

                try{
                    salt.cmdRun(venvPepper, 'I@salt:master', "cd /srv/salt/reclass/classes/system && git diff-index --quiet HEAD --")
                }
                catch(Exception ex){
                    error("You have unstaged changes in your Reclass system model repository. Please reset them and rerun the pipeline.")
                }
                salt.cmdRun(venvPepper, 'I@salt:master', "cd /srv/salt/reclass/classes/system && git checkout $MCP_VERSION")
            }

            if(UPDATE_LOCAL_REPOS.toBoolean()){
                stage("Update local repos"){
                    common.infoMsg("Updating local repositories")

                    def engine = salt.getPillar(venvPepper, 'I@aptly:server', "aptly:server:source:engine")
                    runningOnDocker = engine.get("return")[0].containsValue("docker")

                    if (runningOnDocker) {
                        common.infoMsg("Aptly is running as Docker container")
                    }
                    else {
                        common.infoMsg("Aptly isn't running as Docker container. Going to use aptly user for executing aptly commands")
                    }

                    salt.cmdRun(venvPepper, 'I@salt:master', "cd /srv/salt/reclass/classes/cluster/$cluster_name/cicd/aptly && git checkout $MCP_VERSION")

                    if(runningOnDocker){
                        salt.cmdRun(venvPepper, 'I@aptly:server', "aptly mirror list --raw | grep -E '*' | xargs -n 1 aptly mirror drop -force", true, null, true)
                    }
                    else{
                       salt.cmdRun(venvPepper, 'I@aptly:server', "aptly mirror list --raw | grep -E '*' | xargs -n 1 aptly mirror drop -force", true, null, true, ['runas=aptly'])
                    }

                    salt.enforceState(venvPepper, 'I@aptly:server', 'aptly', true)

                    if(runningOnDocker){
                        salt.runSaltProcessStep(venvPepper, 'I@aptly:server', 'cmd.script', ['salt://aptly/files/aptly_mirror_update.sh', "args=-sv"], null, true)
                        salt.runSaltProcessStep(venvPepper, 'I@aptly:server', 'cmd.script', ['salt://aptly/files/aptly_publish_update.sh', "args=-frv -u http://10.99.0.1:8080"], null, true)
                    }
                    else{
                        salt.runSaltProcessStep(venvPepper, 'I@aptly:server', 'cmd.script', ['salt://aptly/files/aptly_mirror_update.sh', "args=-sv", 'runas=aptly'], null, true)
                        salt.runSaltProcessStep(venvPepper, 'I@aptly:server', 'cmd.script', ['salt://aptly/files/aptly_publish_update.sh', "args=-afrv", 'runas=aptly'], null, true)
                    }

                    salt.enforceState(venvPepper, 'I@aptly:server', 'docker.client.registry', true)

                    salt.enforceState(venvPepper, 'I@aptly:server', 'debmirror', true)

                    salt.enforceState(venvPepper, 'I@aptly:server', 'git.server', true)

                    salt.enforceState(venvPepper, 'I@aptly:server', 'linux.system.file', true)
                }
            }

            stage("Update Drivetrain"){
                salt.cmdRun(venvPepper, 'I@salt:master', "sed -i -e 's/[^ ]*[^ ]/$MCP_VERSION/4' /etc/apt/sources.list.d/mcp_salt.list")
                salt.cmdRun(venvPepper, 'I@salt:master', "apt-get -o Dir::Etc::sourcelist='/etc/apt/sources.list.d/mcp_salt.list' -o Dir::Etc::sourceparts='-' -o APT::Get::List-Cleanup='0' update")
                salt.cmdRun(venvPepper, 'I@salt:master', "apt-get install -y --allow-downgrades salt-formula-*")
                salt.fullRefresh(venvPepper, 'I@salt:master')

                try{
                    salt.enforceState(venvPepper, "I@salt:master", 'reclass', true)
                }
                catch(Exception ex){
                    error("Reclass fails rendering. Pay attention to your cluster model.")
                }

                salt.fullRefresh(venvPepper, '*')

                try{
                    salt.cmdRun(venvPepper, 'I@salt:master', "reclass-salt --top")
                }
                catch(Exception ex){
                    error("Reclass fails rendering. Pay attention to your cluster model.")
                }

                if(UPDATE_PIPELINES.toBoolean()){
                    triggerMirrorJob("git-mirror-downstream-mk-pipelines")
                    triggerMirrorJob("git-mirror-downstream-pipeline-library")
                }

                salt.enforceState(venvPepper, "I@jenkins:client", 'jenkins.client', true)

                salt.cmdRun(venvPepper, "I@salt:master", "salt -C 'I@jenkins:client and I@docker:client' state.sls docker.client --async")

                sleep(180)

                common.infoMsg("Checking if Docker containers are up")

                try{
                    common.retry(10, 30){
                        salt.cmdRun(venvPepper, 'I@jenkins:client and I@docker:client', "! docker service ls | tail -n +2 | grep -v -E '\\s([0-9])/\\1\\s'")
                    }
                }
                catch(Exception ex){
                    error("Docker containers for CI/CD services are having troubles with starting.")
                }
            }
        }
        catch (Throwable e) {
            // If there was an error or exception thrown, the build failed
            currentBuild.result = "FAILURE"
            throw e
        }
    }
}
