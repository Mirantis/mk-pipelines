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

def triggerMirrorJob(String jobName, String reclassSystemBranch) {
    params = jenkinsUtils.getJobParameters(jobName)
    try {
        build job: jobName, parameters: [
            [$class: 'StringParameterValue', name: 'BRANCHES', value: params.get('BRANCHES')],
            [$class: 'StringParameterValue', name: 'CREDENTIALS_ID', value: params.get('CREDENTIALS_ID')],
            [$class: 'StringParameterValue', name: 'SOURCE_URL', value: params.get('SOURCE_URL')],
            [$class: 'StringParameterValue', name: 'TARGET_URL', value: params.get('TARGET_URL')]
        ]
    } catch (Exception updateErr) {
        common.warningMsg(updateErr)
        common.warningMsg('Attempt to update git repo in failsafe manner')
        build job: jobName, parameters: [
            [$class: 'StringParameterValue', name: 'BRANCHES', value: reclassSystemBranch.replace('origin/', '')],
            [$class: 'StringParameterValue', name: 'CREDENTIALS_ID', value: params.get('CREDENTIALS_ID')],
            [$class: 'StringParameterValue', name: 'SOURCE_URL', value: params.get('SOURCE_URL')],
            [$class: 'StringParameterValue', name: 'TARGET_URL', value: params.get('TARGET_URL')]
        ]
    }
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

def wa29352(ArrayList saltMinions, String cname) {
    // WA for PROD-29352. Issue cause due patch https://gerrit.mcp.mirantis.com/#/c/37932/12/openssh/client/root.yml
    // Default soft-param has been removed, what now makes not possible to render some old env's.
    // Like fix, we found copy-paste already generated key from backups, to secrets.yml with correct key name
    def wa29352ClassName = 'cluster.' + cname + '.infra.secrets_root_wa29352'
    def wa29352File = "/srv/salt/reclass/classes/cluster/${cname}/infra/secrets_root_wa29352.yml"
    def wa29352SecretsFile = "/srv/salt/reclass/classes/cluster/${cname}/infra/secrets.yml"
    def _tempFile = '/tmp/wa29352_' + UUID.randomUUID().toString().take(8)
    try {
        salt.cmdRun(venvPepper, 'I@salt:master', "grep -qiv root_private_key ${wa29352SecretsFile}", true, null, false)
        salt.cmdRun(venvPepper, 'I@salt:master', "test ! -f ${wa29352File}", true, null, false)
    }
    catch (Exception ex) {
        common.infoMsg('Work-around for PROD-29352 already apply, nothing todo')
        return
    }
    def rKeysDict = [
        'parameters': [
            '_param': [
                'root_private_key': salt.getPillar(venvPepper, 'I@salt:master', '_param:root_private_key').get('return')[0].values()[0].trim(),
                'root_public_key' : '',
            ]
        ]
    ]
    // save root key,and generate public one from it
    writeFile file: _tempFile, text: rKeysDict['parameters']['_param']['root_private_key'].toString().trim()
    sh('chmod 0600 ' + _tempFile)
    rKeysDict['parameters']['_param']['root_public_key'] = sh(script: "ssh-keygen -q -y -f ${_tempFile}", returnStdout: true).trim()
    sh('rm -fv ' + _tempFile)
    writeYaml file: _tempFile, data: rKeysDict
    def yamlData = sh(script: "cat ${_tempFile} | base64", returnStdout: true).trim()
    salt.cmdRun(venvPepper, 'I@salt:master', "echo '${yamlData}' | base64 -d  > ${wa29352File}", false, null, false)
    common.infoMsg("Add $wa29352ClassName class into secrets.yml")

    // Add 'classes:' directive
    salt.cmdRun(venvPepper, 'I@salt:master', "cd /srv/salt/reclass/classes/cluster/$cname && " +
        "grep -q 'classes:' infra/secrets.yml || sed -i '1iclasses:' infra/secrets.yml")

    salt.cmdRun(venvPepper, 'I@salt:master', "cd /srv/salt/reclass/classes/cluster/$cname && " +
        "grep -q '${wa29352ClassName}' infra/secrets.yml || sed -i '/classes:/ a - $wa29352ClassName' infra/secrets.yml")
    salt.fullRefresh(venvPepper, '*')
    sh('rm -fv ' + _tempFile)
}

def wa29155(ArrayList saltMinions, String cname) {
    // WA for PROD-29155. Issue cause due patch https://gerrit.mcp.mirantis.com/#/c/37932/
    // CHeck for existence cmp nodes, and try to render it. Is failed, apply ssh-key wa
    def ret = ''
    def patched = false
    def wa29155ClassName = 'cluster.' + cname + '.infra.secrets_nova_wa29155'
    def wa29155File = "/srv/salt/reclass/classes/cluster/${cname}/infra/secrets_nova_wa29155.yml"

    try {
        salt.cmdRun(venvPepper, 'I@salt:master', "test ! -f ${wa29155File}", true, null, false)
    }
    catch (Exception ex) {
        common.infoMsg('Work-around for PROD-29155 already apply, nothing todo')
        return
    }
    salt.fullRefresh(venvPepper, 'cfg*')
    salt.fullRefresh(venvPepper, 'cmp*')
    for (String minion in saltMinions) {
        if (!minion.startsWith('cmp')) {
            continue
        }
        // First attempt, second will be performed in next validateReclassModel() stages
        try {
            salt.cmdRun(venvPepper, 'I@salt:master', "reclass -n ${minion}", true, null, false).get('return')[0].values()[0].replaceAll('Salt command execution success', '').trim()
        } catch (Exception e) {
            common.errorMsg(e.toString())
            if (patched) {
                error("Node: ${minion} failed to render after reclass-system upgrade!WA29155 probably didn't help.")
            }
            // check, that failed exactly by our case,  by key-length check.
            def missed_key = salt.getPillar(venvPepper, minion, '_param:nova_compute_ssh_private').get("return")[0].values()[0]
            if (missed_key != '') {
                error("Node: ${minion} failed to render after reclass-system upgrade!")
            }
            common.warningMsg('Perform: Attempt to apply WA for PROD-29155\n' +
                'See https://gerrit.mcp.mirantis.com/#/c/37932/ for more info')
            common.warningMsg('WA-PROD-29155 Generating new ssh key at master node')
            def _tempFile = "/tmp/nova_wa29155_" + UUID.randomUUID().toString().take(8)
            common.infoMsg('Perform: generation NEW ssh-private key for nova-compute')
            salt.cmdRun(venvPepper, 'I@salt:master', "ssh-keygen -f ${_tempFile} -N '' -q")
            def _pub_k = salt.runSaltProcessStep(venvPepper, 'I@salt:master', 'cmd.run', "cat ${_tempFile}.pub").get('return')[0].values()[0].trim()
            def _priv_k = salt.runSaltProcessStep(venvPepper, 'I@salt:master', 'cmd.run', "cat ${_tempFile}").get('return')[0].values()[0].trim()
            salt.cmdRun(venvPepper, 'I@salt:master', "rm -fv ${_tempFile}", false, null, false)
            def novaKeysDict = [
                "parameters": [
                    "_param": [
                        "nova_compute_ssh_private": _priv_k,
                        "nova_compute_ssh_public" : _pub_k
                    ]
                ]
            ]
            writeYaml file: _tempFile, data: novaKeysDict
            def yamlData = sh(script: "cat ${_tempFile} | base64", returnStdout: true).trim()
            salt.cmdRun(venvPepper, 'I@salt:master', "echo '${yamlData}' | base64 -d  > ${wa29155File}", false, null, false)
            common.infoMsg("Add $wa29155ClassName class into secrets.yml")

            // Add 'classes:' directive
            salt.cmdRun(venvPepper, 'I@salt:master', "cd /srv/salt/reclass/classes/cluster/$cname && " +
                "grep -q 'classes:' infra/secrets.yml || sed -i '1iclasses:' infra/secrets.yml")

            salt.cmdRun(venvPepper, 'I@salt:master', "cd /srv/salt/reclass/classes/cluster/$cname && " +
                "grep -q '${wa29155ClassName}' infra/secrets.yml || sed -i '/classes:/ a - $wa29155ClassName' infra/secrets.yml")
            salt.fullRefresh(venvPepper, 'cfg*')
            salt.fullRefresh(venvPepper, 'cmp*')
            patched = true
        }
    }
    if (patched) {
        salt.cmdRun(venvPepper, 'I@salt:master', "cd /srv/salt/reclass/classes/cluster/$cname && git status && " +
            "git add ${wa29155File} && git add -u && git commit --allow-empty -m 'Cluster model updated with WA for PROD-29155. Issue cause due patch https://gerrit.mcp.mirantis.com/#/c/37932/ at ${common.getDatetime()}' ")
        common.infoMsg('Work-around for PROD-29155 successfully applied')
    }

}

def archiveReclassInventory(filename) {
    def _tmp_file = '/tmp/' + filename + UUID.randomUUID().toString().take(8)
    // jenkins may fail at overheap. Compress data with gzip like WA
    def ret = salt.cmdRun(venvPepper, 'I@salt:master', 'reclass -i  2>/dev/null | gzip -9 -c | base64', true, null, false).get('return')[0].values()[0].replaceAll('Salt command execution success', '').trim()
    def _tmp = sh(script: "echo '$ret'  > ${_tmp_file}", returnStdout: false)
    sh(script: "cat ${_tmp_file} | base64 -d | gzip -d > $filename", returnStdout: false)
    archiveArtifacts artifacts: filename
    sh(script: "rm -v ${_tmp_file}|| true")
}

def validateReclassModel(ArrayList saltMinions, String suffix) {
    try {
        for (String minion in saltMinions) {
            common.infoMsg("Reclass model validation for minion ${minion}...")
            def reclassInv = salt.cmdRun(venvPepper, 'I@salt:master', "reclass -n ${minion}", true, null, false).get('return')[0].values()[0].replaceAll('Salt command execution success', '').trim()
            writeFile file: "inventory-${minion}-${suffix}.out", text: reclassInv.toString()
        }
    } catch (Exception e) {
        common.errorMsg('Can not validate current Reclass model. Inspect failed minion manually.')
        error(e.toString())
    }
}

def archiveReclassModelChanges(ArrayList saltMinions, String oldSuffix = 'before', String newSuffix = 'after') {
    for (String minion in saltMinions) {
        def fileName = "reclass-model-${minion}-diff.out"
        sh "diff -u inventory-${minion}-${oldSuffix}.out inventory-${minion}-${newSuffix}.out > ${fileName} || true"
        archiveArtifacts artifacts: "${fileName}"
    }
}

if (common.validInputParam('PIPELINE_TIMEOUT')) {
    try {
        pipelineTimeout = env.PIPELINE_TIMEOUT.toInteger()
    } catch (Exception e) {
        common.warningMsg("Provided PIPELINE_TIMEOUT parameter has invalid value: ${env.PIPELINE_TIMEOUT} - should be interger")
    }
}

timeout(time: pipelineTimeout, unit: 'HOURS') {
    node("python") {
        try {
            def inventoryBeforeFilename = "reclass-inventory-before.out"
            def inventoryAfterFilename = "reclass-inventory-after.out"
            workspace = common.getWorkspace()
            targetMcpVersion = null
            if (!common.validInputParam('TARGET_MCP_VERSION') && !common.validInputParam('MCP_VERSION')) {
                error('You must specify MCP version in TARGET_MCP_VERSION|MCP_VERSION variable')
            }
            // bw comp. for 2018.X => 2018.11 release
            if (common.validInputParam('MCP_VERSION')) {
                targetMcpVersion = env.MCP_VERSION
                common.warningMsg("targetMcpVersion has been changed to:${targetMcpVersion}, which was taken from deprecated pipeline viriable:MCP_VERSION")
            } else {
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
            def minions = salt.getMinions(venvPepper, '*')

            stage('Update Reclass and Salt-Formulas') {
                common.infoMsg('Perform: Full salt sync')
                salt.fullRefresh(venvPepper, '*')
                common.infoMsg('Perform: Validate reclass medata before processing')
                validateReclassModel(minions, 'before')

                common.infoMsg('Perform: archiveReclassInventory before upgrade')
                archiveReclassInventory(inventoryBeforeFilename)

                def cluster_name = salt.getPillar(venvPepper, 'I@salt:master', '_param:cluster_name').get('return')[0].values()[0]
                try {
                    salt.cmdRun(venvPepper, 'I@salt:master', 'cd /srv/salt/reclass/ && git status && git diff-index --quiet HEAD --')
                }
                catch (Exception ex) {
                    error('You have uncommitted changes in your Reclass cluster model repository. Please commit or reset them and rerun the pipeline.')
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
                    def updateRepoList = ['cassandra', 'ceph', 'contrail', 'docker', 'elastic', 'extra', 'openstack', 'percona', 'salt-formulas', 'saltstack', 'ubuntu']
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
                try {
                    common.infoMsg('Perform: UPDATE Salt Formulas')
                    salt.fullRefresh(venvPepper, '*')
                    salt.enforceState(venvPepper, 'I@salt:master', 'linux.system.repo')
                    def saltEnv = salt.getPillar(venvPepper, 'I@salt:master', "_param:salt_master_base_environment").get("return")[0].values()[0]
                    salt.runSaltProcessStep(venvPepper, 'I@salt:master', 'state.sls_id', ["salt_master_${saltEnv}_pkg_formulas", 'salt.master.env'])
                    salt.fullRefresh(venvPepper, '*')
                } catch (Exception updateErr) {
                    common.warningMsg(updateErr)
                    common.warningMsg('Failed to update Salt Formulas repos/packages. Check current available documentation on https://docs.mirantis.com/mcp/latest/, how to update packages.')
                    input message: 'Continue anyway?'
                }

                wa29352(minions, cluster_name)
                wa29155(minions, cluster_name)

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

                    error('Reclass fails rendering. Pay attention to your cluster model.' +
                        'ErrorMessage:' + ex.toString())
                }

                common.infoMsg('Perform: archiveReclassInventory AFTER upgrade')
                archiveReclassInventory(inventoryAfterFilename)

                sh "diff -u $inventoryBeforeFilename $inventoryAfterFilename > reclass-inventory-diff.out || true"
                archiveArtifacts artifacts: "reclass-inventory-diff.out"

                validateReclassModel(minions, 'after')
                archiveReclassModelChanges(minions)
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

            stage('Update Drivetrain') {
                if (upgradeSaltStack) {
                    updateSaltStack("I@salt:master", '["salt-master", "salt-common", "salt-api", "salt-minion"]')

                    salt.enforceState(venvPepper, "I@linux:system", 'linux.system.repo', true)
                    updateSaltStack("I@salt:minion and not I@salt:master", '["salt-minion"]')
                }

                if (updatePipelines) {
                    common.infoMsg('Perform: UPDATE git repos')
                    triggerMirrorJob('git-mirror-downstream-mk-pipelines', reclassSystemBranch)
                    triggerMirrorJob('git-mirror-downstream-pipeline-library', reclassSystemBranch)
                }

                // Retry needed only for rare race-condition in user appearance
                common.infoMsg('Perform: updating users and keys')
                salt.enforceState(venvPepper, "I@linux:system", 'linux.system.user', true)
                common.infoMsg('Perform: updating openssh')
                salt.enforceState(venvPepper, "I@linux:system", 'openssh', true)

                salt.enforceState(venvPepper, 'I@jenkins:client and not I@salt:master', 'jenkins.client', true)
                salt.cmdRun(venvPepper, "I@salt:master", "salt -C 'I@jenkins:client and I@docker:client and not I@salt:master' state.sls docker.client --async")

                sleep(180)

                common.infoMsg('Perform: Checking if Docker containers are up')

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
