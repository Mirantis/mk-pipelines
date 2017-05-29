common = new com.mirantis.mk.Common()
git = new com.mirantis.mk.Git()
python = new com.mirantis.mk.Python()
saltModelTesting = new com.mirantis.mk.SaltModelTesting()

def generateSaltMaster(modelEnv) {
    def nodeFile = "${modelEnv}/nodes/cfg01.${clusterDomain}.yml"
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
    sh "mkdir -p ${modelEnv}/nodes/"
    writeFile(file: nodeFile, text: nodeString)
}

def generate(contextFile) {
    def templateEnv = "${env.WORKSPACE}/template"
    def baseName = sh(script: "basename ${contextFile} .yml", returnStdout: true)
    def modelEnv = "${env.WORKSPACE}/model-${baseName}"
    def cookiecutterTemplateContext = readFile(file: "${env.WORKSPACE}/contexts/contextFile")
    def templateContext = readYaml text: cookiecutterTemplateContext
    def clusterDomain = templateContext.default_context.cluster_domain
    def clusterName = templateContext.default_context.cluster_name
    def cutterEnv = "${env.WORKSPACE}/cutter"
    def jinjaEnv = "${env.WORKSPACE}/jinja"
    def outputDestination = "${modelEnv}/classes/cluster/${clusterName}"
    def targetBranch = "feature/${clusterName}"
    def templateBaseDir = "${env.WORKSPACE}/template"
    def templateDir = "${templateEnv}/template/dir"
    def templateOutputDir = templateBaseDir
    sh("rm -rf ${templateBaseDir} || true")

    def productList = ["infra", "cicd", "opencontrail", "kubernetes", "openstack", "stacklight"]
    for (product in productList) {
        def stagename = (product == "infra") ? "Generate base infrastructure" : "Generate product ${product}"
        println stagename
        if (product == "infra" || (templateContext.default_context["${product}_enabled"]
            && templateContext.default_context["${product}_enabled"].toBoolean())) {
            templateDir = "${templateEnv}/cluster_product/${product}"
            templateOutputDir = "${env.WORKSPACE}/template/output/${product}"
            sh "mkdir -p ${templateOutputDir}"
            sh "mkdir -p ${outputDestination}"
            python.setupCookiecutterVirtualenv(cutterEnv)
            python.buildCookiecutterTemplate(templateDir, cookiecutterTemplateContext, templateOutputDir, cutterEnv, templateBaseDir)
            sh "mv -v ${templateOutputDir}/${clusterName}/* ${outputDestination}"
        }
    }
    generateSaltMaster(modelEnv)
}

def testModel(contextFile) {
    def baseName = sh(script: "basename ${contextFile} .yml", returnStdout: true)
    def modelEnv = "${env.WORKSPACE}/model-${baseName}"
    git.checkoutGitRepository("${modelEnv}/classes/system", RECLASS_MODEL_URL, RECLASS_MODEL_BRANCH, RECLASS_MODEL_CREDENTIALS)
    saltModelTesting.setupAndTestNode("cfg01.${clusterDomain}", "", modelEnv)
}

timestamps {
    node("python&&docker") {
        def templateEnv = "${env.WORKSPACE}/template"

        try {
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
                    gerrit.gerritPatchsetCheckout(COOKIECUTTER_TEMPLATE_URL, COOKIECUTTER_TEMPLATE_BRANCH, "HEAD", CREDENTIALS_ID)
                }
            }

            def contextFiles
            dir("contexts") {
                contextFiles = findFiles(glob: "*.yml")
            }

            for (contextFile in contextFiles) {
                generate(contextFile)
            }

            stage("test-nodes") {
                def partitions = common.partitionList(contextFiles, 3)
                def buildSteps = [:]
                for (int i = 0; i < partitions.size(); i++) {
                    def partition = partitions[i]
                    buildSteps.put("partition-${i}", new HashMap<String,org.jenkinsci.plugins.workflow.cps.CpsClosure2>())
                    for(int k = 0; k < partition.size; k++){
                        def basename = sh(script: "basename ${partition[k]} .yml", returnStdout: true).trim()
                        def modelEnv = "${env.WORKSPACE}/model-${baseName}"
                        buildSteps.get("partition-${i}").put(basename, { saltModelTesting.setupAndTestNode(basename, "", modelEnv) })
                    }
                }
                common.serial(buildSteps)
            }

        } catch (Throwable e) {
             currentBuild.result = "FAILURE"
             throw e
        } finally {
            stage ('Clean workspace directories') {
                sh(returnStatus: true, script: "rm -rfv *")
            }
            common.sendNotification(currentBuild.result,"",["slack"])
        }
    }
}
