def common = new com.mirantis.mk.Common()
def gerrit = new com.mirantis.mk.Gerrit()

node("python") {
  try{
    stage("checkout") {
      gerrit.gerritPatchsetCheckout ([
          credentialsId : CREDENTIALS_ID
      ])
      sh("git submodule init; git submodule sync; git submodule update --recursive")
    }
    stage("test") {
      wrap([$class: 'AnsiColorBuildWrapper']) {
        sh("make test")
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