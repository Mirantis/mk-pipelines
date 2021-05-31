/*
Able to be triggered from Gerrit if :
Variators:
Modes:
1) manual run via job-build , possible to pass refspec
   TODO: currently impossible to use custom COOKIECUTTER_TEMPLATE_URL| RECLASS_SYSTEM_URL Gerrit-one always used.
   - for CC
   - Reclass

2) gerrit trigger
   Automatically switches if GERRIT_PROJECT variable detected
   Always test GERRIT_REFSPEC VS GERRIT_BRANCH-master version of opposite project
 */

import groovy.json.JsonOutput

common = new com.mirantis.mk.Common()
mcpCommon = new com.mirantis.mcp.Common()
gerrit = new com.mirantis.mk.Gerrit()
python = new com.mirantis.mk.Python()

extraVarsYAML = env.EXTRA_VARIABLES_YAML.trim() ?: ''
if (extraVarsYAML) {
    common.mergeEnv(env, extraVarsYAML)
    extraVars = readYaml text: extraVarsYAML
} else {
    extraVars = [:]
}

slaveNode = env.SLAVE_NODE ?: 'old16.04'
checkIncludeOrder = env.CHECK_INCLUDE_ORDER ?: false

// Global var's
gerritConData = [credentialsId       : env.CREDENTIALS_ID,
                 gerritName          : env.GERRIT_NAME ?: 'mcp-jenkins',
                 gerritHost          : env.GERRIT_HOST ?: 'gerrit.mcp.mirantis.com',
                 gerritScheme        : env.GERRIT_SCHEME ?: 'ssh',
                 gerritPort          : env.GERRIT_PORT ?: '29418',
                 gerritRefSpec       : null,
                 gerritProject       : null,
                 withWipeOut         : true,
                 GERRIT_CHANGE_NUMBER: null]
//
//ccTemplatesRepo = env.COOKIECUTTER_TEMPLATE_URL ?: 'ssh://mcp-jenkins@gerrit.mcp.mirantis.com:29418/mk/cookiecutter-templates'
gerritDataCCHEAD = [:]
gerritDataCC = [:]
gerritDataCC << gerritConData
gerritDataCC['gerritBranch'] = env.COOKIECUTTER_TEMPLATE_BRANCH ?: 'master'
gerritDataCC['gerritRefSpec'] = env.COOKIECUTTER_TEMPLATE_REF ?: null
gerritDataCC['gerritProject'] = 'mk/cookiecutter-templates'
//
//reclassSystemRepo = env.RECLASS_SYSTEM_URL ?: 'ssh://mcp-jenkins@gerrit.mcp.mirantis.com:29418/salt-models/reclass-system'
gerritDataRSHEAD = [:]
gerritDataRS = [:]
gerritDataRS << gerritConData
gerritDataRS['gerritBranch'] = env.RECLASS_SYSTEM_BRANCH ?: 'master'
gerritDataRS['gerritRefSpec'] = env.RECLASS_SYSTEM_GIT_REF ?: null
gerritDataRS['gerritProject'] = 'salt-models/reclass-system'

// version of debRepos, aka formulas|reclass|ubuntu
testDistribRevision = env.DISTRIB_REVISION ?: 'nightly'
updatesVersion = ''

// Name of sub-test chunk job
chunkJobName = "test-mk-cookiecutter-templates-chunk"
testModelBuildsData = [:]

def getAndUnpackNodesInfoArtifact(jobName, copyTo, build) {
    return {
        dir(copyTo) {
            copyArtifacts(projectName: jobName, selector: specific(build), filter: "nodesinfo.tar.gz")
            sh "tar -xf nodesinfo.tar.gz"
            sh "rm -v nodesinfo.tar.gz"
        }
    }
}

def testModel(modelFile, reclassArtifactName, artifactCopyPath, useExtraRepos = false) {
    // modelFile - `modelfiname` from model/modelfiname/modelfiname.yaml
    //* Grub all models and send it to check in paralell - by one in thread.
    def _uuid = "${env.JOB_NAME.toLowerCase()}_${env.BUILD_TAG.toLowerCase()}_${modelFile.toLowerCase()}_" + UUID.randomUUID().toString().take(8)
    def _values = [
        MODELS_TARGZ    : "${env.BUILD_URL}/artifact/${reclassArtifactName}",
        DockerCName     : _uuid,
        testReclassEnv  : "model/${modelFile}/",
        modelFile       : "contexts/${modelFile}.yml",
        DISTRIB_REVISION: testDistribRevision,
        useExtraRepos   : useExtraRepos,
        updatesVersion  : updatesVersion,
    ]
    def _values_string = JsonOutput.toJson(_values << extraVars)
    def chunkJob = build job: chunkJobName, parameters: [
        [$class: 'TextParameterValue', name: 'EXTRA_VARIABLES_YAML',
         value : _values_string.stripIndent()],
    ]
    // Put sub-job info into global map.
    testModelBuildsData.put(_uuid, ['jobname'  : chunkJob.fullProjectName,
                                    'copyToDir': "${artifactCopyPath}/${modelFile}",
                                    'buildId'  : "${chunkJob.number}"])
}

def StepTestModel(_basename, _reclassArtifactName, _artifactCopyPath, _useExtraRepos = false) {
    // We need to wrap what we return in a Groovy closure, or else it's invoked
    // when this method is called, not when we pass it to parallel.
    // To do this, you need to wrap the code below in { }, and either return
    // that explicitly, or use { -> } syntax.
    // return node object
    return {
        node(slaveNode) {
            testModel(_basename, _reclassArtifactName, _artifactCopyPath, _useExtraRepos)
        }
    }
}

def StepPrepareGit(templateEnvFolder, gerrit_data) {
    // return git clone  object
    return {
        common.infoMsg("StepPrepareGit: ${gerrit_data}")
        // fetch needed sources
        dir(templateEnvFolder) {
            if (!gerrit_data['gerritRefSpec']) {
                // Get clean HEAD
                gerrit_data['useGerritTriggerBuildChooser'] = false
            }
            def checkouted = gerrit.gerritPatchsetCheckout(gerrit_data)
            if (!checkouted) {
                error("Failed to get repo:${gerrit_data}")
            }
        }
    }
}

def StepGenerateModels(_contextFileList, _virtualenv, _templateEnvDir) {
    return {
        if (fileExists(new File(_templateEnvDir, 'tox.ini').toString())) {
            // Merge contexts for nice base.yml based diff
            dir(_templateEnvDir) {
                sh('tox -ve merge_contexts')
            }
        }
        for (contextFile in _contextFileList) {
            def basename = common.GetBaseName(contextFile, '.yml')
            def contextYaml = readYaml text: readFile(file: "${_templateEnvDir}/contexts/${contextFile}")
            // secrets_encryption overcomplicated for expected 'fast syntax tests'
            // So, lets disable it. It would be tested only in generate-cookiecutter-products.groovy pipeline
            if (contextYaml['default_context'].get('secrets_encryption_enabled')) {
                common.warningMsg('Disabling secrets_encryption_enabled for tests!')
                contextYaml['default_context']['secrets_encryption_enabled'] = 'False'
            }

            // disabling strong_usernames for tests to reduce diff between head and patched model
            common.warningMsg('Disabling strong_usernames for tests!')
            contextYaml['default_context']['strong_usernames'] = 'False'

            def context = mcpCommon.dumpYAML(contextYaml)
            if (!fileExists(new File(_templateEnvDir, 'tox.ini').toString())) {
                common.warningMsg('Forming NEW reclass-root structure...')
                python.generateModel(context, basename, 'cfg01', _virtualenv, "${_templateEnvDir}/model", _templateEnvDir)
            } else {
                // tox-based CC generated structure of reclass,from the root. Otherwise for bw compat, modelEnv
                // still expect only lower lvl of project, aka model/classes/cluster/XXX/. So,lets dump result into
                // temp dir, and then copy it over initial structure.
                def reclassTempRootDir = sh(script: "mktemp -d -p ${env.WORKSPACE}", returnStdout: true).trim()
                python.generateModel(context, basename, 'cfg01', _virtualenv, reclassTempRootDir, _templateEnvDir)
                dir("${_templateEnvDir}/model/${basename}/") {
                    if (fileExists(new File(reclassTempRootDir, 'reclass').toString())) {
                        common.warningMsg('Forming NEW reclass-root structure...')
                        sh("cp -ra ${reclassTempRootDir}/reclass/* .")
                    } else {
                        // those hack needed only for period release/2019.2.0 => current patch.
                        common.warningMsg('Forming OLD reclass-root structure...')
                        sh("mkdir -p classes/cluster/ ; cd classes/cluster/; cp -ra ${reclassTempRootDir}/* .")
                    }
                }
            }
        }
    }
}

def globalVariatorsUpdate() {
    // Simple function, to check and define branch-around variables
    // In general, simply make transition updates for non-master branch
    // based on magic logic
    def newline = '<br/>'
    def messages = []
    if (env.GERRIT_PROJECT) {
        messages.add("<font color='red'>GerritTrigger detected! We are in auto-mode:</font>")
        messages.add("Test env variables has been changed:")
        // TODO are we going to have such branches?
        if (!['nightly', 'testing', 'stable', 'proposed', 'master'].contains(env.GERRIT_BRANCH)) {
            gerritDataCC['gerritBranch'] = env.GERRIT_BRANCH
            gerritDataRS['gerritBranch'] = env.GERRIT_BRANCH
            testDistribRevision = env.GERRIT_BRANCH
        }
        messages.add("COOKIECUTTER_TEMPLATE_BRANCH => ${gerritDataCC['gerritBranch']}")
        messages.add("RECLASS_SYSTEM_BRANCH => ${gerritDataRS['gerritBranch']}")
        // Identify, who triggered. To whom we should pass refspec
        if (env.GERRIT_PROJECT == 'salt-models/reclass-system') {
            gerritDataRS['gerritRefSpec'] = env.GERRIT_REFSPEC
            gerritDataRS['GERRIT_CHANGE_NUMBER'] = env.GERRIT_CHANGE_NUMBER
            messages.add("RECLASS_SYSTEM_GIT_REF => ${gerritDataRS['gerritRefSpec']}")
        } else if (env.GERRIT_PROJECT == 'mk/cookiecutter-templates') {
            gerritDataCC['gerritRefSpec'] = env.GERRIT_REFSPEC
            gerritDataCC['GERRIT_CHANGE_NUMBER'] = env.GERRIT_CHANGE_NUMBER
            messages.add("COOKIECUTTER_TEMPLATE_REF => ${gerritDataCC['gerritRefSpec']}")
        } else {
            error("Unsuported gerrit-project triggered:${env.GERRIT_PROJECT}")
        }
    } else {
        messages.add("<font color='red'>Non-gerrit trigger run detected!</font>")
    }
    gerritDataCCHEAD << gerritDataCC
    gerritDataCCHEAD['gerritRefSpec'] = null
    gerritDataCCHEAD['GERRIT_CHANGE_NUMBER'] = null
    gerritDataRSHEAD << gerritDataRS
    gerritDataRSHEAD['gerritRefSpec'] = null
    gerritDataRSHEAD['GERRIT_CHANGE_NUMBER'] = null
    // check for test XXX vs RELEASE branch, to get correct formulas
    if (gerritDataCC['gerritBranch'].contains('release/')) {
        testDistribRevision = gerritDataCC['gerritBranch']
    } else if (gerritDataRS['gerritBranch'].contains('release')) {
        testDistribRevision = gerritDataRS['gerritBranch']
    }
    // 'binary' branch logic w\o 'release/' prefix
    if (testDistribRevision.contains('/')) {
        if (testDistribRevision.contains('proposed')) {
            updatesVersion = 'proposed'
        }
        testDistribRevision = testDistribRevision.split('/')[-1]
    }
    // Check if we are going to test bleeding-edge release, which doesn't have binary release yet
    // After 2018q4 releases, need to also check 'static' repo, for example ubuntu.
    binTest = common.checkRemoteBinary(['mcp_version': testDistribRevision])
    if (!binTest.linux_system_repo_url || !binTest.linux_system_repo_ubuntu_url) {
        common.errorMsg("Binary release: ${testDistribRevision} not exist or not full. Fallback to 'proposed'! ")
        testDistribRevision = 'proposed'
    }
    messages.add("DISTRIB_REVISION => ${testDistribRevision}")
    def message = messages.join(newline) + newline
    currentBuild.description = currentBuild.description ? message + currentBuild.description : message
}

def replaceGeneratedValues(path) {
    def files = sh(script: "find ${path} -name 'secrets.yml'", returnStdout: true)
    def stepsForParallel = [:]
    stepsForParallel.failFast = true
    files.tokenize().each {
        stepsForParallel.put("Removing generated passwords/secrets from ${it}",
            {
                def secrets = readYaml file: it
                for (String key in secrets['parameters']['_param'].keySet()) {
                    secrets['parameters']['_param'][key] = 'generated'
                }
                // writeYaml can't write to already existing file
                writeYaml file: "${it}.tmp", data: secrets
                sh "mv ${it}.tmp ${it}"
            })
    }
    parallel stepsForParallel
}

def linkReclassModels(contextList, envPath, archiveName) {
    // to be able share reclass for all subenvs
    // Also, makes artifact test more solid - use one reclass for all of sub-models.
    // Archive Structure will be:
    // tar.gz
    // ├── contexts
    // │   └── ceph.yml
    // ├── classes-system <<< reclass system
    // ├── model
    // │   └── ceph       <<< from `context basename`
    // │       ├── classes
    // │       │   ├── cluster
    // │       │   └── system -> ../../../classes-system
    // │       └── nodes
    // │           └── cfg01.ceph-cluster-domain.local.yml
    def archiveBaseName = common.GetBaseName(archiveName, '.tar.gz')
    def classesSystemDir = 'classes-system'
    // copy reclass system under envPath with -R and trailing / to support symlinks direct copy
    sh("cp -R ${archiveBaseName}/ ${envPath}/${classesSystemDir}")
    dir(envPath) {
        for (String _context : contextList) {
            def basename = common.GetBaseName(_context, '.yml')
            dir("${envPath}/model/${basename}/classes") {
                sh(script: "ln -sfv ../../../${classesSystemDir} system ")
            }
        }
        // replace all generated passwords/secrets/keys with hardcode value for infra/secrets.yaml
        replaceGeneratedValues("${envPath}/model")
        // Save all models and all contexts. Warning! `h` flag must be used!
        sh(script: "set -ex; tar -czhf ${env.WORKSPACE}/${archiveName} --exclude='*@tmp' contexts model ${classesSystemDir}", returnStatus: true)
    }
    archiveArtifacts artifacts: archiveName
}

timeout(time: 1, unit: 'HOURS') {
    node(slaveNode) {
        globalVariatorsUpdate()
        def templateEnvHead = "${env.WORKSPACE}/EnvHead/"
        def templateEnvPatched = "${env.WORKSPACE}/EnvPatched/"
        def contextFileListHead = []
        def contextFileListPatched = []
        def vEnv = "${env.WORKSPACE}/venv"
        def headReclassArtifactName = "head_reclass.tar.gz"
        def patchedReclassArtifactName = "patched_reclass.tar.gz"
        def reclassNodeInfoDir = "${env.WORKSPACE}/reclassNodeInfo_compare/"
        def reclassInfoHeadPath = "${reclassNodeInfoDir}/old"
        def reclassInfoPatchedPath = "${reclassNodeInfoDir}/new"
        try {
            sh(script: 'find . -mindepth 1 -delete > /dev/null || true')
            stage('Download and prepare CC env') {
                // Prepare 2 env - for patchset, and for HEAD
                def paralellEnvs = [:]
                paralellEnvs.failFast = true
                paralellEnvs['downloadEnvHead'] = StepPrepareGit(templateEnvHead, gerritDataCCHEAD)
                if (gerritDataCC.get('gerritRefSpec', null)) {
                    paralellEnvs['downloadEnvPatched'] = StepPrepareGit(templateEnvPatched, gerritDataCC)
                    parallel paralellEnvs
                } else {
                    paralellEnvs['downloadEnvPatched'] = { common.warningMsg('No need to process: downloadEnvPatched') }
                    parallel paralellEnvs
                    sh("rsync -a --exclude '*@tmp' ${templateEnvHead} ${templateEnvPatched}")
                }
                if (env.CUSTOM_COOKIECUTTER_CONTEXT) {
                    // readYaml to check custom context structure
                    def customContext = readYaml text: env.CUSTOM_COOKIECUTTER_CONTEXT
                    writeYaml file: "${templateEnvHead}/contexts/custom_context.yml", data: customContext
                    writeYaml file: "${templateEnvPatched}/contexts/custom_context.yml", data: customContext
                    common.infoMsg("Using custom context provided from job parameter 'CUSTOM_COOKIECUTTER_CONTEXT'")
                }
            }
            stage('Check workflow_definition') {
                // Prepare venv for old env's, aka non-tox based
                if (!fileExists(new File(templateEnvPatched, 'tox.ini').toString()) || !fileExists(new File(templateEnvHead, 'tox.ini').toString())) {
                    python.setupVirtualenv(vEnv, 'python2', [], "${templateEnvPatched}/requirements.txt")
                }
                // Check only for patchset
                if (fileExists(new File(templateEnvPatched, 'tox.ini').toString())) {
                    dir(templateEnvPatched) {
                        output = sh(returnStdout: true, script: "tox -ve test")
                        common.infoMsg("[Cookiecutter test] Result: ${output}")
                    }

                } else {
                    common.warningMsg('Old Cookiecutter env detected!')
                    common.infoMsg(python.runVirtualenvCommand(vEnv, "python ${templateEnvPatched}/workflow_definition_test.py"))
                }
            }

            stage('generate models') {
                dir("${templateEnvHead}/contexts") {
                    for (String x : findFiles(glob: "*.yml")) {
                        contextFileListHead.add(x)
                    }
                }
                dir("${templateEnvPatched}/contexts") {
                    for (String x : findFiles(glob: "*.yml")) {
                        contextFileListPatched.add(x)
                    }
                }
                // Generate over 2env's - for patchset, and for HEAD
                def paralellEnvs = [:]
                paralellEnvs.failFast = true
                paralellEnvs['GenerateEnvHead'] = StepGenerateModels(contextFileListHead, vEnv, templateEnvHead)
                if (gerritDataCC.get('gerritRefSpec', null)) {
                    paralellEnvs['GenerateEnvPatched'] = StepGenerateModels(contextFileListPatched, vEnv, templateEnvPatched)
                    parallel paralellEnvs
                } else {
                    paralellEnvs['GenerateEnvPatched'] = { common.warningMsg('No need to process: GenerateEnvPatched') }
                    parallel paralellEnvs
                    sh("rsync -a --exclude '*@tmp' ${templateEnvHead} ${templateEnvPatched}")
                }

                // We need 2 git's, one for HEAD, one for PATCHed.
                // if no patch, use head for both
                RSHeadDir = common.GetBaseName(headReclassArtifactName, '.tar.gz')
                RSPatchedDir = common.GetBaseName(patchedReclassArtifactName, '.tar.gz')
                common.infoMsg("gerritDataRS= ${gerritDataRS}")
                common.infoMsg("gerritDataRSHEAD= ${gerritDataRSHEAD}")
                if (gerritDataRS.get('gerritRefSpec', null)) {
                    StepPrepareGit("${env.WORKSPACE}/${RSPatchedDir}/", gerritDataRS).call()
                    StepPrepareGit("${env.WORKSPACE}/${RSHeadDir}/", gerritDataRSHEAD).call()
                } else {
                    StepPrepareGit("${env.WORKSPACE}/${RSHeadDir}/", gerritDataRS).call()
                    sh("cd ${env.WORKSPACE} ; ln -svf ${RSHeadDir} ${RSPatchedDir}")
                }
                // link all models, to use one global reclass
                // For HEAD
                linkReclassModels(contextFileListHead, templateEnvHead, headReclassArtifactName)
                // For patched
                linkReclassModels(contextFileListPatched, templateEnvPatched, patchedReclassArtifactName)
            }

            stage("Compare cluster lvl Head/Patched") {
                // Compare patched and HEAD reclass pillars
                compareRoot = "${env.WORKSPACE}/cluster_compare/"
                // extract archive and drop all copied classes/system before comparing
                sh(script: """
                   mkdir -pv ${compareRoot}/new ${compareRoot}/old
                   tar -xzf ${patchedReclassArtifactName}  --directory ${compareRoot}/new
                   tar -xzf ${headReclassArtifactName}  --directory ${compareRoot}/old
                   find ${compareRoot} -name classes -type d -exec rm -rf '{}/system' \\;
                   """)
                common.warningMsg('infra/secrets.yml has been skipped from compare!')
                result = '\n' + common.comparePillars(compareRoot, env.BUILD_URL, "-Ev \'infra/secrets.yml|\\.git\'")
                currentBuild.description = currentBuild.description ? currentBuild.description + result : result
            }
            stage('TestContexts Head/Patched') {
                def stepsForParallel = [:]
                stepsForParallel.failFast = true
                common.infoMsg("Found: ${contextFileListHead.size()} HEAD contexts to test.")
                for (String context : contextFileListHead) {
                    def basename = common.GetBaseName(context, '.yml')
                    stepsForParallel.put("ContextHeadTest:${basename}", StepTestModel(basename, headReclassArtifactName, reclassInfoHeadPath))
                }
                common.infoMsg("Found: ${contextFileListPatched.size()} patched contexts to test.")
                for (String context : contextFileListPatched) {
                    def basename = common.GetBaseName(context, '.yml')
                    stepsForParallel.put("ContextPatchedTest:${basename}", StepTestModel(basename, patchedReclassArtifactName, reclassInfoPatchedPath, true))
                }
                parallel stepsForParallel
                common.infoMsg('All TestContexts tests done')
            }
            stage('Compare NodesInfo Head/Patched') {
                // Download all artifacts
                def stepsForParallel = [:]
                stepsForParallel.failFast = true
                common.infoMsg("Found: ${testModelBuildsData.size()} nodeinfo artifacts to download.")
                testModelBuildsData.each { bname, bdata ->
                    stepsForParallel.put("FetchData:${bname}",
                        getAndUnpackNodesInfoArtifact(bdata.jobname, bdata.copyToDir, bdata.buildId))
                }
                parallel stepsForParallel
                // remove timestamp field from rendered files
                sh("find ${reclassNodeInfoDir} -type f -exec sed -i '/  timestamp: .*/d' {} \\;")
                // Compare patched and HEAD reclass pillars
                result = '\n' + common.comparePillars(reclassNodeInfoDir, env.BUILD_URL, '')
                currentBuild.description = currentBuild.description ? currentBuild.description + result : result
            }
            stage('Check include order') {
                if (!checkIncludeOrder) {
                    common.infoMsg('Check include order require to much time, and currently disabled!')

                } else {
                    def correctIncludeOrder = ["service", "system", "cluster"]
                    dir(reclassInfoPatchedPath) {
                        def nodeInfoFiles = findFiles(glob: "**/*.reclass.nodeinfo")
                        def messages = ["<b>Wrong include ordering found</b><ul>"]
                        def stepsForParallel = [:]
                        nodeInfoFiles.each { nodeInfo ->
                            stepsForParallel.put("Checking ${nodeInfo.path}:", {
                                def node = readYaml file: nodeInfo.path
                                def classes = node['classes']
                                def curClassID = 0
                                def prevClassID = 0
                                def wrongOrder = false
                                for (String className in classes) {
                                    def currentClass = className.tokenize('.')[0]
                                    curClassID = correctIncludeOrder.indexOf(currentClass)
                                    if (currentClass != correctIncludeOrder[prevClassID]) {
                                        if (prevClassID > curClassID) {
                                            wrongOrder = true
                                            common.warningMsg("File ${nodeInfo.path} contains wrong order of classes including: Includes for ${className} should be declared before ${correctIncludeOrder[prevClassID]} includes")
                                        } else {
                                            prevClassID = curClassID
                                        }
                                    }
                                }
                                if (wrongOrder) {
                                    messages.add("<li>${nodeInfo.path} contains wrong order of classes including</li>")
                                }
                            })
                        }
                        parallel stepsForParallel
                        def includerOrder = '<b>No wrong include order</b>'
                        if (messages.size() != 1) {
                            includerOrder = messages.join('')
                        }
                        currentBuild.description = currentBuild.description ? currentBuild.description + includerOrder : includerOrder
                    }
                }
            }
            sh(script: 'find . -mindepth 1 -delete > /dev/null || true')

        } catch (Throwable e) {
            currentBuild.result = "FAILURE"
            currentBuild.description = currentBuild.description ? e.message + " " + currentBuild.description : e.message
            throw e
        } finally {
            stage('Save artifacts to Artifactory') {
                def artifactory = new com.mirantis.mcp.MCPArtifactory()
                def artifactoryLink = artifactory.uploadJobArtifactsToArtifactory(['artifactory': 'mcp-ci', 'artifactoryRepo': "artifactory/drivetrain-local/${JOB_NAME}/${BUILD_NUMBER}"])
                currentBuild.description += "<br/>${artifactoryLink}"
            }
        }
    }
}

