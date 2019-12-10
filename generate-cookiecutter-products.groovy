/**
 * Generate cookiecutter cluster by individual products
 *
 * Expected parameters:
 *   COOKIECUTTER_TEMPLATE_CONTEXT      Context parameters for the template generation.
 *   CREDENTIALS_ID                     Credentials id for git
 *   TEST_MODEL                         Run syntax tests for model
 **/
import static groovy.json.JsonOutput.toJson
import static groovy.json.JsonOutput.prettyPrint
import org.apache.commons.net.util.SubnetUtils

common = new com.mirantis.mk.Common()
common2 = new com.mirantis.mcp.Common()
git = new com.mirantis.mk.Git()
python = new com.mirantis.mk.Python()
saltModelTesting = new com.mirantis.mk.SaltModelTesting()
updateSaltFormulasDuringTest = true

slaveNode = env.getProperty('SLAVE_NODE') ?: 'virtual'
gerritCredentials = env.getProperty('CREDENTIALS_ID') ?: 'gerrit'
runTestModel = (env.getProperty('TEST_MODEL') ?: true).toBoolean()
distribRevision = 'proposed'
gitGuessedVersion = false

def GenerateModelToxDocker(Map params) {
    def ccRoot = params['ccRoot']
    def context = params['context']
    def outDir = params['outDir']
    def envOpts = params['envOpts']
    def tempContextFile = new File(ccRoot, 'tempContext.yaml_' + UUID.randomUUID().toString()).toString()
    writeFile file: tempContextFile, text: context
    // Get Jenkins user UID and GID
    def jenkinsUID = sh(script: 'id -u', returnStdout: true).trim()
    def jenkinsGID = sh(script: 'id -g', returnStdout: true).trim()
    /*
        by default, process in image operates via root user
        Otherwise, gpg key for model and all files managed by jenkins user
        To make it compatible, install rrequirementfrom user, but generate model via jenkins
        for build use upstream Ubuntu Bionic image
    */
    def configRun = ['distribRevision': 'nightly',
                     'envOpts'        : envOpts + ["CONFIG_FILE=$tempContextFile",
                                                   "OUTPUT_DIR=${outDir}"
                     ],
                     'image': 'docker-prod-local.artifactory.mirantis.com/mirantis/cicd/jnlp-slave',
                     'runCommands'    : [
                         '001_prepare_generate_auto_reqs': {
                            sh('''
                                pip install tox
                                ''')
                         },
                         // user & group can be different on host and in docker
                         '002_set_jenkins_id': {
                            sh("""
                                usermod -u ${jenkinsUID} jenkins
                                groupmod -g ${jenkinsGID} jenkins
                                """)
                         },
                         '003_run_generate_auto': {
                             print('[Cookiecutter build] Result:\n' +
                                 sh(returnStdout: true, script: 'cd ' + ccRoot + '; su jenkins -c "tox -ve generate_auto" '))
                         }
                     ]
    ]

    saltModelTesting.setupDockerAndTest(configRun)
}

def globalVariatorsUpdate() {
    def templateContext = readYaml text: env.COOKIECUTTER_TEMPLATE_CONTEXT
    def context = templateContext['default_context']
    // TODO add more check's for critical var's
    // Since we can't pin to any '_branch' variable from context, to identify 'default git revision' -
    // because each of them, might be 'refs/' variable, we need to add  some tricky trigger of using
    // 'release/XXX' logic. This is totall guess - so,if even those one failed, to definitely must pass
    // correct variable finally!
    [ context.get('cookiecutter_template_branch'), context.get('shared_reclass_branch'), context.get('mcp_common_scripts_branch') ].any { branch ->
        if (branch.toString().startsWith('release/')) {
            gitGuessedVersion = branch
            return true
        }
    }

    [ 'cookiecutter_template_branch', 'shared_reclass_branch', 'mcp_common_scripts_branch' ].each { repoName ->
        if (context['mcp_version'] in [ "nightly", "testing", "stable" ] && ! context.get(repoName)) {
            context[repoName] = 'master'
        } else if (! context.get(repoName)) {
            context[repoName] = gitGuessedVersion ?: "release/${context['mcp_version']}".toString()
        }
    }
    //
    distribRevision = context['mcp_version']
    if (['master'].contains(context['mcp_version'])) {
        distribRevision = 'nightly'
    }
    if (distribRevision.contains('/')) {
        distribRevision = distribRevision.split('/')[-1]
    }
    // Check if we are going to test bleeding-edge release, which doesn't have binary release yet
    // After 2018q4 releases, need to also check 'static' repo, for example ubuntu.
    def binTest = common.checkRemoteBinary(['mcp_version': distribRevision])
    if (!binTest.linux_system_repo_url || !binTest.linux_system_repo_ubuntu_url) {
        common.errorMsg("Binary release: ${distribRevision} not exist or not full. Fallback to 'proposed'! ")
        distribRevision = 'proposed'
    }

    // (azvyagintsev) WA for PROD-25732
    if (context.cookiecutter_template_url.contains('gerrit.mcp.mirantis.com/mk/cookiecutter-templates')) {
        common.warningMsg('Apply WA for PROD-25732')
        context.cookiecutter_template_url = 'ssh://gerrit.mcp.mirantis.com:29418/mk/cookiecutter-templates.git'
    }
    // check, if we are going to test clear release version, w\o any updates and patches.
    // All 2019.2.X starting from 2019.2.1 and above have update repo which has to be used for salt-formulas.
    // update/2019.2.0 repo can't be used b/c it points to latest 2019.2.X
    if (!gitGuessedVersion && (distribRevision == context.mcp_version) && !(distribRevision ==~ /2019\.2\.[1-9]\d*/)) {
        updateSaltFormulasDuringTest = false
    }

    if (gitGuessedVersion == 'release/proposed/2019.2.0') {
        def mcpSaltRepoUpdateVar = 'deb [arch=amd64] http://mirror.mirantis.com/update/proposed/salt-formulas/xenial xenial main'
        if (context.get('offline_deployment', 'False').toBoolean()) {
            mcpSaltRepoUpdateVar = "deb [arch=amd64] http://${context.get('aptly_server_deploy_address')}/update/proposed/salt-formulas/xenial xenial main".toString()
        }
        // CFG node in 2019.2.X update has to be bootstrapped with update/proposed repository for salt formulas
        context['cloudinit_master_config'] = context.get('cloudinit_master_config', false) ?: [:]
        context['cloudinit_master_config']['MCP_SALT_REPO_UPDATES'] = context['cloudinit_master_config'].get('MCP_SALT_REPO_UPDATES', false) ?: mcpSaltRepoUpdateVar
    }

    common.infoMsg("Using context:\n" + context)
    print prettyPrint(toJson(context))
    return context

}

timeout(time: 1, unit: 'HOURS') {
    node(slaveNode) {
        def context = globalVariatorsUpdate()
        def RequesterEmail = context.get('email_address', '')
        def templateEnv = "${env.WORKSPACE}/template"
        // modelEnv - this is reclass root, aka /srv/salt/reclass
        def modelEnv = "${env.WORKSPACE}/model"
        def testEnv = "${env.WORKSPACE}/test"
        def pipelineEnv = "${env.WORKSPACE}/pipelines"

        try {
            //
            def cutterEnv = "${env.WORKSPACE}/cutter"
            def systemEnv = "${modelEnv}/classes/system"
            def testResult = false
            def user
            wrap([$class: 'BuildUser']) {
                user = env.BUILD_USER_ID
            }
            currentBuild.description = "${context['cluster_name']} ${RequesterEmail}"

            stage('Download Cookiecutter template') {
                sh(script: 'find . -mindepth 1 -delete > /dev/null || true')
                checkout([
                    $class           : 'GitSCM',
                    branches         : [[name: 'FETCH_HEAD'],],
                    extensions       : [[$class: 'RelativeTargetDirectory', relativeTargetDir: templateEnv]],
                    userRemoteConfigs: [[url: context['cookiecutter_template_url'], refspec: context['cookiecutter_template_branch'], credentialsId: gerritCredentials],],
                ])
            }
            stage('Create empty reclass model') {
                dir(path: modelEnv) {
                    sh 'rm -rfv .git; git init'
                    sshagent(credentials: [gerritCredentials]) {
                        sh "git submodule add ${context['shared_reclass_url']} 'classes/system'"
                    }
                }
                checkout([
                    $class           : 'GitSCM',
                    branches         : [[name: 'FETCH_HEAD'],],
                    extensions       : [[$class: 'RelativeTargetDirectory', relativeTargetDir: systemEnv]],
                    userRemoteConfigs: [[url: context['shared_reclass_url'], refspec: context['shared_reclass_branch'], credentialsId: gerritCredentials],],
                ])
                git.commitGitChanges(modelEnv, 'Added new shared reclass submodule', "${user}@localhost", "${user}")
            }

            stage('Generate model') {
                // GNUPGHOME environment variable is required for all gpg commands
                // and for python.generateModel execution
                def envOpts = ["GNUPGHOME=${env.WORKSPACE}/gpghome"]
                withEnv(envOpts) {
                    if (context['secrets_encryption_enabled'] == 'True') {
                        sh "mkdir gpghome; chmod 700 gpghome"
                        def secretKeyID = RequesterEmail ?: "salt@${context['cluster_domain']}".toString()
                        if (!context.get('secrets_encryption_private_key')) {
                            def batchData = """
                                %echo Generating a basic OpenPGP key for Salt-Master
                                %no-protection
                                Key-Type: 1
                                Key-Length: 4096
                                Expire-Date: 0
                                Name-Real: ${context['salt_master_hostname']}.${context['cluster_domain']}
                                Name-Email: ${secretKeyID}
                                %commit
                                %echo done
                            """.stripIndent()
                            writeFile file: 'gpg-batch.txt', text: batchData
                            sh "gpg --gen-key --batch < gpg-batch.txt"
                            sh "gpg --export-secret-key -a ${secretKeyID} > gpgkey.asc"
                        } else {
                            writeFile file: 'gpgkey.asc', text: context['secrets_encryption_private_key']
                            sh "gpg --import gpgkey.asc"
                            secretKeyID = sh(returnStdout: true, script: 'gpg --list-secret-keys --with-colons | grep -E "^sec" | awk -F: \'{print \$5}\'').trim()
                        }
                        context['secrets_encryption_key_id'] = secretKeyID
                    }
                    if (context.get('cfg_failsafe_ssh_public_key')) {
                        writeFile file: 'failsafe-ssh-key.pub', text: context['cfg_failsafe_ssh_public_key']
                    }
                    if (!fileExists(new File(templateEnv, 'tox.ini').toString())) {
                        reqs = new File(templateEnv, 'requirements.txt').toString()
                        if (fileExists(reqs)) {
                            python.setupVirtualenv(cutterEnv, 'python2', [], reqs)
                        } else {
                            python.setupCookiecutterVirtualenv(cutterEnv)
                        }
                        python.generateModel(common2.dumpYAML(['default_context': context]), 'default_context', context['salt_master_hostname'], cutterEnv, modelEnv, templateEnv, false)
                    } else {
                        // tox-based CC generated structure of reclass,from the root. Otherwise for bw compat, modelEnv
                        // still expect only lower lvl of project, aka model/classes/cluster/XXX/. So,lets dump result into
                        // temp dir, and then copy it over initial structure.
                        reclassTempRootDir = sh(script: "mktemp -d -p ${env.WORKSPACE}", returnStdout: true).trim()
                        GenerateModelToxDocker(['context': common2.dumpYAML(['default_context': context]),
                                                'ccRoot' : templateEnv,
                                                'outDir' : reclassTempRootDir,
                                                'envOpts': envOpts])
                        dir(modelEnv) {
                            common.warningMsg('Forming reclass-root structure...')
                            sh("cp -ra ${reclassTempRootDir}/reclass/* .")
                        }
                    }
                    git.commitGitChanges(modelEnv, "Create model ${context['cluster_name']}", "${user}@localhost", "${user}")
                }
            }

            stage("Test") {
                if (runTestModel) {
                    sh("cp -r ${modelEnv} ${testEnv}")
                    if (fileExists('gpgkey.asc')) {
                        common.infoMsg('gpgkey.asc found!Copy it into reclass folder for tests..')
                        sh("cp -v gpgkey.asc ${testEnv}/salt_master_pillar.asc")
                    }
                    def DockerCName = "${env.JOB_NAME.toLowerCase()}_${env.BUILD_TAG.toLowerCase()}"
                    common.warningMsg("Attempt to run test against:\n" +
                        "DISTRIB_REVISION from ${distribRevision}\n" +
                        "updateSaltFormulasDuringTest = ${updateSaltFormulasDuringTest}")
                    try {
                        def config = [
                            'dockerHostname'     : "${context['salt_master_hostname']}",
                            'domain'             : "${context['cluster_domain']}",
                            'reclassEnv'         : testEnv,
                            'distribRevision'    : distribRevision,
                            'dockerContainerName': DockerCName,
                            'testContext'        : 'salt-model-node',
                            'dockerExtraOpts'    : ['--memory=3g'],
                            'updateSaltFormulas' : updateSaltFormulasDuringTest
                        ]
                        if (gitGuessedVersion == 'release/proposed/2019.2.0') {
                            config['updateSaltFormulasRev'] = 'proposed'
                        }
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
                def commonScriptsRepoUrl = context['mcp_common_scripts_repo'] ?: 'ssh://gerrit.mcp.mirantis.com:29418/mcp/mcp-common-scripts'
                checkout([
                    $class           : 'GitSCM',
                    branches         : [[name: 'FETCH_HEAD'],],
                    extensions       : [[$class: 'RelativeTargetDirectory', relativeTargetDir: 'mcp-common-scripts']],
                    userRemoteConfigs: [[url: commonScriptsRepoUrl, refspec: context['mcp_common_scripts_branch'], credentialsId: gerritCredentials],],
                ])

                def outdateGeneration = false
                if (fileExists('mcp-common-scripts/config-drive/create_config_drive.py')) {
                    sh 'cp mcp-common-scripts/config-drive/create_config_drive.py create-config-drive.py'
                } else {
                    outdateGeneration = true
                    sh 'cp mcp-common-scripts/config-drive/create_config_drive.sh create-config-drive && chmod +x create-config-drive'
                }
                sh '[ -f mcp-common-scripts/config-drive/master_config.sh ] && cp mcp-common-scripts/config-drive/master_config.sh user_data || cp mcp-common-scripts/config-drive/master_config.yaml user_data'

                sh "git clone --mirror https://github.com/Mirantis/mk-pipelines.git ${pipelineEnv}/mk-pipelines"
                sh "git clone --mirror https://github.com/Mirantis/pipeline-library.git ${pipelineEnv}/pipeline-library"
                args = [
                    "--user-data user_data", "--model ${modelEnv}",
                    "--mk-pipelines ${pipelineEnv}/mk-pipelines/", "--pipeline-library ${pipelineEnv}/pipeline-library/"
                ]
                if (context['secrets_encryption_enabled'] == 'True') {
                    args.add('--gpg-key gpgkey.asc')
                }
                if (context.get('cfg_failsafe_ssh_public_key')) {
                    if (outdateGeneration) {
                        args.add('--ssh-key failsafe-ssh-key.pub')
                    } else {
                        if (context.get('cfg_failsafe_user')) {
                            args.add('--ssh-keys failsafe-ssh-key.pub')
                            args.add("--cloud-user-name ${context.get('cfg_failsafe_user')}")
                        }
                    }
                }
                // load data from model
                def smc = [:]
                smc['SALT_MASTER_MINION_ID'] = "${context['salt_master_hostname']}.${context['cluster_domain']}"
                smc['SALT_MASTER_DEPLOY_IP'] = context['salt_master_management_address']
                if (context.get('cloudinit_master_config', false)) {
                    context['cloudinit_master_config'].each { k, v ->
                        smc[k] = v
                    }
                }
                if (outdateGeneration) {
                    smc['DEPLOY_NETWORK_GW'] = context['deploy_network_gateway']
                    smc['DEPLOY_NETWORK_NETMASK'] = context['deploy_network_netmask']
                    if (context.get('deploy_network_mtu')) {
                        smc['DEPLOY_NETWORK_MTU'] = context['deploy_network_mtu']
                    }
                    smc['DNS_SERVERS'] = context['dns_server01']
                }
                smc['MCP_VERSION'] = "${context['mcp_version']}"
                if (context['local_repositories'] == 'True') {
                    def localRepoIP = ''
                    if (context['mcp_version'] in ['2018.4.0', '2018.8.0', '2018.8.0-milestone1', '2018.11.0']) {
                        localRepoIP = context['local_repo_url']
                        smc['MCP_SALT_REPO_URL'] = "http://${localRepoIP}/ubuntu-xenial"
                    } else {
                        localRepoIP = context['aptly_server_deploy_address']
                        smc['MCP_SALT_REPO_URL'] = "http://${localRepoIP}"
                    }
                    smc['MCP_SALT_REPO_KEY'] = "http://${localRepoIP}/public.gpg"
                    smc['PIPELINES_FROM_ISO'] = 'false'
                    smc['PIPELINE_REPO_URL'] = "http://${localRepoIP}:8088"
                    smc['LOCAL_REPOS'] = 'true'
                }
                if (context['upstream_proxy_enabled'] == 'True') {
                    if (context['upstream_proxy_auth_enabled'] == 'True') {
                        smc['http_proxy'] = 'http://' + context['upstream_proxy_user'] + ':' + context['upstream_proxy_password'] + '@' + context['upstream_proxy_address'] + ':' + context['upstream_proxy_port']
                        smc['https_proxy'] = 'http://' + context['upstream_proxy_user'] + ':' + context['upstream_proxy_password'] + '@' + context['upstream_proxy_address'] + ':' + context['upstream_proxy_port']
                    } else {
                        smc['http_proxy'] = 'http://' + context['upstream_proxy_address'] + ':' + context['upstream_proxy_port']
                        smc['https_proxy'] = 'http://' + context['upstream_proxy_address'] + ':' + context['upstream_proxy_port']
                    }
                }

                for (i in common.entries(smc)) {
                    sh "sed -i 's,export ${i[0]}=.*,export ${i[0]}=\${${i[0]}:-\"${i[1]}\"},' user_data"
                }

                // calculate netmask
                def deployNetworkSubnet = ''
                if (context.get('deploy_network_subnet')) {
                    def subnet = new SubnetUtils(context['deploy_network_subnet'])
                    deployNetworkSubnet = subnet.getInfo().getNetmask()
                } else if (context.get('deploy_network_netmask')) { // case for 2018.4.0
                    deployNetworkSubnet = context['deploy_network_netmask']
                } else {
                    error('Neither context parameter deploy_network_subnet or deploy_network_netmask should be set!')
                }
                // create cfg config-drive
                if (outdateGeneration) {
                    args += ["--hostname ${context['salt_master_hostname']}", "${context['salt_master_hostname']}.${context['cluster_domain']}-config.iso"]
                    sh "./create-config-drive ${args.join(' ')}"
                } else {
                    args += [
                        "--name ${context['salt_master_hostname']}", "--hostname ${context['salt_master_hostname']}.${context['cluster_domain']}", "--clean-up",
                        "--ip ${context['salt_master_management_address']}", "--netmask ${deployNetworkSubnet}", "--gateway ${context['deploy_network_gateway']}",
                        "--dns-nameservers ${context['dns_server01']},${context['dns_server02']}"
                    ]
                    sh "chmod 0755 create-config-drive.py ; ./create-config-drive.py ${args.join(' ')}"
                }
                sh("mkdir output-${context['cluster_name']} && mv ${context['salt_master_hostname']}.${context['cluster_domain']}-config.iso output-${context['cluster_name']}/")

                // save cfg iso to artifacts
                archiveArtifacts artifacts: "output-${context['cluster_name']}/${context['salt_master_hostname']}.${context['cluster_domain']}-config.iso"

                if (context['local_repositories'] == 'True') {
                    def aptlyServerHostname = context.aptly_server_hostname
                    sh "[ -f mcp-common-scripts/config-drive/mirror_config.yaml ] && cp mcp-common-scripts/config-drive/mirror_config.yaml mirror_config || cp mcp-common-scripts/config-drive/mirror_config.sh mirror_config"

                    def smc_apt = [:]
                    smc_apt['SALT_MASTER_DEPLOY_IP'] = context['salt_master_management_address']
                    if (outdateGeneration) {
                        smc_apt['APTLY_DEPLOY_IP'] = context['aptly_server_deploy_address']
                        smc_apt['APTLY_DEPLOY_NETMASK'] = context['deploy_network_netmask']
                    }
                    smc_apt['APTLY_MINION_ID'] = "${aptlyServerHostname}.${context['cluster_domain']}"

                    for (i in common.entries(smc_apt)) {
                        sh "sed -i \"s,export ${i[0]}=.*,export ${i[0]}=${i[1]},\" mirror_config"
                    }

                    // create apt config-drive
                    if (outdateGeneration) {
                        sh "./create-config-drive --user-data mirror_config --hostname ${aptlyServerHostname} ${aptlyServerHostname}.${context['cluster_domain']}-config.iso"
                    } else {
                        args = [
                            "--ip ${context['aptly_server_deploy_address']}", "--netmask ${deployNetworkSubnet}", "--gateway ${context['deploy_network_gateway']}",
                            "--user-data mirror_config", "--hostname ${aptlyServerHostname}.${context['cluster_domain']}", "--name ${aptlyServerHostname}", "--clean-up",
                            "--dns-nameservers ${context['dns_server01']},${context['dns_server02']}"
                        ]
                        sh "python ./create-config-drive.py ${args.join(' ')}"
                    }
                    sh("mv ${aptlyServerHostname}.${context['cluster_domain']}-config.iso output-${context['cluster_name']}/")

                    // save apt iso to artifacts
                    archiveArtifacts artifacts: "output-${context['cluster_name']}/${aptlyServerHostname}.${context['cluster_domain']}-config.iso"
                }
            }

            stage('Save changes reclass model') {
                sh(returnStatus: true, script: "tar -czf ${context['cluster_name']}.tar.gz --exclude='*@tmp' -C ${modelEnv} .")
                archiveArtifacts artifacts: "${context['cluster_name']}.tar.gz"

                if (RequesterEmail != '' && !RequesterEmail.contains('example')) {
                    def mailSubject = "Your Salt model ${context['cluster_name']}"
                    if (context.get('send_method') == 'gcs') {
                        def gcs = new com.mirantis.mk.GoogleCloudStorage()
                        def uploadIsos = [ "output-${context['cluster_name']}/${context['salt_master_hostname']}.${context['cluster_domain']}-config.iso" ]
                        if (context['local_repositories'] == 'True') {
                            uploadIsos << "output-${context['cluster_name']}/${aptlyServerHostname}.${context['cluster_domain']}-config.iso"
                        }
                        // generate random hash to have uniq and unpredictable link to file
                        def randHash = common.generateRandomHashString(64)
                        def config = [
                            'creds': context['gcs_creds'],
                            'project': context['gcs_project'],
                            'dest': "gs://${context['gcs_bucket']}/${randHash}",
                            'sources': uploadIsos
                        ]
                        def fileURLs = gcs.uploadArtifactToGoogleStorageBucket(config).join(' ').replace('gs://', 'https://storage.googleapis.com/')
                        emailext(to: RequesterEmail,
                            body: "Mirantis Jenkins\n\nRequested reclass model ${context['cluster_name']} has been created and available to download via next URL: ${fileURLs} within 7 days.\nEnjoy!\n\nMirantis",
                            subject: mailSubject)
                    } else {
                        emailext(to: RequesterEmail,
                            attachmentsPattern: "output-${context['cluster_name']}/*",
                            body: "Mirantis Jenkins\n\nRequested reclass model ${context['cluster_name']} has been created and attached to this email.\nEnjoy!\n\nMirantis",
                            subject: mailSubject)
                    }
                }
                dir("output-${context['cluster_name']}") {
                    deleteDir()
                }
            }

            // Fail, but leave possibility to get failed artifacts
            if (!testResult && runTestModel) {
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
            stage('Save artifacts to Artifactory') {
                def artifactory = new com.mirantis.mcp.MCPArtifactory()
                def buildProps = ["context=${context['cluster_name']}"]
                if (RequesterEmail != '' && !RequesterEmail.contains('example')) {
                    buildProps.add("emailTo=${RequesterEmail}")
                }
                def artifactoryLink = artifactory.uploadJobArtifactsToArtifactory([
                    'artifactory'    : 'mcp-ci',
                    'artifactoryRepo': "drivetrain-local/${JOB_NAME}/${context['cluster_name']}-${BUILD_NUMBER}",
                    'buildProps'     : buildProps,
                ])
                currentBuild.description += "<br/>${artifactoryLink}"
            }
        }
    }
}
