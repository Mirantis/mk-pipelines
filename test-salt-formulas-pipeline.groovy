/**
 * Test salt formulas pipeline
 *  DEFAULT_GIT_REF
 *  DEFAULT_GIT_URL
 *  CREDENTIALS_ID
 */
def common = new com.mirantis.mk.Common()
def gerrit = new com.mirantis.mk.Gerrit()
def ruby = new com.mirantis.mk.Ruby()
def jenkinsUtils = new com.mirantis.mk.JenkinsUtils()

def gerritRef
try {
  gerritRef = GERRIT_REFSPEC
} catch (MissingPropertyException e) {
  gerritRef = null
}

def defaultGitRef, defaultGitUrl
try {
    defaultGitRef = DEFAULT_GIT_REF
    defaultGitUrl = DEFAULT_GIT_URL
} catch (MissingPropertyException e) {
    defaultGitRef = null
    defaultGitUrl = null
}

def checkouted = false;

node("python&&docker") {
  try{
    stage("stop old tests"){
      if (gerritRef) {
        def runningTestBuildNums = _getRunningTriggeredTestsBuildNumbers(env["JOB_NAME"], GERRIT_CHANGE_NUMBER, GERRIT_PATCHET_NUMBER)
        for(int i=0; i<runningTestBuildNums.size(); i++){
          Jenkins.instance.getItemByFullName(env["JOB_NAME"]).getBuildByNumber(runningTestBuildNums[i]).finish(hudson.model.Result.ABORTED, new java.io.IOException("Aborting build"));
        }
      }
    }
    stage("checkout") {
      if (gerritRef) {
        // job is triggered by Gerrit
        checkouted = gerrit.gerritPatchsetCheckout ([
          credentialsId : CREDENTIALS_ID
        ])
      } else if(defaultGitRef && defaultGitUrl) {
          checkouted = gerrit.gerritPatchsetCheckout(defaultGitUrl, defaultGitRef, "HEAD", CREDENTIALS_ID)
      }
      if(!checkouted){
        throw new Exception("Cannot checkout gerrit patchset, GERRIT_REFSPEC and DEFAULT_GIT_REF is null")
      }
    }
    stage("test") {
      if(checkouted){
        wrap([$class: 'AnsiColorBuildWrapper']) {
          sh("make clean")
          sh("[ $SALT_VERSION != 'latest' ] || export SALT_VERSION=''; make test")
        }
      }
    }
    stage("kitchen") {
      if (fileExists(".kitchen.yml")) {
        common.infoMsg(".kitchen.yml found, running kitchen tests")
        ruby.ensureRubyEnv()
        ruby.installKitchen()
        wrap([$class: 'AnsiColorBuildWrapper']) {
          ruby.runKitchenTests()
        }
      } else {
        common.infoMsg(".kitchen.yml not found")
      }
    }
  } catch (Throwable e) {
     // If there was an error or exception thrown, the build failed
     currentBuild.result = "FAILURE"
     throw e
  } finally {
     if(currentBuild.result == "FAILURE" && fileExists(".kitchen/logs/kitchen.log")){
        common.errorMsg("----------------KITCHEN LOG:---------------")
        println readFile(".kitchen/logs/kitchen.log")
     }
     common.sendNotification(currentBuild.result,"",["slack"])
  }
}

@NonCPS
def _getRunningTriggeredTestsBuildNumbers(jobName, gerritChangeNumber, excludePatchsetNumber){
  return gerrit.getGerritTriggeredBuilds(jenkins.getJobRunningBuilds(jobName), gerritChangeNumber, excludePatchsetNumber)
    .stream().map{it -> it.number}.collect(java.util.stream.Collectors.toList())
}