
/**
 *  Test salt models pipeline
 *  DEFAULT_GIT_REF
 *  DEFAULT_GIT_URL
 *  CREDENTIALS_ID
 *  EXTRA_FORMULAS
 *  SYSTEM_GIT_URL
 *  SYSTEM_GIT_REF
 */

def common = new com.mirantis.mk.Common()
def gerrit = new com.mirantis.mk.Gerrit()
def ssh = new com.mirantis.mk.Ssh()
def git = new com.mirantis.mk.Git()

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
        // test if change aren't already merged
        def gerritChange = gerrit.getGerritChange(GERRIT_NAME, GERRIT_HOST, GERRIT_CHANGE_NUMBER, CREDENTIALS_ID, true)
        // test if gerrit change is already Verified
        if(gerrit.patchsetHasApproval(gerritChange.currentPatchSet,"Verified", "+")){
          common.successMsg("Gerrit change ${GERRIT_CHANGE_NUMBER} patchset ${GERRIT_PATCHSET_NUMBER} already has Verified, skipping tests") // do nothing
        // test WIP contains in commit message
        }else if (gerritChange.commitMessage.contains("WIP")) {
          common.successMsg("Commit message contains WIP, skipping tests") // do nothing
        } else {
          def merged = gerritChange.status == "MERGED"
          if(!merged){
            checkouted = gerrit.gerritPatchsetCheckout ([
              credentialsId : CREDENTIALS_ID
            ])
          } else{
            common.successMsg("Change ${GERRIT_CHANGE_NUMBER} is already merged, no need to test them")
          }
        }
        // defaultGitUrl is passed to the triggered job
        defaultGitUrl = "${GERRIT_SCHEME}://${GERRIT_NAME}@${GERRIT_HOST}:${GERRIT_PORT}/${GERRIT_PROJECT}"
        defaultGitRef = GERRIT_REFSPEC
      } else if(defaultGitRef && defaultGitUrl) {
          checkouted = gerrit.gerritPatchsetCheckout(defaultGitUrl, defaultGitRef, "HEAD", CREDENTIALS_ID)
      } else {
        throw new Exception("Cannot checkout gerrit patchset, GERRIT_REFSPEC and DEFAULT_GIT_REF is null")
      }
    }

    stage("test-nodes") {
      if(checkouted) {
        def nodes = sh(script: "find ./nodes -type f -name 'cfg*.yml'", returnStdout: true).tokenize()
        def branches = [:]
        for (int i = 0; i < nodes.size(); i++) {

          def testTarget = sh(script: "basename ${nodes[i]}.yml", returnStdout: true).trim()
          branches[testTarget] = {
            build job: "test-salt-model-node", parameters: [
              [$class: 'StringParameterValue', name: 'DEFAULT_GIT_URL', value: defaultGitUrl],
              [$class: 'StringParameterValue', name: 'DEFAULT_GIT_REF', value: defaultGitRef],
              [$class: 'StringParameterValue', name: 'NODE_TARGET', value: testTarget],
              [$class: 'StringParameterValue', name: 'EXTRA_FORMULAS', value: EXTRA_FORMULAS],
              [$class: 'StringParameterValue', name: 'CREDENTIALS_ID', value: CREDENTIALS_ID],
              [$class: 'StringParameterValue', name: 'SYSTEM_GIT_URL', value: SYSTEM_GIT_URL],
              [$class: 'StringParameterValue', name: 'SYSTEM_GIT_REF', value: SYSTEM_GIT_REF]
            ]}
          parallel branches
        }
      }
    }
  } catch (Throwable e) {
     currentBuild.result = "FAILURE"
     throw e
  } finally {
     common.sendNotification(currentBuild.result,"",["slack"])
  }
}

@NonCPS
def _getRunningTriggeredTestsBuildNumbers(jobName, gerritChangeNumber, excludePatchsetNumber){
  def gerrit = new com.mirantis.mk.Gerrit()
  def jenkinsUtils = new com.mirantis.mk.JenkinsUtils()
  def triggeredBuilds= gerrit.getGerritTriggeredBuilds(jenkinsUtils.getJobRunningBuilds(jobName), gerritChangeNumber, excludePatchsetNumber)
  def buildNums =[]
  for (int i=0; i<triggeredBuilds.size(); i++) {
      buildNums.add(triggeredBuilds[i].number)
  }
  return buildNums
}