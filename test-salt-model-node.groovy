
/**
 *  Test salt models pipeline
 *  DEFAULT_GIT_REF
 *  DEFAULT_GIT_URL
 *  CREDENTIALS_ID
 *  EXTRA_FORMULAS
 *  CLUSTER_NAME
 *  NODE_TARGET
 *  SYSTEM_GIT_URL
 *  SYSTEM_GIT_REF
 *  FORMULAS_SOURCE
 *  MAX_CPU_PER_JOB
 *  LEGACY_TEST_MODE
 *  RECLASS_IGNORE_CLASS_NOTFOUND
 *  APT_REPOSITORY
 *  APT_REPOSITORY_GPG
 */

def common = new com.mirantis.mk.Common()
def gerrit = new com.mirantis.mk.Gerrit()
def git = new com.mirantis.mk.Git()
def ssh = new com.mirantis.mk.Ssh()
def saltModelTesting = new com.mirantis.mk.SaltModelTesting()

def defaultGitRef = DEFAULT_GIT_REF
def defaultGitUrl = DEFAULT_GIT_URL

def checkouted = false

throttle(['test-model']) {
  timeout(time: 1, unit: 'HOURS') {
    node("python") {
      try{
        stage("checkout") {
          if(defaultGitRef != "" && defaultGitUrl != "") {
              checkouted = gerrit.gerritPatchsetCheckout(defaultGitUrl, defaultGitRef, "HEAD", CREDENTIALS_ID)
          } else {
            throw new Exception("Cannot checkout gerrit patchset, DEFAULT_GIT_URL or DEFAULT_GIT_REF is null")
          }
          if(checkouted) {
            if (fileExists('classes/system')) {
              if (SYSTEM_GIT_URL == "") {
                ssh.prepareSshAgentKey(CREDENTIALS_ID)
                dir('classes/system') {
                  remoteUrl = git.getGitRemote()
                  ssh.ensureKnownHosts(remoteUrl)
                }
                ssh.agentSh("git submodule init; git submodule sync; git submodule update --recursive")
              } else {
                dir('classes/system') {
                  if (!gerrit.gerritPatchsetCheckout(SYSTEM_GIT_URL, SYSTEM_GIT_REF, "HEAD", CREDENTIALS_ID)) {
                    common.errorMsg("Failed to obtain system reclass with url: ${SYSTEM_GIT_URL} and ${SYSTEM_GIT_REF}")
                  }
                }
              }
            }
          }
        }

        stage("test node") {
          if (checkouted) {
            def workspace = common.getWorkspace()
            common.infoMsg("Running salt model test for node ${NODE_TARGET} in cluster ${CLUSTER_NAME}")
            try {
              saltModelTesting.setupAndTestNode(NODE_TARGET, CLUSTER_NAME, EXTRA_FORMULAS, workspace, FORMULAS_SOURCE, FORMULAS_REVISION, MAX_CPU_PER_JOB.toInteger(), RECLASS_IGNORE_CLASS_NOTFOUND, LEGACY_TEST_MODE, APT_REPOSITORY, APT_REPOSITORY_GPG)
            } catch (Exception e) {
              if (e.getMessage() == "script returned exit code 124") {
                common.errorMsg("Impossible to test node due to timeout of salt-master, ABORTING BUILD")
                currentBuild.result = "ABORTED"
              } else {
                throw e
              }
            }
          }
        }
      } catch (Throwable e) {
         // If there was an error or exception thrown, the build failed
         currentBuild.result = "FAILURE"
         currentBuild.description = currentBuild.description ? e.message + " " + currentBuild.description : e.message
         throw e
      }
    }
  }
}
