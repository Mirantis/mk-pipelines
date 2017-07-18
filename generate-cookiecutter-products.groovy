/**
 * Generate cookiecutter cluster by individual products
 *
 * Expected parameters:
 *   COOKIECUTTER_TEMPLATE_CREDENTIALS  Credentials to the Cookiecutter template repo.
 *   COOKIECUTTER_TEMPLATE_URL          Cookiecutter template repo address.
 *   COOKIECUTTER_TEMPLATE_BRANCH       Branch for the template.
 *   COOKIECUTTER_TEMPLATE_CONTEXT      Context parameters for the template generation.
 *   EMAIL_ADDRESS                      Email to send a created tar file
 *   SHARED_RECLASS_URL                 Git repository with shared reclass
 *
**/

common = new com.mirantis.mk.Common()
git = new com.mirantis.mk.Git()
python = new com.mirantis.mk.Python()
saltModelTesting = new com.mirantis.mk.SaltModelTesting()

timestamps {
    node("python&&docker") {
        def templateEnv = "${env.WORKSPACE}/template"
        def modelEnv = "${env.WORKSPACE}/model"
        def testEnv = "${env.WORKSPACE}/test"

        try {
            def templateContext = readYaml text: COOKIECUTTER_TEMPLATE_CONTEXT
            def clusterDomain = templateContext.default_context.cluster_domain
            def clusterName = templateContext.default_context.cluster_name
            def cutterEnv = "${env.WORKSPACE}/cutter"
            def jinjaEnv = "${env.WORKSPACE}/jinja"
            def outputDestination = "${modelEnv}/classes/cluster/${clusterName}"
            def targetBranch = "feature/${clusterName}"
            def templateBaseDir = "${env.WORKSPACE}/template"
            def templateDir = "${templateEnv}/template/dir"
            def templateOutputDir = templateBaseDir

            currentBuild.description = clusterName
            print("Using context:\n" + COOKIECUTTER_TEMPLATE_CONTEXT)

            stage ('Download Cookiecutter template') {
                git.checkoutGitRepository(templateEnv, COOKIECUTTER_TEMPLATE_URL, COOKIECUTTER_TEMPLATE_BRANCH, COOKIECUTTER_TEMPLATE_CREDENTIALS)
            }

            stage ('Create empty reclass model') {
                sh "git init"

                if (SHARED_RECLASS_URL != '') {
                    sh "git submodule add ${SHARED_RECLASS_URL} classes/system"
                    git.commitGitChanges(modelEnv, "Added new shared reclass submodule")
                }
            }

            def productList = ["infra", "cicd", "opencontrail", "kubernetes", "openstack", "stacklight"]
            for (product in productList) {
                def stagename = (product == "infra") ? "Generate base infrastructure" : "Generate product ${product}"
                stage(stagename) {
                    if (product == "infra" || (templateContext.default_context["${product}_enabled"]
                        && templateContext.default_context["${product}_enabled"].toBoolean())) {
                        templateDir = "${templateEnv}/cluster_product/${product}"
                        templateOutputDir = "${env.WORKSPACE}/template/output/${product}"
                        sh "mkdir -p ${templateOutputDir}"
                        sh "mkdir -p ${outputDestination}"
                        python.setupCookiecutterVirtualenv(cutterEnv)
                        python.buildCookiecutterTemplate(templateDir, COOKIECUTTER_TEMPLATE_CONTEXT, templateOutputDir, cutterEnv, templateBaseDir)
                        sh "mv -v ${templateOutputDir}/${clusterName}/* ${outputDestination}"
                    }
                }
            }

            stage('Generate new SaltMaster node') {
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

                git.commitGitChanges(modelEnv, "Create model ${clusterDomain}")
            }

            stage("Test") {
                if (SHARED_RECLASS_URL != "" && TEST_MODEL && TEST_MODEL.toBoolean()) {
                    sh("cp -r ${modelEnv} ${testEnv}")
                    saltModelTesting.setupAndTestNode("cfg01.${clusterDomain}", "", testEnv)
                }
            }

            stage("Generate config drive") {
                // download create-config-drive
                // generate user-data.sh
                // create config-drive

            }

            stage ('Save changes reclass model') {

                sh(returnStatus: true, script: "tar -zcvf ${clusterName}.tar.gz -C ${modelEnv} .")
                archiveArtifacts artifacts: "${clusterName}.tar.gz"

                if (EMAIl_ADDRESS != null && EMAIL_ADDRESS != ""){
                     emailext(to: EMAIL_ADDRESS,
                              attachmentsPattern: "${clusterName}.tar.gz",
                              body: "Mirantis Jenkins\n\nRequested reclass model ${clusterName} has been created and attached to this email.\nEnjoy!\n\nMirantis",
                              subject: "Your Salt model ${clusterName}")
                }
            }

        } catch (Throwable e) {
             // If there was an error or exception thrown, the build failed
             currentBuild.result = "FAILURE"
             throw e
        } finally {
            stage ('Clean workspace directories') {
                sh(returnStatus: true, script: "rm -rfv ${templateEnv}")
                sh(returnStatus: true, script: "rm -rfv ${modelEnv}")
            }
             // common.sendNotification(currentBuild.result,"",["slack"])
        }
    }
}
