/**
 * Test salt formulas pipeline
 *  DEFAULT_GIT_REF
 *  DEFAULT_GIT_URL
 *  CREDENTIALS_ID
 *  KITCHEN_TESTS_PARALLEL
 */
common = new com.mirantis.mk.Common()
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

futureFormulas = []
failedFormulas = []

def setupRunner(defaultGitRef, defaultGitUrl) {
  def branches = [:]
  for (int i = 0; i < PARALLEL_GROUP_SIZE.toInteger() && i < futureFormulas.size(); i++) {
    branches["Runner ${i}"] = {
      while (futureFormulas && !failedFormulas) {
        def currentFormula = futureFormulas[0] ? futureFormulas[0] : null
        if (!currentFormula) {
          continue
        }
        futureFormulas.remove(currentFormula)
        try {
          triggerTestFormulaJob(currentFormula, defaultGitRef, defaultGitUrl)
        } catch (Exception e) {
          failedFormulas << currentFormula
          common.warningMsg("Test of ${currentFormula} failed :  ${e}")
        }
      }
    }
  }
  parallel branches
}

def triggerTestFormulaJob(testEnv, defaultGitRef, defaultGitUrl) {
  common.infoMsg("Test of ${testEnv} starts")
  build job: "test-salt-formulas-env", parameters: [
    [$class: 'StringParameterValue', name: 'CREDENTIALS_ID', value: CREDENTIALS_ID],
    [$class: 'StringParameterValue', name: 'KITCHEN_ENV', value: testEnv],
    [$class: 'StringParameterValue', name: 'DEFAULT_GIT_REF', value: defaultGitRef],
    [$class: 'StringParameterValue', name: 'DEFAULT_GIT_URL', value: defaultGitUrl],
    [$class: 'StringParameterValue', name: 'SALT_OPTS', value: SALT_OPTS],
    [$class: 'StringParameterValue', name: 'SALT_VERSION', value: SALT_VERSION]
  ]
}

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
        defaultGitUrl = "${GERRIT_SCHEME}://${GERRIT_NAME}@${GERRIT_HOST}:${GERRIT_PORT}/${GERRIT_PROJECT}"
        defaultGitRef = GERRIT_REFSPEC
      } else if (defaultGitRef && defaultGitUrl) {
        checkouted = gerrit.gerritPatchsetCheckout(defaultGitUrl, defaultGitRef, "HEAD", CREDENTIALS_ID)
      } else {
        throw new Exception("Cannot checkout gerrit patchset, GERRIT_REFSPEC and DEFAULT_GIT_REF is null")
      }
    }
    stage("test") {
      if (checkouted) {
        try {
          saltVersion = SALT_VERSION
        } catch (MissingPropertyException e) {
          saltVersion = "latest"
        }
        withEnv(["SALT_VERSION=${saltVersion}"]) {
          sh("make clean && make test")
        }
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
            common.infoMsg("Found " + kitchenEnvs.size() + " environment(s)")
            for (int i = 0; i < kitchenEnvs.size(); i++) {
              futureFormulas << kitchenEnvs[i]
            }
            setupRunner(defaultGitRef, defaultGitUrl)
          } else {
            common.warningMsg(".kitchen.yml file not found, no kitchen tests triggered.")
          }
        }
      }
    }
    if (failedFormulas) {
      currentBuild.result = "FAILURE"
      common.warningMsg("The following tests failed: ${failedFormulas}")
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
