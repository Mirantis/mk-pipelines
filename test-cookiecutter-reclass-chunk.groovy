def common = new com.mirantis.mk.Common()
def saltModelTesting = new com.mirantis.mk.SaltModelTesting()

/**
 * Test CC model wrapper
 *  EXTRA_VARIABLES_YAML: yaml based string, to be directly passed into testCCModel
 *  SLAVE_NODE:
 */

slaveNode = env.SLAVE_NODE ?: 'docker'

timeout(time: 1, unit: 'HOURS') {
    node(slaveNode) {
        stage("RunTest") {
            extraVars = readYaml text: EXTRA_VARIABLES_YAML
            try {
                currentBuild.description = extraVars.modelFile
                sh(script: 'find . -mindepth 1 -delete || true', returnStatus: true)
                sh(script: """
                    wget --progress=dot:mega --auth-no-challenge -O models.tar.gz ${extraVars.MODELS_TARGZ}
                    tar -xzf models.tar.gz
                """)
                common.infoMsg("Going to test exactly one context: ${extraVars.modelFile}\n, with params: ${extraVars}")

                def content = readFile(file: extraVars.modelFile)
                def templateContext = readYaml text: content
                def config = [
                    'dockerHostname'     : "cfg01",
                    'domain'             : "${templateContext.default_context.cluster_domain}",
                    'clusterName'        : templateContext.default_context.cluster_name,
                    'reclassEnv'         : extraVars.testReclassEnv,
                    'distribRevision'    : extraVars.DISTRIB_REVISION,
                    'dockerContainerName': extraVars.DockerCName,
                    'testContext'        : extraVars.modelFile,
                    'dockerExtraOpts'    : ['--memory=3g']
                ]
                if (extraVars.DISTRIB_REVISION == 'nightly') {
                    config['nodegenerator'] = true
                }
                if (extraVars.updatesVersion) {
                    config['updateSaltFormulasRev'] = extraVars.updatesVersion
                }
                if (extraVars.useExtraRepos) {
                    config['extraRepos'] = extraVars.extraRepos ? extraVars.extraRepos : [:]
                    config['extraRepoMergeStrategy'] = extraVars.extraRepoMergeStrategy ? extraVars.extraRepoMergeStrategy : ''
                }
                saltModelTesting.testNode(config)
            } catch (Throwable e) {
                // If there was an error or exception thrown, the build failed
                currentBuild.result = "FAILURE"
                currentBuild.description = currentBuild.description ? e.message + " " + currentBuild.description : e.message
                throw e
            } finally {
                stage('Save artifacts to Artifactory') {
                    def artifactory = new com.mirantis.mcp.MCPArtifactory()
                    def envGerritVars = ["GERRIT_PROJECT=${extraVars.get('GERRIT_PROJECT', '')}", "GERRIT_CHANGE_NUMBER=${extraVars.get('GERRIT_CHANGE_NUMBER', '')}",
                                         "GERRIT_PATCHSET_NUMBER=${extraVars.get('GERRIT_PATCHSET_NUMBER', '')}", "GERRIT_CHANGE_ID=${extraVars.get('GERRIT_CHANGE_ID', '')}",
                                         "GERRIT_PATCHSET_REVISION=${extraVars.get('GERRIT_PATCHSET_REVISION', '')}"]
                    withEnv(envGerritVars) {
                        def artifactoryLink = artifactory.uploadJobArtifactsToArtifactory(['artifactory': 'mcp-ci', 'artifactoryRepo': "artifactory/drivetrain-local/${JOB_NAME}/${BUILD_NUMBER}"])
                        currentBuild.description += "<br/>${artifactoryLink}"
                    }
                }
            }
        }
    }
}
