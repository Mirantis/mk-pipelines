/**
 * Test salt formulas pipeline
 *  DEFAULT_GIT_REF
 *  DEFAULT_GIT_URL
 *  CREDENTIALS_ID
 */
def common = new com.mirantis.mk.Common()
def ruby = new com.mirantis.mk.Ruby()
def gerrit = new com.mirantis.mk.Gerrit()

def defaultGitRef, defaultGitUrl
try {
  defaultGitRef = DEFAULT_GIT_REF
  defaultGitUrl = DEFAULT_GIT_URL
} catch (MissingPropertyException e) {
  defaultGitRef = null
  defaultGitUrl = null
}

def checkouted = false
def openstackTest = false

throttle(['test-formula']) {
  timeout(time: 1, unit: 'HOURS') {
    node("python&&docker") {
      try {
        stage("checkout") {
          if (defaultGitRef && defaultGitUrl) {
            checkouted = gerrit.gerritPatchsetCheckout(defaultGitUrl, defaultGitRef, "HEAD", CREDENTIALS_ID)
          } else {
            throw new Exception("Cannot checkout gerrit patchset, DEFAULT_GIT_REF is null")
          }
        }
        stage("cleanup") {
          if (checkouted) {
            sh("make clean")
          }
        }
        stage("kitchen") {
          if (checkouted) {
            if (fileExists(".kitchen.yml") || fileExists(".kitchen.openstack.yml")) {
              if (fileExists(".kitchen.openstack.yml")) {
                common.infoMsg("Openstack Kitchen test configuration found, running Openstack kitchen tests.")
                if (fileExists(".kitchen.yml")) {
                  common.infoMsg("Ignoring the docker Kitchen test configuration file.")
                }
                openstackTest = true
              } else {
                common.infoMsg("Docker Kitchen test configuration found, running Docker kitchen tests.")
              }
              ruby.ensureRubyEnv()
              if (fileExists(".travis.yml")) {
                common.infoMsg(".travis.yml found, running custom kitchen init")
                def kitchenConfigYML = readYaml(file: ".travis.yml")
                def kitchenInit = kitchenConfigYML["install"]
                def kitchenInstalled = false
                if (kitchenInit && !kitchenInit.isEmpty()) {
                  if (!openstackTest) {
                    for (int i = 0; i < kitchenInit.size(); i++) {
                      if (kitchenInit[i].trim().startsWith("test -e Gemfile")) { //found Gemfile config
                        common.infoMsg("Custom Gemfile configuration found, using them")
                        ruby.installKitchen(kitchenInit[i].trim())
                        kitchenInstalled = true
                      }
                    }
                  } else {
                    for (int i = 0; i < kitchenInit.size(); i++) {
                      if (kitchenInit[i].trim().startsWith("git clone")) { //found Gemfile config TODO: Change keywords ??
                        common.infoMsg("Custom Gemfile configuration found, using them")
                        sh(kitchenInit[i].trim())
                        kitchenInstalled = true
                      }
                    }
                  }
                }
                if (!kitchenInstalled) {
                  ruby.installKitchen()
                }
              } else {
                common.infoMsg(".travis.yml not found, running default kitchen init")
                ruby.installKitchen()
              }
              common.infoMsg("Running part of kitchen test")
              if (KITCHEN_ENV != null && !KITCHEN_ENV.isEmpty() && KITCHEN_ENV != "") {
                def cleanEnv = KITCHEN_ENV.replaceAll("\\s?SUITE=[^\\s]*", "")
                sh("find . -type f -exec sed -i 's/apt.mirantis.com/apt.mcp.mirantis.net/g' {} \\;")
                sh("find . -type f -exec sed -i 's/apt-mk.mirantis.com/apt.mcp.mirantis.net/g' {} \\;")
                def suite = ruby.getSuiteName(KITCHEN_ENV)
                if (suite && suite != "") {
                  common.infoMsg("Running kitchen test with environment:" + KITCHEN_ENV.trim())
                  ruby.runKitchenTests(cleanEnv, suite)
                } else {
                  common.warningMsg("No SUITE was found. Running with all suites.")
                  ruby.runKitchenTests(cleanEnv, "")
                }
              } else {
                throw new Exception("KITCHEN_ENV parameter is empty or invalid. This may indicate wrong env settings of initial test job or .travis.yml file.")
              }
            } else {
              throw new Exception(".kitchen.yml file not found, no kitchen tests triggered.")
            }
          }
        }
      } catch (Throwable e) {
        // If there was an error or exception thrown, the build failed
        currentBuild.result = "FAILURE"
        ruby.runKitchenCommand("destroy")
        throw e
      } finally {
        if (currentBuild.result == "FAILURE" && fileExists(".kitchen/logs/kitchen.log")) {
          common.errorMsg("----------------KITCHEN LOG:---------------")
          println readFile(".kitchen/logs/kitchen.log")
        }
      }
    }
  }
}
