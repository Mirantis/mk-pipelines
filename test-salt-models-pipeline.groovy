def common = new com.mirantis.mk.Common()
def gerrit = new com.mirantis.mk.Gerrit()
def ssh = new com.mirantis.mk.Ssh()
def git = new com.mirantis.mk.Git()

node("python") {
  try{
    stage("checkout") {
      gerrit.gerritPatchsetCheckout ([
          credentialsId : CREDENTIALS_ID
      ])

      if (fileExists('classes/system')) {
        ssh.prepareSshAgentKey(CREDENTIALS_ID)
        dir('classes/system') {
          remoteUrl = git.getGitRemote()
          ssh.ensureKnownHosts(remoteUrl)
        }
        ssh.agentSh("git submodule init; git submodule sync; git submodule update --recursive")
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
