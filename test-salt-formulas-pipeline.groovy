/**
 * Test salt formulas pipeline
 *  DEFAULT_GIT_REF
 *  DEFAULT_GIT_URL
 *  CREDENTIALS_ID
 *  KITCHEN_TESTS_PARALLEL
 */
def common = new com.mirantis.mk.Common()
def gerrit = new com.mirantis.mk.Gerrit()
def ruby = new com.mirantis.mk.Ruby()

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
        def runningTestBuildNums = _getRunningTriggeredTestsBuildNumbers(env["JOB_NAME"], GERRIT_CHANGE_NUMBER, GERRIT_PATCHSET_NUMBER)
        for(int i=0; i<runningTestBuildNums.size(); i++){
          common.infoMsg("Old test with run number ${runningTestBuildNums[i]} found, stopping")
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
        def kitchenEnvs = []
        if(fileExists(".travis.yml")){
          common.infoMsg(".travis.yml found, running custom kitchen init")
          def kitchenConfigYML = readYaml(file: ".travis.yml")
          kitchenEnvs=kitchenConfigYML["env"]
          def kitchenInit = kitchenConfigYML["install"]
          for(int i=0;i<kitchenInit.size();i++){
            sh(kitchenInit[i])
          }
        }else{
          common.infoMsg(".travis.yml not found, running default kitchen init")
          ruby.installKitchen()
        }
        wrap([$class: 'AnsiColorBuildWrapper']) {
          if(!kitchenEnvs.isEmpty()){
            for(int i=0;i<kitchenEnvs.size();i++){
              ruby.runKitchenTests(kitchenEnvs[i], KITCHEN_TESTS_PARALLEL.toBoolean())
            }
          }else{
            ruby.runKitchenTests("", KITCHEN_TESTS_PARALLEL.toBoolean())
          }
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
  def gerrit = new com.mirantis.mk.Gerrit()
  def jenkinsUtils = new com.mirantis.mk.JenkinsUtils()
  def triggeredBuilds= gerrit.getGerritTriggeredBuilds(jenkinsUtils.getJobRunningBuilds(jobName), gerritChangeNumber, excludePatchsetNumber)
  def buildNums =[]
  for(int i=0;i<triggeredBuilds.size();i++){
      buildNums.add(triggeredBuilds[i].number)
  }
  return buildNums
}
