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
def merged = false
def systemRefspec = "HEAD"
node() {
  try {
    stage("Checkout") {
      if (gerritRef) {
        // job is triggered by Gerrit
        // test if change aren't already merged
        def gerritChange = gerrit.getGerritChange(GERRIT_NAME, GERRIT_HOST, GERRIT_CHANGE_NUMBER, gerritCredentials)
        merged = gerritChange.status == "MERGED"
        if(!merged){
          checkouted = gerrit.gerritPatchsetCheckout ([
            credentialsId : gerritCredentials
          ])
          systemRefspec = GERRIT_REFSPEC
        }
        // change defaultGit variables if job triggered from Gerrit
        defaultGitUrl = "${GERRIT_SCHEME}://${GERRIT_NAME}@${GERRIT_HOST}:${GERRIT_PORT}/${GERRIT_PROJECT}"
      } else if(defaultGitRef && defaultGitUrl) {
          checkouted = gerrit.gerritPatchsetCheckout(defaultGitUrl, defaultGitRef, "HEAD", gerritCredentials)
      }
    }

    stage("Test") {
      if(merged){
        common.successMsg("Gerrit change is already merged, no need to test them")
      }else{
        if(checkouted){

          def documentationOnly = false
          if (gerritRef) {
            documentationOnly = sh(script: "git diff-tree --no-commit-id --name-only -r HEAD | grep -v .releasenotes", returnStatus: true) == 1
          }

          def branches = [:]
          def testModels = documentationOnly ? [] : TEST_MODELS.split(',')
            for (int i = 0; i < testModels.size(); i++) {
              def cluster = testModels[i]
              def clusterGitUrl = defaultGitUrl.substring(0, defaultGitUrl.lastIndexOf("/") + 1) + cluster
              branches["${cluster}"] = {
                build job: "test-salt-model-${cluster}", parameters: [
                  [$class: 'StringParameterValue', name: 'DEFAULT_GIT_URL', value: clusterGitUrl],
                  [$class: 'StringParameterValue', name: 'DEFAULT_GIT_REF', value: "HEAD"],
                  [$class: 'StringParameterValue', name: 'SYSTEM_GIT_URL', value: defaultGitUrl],
                  [$class: 'StringParameterValue', name: 'SYSTEM_GIT_REF', value: systemRefspec]
                ]
              }
            }
          parallel branches
        }else{
           throw new Exception("Cannot checkout gerrit patchset, GERRIT_REFSPEC and DEFAULT_GIT_REF is null")
        }
      }
    }
  } catch (Throwable e) {
      // If there was an error or exception thrown, the build failed
      currentBuild.result = "FAILURE"
      currentBuild.description = currentBuild.description ? e.message + " " + currentBuild.description : e.message
      throw e
  } finally {
      common.sendNotification(currentBuild.result,"",["slack"])
  }
}
