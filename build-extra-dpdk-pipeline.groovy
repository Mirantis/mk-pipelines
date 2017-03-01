def common = new com.mirantis.mk.Common()
def aptly = new com.mirantis.mk.Aptly()
def timestamp = common.getDatetime()

node("docker") {
  try {
    stage("checkout") {
      sh("test -d debs && rm -rf debs || true")
      sh("test -d build && rm -rf build || true")
      git poll: false, url: SOURCE_URL, branch: SOURCE_BRANCH, credentialsId: SOURCE_CREDENTIALS
    }
    stage("build") {
      sh("docker run -v "+common.getWorkspace()+":"+common.getWorkspace()+" -w "+common.getWorkspace()+" --rm=true --privileged "+OS+":"+DIST+" /bin/bash -c 'apt-get update && apt-get install -y packaging-dev && ./build-debs.sh "+DIST+"'")
      archiveArtifacts artifacts: "debs/"+DIST+"-"+ARCH+"/*.deb"
    }
    lock("aptly-api") {
      stage("upload") {
        buildSteps = [:]
        debFiles = sh script: "ls debs/"+DIST+"-"+ARCH+"/*.deb", returnStdout: true
        for (file in debFiles.tokenize()) {
            workspace = common.getWorkspace()
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
       throw e
    } finally {
       common.sendNotification(currentBuild.result,"",["slack"])
    }
}