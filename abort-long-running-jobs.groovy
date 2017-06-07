/**
 * Long running jobs killer
 *
 *  MAX_DURATION_IN_HOURS - max permitted job duration in hours
 */
common = new com.mirantis.mk.Common()
jenkinsUtils = new com.mirantis.mk.JenkinsUtils()

node{
  stage("Kill long running jobs"){
    for (int i=0; i < Jenkins.instance.items.size(); i++) {
      if(!jenkinsUtils.killStuckBuilds(3600 * Integer.parseInt(MAX_DURATION_IN_HOURS), Jenkins.instance.items[i])){
         common.errorMsg("Kill failed!")
      }
    }
  }
}