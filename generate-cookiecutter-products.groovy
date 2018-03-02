/**
 * Generate cookiecutter cluster by individual products
 *
 * Expected parameters:
 *   COOKIECUTTER_TEMPLATE_CONTEXT      Context parameters for the template generation.
 *   EMAIL_ADDRESS                      Email to send a created tar file
 *
**/

// Deprecation to avoid unexpected behaviour because it should be passed via initial context.
// Need to delete this "if" statement at 1 April 2018.
if(env.COOKIECUTTER_TEMPLATE_CREDENTIALS ||
   env.COOKIECUTTER_TEMPLATE_URL ||
   env.COOKIECUTTER_TEMPLATE_BRANCH ||
   env.COOKIECUTTER_TEMPLATE_PATH ||
   env.SHARED_RECLASS_URL){
    println '''
    DEPRECATION: Please note that the following variables are deprocated:
    - COOKIECUTTER_TEMPLATE_CREDENTIALS
    - COOKIECUTTER_TEMPLATE_URL
    - COOKIECUTTER_TEMPLATE_BRANCH
    - COOKIECUTTER_TEMPLATE_PATH
    - SHARED_RECLASS_URL
    You need to pass the values using the following variables from initial cookiecutter context:
    - cookiecutter_template_url
    - cookiecutter_template_branch
    - shared_reclass_url
    The following variables are not needed anymore:
    - COOKIECUTTER_TEMPLATE_CREDENTIALS - cookiecutter-templates repos are accessible for anounimous
                                        (https://gerrit.mcp.mirantis.net)
    - COOKIECUTTER_TEMPLATE_PATH - hardcoded to "${env.WORKSPACE}/template"
    '''
    currentBuild.result = "FAILURE"
    return
}

common = new com.mirantis.mk.Common()
git = new com.mirantis.mk.Git()
python = new com.mirantis.mk.Python()
saltModelTesting = new com.mirantis.mk.SaltModelTesting()
ssh = new com.mirantis.mk.Ssh()

timeout(time: 12, unit: 'HOURS') {
    node("python&&docker") {
        def templateEnv = "${env.WORKSPACE}/template"
        def modelEnv = "${env.WORKSPACE}/model"
        def testEnv = "${env.WORKSPACE}/test"
        def pipelineEnv = "${env.WORKSPACE}/pipelines"

        try {
            def templateContext = readYaml text: COOKIECUTTER_TEMPLATE_CONTEXT
            def mcpVersion = templateContext.default_context.mcp_version
            def sharedReclassUrl = templateContext.default_context.shared_reclass_url
            def clusterDomain = templateContext.default_context.cluster_domain
            def clusterName = templateContext.default_context.cluster_name
            def saltMaster = templateContext.default_context.salt_master_hostname
            def cutterEnv = "${env.WORKSPACE}/cutter"
            def jinjaEnv = "${env.WORKSPACE}/jinja"
            def outputDestination = "${modelEnv}/classes/cluster/${clusterName}"
            def systemEnv = "${modelEnv}/classes/system"
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
                def cookiecutterTemplateUrl = templateContext.default_context.cookiecutter_template_url
                def cookiecutterTemplateBranch = templateContext.default_context.cookiecutter_template_branch
                git.checkoutGitRepository(templateEnv, cookiecutterTemplateUrl, 'master')
                // Use refspec if exists first of all
                if (cookiecutterTemplateBranch.toString().startsWith('refs/')) {
                    dir(templateEnv) {
                        ssh.agentSh("git fetch ${cookiecutterTemplateUrl} ${cookiecutterTemplateBranch} && git checkout FETCH_HEAD")
                    }
                } else {
                    // Use mcpVersion git tag if not specified branch for cookiecutter-templates
                    if (cookiecutterTemplateBranch == '') {
                        cookiecutterTemplateBranch = mcpVersion
                        // Don't have nightly/testing/stable for cookiecutter-templates repo, therefore use master
                        if(mcpVersion == "nightly" || mcpVersion == "testing" || mcpVersion == "stable"){
                            cookiecutterTemplateBranch = 'master'
                        }
                    }
                    git.changeGitBranch(templateEnv, cookiecutterTemplateBranch)
                }
            }

            stage ('Create empty reclass model') {
                dir(path: modelEnv) {
                    sh "rm -rfv .git"
                    sh "git init"
                    ssh.agentSh("git submodule add ${sharedReclassUrl} 'classes/system'")
                }
                def sharedReclassBranch = templateContext.default_context.shared_reclass_branch
                // Use refspec if exists first of all
                if (sharedReclassBranch.toString().startsWith('refs/')) {
                    dir(systemEnv) {
                        ssh.agentSh("git fetch ${sharedReclassUrl} ${sharedReclassBranch} && git checkout FETCH_HEAD")
                    }
                } else {
                    // Use mcpVersion git tag if not specified branch for reclass-system
                    if (sharedReclassBranch == '') {
                        sharedReclassBranch = mcpVersion
                        // Don't have nightly/testing/stable for reclass-system repo, therefore use master
                        if(mcpVersion == "nightly" || mcpVersion == "testing" || mcpVersion == "stable"){
                            sharedReclassBranch = 'master'
                        }
                    }
                    git.changeGitBranch(systemEnv, sharedReclassBranch)
                }
                git.commitGitChanges(modelEnv, "Added new shared reclass submodule", "${user}@localhost", "${user}")
            }

            def productList = ["infra", "cicd", "opencontrail", "kubernetes", "openstack", "oss", "stacklight", "ceph"]
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
                def nodeFile = "${modelEnv}/nodes/${saltMaster}.${clusterDomain}.yml"
                def nodeString = """classes:
- cluster.${clusterName}.infra.config
parameters:
  _param:
    linux_system_codename: xenial
    reclass_data_revision: master
  linux:
    system:
      name: ${saltMaster}
      domain: ${clusterDomain}
    """
                sh "mkdir -p ${modelEnv}/nodes/"
                writeFile(file: nodeFile, text: nodeString)

                git.commitGitChanges(modelEnv, "Create model ${clusterName}", "${user}@localhost", "${user}")
            }

            stage("Test") {
                if (sharedReclassUrl != "" && TEST_MODEL && TEST_MODEL.toBoolean()) {
                    sh("cp -r ${modelEnv} ${testEnv}")
                    saltModelTesting.setupAndTestNode("${saltMaster}.${clusterDomain}", "", testEnv)
                }
            }

            stage("Generate config drives") {
                // apt package genisoimage is required for this stage

                // download create-config-drive
                // FIXME: that should be refactored, to use git clone - to be able download it from custom repo.
                def config_drive_script_url = "https://raw.githubusercontent.com/Mirantis/mcp-common-scripts/master/config-drive/create_config_drive.sh"
                def user_data_script_url = "https://raw.githubusercontent.com/Mirantis/mcp-common-scripts/master/config-drive/master_config.sh"

                sh "wget -O create-config-drive ${config_drive_script_url} && chmod +x create-config-drive"
                sh "wget -O user_data.sh ${user_data_script_url}"

                sh "git clone --mirror https://github.com/Mirantis/mk-pipelines.git ${pipelineEnv}/mk-pipelines"
                sh "git clone --mirror https://github.com/Mirantis/pipeline-library.git ${pipelineEnv}/pipeline-library"
                args = "--user-data user_data.sh --hostname ${saltMaster} --model ${modelEnv} --mk-pipelines ${pipelineEnv}/mk-pipelines/ --pipeline-library ${pipelineEnv}/pipeline-library/ ${saltMaster}.${clusterDomain}-config.iso"

                // load data from model
                def smc = [:]
                smc['SALT_MASTER_MINION_ID'] = "${saltMaster}.${clusterDomain}"
                smc['SALT_MASTER_DEPLOY_IP'] = templateContext['default_context']['salt_master_management_address']
                smc['DEPLOY_NETWORK_GW'] = templateContext['default_context']['deploy_network_gateway']
                smc['DEPLOY_NETWORK_NETMASK'] = templateContext['default_context']['deploy_network_netmask']
                smc['DNS_SERVERS'] = templateContext['default_context']['dns_server01']
                if (templateContext['default_context']['local_repositories'] == 'True'){
                    smc['PIPELINES_FROM_ISO'] = 'false'
                    smc['PIPELINE_REPO_URL'] = 'http://' + templateContext['default_context']['aptly_server_deploy_address'] + ':8088'
                }
                if (templateContext['default_context']['upstream_proxy_enabled'] == 'True'){
                    if (templateContext['default_context']['upstream_proxy_auth_enabled'] == 'True'){
                        smc['http_proxy'] = 'http://' + templateContext['default_context']['upstream_proxy_user'] + ':' + templateContext['default_context']['upstream_proxy_password'] + '@' + templateContext['default_context']['upstream_proxy_address'] + ':' + templateContext['default_context']['upstream_proxy_port']
                        smc['https_proxy'] = 'http://' + templateContext['default_context']['upstream_proxy_user'] + ':' + templateContext['default_context']['upstream_proxy_password'] + '@' + templateContext['default_context']['upstream_proxy_address'] + ':' + templateContext['default_context']['upstream_proxy_port']
                    } else {
                        smc['http_proxy'] = 'http://' + templateContext['default_context']['upstream_proxy_address'] + ':' + templateContext['default_context']['upstream_proxy_port']
                        smc['https_proxy'] = 'http://' + templateContext['default_context']['upstream_proxy_address'] + ':' + templateContext['default_context']['upstream_proxy_port']
                    }
                }

                for (i in common.entries(smc)) {
                    sh "sed -i \"s,export ${i[0]}=.*,export ${i[0]}=${i[1]},\" user_data.sh"
                }

                // create cfg config-drive
                sh "./create-config-drive ${args}"
                sh("mkdir output-${clusterName} && mv ${saltMaster}.${clusterDomain}-config.iso output-${clusterName}/")

                // save cfg iso to artifacts
                archiveArtifacts artifacts: "output-${clusterName}/${saltMaster}.${clusterDomain}-config.iso"

                if (templateContext['default_context']['local_repositories'] == 'True'){
                    def aptlyServerHostname = templateContext.default_context.aptly_server_hostname
                    def user_data_script_apt_url = "https://raw.githubusercontent.com/Mirantis/mcp-common-scripts/master/config-drive/mirror_config.sh"
                    sh "wget -O mirror_config.sh ${user_data_script_apt_url}"

                    def smc_apt = [:]
                    smc_apt['SALT_MASTER_DEPLOY_IP'] = templateContext['default_context']['salt_master_management_address']
                    smc_apt['APTLY_DEPLOY_IP'] = templateContext['default_context']['aptly_server_deploy_address']
                    smc_apt['APTLY_DEPLOY_NETMASK'] = templateContext['default_context']['deploy_network_netmask']
                    smc_apt['APTLY_MINION_ID'] = "${aptlyServerHostname}.${clusterDomain}"

                    for (i in common.entries(smc_apt)) {
                        sh "sed -i \"s,export ${i[0]}=.*,export ${i[0]}=${i[1]},\" mirror_config.sh"
                    }

                    // create apt config-drive
                    sh "./create-config-drive --user-data mirror_config.sh --hostname ${aptlyServerHostname} ${aptlyServerHostname}.${clusterDomain}-config.iso"
                    sh("mv ${aptlyServerHostname}.${clusterDomain}-config.iso output-${clusterName}/")

                    // save apt iso to artifacts
                    archiveArtifacts artifacts: "output-${clusterName}/${aptlyServerHostname}.${clusterDomain}-config.iso"
                }
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
                sh(returnStatus: true, script: "rm -rf ${pipelineEnv}")
            }
             // common.sendNotification(currentBuild.result,"",["slack"])
        }
    }
}
