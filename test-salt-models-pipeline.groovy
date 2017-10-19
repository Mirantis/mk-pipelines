
/**
 *  Test salt models pipeline
 *  DEFAULT_GIT_REF
 *  DEFAULT_GIT_URL
 *  CREDENTIALS_ID
 *  EXTRA_FORMULAS
 *  SYSTEM_GIT_URL
 *  SYSTEM_GIT_REF
 *  MAX_CPU_PER_JOB
 *  LEGACY_TEST_MODE
 *  RECLASS_IGNORE_CLASS_NOTFOUND
 */

def common = new com.mirantis.mk.Common()
def gerrit = new com.mirantis.mk.Gerrit()
def ssh = new com.mirantis.mk.Ssh()
def git = new com.mirantis.mk.Git()

def  config_node_name_pattern
try {
  config_node_name_pattern = CONFIG_NODE_NAME_PATTERN
} catch (MissingPropertyException e) {
  config_node_name_pattern = "cfg01"
}

def gerritRef
try {
  gerritRef = GERRIT_REFSPEC
} catch (MissingPropertyException e) {
  gerritRef = null
}

def formulasSource
try {
  formulasSource = FORMULAS_SOURCE
} catch (MissingPropertyException e) {
  formulasSource = "pkg"
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



def triggerTestNodeJob(defaultGitUrl, defaultGitRef, clusterName, testTarget, formulasSource) {
  build job: "test-salt-model-node", parameters: [
    [$class: 'StringParameterValue', name: 'DEFAULT_GIT_URL', value: defaultGitUrl],
    [$class: 'StringParameterValue', name: 'DEFAULT_GIT_REF', value: defaultGitRef],
    [$class: 'StringParameterValue', name: 'CLUSTER_NAME', value: clusterName],
    [$class: 'StringParameterValue', name: 'NODE_TARGET', value: testTarget],
    [$class: 'StringParameterValue', name: 'FORMULAS_SOURCE', value: formulasSource],
    [$class: 'StringParameterValue', name: 'EXTRA_FORMULAS', value: EXTRA_FORMULAS],
    [$class: 'StringParameterValue', name: 'FORMULAS_REVISION', value: FORMULAS_REVISION],
    [$class: 'StringParameterValue', name: 'CREDENTIALS_ID', value: CREDENTIALS_ID],
    [$class: 'StringParameterValue', name: 'SYSTEM_GIT_URL', value: SYSTEM_GIT_URL],
    [$class: 'StringParameterValue', name: 'MAX_CPU_PER_JOB', value: MAX_CPU_PER_JOB],
    [$class: 'StringParameterValue', name: 'SYSTEM_GIT_REF', value: SYSTEM_GIT_REF],
    [$class: 'BooleanParameterValue', name: 'LEGACY_TEST_MODE', value: LEGACY_TEST_MODE.toBoolean()],
    [$class: 'BooleanParameterValue', name: 'RECLASS_IGNORE_CLASS_NOTFOUND', value: RECLASS_IGNORE_CLASS_NOTFOUND.toBoolean()]
  ]
}


node("python") {
  try{
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
        def modifiedClusters = null

        if (gerritRef) {
          checkChange = sh(script: "git diff-tree --no-commit-id --name-only -r HEAD | grep -v classes/cluster", returnStatus: true)
          if (checkChange == 1) {
            modifiedClusters = sh(script: "git diff-tree --no-commit-id --name-only -r HEAD | grep classes/cluster/ | awk -F/ '{print \$3}' | uniq", returnStdout: true).tokenize()
          }
        }

        def infraYMLs = sh(script: "find ./classes/ -regex '.*cluster/[-_a-zA-Z0-9]*/[infra/]*init\\.yml' -exec grep -il 'cluster_name' {} \\;", returnStdout: true).tokenize()
        def clusterDirectories = sh(script: "ls -d ./classes/cluster/*/ | awk -F/ '{print \$4}'", returnStdout: true).tokenize()

        // create a list of cluster names present in cluster folder
        def infraList = []
        for (elt in infraYMLs) {
          infraList << elt.tokenize('/')[3]
        }

        // verify we have all valid clusters loaded
        def commonList = infraList.intersect(clusterDirectories)
        def differenceList = infraList.plus(clusterDirectories)
        differenceList.removeAll(commonList)

        if(!differenceList.isEmpty()){
          common.warningMsg("The following clusters are not valid : ${differenceList} - That means we cannot found cluster_name in init.yml or infra/init.yml")
        }
        if (modifiedClusters) {
          infraYMLs.removeAll { !modifiedClusters.contains(it.tokenize('/')[3]) }
          common.infoMsg("Testing only modified clusters: ${infraYMLs}")
        }

        def branches = [:]
        def failedNodes = []
        def acc = 0

        for (int i = 0; i < infraYMLs.size(); i++) {
          def infraYMLConfig = readYaml(file: infraYMLs[i])
          if(!infraYMLConfig["parameters"].containsKey("_param")){
              common.warningMsg("ERROR: Cannot find soft params (_param) in file " + infraYMLs[i] + " for obtain a cluster info. Skipping test.")
              continue
          }
          def infraParams = infraYMLConfig["parameters"]["_param"];
          if(!infraParams.containsKey("infra_config_hostname") || !infraParams.containsKey("cluster_name") || !infraParams.containsKey("cluster_domain")){
              common.warningMsg("ERROR: Cannot find _param:infra_config_hostname or _param:cluster_name or _param:cluster_domain  in file " + infraYMLs[i] + " for obtain a cluster info. Skipping test.")
              continue
          }
          def clusterName = infraParams["cluster_name"]
          def clusterDomain = infraParams["cluster_domain"]
          def configHostname = infraParams["infra_config_hostname"]
          def testTarget = String.format("%s.%s", configHostname, clusterDomain)
          if (acc >= PARALLEL_NODE_GROUP_SIZE.toInteger()) {
            parallel branches
            branches = [:]
            acc = 0
          }

          branches[clusterName] = {
            try {
                triggerTestNodeJob(defaultGitUrl, defaultGitRef, clusterName, testTarget, formulasSource)
            } catch (Exception e) {
              failedNodes << [defaultGitUrl, defaultGitRef, clusterName, testTarget, formulasSource]
              common.warningMsg("Test of ${clusterName} failed :  ${e}")
            }
          }
          acc++;
        }
        if (acc != 0) {
          parallel branches
        }

        def nbRetry = 1
        def maxNbRetry = infraYMLs.size() > 10 ? infraYMLs.size() / 2 : 10
        for (int i = 0; i < nbRetry && failedNodes && failedNodes.size() <= maxNbRetry; ++i) {
          branches = [:]
          acc = 0
          retryNodes = failedNodes
          failedNodes = []
          for (int i = 0; i < retryNodes.size(); i++) {
            if (acc >= PARALLEL_NODE_GROUP_SIZE.toInteger()) {
              parallel branches
              branches = [:]
              acc = 0
            }

            common.infoMsg("Test of ${retryNodes[i][2]} failed, retrigger it to make sure")
            branches[retryNodes[i][2]] = {
              try {
                  triggerTestNodeJob(retryNodes[i][0], retryNodes[i][1], retryNodes[i][2], retryNodes[i][3], retryNodes[i][4])
              } catch (Exception e) {
                failedNodes << retryNodes[i]
                common.warningMsg("Test of ${retryNodes[i][2]} failed :  ${e}")
              }
            }
            acc++
          }
          if (acc != 0) {
            parallel branches
          }
        }
        if (failedNodes) {
          currentBuild.result = "FAILURE"
        }
      }
    }
  } catch (Throwable e) {
     currentBuild.result = "FAILURE"
     currentBuild.description = currentBuild.description ? e.message + " " + currentBuild.description : e.message
     throw e
  } finally {
     common.sendNotification(currentBuild.result,"",["slack"])
  }
}
