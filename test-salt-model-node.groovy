
/**
 *  Test salt models pipeline
 *  DEFAULT_GIT_REF
 *  DEFAULT_GIT_URL
 *  CREDENTIALS_ID
 *  CLUSTER_NAME
 *  NODE_TARGET
 *  SYSTEM_GIT_URL
 *  SYSTEM_GIT_REF
 *  DISTRIB_REVISION of apt mirrror to be used (http://mirror.mirantis.com/DISTRIB_REVISION/ by default)
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

def defaultGitRef = env.DEFAULT_GIT_REF ?: null
def defaultGitUrl = env.DEFAULT_GIT_URL ?: null

def distribRevision = env.DISTRIB_REVISION ?: 'nightly'
def checkouted = false

def nodeLabel = 'old16.04'

throttle(['test-model']) {
  timeout(time: 1, unit: 'HOURS') {
    node(nodeLabel) {
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

            def DockerCName = "${env.JOB_NAME.toLowerCase()}_${env.BUILD_TAG.toLowerCase()}"
            def config = [
              'dockerHostname': NODE_TARGET,
              'clusterName': CLUSTER_NAME,
              'reclassEnv': workspace,
              'distribRevision': distribRevision,
              'dockerMaxCpus': MAX_CPU_PER_JOB.toInteger(),
              'ignoreClassNotfound': RECLASS_IGNORE_CLASS_NOTFOUND,
              'aptRepoUrl': APT_REPOSITORY,
              'aptRepoGPG': APT_REPOSITORY_GPG,
              'dockerContainerName': DockerCName,
              'testContext': 'salt-model-node'
            ]
            saltModelTesting.testNode(config)
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
