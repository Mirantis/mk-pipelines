/**
 *
 * Update Salt environment pipeline
 *
 * Expected parameters:
 *   SALT_MASTER_URL            Salt API server location
 *   SALT_MASTER_CREDENTIALS    Credentials to the Salt API
 *   TARGET_MCP_VERSION         Version of MCP to upgrade to
 *   UPGRADE_SALTSTACK          Upgrade SaltStack packages to new version.
 *   UPDATE_CLUSTER_MODEL       Update MCP version parameter in cluster model
 *   UPDATE_PIPELINES           Update pipeline repositories on Gerrit
 *   UPDATE_LOCAL_REPOS         Update local repositories
 */

// Load shared libs
salt = new com.mirantis.mk.Salt()
common = new com.mirantis.mk.Common()
python = new com.mirantis.mk.Python()
jenkinsUtils = new com.mirantis.mk.JenkinsUtils()
def pipelineTimeout = 12
venvPepper = "venvPepper"
workspace = ""

def triggerMirrorJob(jobName) {
    params = jenkinsUtils.getJobParameters(jobName)
    build job: jobName, parameters: [
        [$class: 'StringParameterValue', name: 'BRANCHES', value: params.get("BRANCHES")],
        [$class: 'StringParameterValue', name: 'CREDENTIALS_ID', value: params.get("CREDENTIALS_ID")],
        [$class: 'StringParameterValue', name: 'SOURCE_URL', value: params.get("SOURCE_URL")],
        [$class: 'StringParameterValue', name: 'TARGET_URL', value: params.get("TARGET_URL")]
    ]
}

def updateSaltStack(target, pkgs) {
    try {
        salt.runSaltProcessStep(venvPepper, target, 'pkg.install', ["force_yes=True", "pkgs='$pkgs'"], null, true, 5)
    } catch (Exception ex) {
    }

    common.retry(20, 60) {
        salt.minionsReachable(venvPepper, 'I@salt:master', '*')
        def running = salt.runSaltProcessStep(venvPepper, target, 'saltutil.running', [], null, true, 5)
        for (value in running.get("return")[0].values()) {
            if (value != []) {
                throw new Exception("Not all salt-minions are ready for execution")
            }
        }
    }

    def saltVersion = salt.getPillar(venvPepper, 'I@salt:master', '_param:salt_version').get('return')[0].values()[0]
    def saltMinionVersions = salt.cmdRun(venvPepper, target, "apt-cache policy salt-common |  awk '/Installed/ && /$saltVersion/'").get("return")
    def saltMinionVersion = ""

    for (minion in saltMinionVersions[0].keySet()) {
        saltMinionVersion = saltMinionVersions[0].get(minion).replace("Salt command execution success", "").trim()
        if (saltMinionVersion == "") {
            error("Installed version of Salt on $minion doesn't match specified version in the model.")
        }
    }
}

def archiveReclassInventory(filename) {
    def ret = salt.cmdRun(venvPepper, 'I@salt:master', "reclass -i", true, null, false)
    def reclassInv = ret.values()[0]
    writeFile file: filename, text: reclassInv.toString()
    archiveArtifacts artifacts: "$filename"
}

if (common.validInputParam('PIPELINE_TIMEOUT') && env.PIPELINE_TIMEOUT.isInteger()) {
    pipelineTimeout = env.PIPELINE_TIMEOUT.toInteger()
}

timeout(time: pipelineTimeout, unit: 'HOURS') {
    node("python") {
        try {
            workspace = common.getWorkspace()
            targetMcpVersion = null
            if (!common.validInputParam('TARGET_MCP_VERSION') && !common.validInputParam('MCP_VERSION')) {
                error('You must specify MCP version in TARGET_MCP_VERSION|MCP_VERSION variable')
            }
            // bw comp. for 2018.X => 2018.11 release
            if (common.validInputParam('MCP_VERSION')){
                targetMcpVersion = env.MCP_VERSION
                common.warningMsg("targetMcpVersion has been changed to:${targetMcpVersion}, which was taken from deprecated pipeline viriable:MCP_VERSION")
            }
            else {
                targetMcpVersion = env.TARGET_MCP_VERSION
            }
            // end bw comp. for 2018.X => 2018.11 release
            def gitTargetMcpVersion = targetMcpVersion
            if (targetMcpVersion == 'testing') {
                gitTargetMcpVersion = 'master'
                common.warningMsg("gitTargetMcpVersion has been changed to:${gitTargetMcpVersion}")
            }
            python.setupPepperVirtualenv(venvPepper, SALT_MASTER_URL, SALT_MASTER_CREDENTIALS)

            stage("Update Reclass") {
                def cluster_name = salt.getPillar(venvPepper, 'I@salt:master', "_param:cluster_name").get("return")[0].values()[0]
                try {
                    salt.cmdRun(venvPepper, 'I@salt:master', "cd /srv/salt/reclass/ && git diff-index --quiet HEAD --")
                }
                catch (Exception ex) {
                    error("You have uncommited changes in your Reclass cluster model repository. Please commit or reset them and rerun the pipeline.")
                }
                if (UPDATE_CLUSTER_MODEL.toBoolean()) {
                    common.infoMsg('Perform: UPDATE_CLUSTER_MODEL')
                    def dateTime = common.getDatetime()
                    salt.cmdRun(venvPepper, 'I@salt:master', "cd /srv/salt/reclass/classes/cluster/$cluster_name && " +
                        "grep -r --exclude-dir=aptly -l 'mcp_version: .*' * | xargs --no-run-if-empty sed -i 's/mcp_version: .*/mcp_version: \"$targetMcpVersion\"/g'")
                    // Do the same, for deprecated variable-duplicate
                    salt.cmdRun(venvPepper, 'I@salt:master', "cd /srv/salt/reclass/classes/cluster/$cluster_name && " +
                        "grep -r --exclude-dir=aptly -l 'apt_mk_version: .*' * | xargs --no-run-if-empty sed -i 's/apt_mk_version: .*/apt_mk_version: \"$targetMcpVersion\"/g'")
                    salt.cmdRun(venvPepper, 'I@salt:master', "cd /srv/salt/reclass/classes/system && git checkout ${gitTargetMcpVersion}")
                    // Add new defaults
                    common.infoMsg("Add new defaults")
                    salt.cmdRun(venvPepper, 'I@salt:master', "grep '^- system.defaults\$' /srv/salt/reclass/classes/cluster/$cluster_name/infra/init.yml || " +
                        "sed -i 's/^classes:/classes:\\n- system.defaults/' /srv/salt/reclass/classes/cluster/$cluster_name/infra/init.yml")
                    common.infoMsg("The following changes were made to the cluster model and will be commited. " +
                        "Please consider if you want to push them to the remote repository or not. You have to do this manually when the run is finished.")
                    salt.cmdRun(venvPepper, 'I@salt:master', "cd /srv/salt/reclass/classes/cluster/$cluster_name && git diff")
                    salt.cmdRun(venvPepper, 'I@salt:master', "cd /srv/salt/reclass/classes/cluster/$cluster_name && git status && " +
                        "git add -u && git commit --allow-empty -m 'Cluster model update to the release $targetMcpVersion on $dateTime'")
                }
                salt.enforceState(venvPepper, 'I@salt:master', 'reclass.storage', true)
            }

            if (UPDATE_LOCAL_REPOS.toBoolean()) {
                def cluster_name = salt.getPillar(venvPepper, 'I@salt:master', "_param:cluster_name").get("return")[0].values()[0]
                stage("Update local repos") {
                    common.infoMsg("Updating local repositories")

                    def engine = salt.getPillar(venvPepper, 'I@aptly:publisher', "aptly:publisher:source:engine")
                    runningOnDocker = engine.get("return")[0].containsValue("docker")

                    if (runningOnDocker) {
                        common.infoMsg("Aptly is running as Docker container")
                    } else {
                        common.infoMsg("Aptly isn't running as Docker container. Going to use aptly user for executing aptly commands")
                    }

                    if (runningOnDocker) {
                        salt.cmdRun(venvPepper, 'I@aptly:publisher', "aptly mirror list --raw | grep -E '*' | xargs --no-run-if-empty -n 1 aptly mirror drop -force", true, null, true)
                    } else {
                        salt.cmdRun(venvPepper, 'I@aptly:publisher', "aptly mirror list --raw | grep -E '*' | xargs --no-run-if-empty -n 1 aptly mirror drop -force", true, null, true, ['runas=aptly'])
                    }

                    salt.enforceState(venvPepper, 'I@aptly:publisher', 'aptly', true)

                    if (runningOnDocker) {
                        salt.runSaltProcessStep(venvPepper, 'I@aptly:publisher', 'cmd.script', ['salt://aptly/files/aptly_mirror_update.sh', "args=-sv"], null, true)
                        salt.runSaltProcessStep(venvPepper, 'I@aptly:publisher', 'cmd.script', ['salt://aptly/files/aptly_publish_update.sh', "args=-frv -u http://10.99.0.1:8080"], null, true)
                    } else {
                        salt.runSaltProcessStep(venvPepper, 'I@aptly:publisher', 'cmd.script', ['salt://aptly/files/aptly_mirror_update.sh', "args=-sv", 'runas=aptly'], null, true)
                        salt.runSaltProcessStep(venvPepper, 'I@aptly:publisher', 'cmd.script', ['salt://aptly/files/aptly_publish_update.sh', "args=-afrv", 'runas=aptly'], null, true)
                    }

                    salt.enforceState(venvPepper, 'I@aptly:publisher', 'docker.client.registry', true)

                    salt.enforceState(venvPepper, 'I@aptly:publisher', 'debmirror', true)

                    salt.enforceState(venvPepper, 'I@aptly:publisher', 'git.server', true)

                    salt.enforceState(venvPepper, 'I@aptly:publisher', 'linux.system.file', true)
                }
            }

            stage("Update Drivetrain") {
                salt.cmdRun(venvPepper, 'I@salt:master', "sed -i -e 's/[^ ]*[^ ]/$targetMcpVersion/4' /etc/apt/sources.list.d/mcp_salt.list")
                salt.cmdRun(venvPepper, 'I@salt:master', "apt-get -o Dir::Etc::sourcelist='/etc/apt/sources.list.d/mcp_salt.list' -o Dir::Etc::sourceparts='-' -o APT::Get::List-Cleanup='0' update")
                // Workaround for PROD-22108
                salt.cmdRun(venvPepper, 'I@salt:master', "apt-get purge -y salt-formula-octavia && " +
                    "apt-get install -y salt-formula-octavia")
                // End workaround for PROD-22108
                salt.cmdRun(venvPepper, 'I@salt:master', "apt-get install -y --allow-downgrades salt-formula-*")

                def inventoryBeforeFilename = "reclass-inventory-before.out"
                def inventoryAfterFilename = "reclass-inventory-after.out"

                archiveReclassInventory(inventoryBeforeFilename)

                salt.cmdRun(venvPepper, 'I@salt:master', "sed -i -e 's/[^ ]*[^ ]/$targetMcpVersion/4' /etc/apt/sources.list.d/mcp_extra.list")
                salt.cmdRun(venvPepper, 'I@salt:master', "apt-get -o Dir::Etc::sourcelist='/etc/apt/sources.list.d/mcp_extra.list' -o Dir::Etc::sourceparts='-' -o APT::Get::List-Cleanup='0' update")
                salt.cmdRun(venvPepper, 'I@salt:master', "apt-get install -y --allow-downgrades reclass")

                salt.fullRefresh(venvPepper, 'I@salt:master')

                try {
                    salt.enforceState(venvPepper, "I@salt:master", 'reclass', true)
                }
                catch (Exception ex) {
                    error("Reclass fails rendering. Pay attention to your cluster model.")
                }

                salt.fullRefresh(venvPepper, '*')

                try {
                    salt.cmdRun(venvPepper, 'I@salt:master', "reclass-salt --top")
                }
                catch (Exception ex) {
                    error("Reclass fails rendering. Pay attention to your cluster model.")
                }

                archiveReclassInventory(inventoryAfterFilename)

                sh "diff -u $inventoryBeforeFilename $inventoryAfterFilename > reclass-inventory-diff.out || true"
                archiveArtifacts artifacts: "reclass-inventory-diff.out"

                if (UPGRADE_SALTSTACK.toBoolean()) {
                    salt.enforceState(venvPepper, "I@linux:system", 'linux.system.repo', true)

                    updateSaltStack("I@salt:master", '["salt-master", "salt-common", "salt-api", "salt-minion"]')

                    updateSaltStack("I@salt:minion and not I@salt:master", '["salt-minion"]')
                }

                if (UPDATE_PIPELINES.toBoolean()) {
                    triggerMirrorJob("git-mirror-downstream-mk-pipelines")
                    triggerMirrorJob("git-mirror-downstream-pipeline-library")
                }

                salt.enforceState(venvPepper, "I@jenkins:client", 'jenkins.client', true)

                salt.cmdRun(venvPepper, "I@salt:master", "salt -C 'I@jenkins:client and I@docker:client' state.sls docker.client --async")

                sleep(180)

                common.infoMsg("Checking if Docker containers are up")

                try {
                    common.retry(10, 30) {
                        salt.cmdRun(venvPepper, 'I@jenkins:client and I@docker:client', "! docker service ls | tail -n +2 | grep -v -E '\\s([0-9])/\\1\\s'")
                    }
                }
                catch (Exception ex) {
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
