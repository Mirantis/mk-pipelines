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

def openstack_credentials_id = ''
if (env.OPENSTACK_API_CREDENTIALS) {
  openstack_credentials_id = OPENSTACK_API_CREDENTIALS
}

env.GERRIT_BRANCH = 'master'
if (env.GERRIT_PARENT_BRANCH) {
  env.GERRIT_BRANCH = GERRIT_PARENT_BRANCH
}

def checkouted = false
def openstackTest = false
def travisLess = false      /** TODO: Remove once formulas are witched to new config */
def cleanEnv = ''           /** TODO: Remove once formulas are witched to new config */
def testSuite = ''
envOverrides = []
kitchenFileName = ''

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
          if (fileExists(".travis.yml")) {/** TODO: Remove this legacy block once formulas are witched to new config */
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
                    for (int i = 0; i < kitchenInit.size(); i++) {
                      if (kitchenInit[i].trim().startsWith("if [ ! -e Gemfile ]")) { //found Gemfile config
                        common.infoMsg("Custom Gemfile configuration found, using them")
                        if (openstackTest) {
                          withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: openstack_credentials_id,
                          usernameVariable: 'OS_USERNAME', passwordVariable: 'OS_PASSWORD'], ]) {
                            env.OS_USERNAME = OS_USERNAME
                            env.OS_PASSWORD = OS_PASSWORD
                            env.OS_AUTH_URL = OS_AUTH_URL
                            env.OS_PROJECT_NAME = OS_PROJECT_NAME
                            env.OS_DOMAIN_NAME = OS_DOMAIN_NAME
                            env.OS_AZ = OS_AZ
                          }
                        }
                        ruby.installKitchen(kitchenInit[i].trim())
                        kitchenInstalled = true
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
                  cleanEnv = KITCHEN_ENV.replaceAll("\\s?SUITE=[^\\s]*", "")
                  if (openstackTest) { cleanEnv = "KITCHEN_YAML=.kitchen.openstack.yml " + cleanEnv }
                  sh("grep apt.mirantis.com -Ril | xargs -I{} bash -c \"echo {}; sed -i 's/apt.mirantis.com/apt.mcp.mirantis.net/g' {}\"")
                  sh("grep apt-mk.mirantis.com -Ril | xargs -I{} bash -c \"echo {}; sed -i 's/apt-mk.mirantis.com/apt.mcp.mirantis.net/g' {}\"")
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
            }/** TODO: End of block for removal */
          } else {
            if (checkouted) {
              travisLess = true
              if (fileExists(".kitchen.openstack.yml")) {
                common.infoMsg("Openstack Kitchen test configuration found, running Openstack kitchen tests.")
                kitchenFileName = ".kitchen.openstack.yml"
                envOverrides.add("KITCHEN_YAML=${kitchenFileName}")
                rubyVersion = '2.5.0'
                withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: openstack_credentials_id,
                usernameVariable: 'OS_USERNAME', passwordVariable: 'OS_PASSWORD'], ]) {
                  env.OS_USERNAME = OS_USERNAME
                  env.OS_PASSWORD = OS_PASSWORD
                  env.OS_AUTH_URL = OS_AUTH_URL
                  env.OS_PROJECT_NAME = OS_PROJECT_NAME
                  env.OS_DOMAIN_NAME = OS_DOMAIN_NAME
                  env.OS_AZ = OS_AZ
                }
              } else if (fileExists(".kitchen.yml")) {
                common.infoMsg("Docker Kitchen test configuration found, running Docker kitchen tests.")
                kitchenFileName = ".kitchen.yml"
                rubyVersion = '2.4.1'
              }
              if (kitchenFileName) {
                ruby.ensureRubyEnv(rubyVersion)
                if (!fileExists("Gemfile")) {
                  sh("curl -s -o ./Gemfile 'https://gerrit.mcp.mirantis.com/gitweb?p=salt-formulas/salt-formulas-scripts.git;a=blob_plain;f=Gemfile;hb=refs/heads/master'")
                  ruby.installKitchen()
                } else {
                  common.infoMsg("Override Gemfile found in the kitchen directory, using it.")
                  ruby.installKitchen()
                }
                common.infoMsg("Running part of kitchen test")
                if (KITCHEN_ENV != null && !KITCHEN_ENV.isEmpty() && KITCHEN_ENV != "") {
                  testSuite = KITCHEN_ENV.replaceAll("_", "-").trim()
                  sh("grep apt.mirantis.com -Ril | xargs -I{} bash -c \"echo {}; sed -i 's/apt.mirantis.com/apt.mcp.mirantis.net/g' {}\"")
                  sh("grep apt-mk.mirantis.com -Ril | xargs -I{} bash -c \"echo {}; sed -i 's/apt-mk.mirantis.com/apt.mcp.mirantis.net/g' {}\"")
                  common.infoMsg("Running kitchen test with environment:" + testSuite)
                  ruby.runKitchenTests(envOverrides.join(' '), testSuite)
                } else {
                  throw new Exception("KITCHEN_ENV parameter is empty or invalid. This may indicate wrong env settings of initial test job or .travis.yml file.")
                }
              } else {
                throw new Exception(".kitchen.yml nor .kitchen.openstack.yml file not found, no kitchen tests triggered.")
              }
            }
          }
        }
      } catch (Throwable e) {
        // If there was an error or exception thrown, the build failed
        currentBuild.result = "FAILURE"
        sh(script: 'find .kitchen/logs/ -type f -iname "*.log" | xargs -I{} bash -c "echo {}; cat {}"')
        if (travisLess) {
          ruby.runKitchenCommand("destroy", testSuite)
        } else {
          ruby.runKitchenCommand("destroy", cleanEnv)  /** TODO: Remove once formulas are witched to new config */
        }
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
