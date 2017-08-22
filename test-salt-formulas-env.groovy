/**
 * Test salt formulas pipeline
 *  DEFAULT_GIT_REF
 *  DEFAULT_GIT_URL
 *  CREDENTIALS_ID
 */
def common = new com.mirantis.mk.Common()
def ruby = new com.mirantis.mk.Ruby()

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
      if (defaultGitRef && defaultGitUrl) {
        checkouted = gerrit.gerritPatchsetCheckout(defaultGitUrl, defaultGitRef, "HEAD", CREDENTIALS_ID)
      } else {
        throw new Exception("Cannot checkout gerrit patchset, DEFAULT_GIT_REF is null")
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
          ruby.ensureRubyEnv()
          if (fileExists(".travis.yml")) {
            common.infoMsg(".travis.yml found, running custom kitchen init")
            def kitchenConfigYML = readYaml(file: ".travis.yml")
            def kitchenInit = kitchenConfigYML["install"]
            def kitchenInstalled = false
            if (kitchenInit && !kitchenInit.isEmpty()) {
              for (int i = 0; i < kitchenInit.size(); i++) {
                if (kitchenInit[i].trim().startsWith("test -e Gemfile")) { //found Gemfile config
                  common.infoMsg("Custom Gemfile configuration found, using them")
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
          if (common.validInputParam(KITCHEN_ENV)) {
            def cleanEnv = KITCHEN_ENV.replaceAll("\\s?SUITE=[^\\s]*", "")
            def suitePattern = java.util.regex.Pattern.compile("\\s?SUITE=([^\\s]*)")
            def suiteMatcher = suitePattern.matcher(KITCHEN_ENV)
            if (suiteMatcher.find()) {
              def suite = suiteMatcher.group(1)
              def cleanSuite = suite.replaceAll("_", "-")
              common.infoMsg("Running kitchen test with environment:" + filteredEnvs.trim())
              ruby.runKitchenTests(cleanEnv, cleanSuite)
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
    common.sendNotification(currentBuild.result, "", ["slack"])
  }
}