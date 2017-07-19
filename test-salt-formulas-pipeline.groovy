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

def checkouted = false

node("python") {
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
        def gerritChange = gerrit.getGerritChange(GERRIT_NAME, GERRIT_HOST, GERRIT_CHANGE_NUMBER, CREDENTIALS_ID, true)
        // test if gerrit change is already Verified
        if(gerrit.patchsetHasApproval(gerritChange.currentPatchSet,"Verified","+")){
          common.successMsg("Gerrit change ${GERRIT_CHANGE_NUMBER} patchset ${GERRIT_PATCHSET_NUMBER} already has Verified, skipping tests") // do nothing
        // test WIP contains in commit message
        }else if(gerritChange.commitMessage.contains("WIP")){
          common.successMsg("Commit message contains WIP, skipping tests") // do nothing
        }else{
          // test if change aren't already merged
          def merged = gerritChange.status == "MERGED"
          if(!merged){
            checkouted = gerrit.gerritPatchsetCheckout ([
              credentialsId : CREDENTIALS_ID
            ])
          } else{
            common.successMsg("Change ${GERRIT_CHANGE_NUMBER} is already merged, no need to test them")
          }
        }
      } else if(defaultGitRef && defaultGitUrl) {
          checkouted = gerrit.gerritPatchsetCheckout(defaultGitUrl, defaultGitRef, "HEAD", CREDENTIALS_ID)
      } else {
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
      if(checkouted){
        if (fileExists(".kitchen.yml")) {
          common.infoMsg(".kitchen.yml found, running kitchen tests")
          ruby.ensureRubyEnv()
          def kitchenEnvs = []
          def filteredEnvs = []
          if(fileExists(".travis.yml")){
            common.infoMsg(".travis.yml found, running custom kitchen init")
            def kitchenConfigYML = readYaml(file: ".travis.yml")
            if(kitchenConfigYML.containsKey("env")){
              kitchenEnvs=kitchenConfigYML["env"]
            }
            def kitchenInit = kitchenConfigYML["install"]
            def kitchenInstalled = false
            if(kitchenInit && !kitchenInit.isEmpty()){
              for(int i=0; i<kitchenInit.size(); i++){
                if(kitchenInit[i].trim().startsWith("test -e Gemfile")){ //found Gemfile config
                  common.infoMsg("Custom Gemfile configuration found, using them")
                  ruby.installKitchen(kitchenInit[i].trim())
                  kitchenInstalled = true
                }
              }
            }
            if(!kitchenInstalled){
              ruby.installKitchen()
            }
          }else{
            common.infoMsg(".travis.yml not found, running default kitchen init")
            ruby.installKitchen()
          }
          common.infoMsg("Running kitchen testing, parallel mode: " + KITCHEN_TESTS_PARALLEL.toBoolean())
          wrap([$class: 'AnsiColorBuildWrapper']) {
            if(CUSTOM_KITCHEN_ENVS != null && CUSTOM_KITCHEN_ENVS != ''){
                filteredEnvs = CUSTOM_KITCHEN_ENVS.tokenize('\n')
              } else {
                filteredEnvs = ruby.filterKitchenEnvs(kitchenEnvs).unique()
              }
              // Allow custom filteredEnvs in case of empty kitchenEnvs
            if((kitchenEnvs && !kitchenEnvs.isEmpty() && !filteredEnvs.isEmpty()) || ((kitchenEnvs==null || kitchenEnvs=='') && !filteredEnvs.isEmpty())){
              for(int i=0; i<filteredEnvs.size(); i++){
                common.infoMsg("Found " + filteredEnvs.size() + " environment, kitchen running with env: " + filteredEnvs[i].trim())
                ruby.runKitchenTests(filteredEnvs[i].trim(), KITCHEN_TESTS_PARALLEL.toBoolean())
              }
            } else {
              ruby.runKitchenTests("", KITCHEN_TESTS_PARALLEL.toBoolean())
            }
          }
        } else {
          common.infoMsg(".kitchen.yml not found")
        }
      }
    }
  } catch (Throwable e) {
     // If there was an error or exception thrown, the build failed
     currentBuild.result = "FAILURE"
     ruby.runKitchenCommand("destroy")
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

