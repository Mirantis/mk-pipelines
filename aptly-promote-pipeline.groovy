def common = new com.mirantis.mk.Common()
def aptly = new com.mirantis.mk.Aptly()
node() {
  try{
    stage("promote") {
      lock("aptly-api") {
        wrap([$class: 'AnsiColorBuildWrapper']) {
          aptly.promotePublish(APTLY_URL, SOURCE, TARGET, RECREATE, null, null, DIFF_ONLY)
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