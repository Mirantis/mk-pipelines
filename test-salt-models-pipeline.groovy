/**
 *  Test salt models pipeline
 *  DEFAULT_GIT_URL default git url (will be used if pipeline run is not triggered by gerrit)
 *  DEFAULT_GIT_RED default git ref (branch,tag,...) (will be used if pipeline run is not triggered by gerrit)
 *  CREDENTIALS_ID Jenkins credetials id for git checkout
 *  MAX_CPU_PER_JOB max cpu count for one docket test instance
 *  SYSTEM_GIT_URL reclass system git URL (optional)
 *  SYSTEM_GIT_REF reclass system git URL (optional)
 *  TEST_CLUSTER_NAMES list of comma separated cluster names to test (optional, default all cluster levels)
 *  LEGACY_TEST_MODE legacy test mode flag
 *  RECLASS_IGNORE_CLASS_NOTFOUND ignore missing class flag for reclass config
 *  DISTRIB_REVISION of apt mirrror to be used (http://mirror.mirantis.com/DISTRIB_REVISION/ by default)
 *  APT_REPOSITORY extra apt repository url
 *  APT_REPOSITORY_GPG extra apt repository url GPG
 */

def gerrit = new com.mirantis.mk.Gerrit()
common = new com.mirantis.mk.Common()
def ssh = new com.mirantis.mk.Ssh()
def git = new com.mirantis.mk.Git()

def config_node_name_pattern = env.CONFIG_NODE_NAME_PATTERN ?: 'cfg01'
def gerritRef = env.GERRIT_REFSPEC ?: null
def formulasSource = env.FORMULAS_SOURCE ?: 'pkg'
distribRevision = env.DISTRIB_REVISION ?: 'nightly'

def testClusterNames = env.TEST_CLUSTER_NAMES ?: ''
def defaultGitRef = env.DEFAULT_GIT_REF ?: null
def defaultGitUrl = env.DEFAULT_GIT_URL ?: null

def checkouted = false
futureNodes = []
failedNodes = false


def setupRunner() {
    def branches = [:]
    branches.failFast = true
    for(int i = 0; i < futureNodes.size(); i++) {
        def currentNode = futureNodes[i] ? futureNodes[i] : null
        if (!currentNode) {
            continue
        }
        branches["Runner ${i}"] = {
            try {
                triggerTestNodeJob(currentNode[0], currentNode[1], currentNode[2], currentNode[3], currentNode[4])
            } catch (Exception e) {
                  common.warningMsg("Test of ${currentNode[2]} failed :  ${e}")
                  throw e
            }
        }
    }

    if (branches) {
        common.runParallel(branches, PARALLEL_NODE_GROUP_SIZE.toInteger())
    }
}

def triggerTestNodeJob(defaultGitUrl, defaultGitRef, clusterName, testTarget, formulasSource) {
  common.infoMsg("Test of ${clusterName} starts")
  build job: "test-salt-model-node", parameters: [
    [$class: 'StringParameterValue', name: 'DEFAULT_GIT_URL', value: defaultGitUrl],
    [$class: 'StringParameterValue', name: 'DEFAULT_GIT_REF', value: defaultGitRef],
    [$class: 'StringParameterValue', name: 'CLUSTER_NAME', value: clusterName],
    [$class: 'StringParameterValue', name: 'NODE_TARGET', value: testTarget],
    [$class: 'StringParameterValue', name: 'FORMULAS_SOURCE', value: formulasSource],
    [$class: 'StringParameterValue', name: 'CREDENTIALS_ID', value: CREDENTIALS_ID],
    [$class: 'StringParameterValue', name: 'SYSTEM_GIT_URL', value: SYSTEM_GIT_URL],
    [$class: 'StringParameterValue', name: 'DISTRIB_REVISION', value: distribRevision],
    [$class: 'StringParameterValue', name: 'MAX_CPU_PER_JOB', value: MAX_CPU_PER_JOB],
    [$class: 'StringParameterValue', name: 'SYSTEM_GIT_REF', value: SYSTEM_GIT_REF],
    [$class: 'BooleanParameterValue', name: 'LEGACY_TEST_MODE', value: LEGACY_TEST_MODE.toBoolean()],
    [$class: 'BooleanParameterValue', name: 'RECLASS_IGNORE_CLASS_NOTFOUND', value: RECLASS_IGNORE_CLASS_NOTFOUND.toBoolean()],
    [$class: 'StringParameterValue', name: 'APT_REPOSITORY', value: APT_REPOSITORY],
    [$class: 'StringParameterValue', name: 'APT_REPOSITORY_GPG', value: APT_REPOSITORY_GPG]
  ]
}

def _clusterTestEnabled(infraYMLConfig){
  if (infraYMLConfig["parameters"].containsKey("_jenkins")) {
    if (infraYMLConfig["parameters"]["_jenkins"].containsKey("tests_enabled")) {
      return infraYMLConfig["parameters"]["_jenkins"]["tests_enabled"];
    }
  }
  // ci tests are enabled by default
  return true;
}

timeout(time: 12, unit: 'HOURS') {
  node("python") {
    try{
      stage("checkout") {
        if (gerritRef) {
          // job is triggered by Gerrit
          // test if change aren't already merged
          def gerritChange = gerrit.getGerritChange(GERRIT_NAME, GERRIT_HOST, GERRIT_CHANGE_NUMBER, CREDENTIALS_ID, true)
          // test if gerrit change is already Verified
          if (gerrit.patchsetHasApproval(gerritChange.currentPatchSet,"Verified", "+")) {
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

      stage("Check YAML") {
        common.infoMsg("Checking YAML syntax for changed files")
        def syntaxCheckStatus = sh(script:"set +x;git diff-tree --no-commit-id --diff-filter=d --name-only -r HEAD  | grep .yml | xargs -I {}  python -c \"import yaml; yaml.load(open('{}', 'r'))\" \\;", returnStatus:true)
        if(syntaxCheckStatus > 0){
          common.errorMsg("YAML syntax check failed!")
        }
      }

      stage("test-nodes") {
        if (checkouted) {
          def modifiedClusters = null
          // testing modified cluster is used only if test was triggered by gerrit
          if (gerritRef) {
            def checkChange = sh(script: "set +x;git diff-tree --no-commit-id --name-only -r HEAD | grep -v classes/cluster", returnStatus: true)
            if (checkChange == 1) {
              modifiedClusters = sh(script: "set +x;git diff-tree --no-commit-id --name-only -r HEAD | grep classes/cluster/ | awk -F/ '{print \$3}' | uniq", returnStdout: true).tokenize()
            }
          }

          def infraYMLs = []
          // list of cluster names can be explicitly given
          if (testClusterNames != null && testClusterNames != "") {
            common.infoMsg("TEST_CLUSTER_NAMES param found, using explicitly defined cluster names: ${testClusterNames}")
            def clusterNameRegex = testClusterNames.tokenize(",").collect{it.trim()}.join("|")
            infraYMLs = sh(script:"set +x;find ./classes/ -regextype posix-egrep -regex '.*cluster/(${clusterNameRegex}){1}/[infra/]*init\\.yml' -exec grep -il 'cluster_name' {} \\;", returnStdout: true).tokenize()
          } else {
            common.infoMsg("TEST_CLUSTER_NAMES param not found, all clusters with enabled tests will be tested")
            // else we want to test all cluster levels found
            infraYMLs = sh(script: "set +x;find ./classes/ -regex '.*cluster/[-_a-zA-Z0-9]*/[infra/]*init\\.yml' -exec grep -il 'cluster_name' {} \\;", returnStdout: true).tokenize()
            def clusterDirectories = sh(script: "set +x;ls -d ./classes/cluster/*/ | awk -F/ '{print \$4}'", returnStdout: true).tokenize()

            // create a list of cluster names present in cluster folder
            def infraList = []
            for (elt in infraYMLs) {
              infraList << elt.tokenize('/')[3]
            }

            // verify we have all valid clusters loaded
            def commonList = infraList.intersect(clusterDirectories)
            def differenceList = infraList.plus(clusterDirectories)
            differenceList.removeAll(commonList)

            if (!differenceList.isEmpty()) {
              common.warningMsg("The following clusters are not valid : ${differenceList} - That means we cannot found cluster_name in init.yml or infra/init.yml")
            }
            if (modifiedClusters) {
              infraYMLs.removeAll { !modifiedClusters.contains(it.tokenize('/')[3]) }
              common.infoMsg("Testing only modified clusters: ${infraYMLs}")
            }
          }
          common.infoMsg("Starting salt models test for these clusters " + infraYMLs.collect{ it.tokenize("/")[3] })
          if (infraYMLs.size() > 0) {
            for (int i = 0; i < infraYMLs.size(); i++) {
              def infraYMLConfig = readYaml(file: infraYMLs[i])
              if (_clusterTestEnabled(infraYMLConfig)) {
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

                  futureNodes << [defaultGitUrl, defaultGitRef, clusterName, testTarget, formulasSource]
              }
            }
          } else {
            common.warningMsg("List of found salt model clusters is empty, no tests will be started!")
          }

          setupRunner()

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
}
