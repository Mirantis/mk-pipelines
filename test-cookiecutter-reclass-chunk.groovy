package com.mirantis.mk

def common = new com.mirantis.mk.Common()
def saltModelTesting = new com.mirantis.mk.SaltModelTesting()

/**
 * Test CC model wrapper
 *  EXTRA_VARIABLES_YAML: yaml based string, to be directly passed into testCCModel
 *  SLAVE_NODE:
 */

slaveNode = env.SLAVE_NODE ?: 'python&&docker'

timeout(time: 1, unit: 'HOURS') {
  node(slaveNode) {
    try {
      extraVars = readYaml text: EXTRA_VARIABLES_YAML
      currentBuild.description = extraVars.modelFile
      saltModelTesting.testCCModel(extraVars)
    } catch (Throwable e) {
      // If there was an error or exception thrown, the build failed
      currentBuild.result = "FAILURE"
      currentBuild.description = currentBuild.description ? e.message + " " + currentBuild.description : e.message
      throw e
    }
  }
}
