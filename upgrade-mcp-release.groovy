/**
 *
 * Update Salt environment pipeline
 *
 * Expected parameters:
 *   TARGET_MCP_VERSION         Version of MCP to upgrade to
 *   GIT_REFSPEC                Git repo ref to be used
 *   DRIVE_TRAIN_PARAMS         Yaml, DriveTrain releated params:
 *     SALT_MASTER_URL            Salt API server location
 *     SALT_MASTER_CREDENTIALS    Credentials to the Salt API
 *     UPGRADE_SALTSTACK          Upgrade SaltStack packages to new version.
 *     UPDATE_CLUSTER_MODEL       Update MCP version parameter in cluster model
 *     UPDATE_PIPELINES           Update pipeline repositories on Gerrit
 *     UPDATE_LOCAL_REPOS         Update local repositories
 */

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
    // wait 2 mins when salt-* packages are updated which leads to salt-* services restart
    common.retry(2, 120) {
        salt.runSaltProcessStep(venvPepper, target, 'pkg.install', ["force_yes=True", "pkgs='$pkgs'"], null, true, 5)
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

def validateReclassModel(ArrayList saltMinions, String suffix) {
    try {
        for(String minion in saltMinions) {
            common.infoMsg("Reclass model validation for minion ${minion}...")
            def ret = salt.cmdRun(venvPepper, 'I@salt:master', "reclass -n ${minion}", true, null, false)
            def reclassInv = ret.values()[0]
            writeFile file: "inventory-${minion}-${suffix}.out", text: reclassInv.toString()
        }
    } catch (Exception e) {
        common.errorMsg('Can not validate current Reclass model. Inspect failed minion manually.')
        error(e)
    }
}

def archiveReclassModelChanges(ArrayList saltMinions, String oldSuffix='before', String newSuffix='after') {
    for(String minion in saltMinions) {
        def fileName = "reclass-model-${minion}-diff.out"
        sh "diff -u inventory-${minion}-${oldSuffix}.out inventory-${minion}-${newSuffix}.out > ${fileName} || true"
        archiveArtifacts artifacts: "${fileName}"
    }
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
            def gitTargetMcpVersion = env.getProperty('GIT_REFSPEC')
            if (targetMcpVersion in ['testing', 'proposed']) {
                gitTargetMcpVersion = 'master'
                common.warningMsg("gitTargetMcpVersion has been changed to:${gitTargetMcpVersion}")
            } else if (!gitTargetMcpVersion) {
                // backward compatibility for 2018.11.0
                gitTargetMcpVersion = "release/${targetMcpVersion}"
            }
            def saltMastURL = ''
            def saltMastCreds = ''
            def upgradeSaltStack = ''
            def updateClusterModel = ''
            def updatePipelines = ''
            def updateLocalRepos = ''
            def reclassSystemBranch = ''
            def driteTrainParamsYaml = env.getProperty('DRIVE_TRAIN_PARAMS')
            if (driteTrainParamsYaml) {
                def driteTrainParams = readYaml text: driteTrainParamsYaml
                saltMastURL = driteTrainParams.get('SALT_MASTER_URL')
                defsaltMastCreds = driteTrainParams.get('SALT_MASTER_CREDENTIALS')
                upgradeSaltStack = driteTrainParams.get('UPGRADE_SALTSTACK', false).toBoolean()
                updateClusterModel = driteTrainParams.get('UPDATE_CLUSTER_MODEL', false).toBoolean()
                updatePipelines = driteTrainParams.get('UPDATE_PIPELINES', false).toBoolean()
                updateLocalRepos = driteTrainParams.get('UPDATE_LOCAL_REPOS', false).toBoolean()
                reclassSystemBranch = driteTrainParams.get('RECLASS_SYSTEM_BRANCH', gitTargetMcpVersion)
            } else {
                // backward compatibility for 2018.11.0
                saltMastURL = env.getProperty('SALT_MASTER_URL')
                saltMastCreds = env.getProperty('SALT_MASTER_CREDENTIALS')
                upgradeSaltStack = env.getProperty('UPGRADE_SALTSTACK', false).toBoolean()
                updateClusterModel = env.getProperty('UPDATE_CLUSTER_MODEL', false).toBoolean()
                updatePipelines = env.getProperty('UPDATE_PIPELINES', false).toBoolean()
                updateLocalRepos = env.getProperty('UPDATE_LOCAL_REPOS', false).toBoolean()
                reclassSystemBranch = gitTargetMcpVersion
            }

            python.setupPepperVirtualenv(venvPepper, saltMastURL, saltMastCreds)

            def inventoryBeforeFilename = "reclass-inventory-before.out"
            def inventoryAfterFilename = "reclass-inventory-after.out"

            def minions = salt.getMinions(venvPepper, '*')

            stage("Update Reclass and Salt-Formulas ") {
                validateReclassModel(minions, 'before')

                def cluster_name = salt.getPillar(venvPepper, 'I@salt:master', "_param:cluster_name").get("return")[0].values()[0]
                try {
                    salt.cmdRun(venvPepper, 'I@salt:master', "cd /srv/salt/reclass/ && git diff-index --quiet HEAD --")
                }
                catch (Exception ex) {
                    error("You have uncommited changes in your Reclass cluster model repository. Please commit or reset them and rerun the pipeline.")
                }
                if (updateClusterModel) {
                    common.infoMsg('Perform: UPDATE_CLUSTER_MODEL')
                    def dateTime = common.getDatetime()
                    salt.cmdRun(venvPepper, 'I@salt:master', "cd /srv/salt/reclass/classes/cluster/$cluster_name && " +
                        "grep -r --exclude-dir=aptly -l 'mcp_version: .*' * | xargs --no-run-if-empty sed -i 's/mcp_version: .*/mcp_version: \"$targetMcpVersion\"/g'")
                    // Do the same, for deprecated variable-duplicate
                    salt.cmdRun(venvPepper, 'I@salt:master', "cd /srv/salt/reclass/classes/cluster/$cluster_name && " +
                        "grep -r --exclude-dir=aptly -l 'apt_mk_version: .*' * | xargs --no-run-if-empty sed -i 's/apt_mk_version: .*/apt_mk_version: \"$targetMcpVersion\"/g'")
                    salt.cmdRun(venvPepper, 'I@salt:master', "cd /srv/salt/reclass/classes/cluster/$cluster_name && " +
                        "grep -r --exclude-dir=aptly -l 'jenkins_pipelines_branch: .*' * | xargs --no-run-if-empty sed -i 's/jenkins_pipelines_branch: .*/jenkins_pipelines_branch: \"$gitTargetMcpVersion\"/g'")
                    salt.cmdRun(venvPepper, 'I@salt:master', "cd /srv/salt/reclass/classes/system && git checkout ${reclassSystemBranch}")
                    // Add new defaults
                    common.infoMsg("Add new defaults")
                    salt.cmdRun(venvPepper, 'I@salt:master', "grep '^    mcp_version: ' /srv/salt/reclass/classes/cluster/$cluster_name/infra/init.yml || " +
                        "sed -i 's/^  _param:/  _param:\\n    mcp_version: \"$targetMcpVersion\"/' /srv/salt/reclass/classes/cluster/$cluster_name/infra/init.yml")
                    salt.cmdRun(venvPepper, 'I@salt:master', "grep '^- system.defaults\$' /srv/salt/reclass/classes/cluster/$cluster_name/infra/init.yml || " +
                        "sed -i 's/^classes:/classes:\\n- system.defaults/' /srv/salt/reclass/classes/cluster/$cluster_name/infra/init.yml")
                    common.infoMsg("The following changes were made to the cluster model and will be commited. " +
                        "Please consider if you want to push them to the remote repository or not. You have to do this manually when the run is finished.")
                    salt.cmdRun(venvPepper, 'I@salt:master', "cd /srv/salt/reclass/classes/cluster/$cluster_name && git diff")
                    salt.cmdRun(venvPepper, 'I@salt:master', "cd /srv/salt/reclass/classes/cluster/$cluster_name && git status && " +
                        "git add -u && git commit --allow-empty -m 'Cluster model update to the release $targetMcpVersion on $dateTime'")
                }

                try {
                    common.infoMsg('Perform: UPDATE Salt Formulas')
                    salt.enforceState(venvPepper, 'I@salt:master', 'linux.system.repo')
                    def saltEnv = salt.getPillar(venvPepper, 'I@salt:master', "_param:salt_master_base_environment").get("return")[0].values()[0]
                    salt.runSaltProcessStep(venvPepper, 'I@salt:master', 'state.sls_id', ["salt_master_${saltEnv}_pkg_formulas",'salt.master.env'])
                } catch (Exception updateErr) {
                    common.warningMsg(updateErr)
                    common.warningMsg('Failed to update Salt Formulas repos/packages. Check current available documentation on https://docs.mirantis.com/mcp/latest/, how to update packages.')
                    input message: 'Continue anyway?'
                }

                archiveReclassInventory(inventoryBeforeFilename)

                try {
                    common.infoMsg('Perform: UPDATE Reclass package')
                    salt.runSaltProcessStep(venvPepper, 'I@salt:master', 'pkg.install', ["reclass"])
                } catch (Exception updateErr) {
                    common.warningMsg(updateErr)
                    common.warningMsg('Failed to update Reclass package. Check current available documentation on https://docs.mirantis.com/mcp/latest/, how to update packages.')
                    input message: 'Continue anyway?'
                }

                salt.fullRefresh(venvPepper, 'I@salt:master')
                salt.enforceState(venvPepper, 'I@salt:master', 'reclass.storage', true)
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

                validateReclassModel(minions, 'after')
                archiveReclassModelChanges(minions)
            }

            if (updateLocalRepos) {
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
                if (upgradeSaltStack) {
                    updateSaltStack("I@salt:master", '["salt-master", "salt-common", "salt-api", "salt-minion"]')

                    salt.enforceState(venvPepper, "I@linux:system", 'linux.system.repo', true)
                    updateSaltStack("I@salt:minion and not I@salt:master", '["salt-minion"]')
                }

                if (updatePipelines) {
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
