def common = new com.mirantis.mk.Common()
def aptly = new com.mirantis.mk.Aptly()
def debian = new com.mirantis.mk.Debian()

def snapshot
try {
  snapshot = DEBIAN_SNAPSHOT
} catch (MissingPropertyException e) {
  snapshot = false
}
def debian_branch
try {
  debian_branch = DEBIAN_BRANCH
} catch (MissingPropertyException e) {
  debian_branch = null
}
def revisionPostfix
try {
  revisionPostfix = REVISION_POSTFIX
} catch (MissingPropertyException e) {
  revisionPostfix = null
}

def uploadPpa
try {
  uploadPpa = UPLOAD_PPA.toBoolean()
} catch (MissingPropertyException e) {
  uploadPpa = null
}

def lintianCheck
try {
  lintianCheck = LINTIAN_CHECK.toBoolean()
} catch (MissingPropertyException e) {
  lintianCheck = true
}

def uploadAptly
try {
  uploadAptly = UPLOAD_APTLY.toBoolean()
} catch (MissingPropertyException e) {
  uploadAptly = true
}

def timestamp = common.getDatetime()
timeout(time: 12, unit: 'HOURS') {
  node("docker") {
    try{
      stage("checkout") {
        sh("rm -rf src || true")
        dir("src") {
          def pollBranches = [[name:SOURCE_BRANCH]]
          if (debian_branch) {
            pollBranches.add([name:DEBIAN_BRANCH])
          }
          def extensions = [[$class: 'CleanCheckout']]
          def userRemoteConfigs = [[credentialsId: SOURCE_CREDENTIALS, url: SOURCE_URL]]
          // Checkout specified refspec to local branch
          if (common.validInputParam('SOURCE_REFSPEC')) {
            extensions.add([$class: 'BuildChooserSetting', buildChooser: [$class: 'GerritTriggerBuildChooser']])
            extensions.add([$class: 'LocalBranch', localBranch: SOURCE_BRANCH])
            userRemoteConfigs[0]['refspec'] = SOURCE_REFSPEC
          }
          checkout changelog: true, poll: false,
            scm: [$class: 'GitSCM', branches: pollBranches, doGenerateSubmoduleConfigurations: false,
            extensions: extensions,  submoduleCfg: [], userRemoteConfigs: userRemoteConfigs]
          if (debian_branch){
            /* There are 2 schemas of build spec keeping:
                   1. Separate branch with build specs. I.e. debian/xenial
                   2. Separate directory with specs.
               Logic below makes package build compatible with both schemas.
            */
            def retStatus = sh(script: 'git checkout ' + DEBIAN_BRANCH, returnStatus: true)
            if (retStatus != 0) {
              common.warningMsg("Cannot checkout ${DEBIAN_BRANCH} branch. Going to build package by ${SOURCE_BRANCH} branch.")
            }
          }
        }
        debian.cleanup(OS+":"+DIST)
      }
      stage("build-source") {
        // If SOURCE_REFSPEC is defined refspec will be checked out to local branch and need to build it instead of origin branch.
        if (common.validInputParam('SOURCE_REFSPEC')) {
          debian.buildSource("src", OS+":"+DIST, snapshot, 'Jenkins', 'autobuild@mirantis.com', revisionPostfix, '')
        } else {
          debian.buildSource("src", OS+":"+DIST, snapshot, 'Jenkins', 'autobuild@mirantis.com', revisionPostfix)
        }
        archiveArtifacts artifacts: "build-area/*.dsc"
        archiveArtifacts artifacts: "build-area/*_source.changes"
        archiveArtifacts artifacts: "build-area/*.tar.*"
      }
      stage("build-binary") {
        dsc = sh script: "ls build-area/*.dsc", returnStdout: true
        if(common.validInputParam("PRE_BUILD_SCRIPT")) {
          writeFile([file:"pre_build_script.sh", text: env['PRE_BUILD_SCRIPT']])
        }
        debian.buildBinary(
          dsc.trim(),
          OS+":"+DIST,
          EXTRA_REPO_URL,
          EXTRA_REPO_KEY_URL
        )
        archiveArtifacts artifacts: "build-area/*.deb"
      }

      if (lintianCheck) {
        stage("lintian") {
          changes = sh script: "ls build-area/*_"+ARCH+".changes", returnStdout: true
          try {
            debian.runLintian(changes.trim(), OS, OS+":"+DIST)
          } catch (Exception e) {
            println "[WARN] Lintian returned non-zero exit status"
            currentBuild.result = 'UNSTABLE'
          }
        }
      }

      if (uploadAptly) {
        lock("aptly-api") {
          stage("upload") {
            buildSteps = [:]
            debFiles = sh script: "ls build-area/*.deb", returnStdout: true
            for (file in debFiles.tokenize()) {
              workspace = common.getWorkspace()
              def fh = new File((workspace+"/"+file).trim())
              buildSteps[fh.name.split('_')[0]] = aptly.uploadPackageStep(
                    "build-area/"+fh.name,
                    APTLY_URL,
                    APTLY_REPO,
                    true
                )
            }
            parallel buildSteps
          }

          stage("publish") {
            aptly.snapshotRepo(APTLY_URL, APTLY_REPO, timestamp)
            retry(2){
              aptly.publish(APTLY_URL)
            }
          }
        }
      }
      if (uploadPpa) {
        stage("upload launchpad") {
          debian.importGpgKey("launchpad-private")
          debian.uploadPpa(PPA, "build-area", "launchpad-private")
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

