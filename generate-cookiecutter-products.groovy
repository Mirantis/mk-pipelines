/**
 * Generate cookiecutter cluster by individual products
 *
 * Expected parameters:
 *   COOKIECUTTER_TEMPLATE_CONTEXT      Context parameters for the template generation.
 *   EMAIL_ADDRESS                      Email to send a created tar file
 *   CREDENTIALS_ID                     Credentials id for git
 **/

common = new com.mirantis.mk.Common()
git = new com.mirantis.mk.Git()
python = new com.mirantis.mk.Python()
saltModelTesting = new com.mirantis.mk.SaltModelTesting()
ssh = new com.mirantis.mk.Ssh()

slaveNode = env.SLAVE_NODE ?: 'python&&docker'
gerritCredentials = env.CREDENTIALS_ID ?: 'gerrit'

timeout(time: 2, unit: 'HOURS') {
    node(slaveNode) {
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
            def testResult = false
            wrap([$class: 'BuildUser']) {
                user = env.BUILD_USER_ID
            }
            currentBuild.description = clusterName
            print("Using context:\n" + COOKIECUTTER_TEMPLATE_CONTEXT)

            stage('Download Cookiecutter template') {
                sh(script: 'find . -mindepth 1 -delete > /dev/null || true')
                def cookiecutterTemplateUrl = templateContext.default_context.cookiecutter_template_url
                def cookiecutterTemplateBranch = templateContext.default_context.cookiecutter_template_branch
                git.checkoutGitRepository(templateEnv, cookiecutterTemplateUrl, 'master', gerritCredentials)
                // Use refspec if exists first of all
                if (cookiecutterTemplateBranch.toString().startsWith('refs/')) {
                    dir(templateEnv) {
                        withCredentials(gerritCredentials){
                            ssh.agentSh("git fetch ${cookiecutterTemplateUrl} ${cookiecutterTemplateBranch} && git checkout FETCH_HEAD")
                        }
                    }
                } else {
                    // Use mcpVersion git tag if not specified branch for cookiecutter-templates
                    if (cookiecutterTemplateBranch == '') {
                        cookiecutterTemplateBranch = mcpVersion
                        // Don't have nightly/testing/stable for cookiecutter-templates repo, therefore use master
                        if (["nightly", "testing", "stable"].contains(mcpVersion)) {
                            cookiecutterTemplateBranch = 'master'
                        }
                    }
                    git.changeGitBranch(templateEnv, cookiecutterTemplateBranch)
                }
            }

            stage('Create empty reclass model') {
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
                        // Don't have nightly/testing for reclass-system repo, therefore use master
                        if (["nightly", "testing", "stable"].contains(mcpVersion)) {
                            common.warningMsg("Fetching reclass-system from master!")
                            sharedReclassBranch = 'master'
                        }
                    }
                    git.changeGitBranch(systemEnv, sharedReclassBranch)
                }
                git.commitGitChanges(modelEnv, "Added new shared reclass submodule", "${user}@localhost", "${user}")
            }

            stage('Generate model') {
                python.setupCookiecutterVirtualenv(cutterEnv)
                python.generateModel(COOKIECUTTER_TEMPLATE_CONTEXT, 'default_context', saltMaster, cutterEnv, modelEnv, templateEnv, false)
                git.commitGitChanges(modelEnv, "Create model ${clusterName}", "${user}@localhost", "${user}")
            }

            stage("Test") {
                if (TEST_MODEL.toBoolean() && sharedReclassUrl != '') {
                    distribRevision = mcpVersion
                    if (['master'].contains(mcpVersion)) {
                        distribRevision = 'nightly'
                    }
                    if (distribRevision.contains('/')) {
                        distribRevision = distribRevision.split('/')[-1]
                    }
                    // Check if we are going to test bleeding-edge release, which doesn't have binary release yet
                    if (!common.checkRemoteBinary([apt_mk_version: distribRevision]).linux_system_repo_url) {
                        common.errorMsg("Binary release: ${distribRevision} not exist. Fallback to 'proposed'! ")
                        distribRevision = 'proposed'
                    }
                    sh("cp -r ${modelEnv} ${testEnv}")
                    def DockerCName = "${env.JOB_NAME.toLowerCase()}_${env.BUILD_TAG.toLowerCase()}"
                    common.infoMsg("Attempt to run test against distribRevision: ${distribRevision}")
                    try {
                        def config = [
                            'dockerHostname'     : "${saltMaster}.${clusterDomain}",
                            'reclassEnv'         : testEnv,
                            'distribRevision'    : distribRevision,
                            'dockerContainerName': DockerCName,
                            'testContext'        : 'salt-model-node'
                        ]
                        testResult = saltModelTesting.testNode(config)
                        common.infoMsg("Test finished: SUCCESS")
                    } catch (Exception ex) {
                        common.warningMsg("Test finished: FAILED")
                        testResult = false
                    }
                } else {
                    common.warningMsg("Test stage has been skipped!")
                }
            }
            stage("Generate config drives") {
                // apt package genisoimage is required for this stage

                // download create-config-drive
                // FIXME: that should be refactored, to use git clone - to be able download it from custom repo.
                def mcpCommonScriptsBranch = templateContext['default_context']['mcp_common_scripts_branch']
                if (mcpCommonScriptsBranch == '') {
                    mcpCommonScriptsBranch = mcpVersion
                    // Don't have n/t/s for mcp-common-scripts repo, therefore use master
                    if (["nightly", "testing", "stable"].contains(mcpVersion)) {
                        common.warningMsg("Fetching mcp-common-scripts from master!")
                        mcpCommonScriptsBranch = 'master'
                    }
                }

                def commonScriptsRepoUrl = 'https://gerrit.mcp.mirantis.com/mcp/mcp-common-scripts'
                checkout([
                    $class           : 'GitSCM',
                    branches         : [[name: 'FETCH_HEAD'],],
                    extensions       : [[$class: 'RelativeTargetDirectory', relativeTargetDir: 'mcp-common-scripts']],
                    userRemoteConfigs: [[url: commonScriptsRepoUrl, refspec: mcpCommonScriptsBranch],],
                ])

                sh "cp mcp-common-scripts/config-drive/create_config_drive.sh create-config-drive && chmod +x create-config-drive"
                sh "[ -f mcp-common-scripts/config-drive/master_config.sh ] && cp mcp-common-scripts/config-drive/master_config.sh user_data || cp mcp-common-scripts/config-drive/master_config.yaml user_data"

                sh "git clone --mirror https://github.com/Mirantis/mk-pipelines.git ${pipelineEnv}/mk-pipelines"
                sh "git clone --mirror https://github.com/Mirantis/pipeline-library.git ${pipelineEnv}/pipeline-library"
                args = "--user-data user_data --hostname ${saltMaster} --model ${modelEnv} --mk-pipelines ${pipelineEnv}/mk-pipelines/ --pipeline-library ${pipelineEnv}/pipeline-library/ ${saltMaster}.${clusterDomain}-config.iso"

                // load data from model
                def smc = [:]
                smc['SALT_MASTER_MINION_ID'] = "${saltMaster}.${clusterDomain}"
                smc['SALT_MASTER_DEPLOY_IP'] = templateContext['default_context']['salt_master_management_address']
                smc['DEPLOY_NETWORK_GW'] = templateContext['default_context']['deploy_network_gateway']
                smc['DEPLOY_NETWORK_NETMASK'] = templateContext['default_context']['deploy_network_netmask']
                if (templateContext['default_context'].get('deploy_network_mtu')) {
                    smc['DEPLOY_NETWORK_MTU'] = templateContext['default_context']['deploy_network_mtu']
                }
                smc['DNS_SERVERS'] = templateContext['default_context']['dns_server01']
                smc['MCP_VERSION'] = "${mcpVersion}"
                if (templateContext['default_context']['local_repositories'] == 'True') {
                    def localRepoIP = templateContext['default_context']['local_repo_url']
                    smc['MCP_SALT_REPO_KEY'] = "http://${localRepoIP}/public.gpg"
                    smc['MCP_SALT_REPO_URL'] = "http://${localRepoIP}/ubuntu-xenial"
                    smc['PIPELINES_FROM_ISO'] = 'false'
                    smc['PIPELINE_REPO_URL'] = "http://${localRepoIP}:8088"
                    smc['LOCAL_REPOS'] = 'true'
                }
                if (templateContext['default_context']['upstream_proxy_enabled'] == 'True') {
                    if (templateContext['default_context']['upstream_proxy_auth_enabled'] == 'True') {
                        smc['http_proxy'] = 'http://' + templateContext['default_context']['upstream_proxy_user'] + ':' + templateContext['default_context']['upstream_proxy_password'] + '@' + templateContext['default_context']['upstream_proxy_address'] + ':' + templateContext['default_context']['upstream_proxy_port']
                        smc['https_proxy'] = 'http://' + templateContext['default_context']['upstream_proxy_user'] + ':' + templateContext['default_context']['upstream_proxy_password'] + '@' + templateContext['default_context']['upstream_proxy_address'] + ':' + templateContext['default_context']['upstream_proxy_port']
                    } else {
                        smc['http_proxy'] = 'http://' + templateContext['default_context']['upstream_proxy_address'] + ':' + templateContext['default_context']['upstream_proxy_port']
                        smc['https_proxy'] = 'http://' + templateContext['default_context']['upstream_proxy_address'] + ':' + templateContext['default_context']['upstream_proxy_port']
                    }
                }

                for (i in common.entries(smc)) {
                    sh "sed -i 's,${i[0]}=.*,${i[0]}=${i[1]},' user_data"
                }

                // create cfg config-drive
                sh "./create-config-drive ${args}"
                sh("mkdir output-${clusterName} && mv ${saltMaster}.${clusterDomain}-config.iso output-${clusterName}/")

                // save cfg iso to artifacts
                archiveArtifacts artifacts: "output-${clusterName}/${saltMaster}.${clusterDomain}-config.iso"

                if (templateContext['default_context']['local_repositories'] == 'True') {
                    def aptlyServerHostname = templateContext.default_context.aptly_server_hostname
                    sh "[ -f mcp-common-scripts/config-drive/mirror_config.yaml ] && cp mcp-common-scripts/config-drive/mirror_config.yaml mirror_config || cp mcp-common-scripts/config-drive/mirror_config.sh mirror_config"

                    def smc_apt = [:]
                    smc_apt['SALT_MASTER_DEPLOY_IP'] = templateContext['default_context']['salt_master_management_address']
                    smc_apt['APTLY_DEPLOY_IP'] = templateContext['default_context']['aptly_server_deploy_address']
                    smc_apt['APTLY_DEPLOY_NETMASK'] = templateContext['default_context']['deploy_network_netmask']
                    smc_apt['APTLY_MINION_ID'] = "${aptlyServerHostname}.${clusterDomain}"

                    for (i in common.entries(smc_apt)) {
                        sh "sed -i \"s,export ${i[0]}=.*,export ${i[0]}=${i[1]},\" mirror_config"
                    }

                    // create apt config-drive
                    sh "./create-config-drive --user-data mirror_config --hostname ${aptlyServerHostname} ${aptlyServerHostname}.${clusterDomain}-config.iso"
                    sh("mv ${aptlyServerHostname}.${clusterDomain}-config.iso output-${clusterName}/")

                    // save apt iso to artifacts
                    archiveArtifacts artifacts: "output-${clusterName}/${aptlyServerHostname}.${clusterDomain}-config.iso"
                }
            }

            stage('Save changes reclass model') {
                sh(returnStatus: true, script: "tar -czf output-${clusterName}/${clusterName}.tar.gz --exclude='*@tmp' -C ${modelEnv} .")
                archiveArtifacts artifacts: "output-${clusterName}/${clusterName}.tar.gz"

                if (EMAIL_ADDRESS != null && EMAIL_ADDRESS != "") {
                    emailext(to: EMAIL_ADDRESS,
                        attachmentsPattern: "output-${clusterName}/*",
                        body: "Mirantis Jenkins\n\nRequested reclass model ${clusterName} has been created and attached to this email.\nEnjoy!\n\nMirantis",
                        subject: "Your Salt model ${clusterName}")
                }
                dir("output-${clusterName}") {
                    deleteDir()
                }
            }

            // Fail, but leave possibility to get failed artifacts
            if (!testResult && TEST_MODEL.toBoolean()) {
                common.warningMsg('Test finished: FAILURE. Please check logs and\\or debug failed model manually!')
                error('Test stage finished: FAILURE')
            }

        } catch (Throwable e) {
            currentBuild.result = "FAILURE"
            currentBuild.description = currentBuild.description ? e.message + " " + currentBuild.description : e.message
            throw e
        } finally {
            stage('Clean workspace directories') {
                sh(script: 'find . -mindepth 1 -delete > /dev/null || true')
            }
            // common.sendNotification(currentBuild.result,"",["slack"])
        }
    }
}
