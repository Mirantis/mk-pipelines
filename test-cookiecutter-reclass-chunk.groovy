package com.mirantis.mk

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
            try {
                extraVars = readYaml text: EXTRA_VARIABLES_YAML
                currentBuild.description = extraVars.modelFile
                sh(script:  'find . -mindepth 1 -delete || true', returnStatus: true)
                sh(script: """
                    wget --progress=dot:mega --auth-no-challenge -O models.tar.gz ${extraVars.MODELS_TARGZ}
                    tar -xzf models.tar.gz
                """)
                common.infoMsg("Going to test exactly one context: ${extraVars.modelFile}\n, with params: ${extraVars}")

                def content = readFile(file: extraVars.modelFile)
                def templateContext = readYaml text: content
                def config = [
                    'dockerHostname': "cfg01",
                    'domain': "${templateContext.default_context.cluster_domain}",
                    'clusterName': templateContext.default_context.cluster_name,
                    'reclassEnv': extraVars.testReclassEnv,
                    'distribRevision': extraVars.DISTRIB_REVISION,
                    'dockerContainerName': extraVars.DockerCName,
                    'testContext': extraVars.modelFile
                ]
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
            }
        }
    }
}
