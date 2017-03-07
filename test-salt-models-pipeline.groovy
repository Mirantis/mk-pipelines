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

def systemGitRef, systemGitUrl
try {
    systemGitRef = RECLASS_SYSTEM_GIT_REF
    systemGitUrl = RECLASS_SYSTEM_GIT_URL
} catch (MissingPropertyException e) {
    systemGitRef = null
    systemGitUrl = null
}

node("python") {
  try{
    stage("checkout") {
      if (gerritRef) {
        gerrit.gerritPatchsetCheckout ([
          credentialsId : CREDENTIALS_ID
        ])
      } else {
        git.checkoutGitRepository('.', GIT_URL, "master", CREDENTIALS_ID)
      }

      if (fileExists('classes/system')) {
        ssh.prepareSshAgentKey(CREDENTIALS_ID)
        dir('classes/system') {
          remoteUrl = git.getGitRemote()
          ssh.ensureKnownHosts(remoteUrl)
        }
        ssh.agentSh("git submodule init; git submodule sync; git submodule update --recursive")

        if (systemGitRef) {
          common.infoMsg("Fetching alternate system reclass (${systemGitUrl} ${systemGitRef})")
          dir('classes/system') {
            ssh.ensureKnownHosts(RECLASS_SYSTEM_GIT_URL)
            ssh.agentSh("git fetch ${systemGitUrl} ${systemGitRef} && git checkout FETCH_HEAD")
          }
        }
      }
    }
    stage("test") {
      timeout(10800) {
        wrap([$class: 'AnsiColorBuildWrapper']) {
          sh("make test")
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
