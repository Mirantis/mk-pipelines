
/**
 *  Test salt models pipeline
 *  DEFAULT_GIT_REF
 *  DEFAULT_GIT_URL
 *  CREDENTIALS_ID
 */

def common = new com.mirantis.mk.Common()
def gerrit = new com.mirantis.mk.Gerrit()
def ssh = new com.mirantis.mk.Ssh()
def git = new com.mirantis.mk.Git()

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

node("python") {
  try{
    stage("checkout") {
      if (gerritRef) {
        // job is triggered by Gerrit
        checkouted = gerrit.gerritPatchsetCheckout ([
          credentialsId : CREDENTIALS_ID
        ])
      } else if(defaultGitRef && defaultGitUrl) {
          checkouted = gerrit.gerritPatchsetCheckout(defaultGitUrl, defaultGitRef, "master", CREDENTIALS_ID)
      }
      if(checkouted){
        if (fileExists('classes/system')) {
          ssh.prepareSshAgentKey(CREDENTIALS_ID)
          dir('classes/system') {
            remoteUrl = git.getGitRemote()
            ssh.ensureKnownHosts(remoteUrl)
          }
          ssh.agentSh("git submodule init; git submodule sync; git submodule update --recursive")
        }
      }else{
        common.errorMsg("Cannot checkout gerrit patchset, GERRIT_REFSPEC and DEFAULT_GIT_REF is null")
      }
    }
    stage("test") {
      if(checkouted){
        timeout(1440) {
          wrap([$class: 'AnsiColorBuildWrapper']) {
            sh("make test")
          }
        }
      }
    }
  } catch (Throwable e) {
     // If there was an error or exception thrown, the build failed
     currentBuild.result = "FAILURE"
     throw e
  } finally {
     common.sendNotification(currentBuild.result,"",["slack"])
  }
}
