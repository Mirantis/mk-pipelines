def gerrit = new com.mirantis.mk.Gerrit()
def common = new com.mirantis.mk.Common()

def gerritCredentials
try {
    gerritCredentials = CREDENTIALS_ID
} catch (MissingPropertyException e) {
    gerritCredentials = "gerrit"
}

def gerritRef
try {
  gerritRef = GERRIT_REFSPEC
} catch (MissingPropertyException e) {
  gerritRef = null
}

def defaultGitRef, defaultGitUrl
try {
    defaultGitRef = DEFAULT_GIT_REF
    defaultGitUrl = DEFAULT_GIT_URL
} catch (MissingPropertyException e) {
    defaultGitRef = null
    defaultGitUrl = null
}
def checkouted = false

try {
  stage("Checkout") {
    node() {
      if (gerritRef) {
        // job is triggered by Gerrit
        checkouted = gerrit.gerritPatchsetCheckout ([
          credentialsId : gerritCredentials
        ])
        // change defaultGit variables if job triggered from Gerrit
        defaultGitUrl = "${GERRIT_SCHEME}://${GERRIT_NAME}@${GERRIT_HOST}:${GERRIT_PORT}/${GERRIT_PROJECT}"
      } else if(defaultGitRef && defaultGitUrl) {
          checkouted = gerrit.gerritPatchsetCheckout(defaultGitUrl, defaultGitRef, "HEAD", gerritCredentials)
      }
    }
  }

  stage("Test") {
    if(checkouted){
      def branches = [:]
      def testModels = TEST_MODELS.split(',')
        for (int i = 0; i < testModels.size(); i++) {
          def cluster = testModels[i]
          def clusterGitUrl = defaultGitUrl.substring(0, defaultGitUrl.lastIndexOf("/") + 1) + cluster
          branches["${cluster}"] = {
            build job: "test-salt-model-${cluster}", parameters: [
              [$class: 'StringParameterValue', name: 'DEFAULT_GIT_URL', value: clusterGitUrl],
              [$class: 'StringParameterValue', name: 'DEFAULT_GIT_REF', value: "HEAD"]
            ]
          }
        }
      parallel branches
    }else{
       common.errorMsg("Cannot checkout gerrit patchset, GERRIT_REFSPEC and DEFAULT_GIT_REF is null")
    }
  }
} catch (Throwable e) {
    // If there was an error or exception thrown, the build failed
    currentBuild.result = "FAILURE"
    throw e
} finally {
    common.sendNotification(currentBuild.result,"",["slack"])
}
