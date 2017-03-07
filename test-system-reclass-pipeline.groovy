def gerrit = new com.mirantis.mk.Gerrit()
def common = new com.mirantis.mk.Common()

def gerritCredentials
try {
    gerritCredentials = CREDENTIALS_ID
} catch (MissingPropertyException e) {
    gerritCredentials = "gerrit"
}

try {
  stage("Checkout") {
    node() {
      gerrit.gerritPatchsetCheckout ([
        credentialsId : gerritCredentials
      ])
    }
  }

  stage("Test") {
    def branches = [:]
    def testModels = TEST_MODELS.split(',')

    for (int i = 0; i < testModels.size(); i++) {
      def cluster = testModels[i]
      branches["${cluster}"] = {
        build job: "test-salt-model-${cluster}", parameters: [
          [$class: 'StringParameterValue', name: 'RECLASS_SYSTEM_GIT_URL', value: "${GERRIT_SCHEME}://${GERRIT_NAME}@${GERRIT_HOST}:${GERRIT_PORT}/${GERRIT_PROJECT}.git"],
          [$class: 'StringParameterValue', name: 'RECLASS_SYSTEM_GIT_REF', value: GERRIT_REFSPEC]
        ]
      }
    }
    parallel branches
  }
} catch (Throwable e) {
    // If there was an error or exception thrown, the build failed
    currentBuild.result = "FAILURE"
    throw e
} finally {
    common.sendNotification(currentBuild.result,"",["slack"])
}
