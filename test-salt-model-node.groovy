
/**
 *  Test salt models pipeline
 *  DEFAULT_GIT_REF
 *  DEFAULT_GIT_URL
 *  CREDENTIALS_ID
 *  EXTRA_FORMULAS
 *  NODE_TARGET
 *  SYSTEM_GIT_URL
 *  SYSTEM_GIT_REF
 *  FORMULAS_SOURCE
 */

def common = new com.mirantis.mk.Common()
def gerrit = new com.mirantis.mk.Gerrit()
def git = new com.mirantis.mk.Git()
def ssh = new com.mirantis.mk.Ssh()
def saltModelTesting = new com.mirantis.mk.SaltModelTesting()

def defaultGitRef = DEFAULT_GIT_REF
def defaultGitUrl = DEFAULT_GIT_URL

def checkouted = false
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
        saltModelTesting.setupAndTestNode(NODE_TARGET, EXTRA_FORMULAS, workspace, FORMULAS_SOURCE, FORMULAS_REVISION)
      }
    }
  } catch (Throwable e) {
     // If there was an error or exception thrown, the build failed
     currentBuild.result = "FAILURE"
     throw e
  }
}
