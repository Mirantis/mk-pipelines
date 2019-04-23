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
    salt.cmdRun(venvPepper, "I@salt:master", "salt -C '${target}' --async pkg.install force_yes=True pkgs='$pkgs'")
    // can't use same function from pipeline lib, as at the moment of running upgrade pipeline Jenkins
    // still using pipeline lib from current old mcp-version
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
        dir(suffix) {
            for(String minion in saltMinions) {
                common.infoMsg("Reclass model validation for minion ${minion}...")
                def ret = salt.cmdRun("${workspace}/${venvPepper}", 'I@salt:master', "reclass -n ${minion}", true, null, false).get('return')[0].values()[0]
                writeFile file: minion, text: ret.toString()
            }
        }
    } catch (Exception e) {
        common.errorMsg('Can not validate current Reclass model. Inspect failed minion manually.')
        error(e)
    }
}

def archiveReclassModelChanges(ArrayList saltMinions, String oldSuffix, String newSuffix) {
    def diffDir = 'pillarsDiff'
    dir(diffDir) {
        for(String minion in saltMinions) {
            def fileName = "reclass-model-${minion}-diff.out"
            sh "diff -u ${workspace}/${oldSuffix}/${minion} ${workspace}/${newSuffix}/${minion} > ${fileName} || true"
        }
    }
    archiveArtifacts artifacts: "${oldSuffix}/*"
    archiveArtifacts artifacts: "${newSuffix}/*"
    archiveArtifacts artifacts: "${diffDir}/*"
}

if (common.validInputParam('PIPELINE_TIMEOUT')) {
    try {
        pipelineTimeout = env.PIPELINE_TIMEOUT.toInteger()
    } catch(Exception e) {
        common.warningMsg("Provided PIPELINE_TIMEOUT parameter has invalid value: ${env.PIPELINE_TIMEOUT} - should be interger")
    }
}

timeout(time: pipelineTimeout, unit: 'HOURS') {
    node("python && docker") {
        try {
            workspace = common.getWorkspace()
            deleteDir()
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
            if (targetMcpVersion in ['nightly', 'testing']) {
                gitTargetMcpVersion = 'master'
            } else if (targetMcpVersion == 'proposed') {
                gitTargetMcpVersion = 'proposed'
            } else if (!gitTargetMcpVersion) {
                // backward compatibility for 2018.11.0
                gitTargetMcpVersion = "release/${targetMcpVersion}"
            }
            common.warningMsg("gitTargetMcpVersion has been changed to:${gitTargetMcpVersion}")
            def saltMastURL = ''
            def saltMastCreds = ''
            def upgradeSaltStack = ''
            def updateClusterModel = ''
            def updatePipelines = ''
            def updateLocalRepos = ''
            def reclassSystemBranch = ''
            def reclassSystemBranchDefault = gitTargetMcpVersion
            if (gitTargetMcpVersion != 'proposed') {
                reclassSystemBranchDefault = "origin/${gitTargetMcpVersion}"
            }
            def driteTrainParamsYaml = env.getProperty('DRIVE_TRAIN_PARAMS')
            if (driteTrainParamsYaml) {
                def driteTrainParams = readYaml text: driteTrainParamsYaml
                saltMastURL = driteTrainParams.get('SALT_MASTER_URL')
                saltMastCreds = driteTrainParams.get('SALT_MASTER_CREDENTIALS')
                upgradeSaltStack = driteTrainParams.get('UPGRADE_SALTSTACK', false).toBoolean()
                updateClusterModel = driteTrainParams.get('UPDATE_CLUSTER_MODEL', false).toBoolean()
                updatePipelines = driteTrainParams.get('UPDATE_PIPELINES', false).toBoolean()
                updateLocalRepos = driteTrainParams.get('UPDATE_LOCAL_REPOS', false).toBoolean()
                reclassSystemBranch = driteTrainParams.get('RECLASS_SYSTEM_BRANCH', reclassSystemBranchDefault)
            } else {
                // backward compatibility for 2018.11.0
                saltMastURL = env.getProperty('SALT_MASTER_URL')
                saltMastCreds = env.getProperty('SALT_MASTER_CREDENTIALS')
                upgradeSaltStack = env.getProperty('UPGRADE_SALTSTACK').toBoolean()
                updateClusterModel = env.getProperty('UPDATE_CLUSTER_MODEL').toBoolean()
                updatePipelines = env.getProperty('UPDATE_PIPELINES').toBoolean()
                updateLocalRepos = env.getProperty('UPDATE_LOCAL_REPOS').toBoolean()
                reclassSystemBranch = reclassSystemBranchDefault
            }

            python.setupPepperVirtualenv(venvPepper, saltMastURL, saltMastCreds)

            def pillarsBeforeSuffix = 'pillarsBefore'
            def pillarsAfterSuffix = 'pillarsAfter'
            def inventoryBeforeFilename = "reclass-inventory-before.out"
            def inventoryAfterFilename = "reclass-inventory-after.out"

            def minions = salt.getMinions(venvPepper, '*')
            def cluster_name = salt.getPillar(venvPepper, 'I@salt:master', "_param:cluster_name").get("return")[0].values()[0]

            stage("Update Reclass and Salt-Formulas ") {
                validateReclassModel(minions, pillarsBeforeSuffix)
                archiveReclassInventory(inventoryBeforeFilename)

                try {
                    salt.cmdRun(venvPepper, 'I@salt:master', "cd /srv/salt/reclass/ && git diff-index --quiet HEAD --")
                }
                catch (Exception ex) {
                    error("You have uncommited changes in your Reclass cluster model repository. Please commit or reset them and rerun the pipeline.")
                }
                if (updateClusterModel) {
                    common.infoMsg('Perform: UPDATE_CLUSTER_MODEL')
                    def dateTime = common.getDatetime()
                    salt.cmdRun(venvPepper, 'I@salt:master', "cd /srv/salt/reclass/ && git submodule foreach git fetch")
                    salt.cmdRun(venvPepper, 'I@salt:master', "cd /srv/salt/reclass/classes/cluster/$cluster_name && " +
                        "grep -r --exclude-dir=aptly -l 'mcp_version: .*' * | xargs --no-run-if-empty sed -i 's|mcp_version: .*|mcp_version: \"$targetMcpVersion\"|g'")
                    // Do the same, for deprecated variable-duplicate
                    salt.cmdRun(venvPepper, 'I@salt:master', "cd /srv/salt/reclass/classes/cluster/$cluster_name && " +
                        "grep -r --exclude-dir=aptly -l 'apt_mk_version: .*' * | xargs --no-run-if-empty sed -i 's|apt_mk_version: .*|apt_mk_version: \"$targetMcpVersion\"|g'")
                    salt.cmdRun(venvPepper, 'I@salt:master', "cd /srv/salt/reclass/classes/cluster/$cluster_name && " +
                        "grep -r --exclude-dir=aptly -l 'jenkins_pipelines_branch: .*' * | xargs --no-run-if-empty sed -i 's|jenkins_pipelines_branch: .*|jenkins_pipelines_branch: \"$gitTargetMcpVersion\"|g'")
                    // Set new k8s param
                    salt.cmdRun(venvPepper, 'I@salt:master', "cd /srv/salt/reclass/classes/cluster/$cluster_name && " +
                        "grep -r --exclude-dir=aptly -l 'kubernetes_containerd_enabled: .*' * | xargs --no-run-if-empty sed -i 's|kubernetes_containerd_enabled: .*|kubernetes_containerd_enabled: True|g'")
                    salt.cmdRun(venvPepper, 'I@salt:master', "cd /srv/salt/reclass/classes/cluster/$cluster_name && " +
                        "grep -r --exclude-dir=aptly -l 'system.linux.system.repo.mcp.salt' * | xargs --no-run-if-empty sed -i 's/system.linux.system.repo.mcp.salt/system.linux.system.repo.mcp.apt_mirantis.salt-formulas/g'")
                    salt.cmdRun(venvPepper, 'I@salt:master', "cd /srv/salt/reclass/classes/cluster/$cluster_name && " +
                        "grep -r --exclude-dir=aptly -l 'system.linux.system.repo.mcp.contrail' * | xargs --no-run-if-empty sed -i 's/system.linux.system.repo.mcp.contrail/system.linux.system.repo.mcp.apt_mirantis.contrail/g'")
                    salt.cmdRun(venvPepper, 'I@salt:master', "cd /srv/salt/reclass/classes/cluster/$cluster_name && " +
                        "grep -r --exclude-dir=aptly -l 'system.linux.system.repo.mcp.updates' * | xargs --no-run-if-empty sed -i 's/system.linux.system.repo.mcp.updates/system.linux.system.repo.mcp.apt_mirantis.update/g'")
                    salt.cmdRun(venvPepper, 'I@salt:master', "cd /srv/salt/reclass/classes/cluster/$cluster_name && " +
                            "grep -r --exclude-dir=aptly -l 'system.linux.system.repo.mcp.extra' * | xargs --no-run-if-empty sed -i 's/system.linux.system.repo.mcp.extra/system.linux.system.repo.mcp.apt_mirantis.extra/g'")
                    salt.cmdRun(venvPepper, 'I@salt:master', "cd /srv/salt/reclass/classes/system && git checkout ${reclassSystemBranch}")
                    // Add kubernetes-extra repo
                    if (salt.testTarget(venvPepper, "I@kubernetes:master")) {
                        // docker-engine conflicts with the recent containerd versions, so it's removed during upgrade. Thus update source engine
                        salt.cmdRun(venvPepper, 'I@salt:master', "cd /srv/salt/reclass/classes/cluster/$cluster_name && " +
                            "grep -r -l 'engine: docker_hybrid' kubernetes | xargs --no-run-if-empty sed -i 's/engine: docker_hybrid/engine: archive/g'")
                        common.infoMsg("Add kubernetes-extra repo")
                        salt.cmdRun(venvPepper, 'I@salt:master', "cd /srv/salt/reclass/classes/cluster/$cluster_name && " +
                            "grep -q system.linux.system.repo.mcp.apt_mirantis.update.kubernetes_extra kubernetes/common.yml || sed -i '/classes:/ a - system.linux.system.repo.mcp.apt_mirantis.update.kubernetes_extra' kubernetes/common.yml")
                        salt.cmdRun(venvPepper, 'I@salt:master', "cd /srv/salt/reclass/classes/cluster/$cluster_name && " +
                            "grep -q system.linux.system.repo.mcp.apt_mirantis.kubernetes_extra kubernetes/common.yml || sed -i '/classes:/ a - system.linux.system.repo.mcp.apt_mirantis.kubernetes_extra' kubernetes/common.yml")
                    }
                    // Add all update repositories
                    def repoIncludeBase = '- system.linux.system.repo.mcp.apt_mirantis.'
                    def updateRepoList = [ 'cassandra', 'ceph', 'contrail', 'docker', 'elastic', 'extra', 'openstack', 'percona', 'salt-formulas', 'saltstack', 'ubuntu' ]
                    updateRepoList.each { repo ->
                        def repoNameUpdateInclude = "${repoIncludeBase}update.${repo}"
                        def filesWithInclude = salt.cmdRun(venvPepper, 'I@salt:master', "cd /srv/salt/reclass/classes/cluster/$cluster_name && grep -Plr '\\${repoIncludeBase}${repo}\$' . || true", false).get('return')[0].values()[0].trim().tokenize('\n')
                        filesWithInclude.each { file ->
                            def updateRepoIncludeExist = salt.cmdRun(venvPepper, 'I@salt:master', "cd /srv/salt/reclass/classes/cluster/$cluster_name && grep -P '\\${repoNameUpdateInclude}\$' ${file} || echo not_found", false, null, true).get('return')[0].values()[0].trim()
                            if (updateRepoIncludeExist == 'not_found') {
                                // Include needs to be added
                                salt.cmdRun(venvPepper, 'I@salt:master', "cd /srv/salt/reclass/classes/cluster/$cluster_name && " +
                                        "sed -i 's/\\( *\\)${repoIncludeBase}${repo}\$/&\\n\\1${repoNameUpdateInclude}/g' ${file}")
                                common.infoMsg("Update repo for ${repo} is added to ${file}")
                            }
                        }
                    }
                    // Add new defaults
                    common.infoMsg("Add new defaults")
                    salt.cmdRun(venvPepper, 'I@salt:master', "grep '^    mcp_version: ' /srv/salt/reclass/classes/cluster/$cluster_name/infra/init.yml || " +
                        "sed -i 's|^  _param:|  _param:\\n    mcp_version: \"$targetMcpVersion\"|' /srv/salt/reclass/classes/cluster/$cluster_name/infra/init.yml")
                    salt.cmdRun(venvPepper, 'I@salt:master', "grep '^- system.defaults\$' /srv/salt/reclass/classes/cluster/$cluster_name/infra/init.yml || " +
                        "sed -i 's|^classes:|classes:\\n- system.defaults|' /srv/salt/reclass/classes/cluster/$cluster_name/infra/init.yml")
                    salt.cmdRun(venvPepper, 'I@salt:master', "cd /srv/salt/reclass/classes/cluster/$cluster_name && " +
                            "grep -r -l 'docker_image_jenkins: .*' cicd | xargs --no-run-if-empty sed -i 's|\\s*docker_image_jenkins: .*||g'")
                    salt.cmdRun(venvPepper, 'I@salt:master', "cd /srv/salt/reclass/classes/cluster/$cluster_name && " +
                            "grep -r -l 'docker_image_jenkins_slave: .*' cicd | xargs --no-run-if-empty sed -i 's|\\s*docker_image_jenkins_slave: .*||g'")
                    common.infoMsg("The following changes were made to the cluster model and will be commited. " +
                        "Please consider if you want to push them to the remote repository or not. You have to do this manually when the run is finished.")
                    salt.cmdRun(venvPepper, 'I@salt:master', "cd /srv/salt/reclass/classes/cluster/$cluster_name && git diff")
                    salt.cmdRun(venvPepper, 'I@salt:master', "cd /srv/salt/reclass/classes/cluster/$cluster_name && git status && " +
                        "git add -u && git commit --allow-empty -m 'Cluster model update to the release $targetMcpVersion on $dateTime'")
                }

                salt.runSaltProcessStep(venvPepper, 'I@salt:master', 'saltutil.refresh_pillar')
                try {
                    salt.enforceState(venvPepper, 'I@salt:master', 'linux.system.repo')
                } catch (Exception e) {
                    common.errorMsg("Something wrong with model after UPDATE_CLUSTER_MODEL step. Please check model.")
                    throw e
                }

                common.infoMsg('Running a check for compatibility with new Reclass/Salt-Formulas packages')
                def saltModelDir = 'salt-model'
                def nodesArtifact = 'pillarsFromValidation.tar.gz'
                def reclassModel = 'reclassModel.tar.gz'
                def pillarsAfterValidation = 'pillarsFromValidation'
                try {
                    def repos = salt.getPillar(venvPepper, 'I@salt:master', "linux:system:repo").get("return")[0].values()[0]
                    def cfgInfo = salt.getPillar(venvPepper, 'I@salt:master', "reclass:storage:node:infra_cfg01_node").get("return")[0].values()[0]
                    def docker_image_for_test = salt.getPillar(venvPepper, 'I@salt:master', "_param:docker_image_cvp_sanity_checks").get("return")[0].values()[0]
                    def saltModelTesting = new com.mirantis.mk.SaltModelTesting()
                    def config = [
                        'dockerHostname': "cfg01",
                        'distribRevision': "${targetMcpVersion}",
                        'baseRepoPreConfig': true,
                        'extraRepoMergeStrategy': 'override',
                        'dockerContainerName': 'new-reclass-package-check',
                        'dockerMaxCpus': 1,
                        'image': docker_image_for_test,
                        'dockerExtraOpts': [
                            "-v ${env.WORKSPACE}/${saltModelDir}:/srv/salt/reclass",
                            "--entrypoint ''",
                        ],
                        'extraRepos': ['repo': repos, 'aprConfD': "APT::Get::AllowUnauthenticated 'true';" ],
                        'envOpts': [ "CLUSTER_NAME=${cluster_name}", "NODES_ARTIFACT_NAME=${nodesArtifact}" ]
                    ]
                    def tarName = '/tmp/currentModel.tar.gz'
                    salt.cmdRun(venvPepper, 'I@salt:master', "tar -cf ${tarName} --mode='a+rwX' --directory=/srv/salt/reclass classes")
                    if (cfgInfo == '') {
                        // case for old setups when cfg01 node model was static
                        def node_name = salt.getPillar(venvPepper, 'I@salt:master', "linux:system:name").get("return")[0].values()[0]
                        def node_domain = salt.getPillar(venvPepper, 'I@salt:master', "linux:system:domain").get("return")[0].values()[0]
                        salt.cmdRun(venvPepper, 'I@salt:master', "tar -rf ${tarName} --mode='a+rwX' --directory=/srv/salt/reclass nodes/${node_name}.${node_domain}.yml")
                        config['envOpts'].add("CFG_NODE_NAME=${node_name}.${node_domain}")
                    }
                    def modelHash = salt.cmdRun(venvPepper, 'I@salt:master', "cat ${tarName} | gzip -9 -c | base64", false, null, false).get('return')[0].values()[0]
                    writeFile file: 'modelHash', text: modelHash
                    sh "cat modelHash | base64 -d | gzip -d > ${reclassModel}"
                    sh "mkdir ${saltModelDir} && tar -xf ${reclassModel} -C ${saltModelDir}"

                    config['runCommands'] = [
                        '001_Install_Salt_Reclass_Packages': { sh('apt-get install -y reclass salt-formula-*') },
                        '002_Get_new_nodes': {
                            try {
                                sh('''#!/bin/bash
                                new_generated_dir=/srv/salt/_new_nodes
                                new_pillar_dir=/srv/salt/_new_pillar
                                reclass_classes=/srv/salt/reclass/classes/
                                mkdir -p ${new_generated_dir} ${new_pillar_dir}
                                nodegenerator -b ${reclass_classes} -o ${new_generated_dir} ${CLUSTER_NAME}
                                for node in $(ls ${new_generated_dir}); do
                                    nodeName=$(basename -s .yml ${node})
                                    reclass -n ${nodeName} -c ${reclass_classes} -u ${new_generated_dir} > ${new_pillar_dir}/${nodeName}
                                done
                                if [[ -n "${CFG_NODE_NAME}" ]]; then
                                    reclass -n ${CFG_NODE_NAME} -c ${reclass_classes} -u /srv/salt/reclass/nodes > ${new_pillar_dir}/${CFG_NODE_NAME}
                                fi
                                tar -czf /tmp/${NODES_ARTIFACT_NAME} -C ${new_pillar_dir}/ .
                                ''')
                            } catch (Exception e) {
                                print "Test new nodegenerator tool is failed: ${e}"
                                throw e
                            }
                        },
                    ]
                    config['runFinally'] = [ '001_Archive_nodegenerator_artefact': {
                        sh(script: "mv /tmp/${nodesArtifact} ${env.WORKSPACE}/${nodesArtifact}")
                        archiveArtifacts artifacts: nodesArtifact
                    }]
                    saltModelTesting.setupDockerAndTest(config)
                    def pillarsValidationDiff = "${pillarsAfterValidation}/diffFromOriginal"
                    sh "mkdir -p ${pillarsValidationDiff} && tar -xf ${nodesArtifact} --dir ${pillarsAfterValidation}/"
                    def changesFound = false
                    for(String minion in minions) {
                        try {
                            sh (script:"diff -u -w -I '^Salt command execution success' -I '^  node: ' -I '^  uri: ' -I '^  timestamp: ' ${pillarsBeforeSuffix}/${minion} ${pillarsAfterValidation}/${minion} > ${pillarsValidationDiff}/${minion}", returnStdout: true)
                        } catch(Exception e) {
                            changesFound = true
                            archiveArtifacts artifacts: "${pillarsValidationDiff}/${minion}"
                            def buildUrl = env.BUILD_URL ? env.BUILD_URL : "${env.JENKINS_URL}/job/${env.JOB_NAME}/${env.BUILD_NUMBER}"
                            common.errorMsg("Found diff changes for ${minion} minion: ${buildUrl}/artifact/${pillarsValidationDiff}/${minion}/*view*/ ")
                        }
                    }
                    if (changesFound) {
                        common.warningMsg('Found diff changes between current pillar data and updated. Inspect logs above.')
                        input message: 'Continue anyway?'
                    } else {
                        common.infoMsg('Diff between current pillar data and updated one - not found.')
                    }
                }  catch (Exception updateErr) {
                    common.warningMsg(updateErr)
                    common.warningMsg('Failed to validate update Salt Formulas repos/packages.')
                    input message: 'Continue anyway?'
                } finally {
                    sh "rm -rf ${saltModelDir} ${nodesArtifact} ${pillarsAfterValidation} ${reclassModel}"
                }

                try {
                    common.infoMsg('Perform: UPDATE Salt Formulas')
                    def saltEnv = salt.getPillar(venvPepper, 'I@salt:master', "_param:salt_master_base_environment").get("return")[0].values()[0]
                    salt.runSaltProcessStep(venvPepper, 'I@salt:master', 'state.sls_id', ["salt_master_${saltEnv}_pkg_formulas",'salt.master.env'])
                } catch (Exception updateErr) {
                    common.warningMsg(updateErr)
                    common.warningMsg('Failed to update Salt Formulas repos/packages. Check current available documentation on https://docs.mirantis.com/mcp/latest/, how to update packages.')
                    input message: 'Continue anyway?'
                }

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

                validateReclassModel(minions, pillarsAfterSuffix)
                archiveReclassModelChanges(minions, pillarsBeforeSuffix, pillarsAfterSuffix)
            }

            if (updateLocalRepos) {
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

                // updating users and keys
                salt.enforceState(venvPepper, "I@linux:system", 'linux.system.user', true)
                salt.enforceState(venvPepper, "I@linux:system", 'openssh', true)

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
