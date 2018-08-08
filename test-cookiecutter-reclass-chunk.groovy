package com.mirantis.mk
def common = new com.mirantis.mk.Common()
def saltModelTesting = new com.mirantis.mk.SaltModelTesting()

/**
 * Test CC model wrapper
 *  EXTRA_VARIABLES_YAML: yaml based string, to be directly passed into testCCModel
 */

timeout(time: 1, unit: 'HOURS') {
node() {
  try {
    extra_vars = readYaml text: EXTRA_VARIABLES_YAML
    currentBuild.description = extra_vars.modelFile
    saltModelTesting.testCCModel(extra_vars)
    } catch (Throwable e) {
          // If there was an error or exception thrown, the build failed
          currentBuild.result = "FAILURE"
          currentBuild.description = currentBuild.description ? e.message + " " + currentBuild.description : e.message
          throw e
        }
      }
    }
