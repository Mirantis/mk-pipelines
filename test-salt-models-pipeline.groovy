
/**
 *  Test salt models pipeline
 *  DEFAULT_GIT_REF
 *  DEFAULT_GIT_URL
 *  CREDENTIALS_ID
 *  EXTRA_FORMULAS
 */

def common = new com.mirantis.mk.Common()
def gerrit = new com.mirantis.mk.Gerrit()
def ssh = new com.mirantis.mk.Ssh()
def git = new com.mirantis.mk.Git()
def saltModelTesting = new com.mirantis.mk.SaltModelTesting()

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
          common.successMsg("Gerrit change ${GERRIT_CHANGE_NUMBER} patchset ${GERRIT_PATCHSET_NUMBER} already has Verified, skipping tests")
          currentBuild.result = 'ABORTED'
        // test WIP contains in commit message
        }else if (gerritChange.commitMessage.contains("WIP")) {
          common.successMsg("Commit message contains WIP, skipping tests")
          currentBuild.result = 'ABORTED'
        } else {
          def merged = gerritChange.status == "MERGED"
          if(!merged){
            checkouted = gerrit.gerritPatchsetCheckout ([
              credentialsId : CREDENTIALS_ID
            ])
          } else{
            common.successMsg("Change ${GERRIT_CHANGE_NUMBER} is already merged, no need to test them")
            currentBuild.result = 'ABORTED'
          }
        }
      } else if(defaultGitRef && defaultGitUrl) {
          checkouted = gerrit.gerritPatchsetCheckout(defaultGitUrl, defaultGitRef, "HEAD", CREDENTIALS_ID)
      } else {
        throw new Exception("Cannot checkout gerrit patchset, GERRIT_REFSPEC and DEFAULT_GIT_REF is null")
      }
      if(checkouted) {
        if (fileExists('classes/system')) {
          ssh.prepareSshAgentKey(CREDENTIALS_ID)
          dir('classes/system') {
            remoteUrl = git.getGitRemote()
            ssh.ensureKnownHosts(remoteUrl)
          }
          ssh.agentSh("git submodule init; git submodule sync; git submodule update --recursive")
        }
      }
    }

    stage("test-nodes") {
      if(checkouted) {
        def workspace = common.getWorkspace()
        def nodes = sh(script: "find ./nodes -type f -name 'cfg*.yml'", returnStdout: true).tokenize()
        def buildSteps = [:]
        def partitionSize = (nodes.size() <= PARALLEL_NODE_GROUP_SIZE.toInteger()) ? nodes.size() : PARALLEL_NODE_GROUP_SIZE.toInteger()
        def partitions = common.partitionList(nodes, partitionSize)
        for (int i =0; i < partitions.size();i++) {
          def partition = partitions[i]
          buildSteps.put("partition-${i}", new HashMap<String,org.jenkinsci.plugins.workflow.cps.CpsClosure2>())
          for(int k=0; k < partition.size;k++){
              def basename = sh(script: "basename ${partition[k]} .yml", returnStdout: true).trim()
              buildSteps.get("partition-${i}").put(basename, { saltModelTesting.setupAndTestNode(basename, EXTRA_FORMULAS, workspace) })
          }
        }
        common.serial(buildSteps)
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