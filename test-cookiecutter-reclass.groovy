common = new com.mirantis.mk.Common()
gerrit = new com.mirantis.mk.Gerrit()
git = new com.mirantis.mk.Git()
python = new com.mirantis.mk.Python()
saltModelTesting = new com.mirantis.mk.SaltModelTesting()

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

def generateModel(modelFile, cutterEnv) {
    def templateEnv = "${env.WORKSPACE}"
    def modelEnv = "${env.WORKSPACE}/model"
    def basename = sh(script: "basename ${modelFile} .yml", returnStdout: true).trim()
    def generatedModel = "${modelEnv}/${basename}"
    def testEnv = "${env.WORKSPACE}/test"
    def content = readFile(file: "${templateEnv}/contexts/${modelFile}")
    def templateContext = readYaml text: content
    def clusterDomain = templateContext.default_context.cluster_domain
    def clusterName = templateContext.default_context.cluster_name
    def outputDestination = "${generatedModel}/classes/cluster/${clusterName}"
    def targetBranch = "feature/${clusterName}"
    def templateBaseDir = "${env.WORKSPACE}"
    def templateDir = "${templateEnv}/dir"
    def templateOutputDir = templateBaseDir
    sh "rm -rf ${generatedModel} || true"

    common.infoMsg("Generating model from context ${modelFile}")

    def productList = ["infra", "cicd", "opencontrail", "kubernetes", "openstack", "stacklight"]
    for (product in productList) {

        // get templateOutputDir and productDir
        if (product.startsWith("stacklight")) {
            templateOutputDir = "${env.WORKSPACE}/output/stacklight"
            try {
                productDir = "stacklight" + templateContext.default_context['stacklight_version']
            } catch (Throwable e) {
                productDir = "stacklight1"
            }
        } else {
            templateOutputDir = "${env.WORKSPACE}/output/${product}"
            productDir = product
        }

        if (product == "infra" || (templateContext.default_context["${product}_enabled"]
            && templateContext.default_context["${product}_enabled"].toBoolean())) {

            templateDir = "${templateEnv}/cluster_product/${productDir}"
            common.infoMsg("Generating product " + product + " from " + templateDir + " to " + templateOutputDir)

            sh "rm -rf ${templateOutputDir} || true"
            sh "mkdir -p ${templateOutputDir}"
            sh "mkdir -p ${outputDestination}"

            python.buildCookiecutterTemplate(templateDir, content, templateOutputDir, cutterEnv, templateBaseDir)
            sh "mv -v ${templateOutputDir}/${clusterName}/* ${outputDestination}"
        } else {
            common.warningMsg("Product " + product + " is disabled")
        }
    }
    generateSaltMaster(generatedModel, clusterDomain, clusterName)
}

def testModel(modelFile, testEnv) {
    def templateEnv = "${env.WORKSPACE}"
    def content = readFile(file: "${templateEnv}/contexts/${modelFile}.yml")
    def templateContext = readYaml text: content
    def clusterDomain = templateContext.default_context.cluster_domain
    git.checkoutGitRepository("${testEnv}/classes/system", RECLASS_MODEL_URL, RECLASS_MODEL_BRANCH, CREDENTIALS_ID)
    saltModelTesting.setupAndTestNode("cfg01.${clusterDomain}", EXTRA_FORMULAS, testEnv)
}

def gerritRef
try {
  gerritRef = GERRIT_REFSPEC
} catch (MissingPropertyException e) {
  gerritRef = null
}

timestamps {
    node("python&&docker") {
        def templateEnv = "${env.WORKSPACE}"
        def cutterEnv = "${env.WORKSPACE}/cutter"
        def jinjaEnv = "${env.WORKSPACE}/jinja"

        try {
            stage("Cleanup") {
                sh("rm -rf * || true")
            }

            stage ('Download Cookiecutter template') {
                if (gerritRef) {
                    def gerritChange = gerrit.getGerritChange(GERRIT_NAME, GERRIT_HOST, GERRIT_CHANGE_NUMBER, CREDENTIALS_ID)
                    merged = gerritChange.status == "MERGED"
                    if(!merged){
                        checkouted = gerrit.gerritPatchsetCheckout ([
                            credentialsId : CREDENTIALS_ID
                        ])
                    } else{
                        common.successMsg("Change ${GERRIT_CHANGE_NUMBER} is already merged, no need to gate them")
                    }
                } else {
                    git.checkoutGitRepository(templateEnv, COOKIECUTTER_TEMPLATE_URL, COOKIECUTTER_TEMPLATE_BRANCH, CREDENTIALS_ID)
                }
            }

            stage("Setup") {
                python.setupCookiecutterVirtualenv(cutterEnv)
            }

            def contextFiles
            dir("${templateEnv}/contexts") {
                contextFiles = findFiles(glob: "*.yml")
            }

            def contextFileList = []
            for (int i = 0; i < contextFiles.size(); i++) {
                contextFileList << contextFiles[i]
            }

            stage("generate-model") {
                for (contextFile in contextFileList) {
                    generateModel(contextFile, cutterEnv)
                }
            }

            dir("${env.WORKSPACE}") {
                sh(returnStatus: true, script: "tar -zcvf model.tar.gz -C model .")
                archiveArtifacts artifacts: "model.tar.gz"
            }

            stage("test-nodes") {
                def partitions = common.partitionList(contextFileList, PARALLEL_NODE_GROUP_SIZE.toInteger())
                def buildSteps = [:]
                for (int i = 0; i < partitions.size(); i++) {
                    def partition = partitions[i]
                    buildSteps.put("partition-${i}", new HashMap<String,org.jenkinsci.plugins.workflow.cps.CpsClosure2>())
                    for(int k = 0; k < partition.size; k++){
                        def basename = sh(script: "basename ${partition[k]} .yml", returnStdout: true).trim()
                        def testEnv = "${env.WORKSPACE}/model/${basename}"
                        buildSteps.get("partition-${i}").put(basename, { testModel(basename, testEnv) })
                    }
                }
                common.serial(buildSteps)
            }

            stage ('Clean workspace directories') {
                sh(returnStatus: true, script: "rm -rfv * > /dev/null || true")
            }

        } catch (Throwable e) {
             currentBuild.result = "FAILURE"
             throw e
        } finally {
            common.sendNotification(currentBuild.result,"",["slack"])
        }
    }
}
