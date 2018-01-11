def common = new com.mirantis.mk.Common()
def aptly = new com.mirantis.mk.Aptly()
def git = new com.mirantis.mk.Git()
def timestamp = common.getDatetime()

def binaryPackages
try {
  binaryPackages = BINARY_PACKAGES
} catch (MissingPropertyException e) {
  binaryPackages = ""
}
timeout(time: 12, unit: 'HOURS') {
  node("docker") {
    try {
      def workspace = common.getWorkspace()
      stage("checkout") {
        sh("test -d debs && rm -rf debs || true")
        sh("test -d build && rm -rf build || true")
        git.checkoutGitRepository(
                              ".",
                              SOURCE_URL,
                              SOURCE_BRANCH,
                              SOURCE_CREDENTIALS,
                              false,
                              30,
                              1
                          )
      }
      stage("build") {
        if (binaryPackages == "all" || binaryPackages == "") {
          sh("docker run -v " + workspace + ":" + workspace + " -w " + workspace + " --rm=true --privileged "+OS+":" + DIST +
              " /bin/bash -c 'apt-get update && apt-get install -y packaging-dev && ./build-debs.sh " + DIST + "'")
        } else {
          sh("docker run -v " + workspace + ":" + workspace + " -w " + workspace + " --rm=true --privileged "+OS+":" + DIST +
              " /bin/bash -c 'apt-get update && apt-get install -y packaging-dev && ./build-debs.sh " + DIST + " " + binaryPackages + "'")
        }
        archiveArtifacts artifacts: "debs/${DIST}-${ARCH}/*.deb"
      }
      lock("aptly-api") {
        stage("upload") {
          buildSteps = [:]
          debFiles = sh script: "ls debs/"+DIST+"-"+ARCH+"/*.deb", returnStdout: true
          for (file in debFiles.tokenize()) {
              def fh = new File((workspace+"/"+file).trim())
              buildSteps[fh.name.split('_')[0]] = aptly.uploadPackageStep(
                  "debs/"+DIST+"-"+ARCH+"/"+fh.name,
                  APTLY_URL,
                  APTLY_REPO,
                  true
              )
          }
          parallel buildSteps
        }
        stage("publish") {
            aptly.snapshotRepo(APTLY_URL, APTLY_REPO, timestamp)
            aptly.publish(APTLY_URL)
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
}
