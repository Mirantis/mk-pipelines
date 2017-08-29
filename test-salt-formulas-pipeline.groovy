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

def parallelGroupSize
try {
  parallelGroupSize = Integer.valueOf(PARALLEL_GROUP_SIZE)
} catch (MissingPropertyException e) {
  parallelGroupSize = 4
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
  try {
    stage("checkout") {
      if (gerritRef) {
        // job is triggered by Gerrit
        def gerritChange = gerrit.getGerritChange(GERRIT_NAME, GERRIT_HOST, GERRIT_CHANGE_NUMBER, CREDENTIALS_ID, true)
        // test if gerrit change is already Verified
        if (gerrit.patchsetHasApproval(gerritChange.currentPatchSet, "Verified", "+")) {
          common.successMsg("Gerrit change ${GERRIT_CHANGE_NUMBER} patchset ${GERRIT_PATCHSET_NUMBER} already has Verified, skipping tests") // do nothing
          // test WIP contains in commit message
        } else if (gerritChange.commitMessage.contains("WIP")) {
          common.successMsg("Commit message contains WIP, skipping tests") // do nothing
        } else {
          // test if change aren't already merged
          def merged = gerritChange.status == "MERGED"
          if (!merged) {
            checkouted = gerrit.gerritPatchsetCheckout([
              credentialsId: CREDENTIALS_ID
            ])
          } else {
            common.successMsg("Change ${GERRIT_CHANGE_NUMBER} is already merged, no need to test them")
          }
        }
      } else if (defaultGitRef && defaultGitUrl) {
        checkouted = gerrit.gerritPatchsetCheckout(defaultGitUrl, defaultGitRef, "HEAD", CREDENTIALS_ID)
      } else {
        throw new Exception("Cannot checkout gerrit patchset, GERRIT_REFSPEC and DEFAULT_GIT_REF is null")
      }
    }
    stage("test") {
      if (checkouted) {
        sh("make clean")
        sh("[ $SALT_VERSION != 'latest' ] || export SALT_VERSION=''; make test")
      }
    }
    stage("kitchen") {
      if (checkouted) {
        if (fileExists(".kitchen.yml")) {
          common.infoMsg(".kitchen.yml found, running kitchen tests")
          def kitchenEnvs = []
          def filteredEnvs = []
          if (fileExists(".travis.yml")) {
            common.infoMsg(".travis.yml file found.")
            def kitchenConfigYML = readYaml(file: ".travis.yml")
            if (kitchenConfigYML.containsKey("env")) {
              kitchenEnvs = kitchenConfigYML["env"]
            }
          } else {
            common.warningMsg(".travis.yml file not found, suites must be passed via CUSTOM_KITCHEN_ENVS parameter.")
          }
          common.infoMsg("Running kitchen testing in parallel mode")
          if (CUSTOM_KITCHEN_ENVS != null && CUSTOM_KITCHEN_ENVS != '') {
            kitchenEnvs = CUSTOM_KITCHEN_ENVS.tokenize('\n')
            common.infoMsg("CUSTOM_KITCHEN_ENVS not empty. Running with custom enviroments: ${kitchenEnvs}")
          }
          if (kitchenEnvs != null && kitchenEnvs != '') {
            def acc = 0
            def kitchenTestRuns = [:]
            common.infoMsg("Found " + kitchenEnvs.size() + " environment(s)")
            for (int i = 0; i < kitchenEnvs.size(); i++) {
              if (acc >= parallelGroupSize) {
                parallel kitchenTestRuns
                kitchenTestRuns = [:]
                acc = 0
              }
              def testEnv = kitchenEnvs[i]
              kitchenTestRuns[testEnv] = {
                build job: "test-salt-formulas-env", parameters: [
                  [$class: 'StringParameterValue', name: 'CREDENTIALS_ID', value: CREDENTIALS_ID],
                  [$class: 'StringParameterValue', name: 'KITCHEN_ENV', value: testEnv],
                  [$class: 'StringParameterValue', name: 'DEFAULT_GIT_REF', value: DEFAULT_GIT_REF],
                  [$class: 'StringParameterValue', name: 'DEFAULT_GIT_URL', value: DEFAULT_GIT_URL],
                  [$class: 'StringParameterValue', name: 'SALT_OPTS', value: SALT_OPTS],
                  [$class: 'StringParameterValue', name: 'SALT_VERSION', value: SALT_VERSION]
                ]
              }
              acc++;
            }
            if (acc != 0) {
              parallel kitchenTestRuns
            }
          } else {
            common.warningMsg(".kitchen.yml file not found, no kitchen tests triggered.")
          }
        }
      }
    }
  } catch (Throwable e) {
    // If there was an error or exception thrown, the build failed
    currentBuild.result = "FAILURE"
    currentBuild.description = currentBuild.description ? e.message + " " + currentBuild.description : e.message
    throw e
  } finally {
    if (currentBuild.result == "FAILURE" && fileExists(".kitchen/logs/kitchen.log")) {
      common.errorMsg("----------------KITCHEN LOG:---------------")
      println readFile(".kitchen/logs/kitchen.log")
    }
    common.sendNotification(currentBuild.result, "", ["slack"])
  }
}