/**
 * Test salt formulas pipeline
 *  DEFAULT_GIT_REF
 *  DEFAULT_GIT_URL
 *  CREDENTIALS_ID
 *  KITCHEN_TESTS_PARALLEL
 *  SMOKE_TEST_DOCKER_IMG  Docker image for run test (default "ubuntu:16.04")
 */
common = new com.mirantis.mk.Common()
def gerrit = new com.mirantis.mk.Gerrit()
def ruby = new com.mirantis.mk.Ruby()
def dockerLib = new com.mirantis.mk.Docker()

def gerritRef = env.GERRIT_REFSPEC ?: null
def defaultGitRef = env.DEFAULT_GIT_REF ?: null
def defaultGitUrl = env.DEFAULT_GIT_URL ?: null
def slaveNode = env.SLAVE_NODE ?: 'virtual'
def saltVersion = env.SALT_VERSION ?: ""

gerritBranch = 'master'
if (common.validInputParam('GERRIT_BRANCH')) {
  gerritBranch = env.GERRIT_BRANCH
} else if (common.validInputParam('GATING_GERRIT_BRANCH')) {
    gerritBranch = env.GATING_GERRIT_BRANCH
  }


def checkouted = false

envOverrides = []
futureFormulas = []
failedFormulas = []
kitchenFileName = ''

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
          if (e.getMessage().contains("completed with status ABORTED")) {
            common.warningMsg("Test of ${currentFormula} was aborted and will be retriggered")
            futureFormulas << currentFormula
          } else {
            failedFormulas << currentFormula
            common.warningMsg("Test of ${currentFormula} failed :  ${e}")
          }
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
    [$class: 'StringParameterValue', name: 'SALT_VERSION', value: SALT_VERSION],
    [$class: 'StringParameterValue', name: 'GERRIT_PARENT_BRANCH', value: gerritBranch]
  ]
}

timeout(time: 4, unit: 'HOURS') {
  node(slaveNode) {
    def img = dockerLib.getImage(env.SMOKE_TEST_DOCKER_IMG, "ubuntu:16.04")
    try {
      if (fileExists("tests/build")) {
        common.infoMsg('Cleaning test env')
        sh ("sudo rm -rf tests/build")
      }
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
            // TODO add try\finally for image-stuck case. (copy-paste from SaltModelTesting)
            withEnv(["SALT_VERSION=${saltVersion}"]) {
              img.inside("-v ${env.WORKSPACE}/:/formula/ -u root:root --cpus=4 --ulimit nofile=4096:8192") {
                sh('''#!/bin/bash -xe
                      cd /etc/apt/
                      echo "deb [arch=amd64] http://mirror.mirantis.com/nightly/ubuntu xenial main restricted universe" > sources.list
                      echo "deb [arch=amd64] http://mirror.mirantis.com/nightly/ubuntu xenial-updates main restricted universe" >> sources.list
                      echo 'Acquire::Languages "none";' > apt.conf.d/docker-no-languages
                      echo 'Acquire::GzipIndexes "true"; Acquire::CompressionTypes::Order:: "gz";' > apt.conf.d/docker-gzip-indexes
                      echo 'APT::Get::Install-Recommends "false"; APT::Get::Install-Suggests "false";' > apt.conf.d/docker-recommends
                      apt-get update
                      apt-get install -y git-core wget curl apt-transport-https
                      apt-get install -y python-pip python3-pip python-virtualenv python3-virtualenv python-yaml autoconf build-essential
                      cd /formula/
                      make clean
                      make test
                      make clean
                      ''')
              }
            }
          }
          finally {
            if (fileExists("tests/build")) {
              common.infoMsg('Cleaning test env')
              sh ("sudo rm -rf tests/build")
            }
          }
        }

      }
    stage("kitchen") {
      if (fileExists(".travis.yml")) {/** TODO: Remove this legacy block once formulas are switched to new configuration */
        if (checkouted) {
          if (fileExists(".kitchen.yml") || fileExists(".kitchen.openstack.yml")) {
            if (fileExists(".kitchen.openstack.yml")) {
              common.infoMsg("Openstack Kitchen test configuration found, running Openstack kitchen tests.")
              if (fileExists(".kitchen.yml")) {
                common.infoMsg("Ignoring the docker Kitchen test configuration file.")
              }
            } else {
              common.infoMsg("Docker Kitchen test configuration found, running Docker kitchen tests.")
            }
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
        }/** TODO: End of block for removal */
        } else {
          if (checkouted) {
            if (fileExists(".kitchen.openstack.yml")) {
              common.infoMsg("Openstack Kitchen test configuration found, running Openstack kitchen tests.")
              kitchenFileName = ".kitchen.openstack.yml"
              envOverrides.add("KITCHEN_YAML=${kitchenFileName}")
            } else if (fileExists(".kitchen.yml")) {
              common.infoMsg("Docker Kitchen test configuration found, running Docker kitchen tests.")
              kitchenFileName = ".kitchen.yml"
            }
            if (kitchenFileName) {
              def kitchenEnvs = []
              ruby.ensureRubyEnv()
              if (!fileExists("Gemfile")) {
                sh("curl -s -o ./Gemfile 'https://gerrit.mcp.mirantis.com/gitweb?p=salt-formulas/salt-formulas-scripts.git;a=blob_plain;f=Gemfile;hb=refs/heads/master'")
                ruby.installKitchen()
              } else {
                common.infoMsg("Override Gemfile found in the kitchen directory, using it.")
                ruby.installKitchen()
              }
              common.infoMsg = ruby.runKitchenCommand("list -b", envOverrides.join(' '))
              kitchenEnvs = ruby.runKitchenCommand("list -b", envOverrides.join(' ')).split()
              common.infoMsg(kitchenEnvs)
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
                common.errorMsg("No enviroments defined in the Kitchen file: ${kitchenFileName}")
              }
            } else {
                common.warningMsg(".kitchen.yml nor .kitchen.openstack.yml file not found, no kitchen tests triggered.")
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
    }
  }
}
