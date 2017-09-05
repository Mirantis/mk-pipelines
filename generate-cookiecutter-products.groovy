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
ssh = new com.mirantis.mk.Ssh()


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
        def user
        wrap([$class: 'BuildUser']) {
            user = env.BUILD_USER_ID
        }

        currentBuild.description = clusterName
        print("Using context:\n" + COOKIECUTTER_TEMPLATE_CONTEXT)

        stage ('Download Cookiecutter template') {
            if (COOKIECUTTER_TEMPLATE_BRANCH.startsWith('refs/changes/')) {
                git.checkoutGitRepository(templateEnv, COOKIECUTTER_TEMPLATE_URL, 'master', COOKIECUTTER_TEMPLATE_CREDENTIALS)

                dir(templateEnv) {
                    ssh.agentSh("git fetch ${COOKIECUTTER_TEMPLATE_URL} ${COOKIECUTTER_TEMPLATE_BRANCH} && git checkout FETCH_HEAD")
                }
            } else {
                git.checkoutGitRepository(templateEnv, COOKIECUTTER_TEMPLATE_URL, COOKIECUTTER_TEMPLATE_BRANCH, COOKIECUTTER_TEMPLATE_CREDENTIALS)
            }

        }

        stage ('Create empty reclass model') {
            dir(path: modelEnv) {
                sh "rm -rfv .git"
                sh "git init"

                if (SHARED_RECLASS_URL != '') {
                    ssh.agentSh "git submodule add \"${SHARED_RECLASS_URL}\" \"classes/system\""
                    git.commitGitChanges(modelEnv, "Added new shared reclass submodule", "${user}@localhost", "${user}")
                }
            }
        }

        def productList = ["infra", "cicd", "opencontrail", "kubernetes", "openstack", "stacklight", "ceph"]
        for (product in productList) {

            // get templateOutputDir and productDir
            if (product.startsWith("stacklight")) {
                templateOutputDir = "${env.WORKSPACE}/output/stacklight"

                def stacklightVersion
                try {
                    stacklightVersion = templateContext.default_context['stacklight_version']
                } catch (Throwable e) {
                    common.warningMsg('Stacklight version loading failed')
                }

                if (stacklightVersion) {
                    productDir = "stacklight" + stacklightVersion
                } else {
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

                python.setupCookiecutterVirtualenv(cutterEnv)
                python.buildCookiecutterTemplate(templateDir, COOKIECUTTER_TEMPLATE_CONTEXT, templateOutputDir, cutterEnv, templateBaseDir)
                sh "mv -v ${templateOutputDir}/${clusterName}/* ${outputDestination}"
            } else {
                common.warningMsg("Product " + product + " is disabled")
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

            git.commitGitChanges(modelEnv, "Create model ${clusterName}", "${user}@localhost", "${user}")
        }

        stage("Test") {
            if (SHARED_RECLASS_URL != "" && TEST_MODEL && TEST_MODEL.toBoolean()) {
                sh("cp -r ${modelEnv} ${testEnv}")
                saltModelTesting.setupAndTestNode("cfg01.${clusterDomain}", "", testEnv)
            }
        }

        stage("Generate config drive") {
            // apt package genisoimage is required for this stage

            // download create-config-drive
            def config_drive_script_url = "https://raw.githubusercontent.com/jiribroulik/scripts/master/create_config_drive.sh"
            sh "wget -O create-config-drive ${config_drive_script_url} && chmod +x create-config-drive"
            def user_data_script_url = "https://raw.githubusercontent.com/mceloud/scripts/master/master_config.sh"
            sh "wget -O user_data.sh ${user_data_script_url}"

            sh "git clone https://github.com/Mirantis/mk-pipelines.git"
            sh "git clone https://github.com/Mirantis/pipeline-library.git"
            args = "--user-data user_data.sh --hostname cfg01 --model ${modelEnv} --mk-pipelines ${env.WORKSPACE}/mk-pipelines/ --pipeline-library ${env.WORKSPACE}/pipeline-library/ cfg01.${clusterDomain}-config.iso"

            // load data from model
            def smc = [:]
            smc['SALT_MASTER_MINION_ID'] = "cfg01.${clusterDomain}"
            smc['SALT_MASTER_DEPLOY_IP'] = templateContext['default_context']['salt_master_management_address']
            smc['DEPLOY_NETWORK_GW'] = templateContext['default_context']['deploy_network_gateway']
            smc['DEPLOY_NETWORK_NETMASK'] = templateContext['default_context']['deploy_network_netmask']
            smc['DNS_SERVERS'] = templateContext['default_context']['dns_server01']
            smc['CICD_CONTROL_ADDRESS'] = templateContext['default_context']['cicd_control_vip_address']
            smc['INFRA_CONFIG_ADDRESS'] = templateContext['default_context']['salt_master_address']

            for (i in common.entries(smc)) {
                sh "sed -i \"s,export ${i[0]}=.*,export ${i[0]}=${i[1]},\" user_data.sh"
            }

            // create config-drive
            sh "./create-config-drive ${args}"
            sh("mkdir output-${clusterName} && mv cfg01.${clusterDomain}-config.iso output-${clusterName}/")
            // save iso to artifacts
            archiveArtifacts artifacts: "output-${clusterName}/cfg01.${clusterDomain}-config.iso"
        }

        stage ('Save changes reclass model') {

            sh(returnStatus: true, script: "tar -zcf output-${clusterName}/${clusterName}.tar.gz -C ${modelEnv} .")
            archiveArtifacts artifacts: "output-${clusterName}/${clusterName}.tar.gz"


            if (EMAIL_ADDRESS != null && EMAIL_ADDRESS != "") {
                 emailext(to: EMAIL_ADDRESS,
                          attachmentsPattern: "output-${clusterName}/*",
                          body: "Mirantis Jenkins\n\nRequested reclass model ${clusterName} has been created and attached to this email.\nEnjoy!\n\nMirantis",
                          subject: "Your Salt model ${clusterName}")
            }
            dir("output-${clusterName}"){
                deleteDir()
            }
        }

    } catch (Throwable e) {
         // If there was an error or exception thrown, the build failed
         currentBuild.result = "FAILURE"
         currentBuild.description = currentBuild.description ? e.message + " " + currentBuild.description : e.message
         throw e
    } finally {
        stage ('Clean workspace directories') {
            sh(returnStatus: true, script: "rm -rf ${templateEnv}")
            sh(returnStatus: true, script: "rm -rf ${modelEnv}")
        }
         // common.sendNotification(currentBuild.result,"",["slack"])
    }
}