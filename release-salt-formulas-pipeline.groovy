def common = new com.mirantis.mk.Common()
def ssh = new com.mirantis.mk.Ssh()
timeout(time: 12, unit: 'HOURS') {
  node() {
    try{
      stage("checkout") {
        dir("src") {
          ssh.prepareSshAgentKey(CREDENTIALS_ID)
          ssh.ensureKnownHosts(SOURCE_URL)
          git url: SOURCE_URL, branch: "master", credentialsId: CREDENTIALS_ID, poll: false
          sh("git branch --set-upstream-to=origin/master")
          ssh.agentSh("make update")
        }
      }
      stage("tag") {
        dir("src/formulas") {
          sh("for i in *; do cd \$i; git remote | grep gerrit || git remote add gerrit $GERRIT_BASE/\$i; git config user.name Jenkins; git config user.email autobuild@mirantis.com; git tag -m $TAG $TAG; cd ..; done")
        }
      }
      stage("push") {
        dir("src/formulas") {
          ssh.agentSh("mr --trust-all -j4 --force run git push gerrit $TAG")
        }
      }
    } catch (Throwable e) {
       // If there was an error or exception thrown, the build failed
       currentBuild.result = "FAILURE"
       currentBuild.description = currentBuild.description ? e.message + " " + currentBuild.description : e.message
       throw e
    } finally {
       common.sendNotification(currentBuild.result,"",["slack"])
    }
  }
}
