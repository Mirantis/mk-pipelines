/**
 * Long running jobs killer
 *
 *  MAX_DURATION_IN_HOURS - max permitted job duration in hours
 */
common = new com.mirantis.mk.Common()

node{
  stage("Kill long running jobs"){
    def jobKilled = false
    for (int i=0; i < Jenkins.instance.items.size(); i++) {
      killStuckBuilds(3600 * Integer.parseInt(MAX_DURATION_IN_HOURS), Jenkins.instance.items[i])
    }
  }
}

@NonCPS
def getRunningBuilds(job){
  return job.builds.findAll{build -> build.isBuilding()}
}

@NonCPS
def killStuckBuilds(maxSeconds, job){
    def result = false
    def runningBuilds = getRunningBuilds(job)
      def jobName = job.name
      for(int j=0; j < runningBuilds.size(); j++){
        int durationInSeconds = (System.currentTimeMillis() - runningBuilds[j].getTimeInMillis())/1000.0
        if(durationInSeconds > maxSeconds){
          def buildId = runningBuilds[j].id
          common.infoMsg("Aborting ${jobName}-${buildId} which is running for ${durationInSeconds}s")
          try{
            runningBuilds[j].finish(hudson.model.Result.ABORTED, new java.io.IOException("Aborting build by long running jobs killer"));
          }catch(e){
            common.errorMsg("Error occured during aborting build: Exception: ${e}")
          }
          result = true
        }
      }
      return result
}