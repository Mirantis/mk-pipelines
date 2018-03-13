/**
 * Parent pipeline for running customer's models
 *
 * Params:
 * CUSTOMERS - Comma-separated list of customer's names
 */
def common = new com.mirantis.mk.Common()

timeout(time: 12, unit: 'HOURS') {
  node() {
    try {
      stage("Run customer's salt models tests") {
        if(common.validInputParam("CUSTOMERS")){
           def customerList = CUSTOMERS.tokenize(",").collect{it.trim()}
           for(int i=0; i<customerList.size(); i++){
             def modelName = customerList[i]
             common.infoMsg("Test of ${modelName} starts")
             build job: "test-salt-model-customer-${modelName}"
            // build job: "test-salt-model-customer-${customerList[i]}", parameters: [
            //   [$class: 'StringParameterValue', name: 'DEFAULT_GIT_URL', value: defaultGitUrl],
            //   [$class: 'StringParameterValue', name: 'DEFAULT_GIT_REF', value: defaultGitRef],
            //   [$class: 'StringParameterValue', name: 'CLUSTER_NAME', value: modelName],
            //   [$class: 'StringParameterValue', name: 'NODE_TARGET', value: testTarget],
            //   [$class: 'StringParameterValue', name: 'FORMULAS_SOURCE', value: formulasSource]
            //   [$class: 'StringParameterValue', name: 'EXTRA_FORMULAS', value: EXTRA_FORMULAS],
            //   [$class: 'StringParameterValue', name: 'FORMULAS_REVISION', value: FORMULAS_REVISION],
            //   [$class: 'StringParameterValue', name: 'CREDENTIALS_ID', value: CREDENTIALS_ID],
            //   [$class: 'StringParameterValue', name: 'SYSTEM_GIT_URL', value: SYSTEM_GIT_URL],
            //   [$class: 'StringParameterValue', name: 'MAX_CPU_PER_JOB', value: MAX_CPU_PER_JOB],
            //   [$class: 'StringParameterValue', name: 'SYSTEM_GIT_REF', value: SYSTEM_GIT_REF],
            //   [$class: 'BooleanParameterValue', name: 'LEGACY_TEST_MODE', value: LEGACY_TEST_MODE.toBoolean()],
            //   [$class: 'BooleanParameterValue', name: 'RECLASS_IGNORE_CLASS_NOTFOUND', value: RECLASS_IGNORE_CLASS_NOTFOUND.toBoolean()]
            // ]
           }
        }
      }
    } catch (Throwable e) {
       // If there was an error or exception thrown, the build failed
       currentBuild.result = "FAILURE"
       currentBuild.description = currentBuild.description ? e.message + " " + currentBuild.description : e.message
       throw e
    } finally {
       common.sendNotification(currentBuild.result, "", ["slack"])
    }
  }
}

