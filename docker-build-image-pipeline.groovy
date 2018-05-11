/**
 * Docker image build pipeline
 * IMAGE_NAME - Image name
 * IMAGE_GIT_URL - Image git repo URL
 * IMAGE_BRANCH - Image repo branch
 * IMAGE_CREDENTIALS_ID - Image repo credentials id
 * IMAGE_TAGS - Image tags
 * DOCKERFILE_PATH - Relative path to docker file in image repo
 * REGISTRY_URL - Docker registry URL (can be empty)
 * ARTIFACTORY_URL - URL to artifactory
 * ARTIFACTORY_NAMESPACE - Artifactory namespace (oss, cicd,...)
 * REGISTRY_CREDENTIALS_ID - Docker hub credentials id
 *
**/

def common = new com.mirantis.mk.Common()
def gerrit = new com.mirantis.mk.Gerrit()
def git = new com.mirantis.mk.Git()
def dockerLib = new com.mirantis.mk.Docker()
def artifactory = new com.mirantis.mcp.MCPArtifactory()
timeout(time: 12, unit: 'HOURS') {
  node("docker") {
    def workspace = common.getWorkspace()
    def imageTagsList = IMAGE_TAGS.tokenize(" ")
    try{

      def buildArgs = []
      try {
        buildArgs = IMAGE_BUILD_PARAMS.tokenize(' ')
      } catch (Throwable e) {
        buildArgs = []
      }
      def dockerApp
        stage("checkout") {
           git.checkoutGitRepository('.', IMAGE_GIT_URL, IMAGE_BRANCH, IMAGE_CREDENTIALS_ID)
        }

        if (IMAGE_BRANCH == "master") {
          try {
            def tag = sh(script: "git describe --tags --abbrev=0", returnStdout: true).trim()
            def revision = sh(script: "git describe --tags --abbrev=4 | grep -oP \"^${tag}-\\K.*\" | awk -F\\- '{print \$1}'", returnStdout: true).trim()
            imageTagsList << tag
            revision = revision ? revision : "0"
            if(Integer.valueOf(revision) > 0){
              imageTagsList << "${tag}-${revision}"
            }
            if (!imageTagsList.contains("latest")) {
              imageTagsList << "latest"
            }
          } catch (Exception e) {
            common.infoMsg("Impossible to find any tag")
          }
        }

        stage("build") {
          common.infoMsg("Building docker image ${IMAGE_NAME}")
          dockerApp = dockerLib.buildDockerImage(IMAGE_NAME, "", "${workspace}/${DOCKERFILE_PATH}", imageTagsList[0], buildArgs)
          if(!dockerApp){
            throw new Exception("Docker build image failed")
          }
        }
        stage("upload to docker hub"){
          docker.withRegistry(REGISTRY_URL, REGISTRY_CREDENTIALS_ID) {
            for(int i=0;i<imageTagsList.size();i++){
              common.infoMsg("Uploading image ${IMAGE_NAME} with tag ${imageTagsList[i]} to dockerhub")
              dockerApp.push(imageTagsList[i])
            }
          }
        }
        stage("upload to artifactory"){
          if(common.validInputParam("ARTIFACTORY_URL") && common.validInputParam("ARTIFACTORY_NAMESPACE")) {
             def artifactoryName = "mcp-ci";
             def artifactoryServer = Artifactory.server(artifactoryName)
             def shortImageName = IMAGE_NAME
             if (IMAGE_NAME.contains("/")) {
                shortImageName = IMAGE_NAME.tokenize("/")[1]
             }
             for (imageTag in imageTagsList) {
               sh "docker tag ${IMAGE_NAME} ${ARTIFACTORY_URL}/mirantis/${ARTIFACTORY_NAMESPACE}/${shortImageName}:${imageTag}"
               for(artifactoryRepo in ["docker-dev-local", "docker-prod-local"]){
                 common.infoMsg("Uploading image ${IMAGE_NAME} with tag ${imageTag} to artifactory ${artifactoryName} using repo ${artifactoryRepo}")
                 artifactory.uploadImageToArtifactory(artifactoryServer, ARTIFACTORY_URL,
                                                   "mirantis/${ARTIFACTORY_NAMESPACE}/${shortImageName}",
                                                   imageTag, artifactoryRepo)
               }
             }
          }else{
            common.warningMsg("ARTIFACTORY_URL not given, upload to artifactory skipped")
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

