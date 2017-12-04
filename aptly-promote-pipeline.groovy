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

def storages
try {
    storages = STORAGES.tokenize(',')
} catch (MissingPropertyException e) {
    storages = ['local']
}

node() {
  try{
    stage("promote") {
      lock("aptly-api") {
        for (storage in storages) {
          aptly.promotePublish(APTLY_URL, SOURCE, TARGET, RECREATE, components, packages, DIFF_ONLY, '-d --timeout 600', DUMP_PUBLISH.toBoolean(), storage)
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
