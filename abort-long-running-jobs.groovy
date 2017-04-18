/**
 * Long running jobs killer
 *
 *  MAX_DURATION_IN_HOURS - max permitted job duration in hours
 */
common = new com.mirantis.mk.Common()

def MAX_ALLOWED_DURATION_IN_SECONDS = 3600 * Integer.parseInt(MAX_DURATION_IN_HOURS)
node{
  stage("Kill long running jobs"){
    def jobKilled = false
    for (int i=0; i < Jenkins.instance.items.size(); i++) {
      def runningBuilds = _getRunningBuilds(Jenkins.instance.items[i])
      def jobName = Jenkins.instance.items[i].name
      for(int j=0; j < runningBuilds.size(); j++){
        def build = runningBuilds[j]
        int durationInSeconds = (System.currentTimeMillis() - build.getTimeInMillis())/1000.0
        if(durationInSeconds > MAX_ALLOWED_DURATION_IN_SECONDS){
          common.infoMsg("Aborting ${job.name}-${build.id} which is running for ${durationInSeconds}s")
          try{
            build.finish(hudson.model.Result.ABORTED, new java.io.IOException("Aborting build by long running jobs killer"));
          }catch(e){
            common.errorMsg("Error occured during aborting build: Exception: ${e}")
          }
        }
      }
    }
    if(!jobKilled){
      common.infoMsg("No job aborted")
    }
  }
}

@NonCPS
def _getRunningBuilds(job){
  return job.builds.findAll{build -> build.isBuilding()}
}