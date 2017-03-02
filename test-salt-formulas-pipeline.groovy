def common = new com.mirantis.mk.Common()
def gerrit = new com.mirantis.mk.Gerrit()
node("python") {
  try{
    stage("checkout") {
      gerrit.gerritPatchsetCheckout ([
          credentialsId : CREDENTIALS_ID
      ])
    }
    stage("test") {
      wrap([$class: 'AnsiColorBuildWrapper']) {
        sh("make clean")
        sh("[ $SALT_VERSION != 'latest' ] || export SALT_VERSION=''; make test")
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