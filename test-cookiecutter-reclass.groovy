common = new com.mirantis.mk.Common()
gerrit = new com.mirantis.mk.Gerrit()
git = new com.mirantis.mk.Git()
python = new com.mirantis.mk.Python()

gerritRef = env.GERRIT_REFSPEC ?: null
slaveNode = (env.SLAVE_NODE ?: 'python&&docker')
def alreadyMerged = false

def reclassVersion = 'v1.5.4'
if (common.validInputParam('RECLASS_VERSION')) {
    reclassVersion = RECLASS_VERSION
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


def testModel(modelFile, reclassVersion = 'v1.5.4') {
    // modelFile - `modelfiname` from model/modelfiname/modelfiname.yaml
    //* Grub all models and send it to check in paralell - by one in thread.

    _values_string = """
  ---
  MODELS_TARGZ: "${env.BUILD_URL}/artifact/patched_reclass.tar.gz"
  DockerCName: "${env.JOB_NAME.toLowerCase()}_${env.BUILD_TAG.toLowerCase()}_${modelFile.toLowerCase()}"
  testReclassEnv: "model/${modelFile}/"
  modelFile: "contexts/${modelFile}.yml"
  DISTRIB_REVISION: "${DISTRIB_REVISION}"
  EXTRA_FORMULAS: "${env.EXTRA_FORMULAS}"
  reclassVersion: "${reclassVersion}"
  """
    build job: "test-mk-cookiecutter-templates-chunk", parameters: [
        [$class: 'StringParameterValue', name: 'EXTRA_VARIABLES_YAML',
         value: _values_string.stripIndent()],
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

def StepPrepareCCenv(refchange, templateEnvFolder) {
    // return git clone  object
    return {
        // fetch needed sources
        dir(templateEnvFolder) {
            if (refchange) {
                def gerritChange = gerrit.getGerritChange(GERRIT_NAME, GERRIT_HOST, GERRIT_CHANGE_NUMBER, CREDENTIALS_ID)
                merged = gerritChange.status == "MERGED"
                if (!merged) {
                    checkouted = gerrit.gerritPatchsetCheckout([
                        credentialsId: CREDENTIALS_ID
                    ])
                } else {
                    // update global variable for success return from pipeline
                    //alreadyMerged = true
                    common.successMsg("Change ${GERRIT_CHANGE_NUMBER} is already merged, no need to gate them")
                    currentBuild.result = 'ABORTED'
                    throw new hudson.AbortException('change already merged')
                }
            } else {
                git.checkoutGitRepository(templateEnvFolder, COOKIECUTTER_TEMPLATE_URL, COOKIECUTTER_TEMPLATE_BRANCH, CREDENTIALS_ID)
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

timeout(time: 1, unit: 'HOURS') {
    node(slaveNode) {
        def templateEnvHead = "${env.WORKSPACE}/env_head/"
        def templateEnvPatched = "${env.WORKSPACE}/env_patched/"
        def contextFileListHead = []
        def contextFileListPatched = []
        def vEnv = "${env.WORKSPACE}/venv"

        try {
            sh(script: 'find . -mindepth 1 -delete > /dev/null || true')
            stage('Download and prepare CC env') {
                // Prepare 2 env - for patchset, and for HEAD
                paralellEnvs = [:]
                paralellEnvs.failFast = true
                paralellEnvs['downloadEnvHead'] = StepPrepareCCenv('', templateEnvHead)
                paralellEnvs['downloadEnvPatched'] = StepPrepareCCenv(gerritRef, templateEnvPatched)
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
                paralellEnvs = [:]
                paralellEnvs.failFast = true
                paralellEnvs['GenerateEnvHead'] = StepGenerateModels(contextFileListPatched, vEnv, templateEnvPatched)
                paralellEnvs['GenerateEnvPatched'] = StepGenerateModels(contextFileListHead, vEnv, templateEnvHead)
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

                if (SYSTEM_GIT_URL == "") {
                    git.checkoutGitRepository("${env.WORKSPACE}/global_reclass/", RECLASS_MODEL_URL, RECLASS_MODEL_BRANCH, CREDENTIALS_ID)
                } else {
                    dir("${env.WORKSPACE}/global_reclass/") {
                        if (!gerrit.gerritPatchsetCheckout(SYSTEM_GIT_URL, SYSTEM_GIT_REF, "HEAD", CREDENTIALS_ID)) {
                            common.errorMsg("Failed to obtain system reclass with url: ${SYSTEM_GIT_URL} and ${SYSTEM_GIT_REF}")
                            throw new RuntimeException("Failed to obtain system reclass")
                        }
                    }
                }
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
                    sh(script: "tar -chzf head_reclass.tar.gz --exclude='*@tmp' model contexts global_reclass", returnStatus: true)
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
                    sh(script: "tar -chzf patched_reclass.tar.gz --exclude='*@tmp' model contexts global_reclass", returnStatus: true)
                    archiveArtifacts artifacts: "patched_reclass.tar.gz"
                    // move for "Compare Pillars" stage
                    sh(script: "mv -v patched_reclass.tar.gz ${env.WORKSPACE}")
                }
            }

            stage("Compare Pillars") {
                // Compare patched and HEAD reclass pillars
                compareRoot = "${env.WORKSPACE}/test_compare/"
                sh(script: """
                   mkdir -pv ${compareRoot}/new ${compareRoot}/old
                   tar -xzf patched_reclass.tar.gz  --directory ${compareRoot}/new
                   tar -xzf head_reclass.tar.gz  --directory ${compareRoot}/old
                   """)
                common.warningMsg('infra/secrets.yml has been skipped from compare!')
                rezult = common.comparePillars(compareRoot, env.BUILD_URL, "-Ev 'infra/secrets.yml'")
                currentBuild.description = rezult
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
            currentBuild.result = "FAILURE"
            currentBuild.description = currentBuild.description ? e.message + " " + currentBuild.description : e.message
            throw e
        } finally {
            def dummy = "dummy"
            //FAILING common.sendNotification(currentBuild.result,"",["slack"])
        }
    }
}
