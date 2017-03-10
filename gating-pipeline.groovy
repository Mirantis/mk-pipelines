/**
 * Gerrit gating pipeline
 * CREDENTIALS_ID - Gerrit credentails ID
 *
**/

def common = new com.mirantis.mk.Common()
def gerrit = new com.mirantis.mk.Gerrit()
def ssh = new com.mirantis.mk.Ssh()
node("python") {
  try{
    stage("test") {
      /*
      wrap([$class: 'AnsiColorBuildWrapper']) {
        build job: "test-${GERRIT_PROJECT}", parameters: [
          [$class: 'StringParameterValue', name: 'GERRIT_BRANCH', value: GERRIT_BRANCH],
          [$class: 'StringParameterValue', name: 'GERRIT_NAME', value: GERRIT_NAME],
          [$class: 'StringParameterValue', name: 'GERRIT_HOST', value: GERRIT_HOST],
          [$class: 'StringParameterValue', name: 'GERRIT_PORT', value: GERRIT_PORT],
          [$class: 'StringParameterValue', name: 'GERRIT_PROJECT', value: GERRIT_PROJECT],
          [$class: 'StringParameterValue', name: 'GERRIT_REFSPEC', value: GERRIT_REFSPEC]
        ]
      }*/
    }
    stage("submit review"){
      ssh.prepareSshAgentKey(CREDENTIALS_ID)
      ssh.ensureKnownHosts(GERRIT_HOST)
      ssh.agentSh(String.format("ssh -p 29418 %s@%s gerrit review --submit %s,%s", GERRIT_NAME, GERRIT_HOST, GERRIT_CHANGE_NUMBER, GERRIT_PATCHSET_NUMBER))
      println(String.format("Gerrit review %s,%s submitted", GERRIT_CHANGE_NUMBER, GERRIT_PATCHSET_NUMBER))
    }
  } catch (Throwable e) {
     // If there was an error or exception thrown, the build failed
     currentBuild.result = "FAILURE"
     throw e
  } finally {
     common.sendNotification(currentBuild.result,"",["slack"])
  }
}