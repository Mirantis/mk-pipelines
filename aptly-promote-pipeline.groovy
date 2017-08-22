def common = new com.mirantis.mk.Common()
def aptly = new com.mirantis.mk.Aptly()


def packages
try {
  packages = PACKAGES
} catch (MissingPropertyException e) {
  packages = ""
}

def components
try {
  components = COMPONENTS
} catch (MissingPropertyException e) {
  components = ""
}

node() {
  try{
    stage("promote") {
      lock("aptly-api") {
        aptly.promotePublish(APTLY_URL, SOURCE, TARGET, RECREATE, components, packages, DIFF_ONLY)
      }
    }
  } catch (Throwable e) {
     // If there was an error or exception thrown, the build failed
     currentBuild.result = "FAILURE"
     currentBuild.description = e.message
     throw e
  } finally {
     common.sendNotification(currentBuild.result,"",["slack"])
  }
}