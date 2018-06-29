def common = new com.mirantis.mk.Common()
def git = new com.mirantis.mk.Git()
stage("Mirror") {
  timeout(time: 12, unit: 'HOURS') {
    node() {
      try{
        def branches = BRANCHES.tokenize(',')
        def pollBranches = []
        for (i=0; i < branches.size(); i++) {
            pollBranches.add([name:branches[i]])
        }
        dir("target") {
          try{
              checkout changelog: true, poll: true,
                scm: [$class: 'GitSCM', branches: pollBranches, doGenerateSubmoduleConfigurations: false,
                extensions: [[$class: 'CleanCheckout']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: CREDENTIALS_ID, url: TARGET_URL]]]
            } catch(hudson.AbortException e){
              if(e.message.trim().equals("Couldn't find any revision to build. Verify the repository and branch configuration for this job.")){
                  common.warningMsg("Warning: Cannot checkout target repo source repo is empty")
              } else {
                  throw e
              }
            }
        }
        dir("source") {
          try{
            checkout changelog: true, poll: true,
              scm: [$class: 'GitSCM', branches: pollBranches, doGenerateSubmoduleConfigurations: false,
              extensions: [[$class: 'CleanCheckout']],  submoduleCfg: [], userRemoteConfigs: [[credentialsId: CREDENTIALS_ID, url: SOURCE_URL]]]
            } catch(hudson.AbortException e){
                if(e.message.trim().equals("Couldn't find any revision to build. Verify the repository and branch configuration for this job.")){
                  common.warningMsg("Warning: Cannot checkout source repo source repo is empty")
                } else {
                    throw e
                }
            }
          git.mirrorGit(SOURCE_URL, TARGET_URL, CREDENTIALS_ID, BRANCHES, true, true, false)
        }
      } catch (Throwable e) {
         // If there was an error or exception thrown, the build failed
         currentBuild.result = "FAILURE"
         currentBuild.description = currentBuild.description ? e.message + " " + currentBuild.description : e.message
         throw e
      } finally {
         if(env.getEnvironment().containsKey("NOTIFICATION_RECIPIENTS")){
           common.sendNotification(currentBuild.result,"",["slack", "email"], ["failed"], env["JOB_NAME"], env["BUILD_NUMBER"], env["BUILD_URL"], "MCP jenkins", env["NOTIFICATION_RECIPIENTS"])
         }else{
           common.sendNotification(currentBuild.result, "", ["slack"])
         }
      }
    }
  }
}
