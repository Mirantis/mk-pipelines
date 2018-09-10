/*
Able to be triggered from Gerrit if :
Variators:
Modes:
1) manual run via job-build , possible to pass refspec
   - for CC
   - Reclass
   TODO: currently impossible to use custom COOKIECUTTER_TEMPLATE_URL| RECLASS_SYSTEM_URL Gerrit-one always used.
 - gerrit trigget.
   Automatically switches if GERRIT_PROJECT variable detected
   Always test GERRIT_REFSPEC VS GERRIT_BRANCH-master version of opposite project
 */

common = new com.mirantis.mk.Common()
gerrit = new com.mirantis.mk.Gerrit()
git = new com.mirantis.mk.Git()
python = new com.mirantis.mk.Python()

slaveNode = env.SLAVE_NODE ?: 'python&&docker'

// Global var's
alreadyMerged = false
gerritConData = [credentialsId       : env.CREDENTIALS_ID,
                 gerritName          : env.GERRIT_NAME ?: 'mcp-jenkins',
                 gerritHost          : env.GERRIT_HOST ?: 'gerrit.mcp.mirantis.net',
                 gerritRefSpec       : null,
                 gerritProject       : null,
                 withWipeOut         : true,
                 GERRIT_CHANGE_NUMBER: null]
//
//ccTemplatesRepo = env.COOKIECUTTER_TEMPLATE_URL ?: 'ssh://mcp-jenkins@gerrit.mcp.mirantis.net:29418/mk/cookiecutter-templates'
gerritDataCC = [:]
gerritDataCC << gerritConData
gerritDataCC['gerritBranch'] = env.COOKIECUTTER_TEMPLATE_BRANCH ?: 'master'
gerritDataCC['gerritProject'] = 'mk/cookiecutter-templates'
//
//reclassSystemRepo = env.RECLASS_SYSTEM_URL ?: 'ssh://mcp-jenkins@gerrit.mcp.mirantis.net:29418/salt-models/reclass-system'
gerritDataRS = [:]
gerritDataRS << gerritConData
gerritDataRS['gerritBranch'] = env.RECLASS_MODEL_BRANCH ?: 'master'
gerritDataRS['gerritProject'] = 'salt-models/reclass-system'

// version of debRepos, aka formulas\reclass
testDistribRevision = env.DISTRIB_REVISION ?: 'nightly'
reclassVersion = 'v1.5.4'
if (common.validInputParam(env.RECLASS_VERSION)) {
    reclassVersion = env.RECLASS_VERSION
}

def generateSaltMaster(modEnv, clusterDomain, clusterName) {
    def nodeFile = "${modEnv}/nodes/cfg01.${clusterDomain}.yml"
    def nodeString = """classes:
- cluster.${clusterName}.infra.config
parameters:
    _param:
        linux_system_codename: xenial
        reclass_data_revision: master
    linux:
        system:
            name: cfg01
            domain: ${clusterDomain}
"""
    sh "mkdir -p ${modEnv}/nodes/"
    println "Create file ${nodeFile}"
    writeFile(file: nodeFile, text: nodeString)
}

/**
 *
 * @param contextFile - path to `contexts/XXX.yaml file`
 * @param virtualenv - pyvenv with CC and dep's
 * @param templateEnvDir - root of CookieCutter
 * @return
 */

def generateModel(contextFile, virtualenv, templateEnvDir) {
    def modelEnv = "${templateEnvDir}/model"
    def basename = common.GetBaseName(contextFile, '.yml')
    def generatedModel = "${modelEnv}/${basename}"
    def content = readFile(file: "${templateEnvDir}/contexts/${contextFile}")
    def templateContext = readYaml text: content
    def clusterDomain = templateContext.default_context.cluster_domain
    def clusterName = templateContext.default_context.cluster_name
    def outputDestination = "${generatedModel}/classes/cluster/${clusterName}"
    def templateBaseDir = templateEnvDir
    def templateDir = "${templateEnvDir}/dir"
    def templateOutputDir = templateBaseDir
    dir(templateEnvDir) {
        sh(script: "rm -rf ${generatedModel} || true")
        common.infoMsg("Generating model from context ${contextFile}")
        def productList = ["infra", "cicd", "opencontrail", "kubernetes", "openstack", "oss", "stacklight", "ceph"]
        for (product in productList) {

            // get templateOutputDir and productDir
            if (product.startsWith("stacklight")) {
                templateOutputDir = "${templateEnvDir}/output/stacklight"
                try {
                    productDir = "stacklight" + templateContext.default_context['stacklight_version']
                } catch (Throwable e) {
                    productDir = "stacklight1"
                }
            } else {
                templateOutputDir = "${templateEnvDir}/output/${product}"
                productDir = product
            }

            if (product == "infra" || (templateContext.default_context["${product}_enabled"]
                && templateContext.default_context["${product}_enabled"].toBoolean())) {

                templateDir = "${templateEnvDir}/cluster_product/${productDir}"
                common.infoMsg("Generating product " + product + " from " + templateDir + " to " + templateOutputDir)

                sh "rm -rf ${templateOutputDir} || true"
                sh "mkdir -p ${templateOutputDir}"
                sh "mkdir -p ${outputDestination}"

                python.buildCookiecutterTemplate(templateDir, content, templateOutputDir, virtualenv, templateBaseDir)
                sh "mv -v ${templateOutputDir}/${clusterName}/* ${outputDestination}"
            } else {
                common.warningMsg("Product " + product + " is disabled")
            }
        }
        generateSaltMaster(generatedModel, clusterDomain, clusterName)
    }
}


def testModel(modelFile, reclassVersion = 'v1.5.4') {
    // modelFile - `modelfiname` from model/modelfiname/modelfiname.yaml
    //* Grub all models and send it to check in paralell - by one in thread.

    _values_string = """
  ---
  MODELS_TARGZ: "${env.BUILD_URL}/artifact/patched_reclass.tar.gz"
  DockerCName: "${env.JOB_NAME.toLowerCase()}_${env.BUILD_TAG.toLowerCase()}_${modelFile.toLowerCase()}"
  testReclassEnv: "model/${modelFile}/"
  modelFile: "contexts/${modelFile}.yml"
  DISTRIB_REVISION: "${testDistribRevision}"
  EXTRA_FORMULAS: "${env.EXTRA_FORMULAS}"
  reclassVersion: "${reclassVersion}"
  """
    build job: "test-mk-cookiecutter-templates-chunk", parameters: [
        [$class: 'StringParameterValue', name: 'EXTRA_VARIABLES_YAML',
         value : _values_string.stripIndent()],
    ]
}

def StepTestModel(basename) {
    // We need to wrap what we return in a Groovy closure, or else it's invoked
    // when this method is called, not when we pass it to parallel.
    // To do this, you need to wrap the code below in { }, and either return
    // that explicitly, or use { -> } syntax.
    // return node object
    return {
        node(slaveNode) {
            testModel(basename)
        }
    }
}

def StepPrepareGit(templateEnvFolder, gerrit_data) {
    // return git clone  object
    return {
        def checkouted = false
        common.infoMsg("StepPrepareGit: ${gerrit_data}")
        // fetch needed sources
        dir(templateEnvFolder) {
            if (gerrit_data['gerritRefSpec']) {
                // Those part might be not work,in case manual var's pass
                def gerritChange = gerrit.getGerritChange(gerrit_data['gerritName'], gerrit_data['gerritHost'],
                    gerrit_data['GERRIT_CHANGE_NUMBER'], gerrit_data['credentialsId'])
                merged = gerritChange.status == "MERGED"
                if (!merged) {
                    checkouted = gerrit.gerritPatchsetCheckout(gerrit_data)
                } else {
                    // update global variable for pretty return from pipeline
                    alreadyMerged = true
                    common.successMsg("Change ${gerrit_data['GERRIT_CHANGE_NUMBER']} is already merged, no need to gate them")
                    error('change already merged')
                }
            } else {
                // Get clean HEAD
                gerrit_data['useGerritTriggerBuildChooser'] = false
                checkouted = gerrit.gerritPatchsetCheckout(gerrit_data)
                if (!checkouted) {
                    error("Failed to get repo:${gerrit_data}")
                }
            }
        }
    }
}

def StepGenerateModels(_contextFileList, _virtualenv, _templateEnvDir) {
    return {
        for (contextFile in _contextFileList) {
            generateModel(contextFile, _virtualenv, _templateEnvDir)
        }
    }
}

def globalVariatorsUpdate() {
    // Simple function, to check and define branch-around variables
    // In general, simply make transition updates for non-master branch
    // based on magic logic
    def message = ''
    if (!common.validInputParam(env.GERRIT_PROJECT)) {
        if (!['nightly', 'testing', 'stable', 'proposed', 'master'].contains(env.GERRIT_BRANCH)) {
            gerritDataCC['gerritBranch'] = env.GERRIT_BRANCH
            gerritDataRS['gerritBranch'] = env.GERRIT_BRANCH
            // 'binary' branch logic w\o 'release/' prefix
            testDistribRevision = env.GERRIT_BRANCH.split('/')[-1]
            // Check if we are going to test bleeding-edge release, which doesn't have binary release yet
            if (!common.checkRemoteBinary([apt_mk_version: testDistribRevision]).linux_system_repo_url) {
                common.errorMsg("Binary release: ${testDistribRevision} not exist. Fallback to 'proposed'! ")
                testDistribRevision = 'proposed'
            }
        }
        // Identify, who triggered. To whom we should pass refspec
        if (env.GERRIT_PROJECT == 'salt-models/reclass-system') {
            gerritDataRS['gerritRefSpec'] = env.GERRIT_REFSPEC
            gerritDataRS['GERRIT_CHANGE_NUMBER'] = env.GERRIT_CHANGE_NUMBER
            message = "<br/>RECLASS_SYSTEM_GIT_REF =>${gerritDataRS['gerritRefSpec']}"
        } else if (env.GERRIT_PROJECT == 'mk/cookiecutter-templates') {
            gerritDataCC['gerritRefSpec'] = env.GERRIT_REFSPEC
            gerritDataCC['GERRIT_CHANGE_NUMBER'] = env.GERRIT_CHANGE_NUMBER
            message = "<br/>COOKIECUTTER_TEMPLATE_REF =>${gerritDataCC['gerritRefSpec']}"
        } else {
            error("Unsuported gerrit-project triggered:${env.GERRIT_PROJECT}")
        }

        message = "<font color='red'>GerritTrigger detected! We are in auto-mode:</font>" +
            "<br/>Test env variables has been changed:" +
            "<br/>COOKIECUTTER_TEMPLATE_BRANCH => ${gerritDataCC['gerritBranch']}" +
            "<br/>DISTRIB_REVISION =>${testDistribRevision}" +
            "<br/>RECLASS_MODEL_BRANCH=> ${gerritDataRS['gerritBranch']}" + message
        common.warningMsg(message)
        currentBuild.description = currentBuild.description ? message + "<br/>" + currentBuild.description : message
    } else {
        // Check for passed variables:
        if (common.validInputParam(env.RECLASS_SYSTEM_GIT_REF)) {
            gerritDataRS['gerritRefSpec'] = RECLASS_SYSTEM_GIT_REF
        }
        if (common.validInputParam(env.COOKIECUTTER_TEMPLATE_REF)) {
            gerritDataCC['gerritRefSpec'] = COOKIECUTTER_TEMPLATE_REF
        }
        message = "<font color='red'>Manual run detected!</font>" + "<br/>"
        currentBuild.description = currentBuild.description ? message + "<br/>" + currentBuild.description : message
    }

}

timeout(time: 1, unit: 'HOURS') {
    node(slaveNode) {
        globalVariatorsUpdate()
        def gerritDataCCHEAD = [:]
        def templateEnvHead = "${env.WORKSPACE}/EnvHead/"
        def templateEnvPatched = "${env.WORKSPACE}/EnvPatched/"
        def contextFileListHead = []
        def contextFileListPatched = []
        def vEnv = "${env.WORKSPACE}/venv"

        try {
            sh(script: 'find . -mindepth 1 -delete > /dev/null || true')
            stage('Download and prepare CC env') {
                // Prepare 2 env - for patchset, and for HEAD
                def paralellEnvs = [:]
                paralellEnvs.failFast = true
                paralellEnvs['downloadEnvPatched'] = StepPrepareGit(templateEnvPatched, gerritDataCC)
                gerritDataCCHEAD << gerritDataCC
                gerritDataCCHEAD['gerritRefSpec'] = null; gerritDataCCHEAD['GERRIT_CHANGE_NUMBER'] = null
                paralellEnvs['downloadEnvHead'] = StepPrepareGit(templateEnvHead, gerritDataCCHEAD)
                parallel paralellEnvs
            }
            stage("Check workflow_definition") {
                // Check only for patchset
                python.setupVirtualenv(vEnv, 'python2', [], "${templateEnvPatched}/requirements.txt")
                common.infoMsg(python.runVirtualenvCommand(vEnv, "python ${templateEnvPatched}/workflow_definition_test.py"))
            }

            stage("generate models") {
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
                paralellEnvs['GenerateEnvPatched'] = StepGenerateModels(contextFileListPatched, vEnv, templateEnvPatched)
                paralellEnvs['GenerateEnvHead'] = StepGenerateModels(contextFileListHead, vEnv, templateEnvHead)
                parallel paralellEnvs

                // Collect artifacts
                dir(templateEnvPatched) {
                    // Collect only models. For backward comparability - who know, probably someone use it..
                    sh(script: "tar -czf model.tar.gz -C model ../contexts .", returnStatus: true)
                    archiveArtifacts artifacts: "model.tar.gz"
                }

                // to be able share reclass for all subenvs
                // Also, makes artifact test more solid - use one reclass for all of sub-models.
                // Archive Structure will be:
                // tar.gz
                // ├── contexts
                // │   └── ceph.yml
                // ├── global_reclass <<< reclass system
                // ├── model
                // │   └── ceph       <<< from `context basename`
                // │       ├── classes
                // │       │   ├── cluster
                // │       │   └── system -> ../../../global_reclass
                // │       └── nodes
                // │           └── cfg01.ceph-cluster-domain.local.yml
                StepPrepareGit("${env.WORKSPACE}/global_reclass/", gerritDataRS).call()
                // link all models, to use one global reclass
                // For HEAD
                dir(templateEnvHead) {
                    for (String context : contextFileListHead) {
                        def basename = common.GetBaseName(context, '.yml')
                        dir("${templateEnvHead}/model/${basename}") {
                            sh(script: 'mkdir -p classes/; ln -sfv ../../../../global_reclass classes/system ')
                        }
                    }
                    // Save all models and all contexts. Warning! `h` flag must be used.
                    sh(script: "set -ex;tar -chzf head_reclass.tar.gz --exclude='*@tmp' model contexts")
                    archiveArtifacts artifacts: "head_reclass.tar.gz"
                    // move for "Compare Pillars" stage
                    sh(script: "mv -v head_reclass.tar.gz ${env.WORKSPACE}")
                }
                // For patched
                dir(templateEnvPatched) {
                    for (String context : contextFileListPatched) {
                        def basename = common.GetBaseName(context, '.yml')
                        dir("${templateEnvPatched}/model/${basename}") {
                            sh(script: 'mkdir -p classes/; ln -sfv ../../../../global_reclass classes/system ')
                        }
                    }
                    // Save all models and all contexts. Warning! `h` flag must be used.
                    sh(script: "set -ex;tar -chzf patched_reclass.tar.gz --exclude='*@tmp' model contexts")
                    archiveArtifacts artifacts: "patched_reclass.tar.gz"
                    // move for "Compare Pillars" stage
                    sh(script: "mv -v patched_reclass.tar.gz ${env.WORKSPACE}")
                }
            }

            stage("Compare Cluster lvl models") {
                // Compare patched and HEAD reclass pillars
                compareRoot = "${env.WORKSPACE}/test_compare/"
                sh(script: """
                   mkdir -pv ${compareRoot}/new ${compareRoot}/old
                   tar -xzf patched_reclass.tar.gz  --directory ${compareRoot}/new
                   tar -xzf head_reclass.tar.gz  --directory ${compareRoot}/old
                   """)
                common.warningMsg('infra/secrets.yml has been skipped from compare!')
                rezult = common.comparePillars(compareRoot, env.BUILD_URL, "-Ev \'infra/secrets.yml\'")
                currentBuild.description = currentBuild.description + '\n' + rezult
            }
            stage("test-contexts") {
                // Test contexts for patched only
                stepsForParallel = [:]
                common.infoMsg("Found: ${contextFileListPatched.size()} patched contexts to test.")
                for (String context : contextFileListPatched) {
                    def basename = common.GetBaseName(context, '.yml')
                    stepsForParallel.put("ContextPatchTest:${basename}", StepTestModel(basename))
                }
                parallel stepsForParallel
                common.infoMsg('All tests done')
            }
            sh(script: 'find . -mindepth 1 -delete > /dev/null || true')

        } catch (Throwable e) {
            if (alreadyMerged) {
                currentBuild.result = 'ABORTED'
                currentBuild.description = "Change ${GERRIT_CHANGE_NUMBER} is already merged, no need to gate them"
                return
            }
            currentBuild.result = "FAILURE"
            currentBuild.description = currentBuild.description ? e.message + " " + currentBuild.description : e.message
            throw e
        } finally {
            def dummy = "dummy"
        }
    }
}
