/**
 * Docker image build pipeline
 * IMAGE_NAME - Image name
 * IMAGE_GIT_URL - Image git repo URL
 * IMAGE_BRANCH - Image repo branch
 * IMAGE_CREDENTIALS_ID - Image repo credentials id
 * IMAGE_TAGS - Image tags
 * DOCKERFILE_PATH - Relative path to docker file in image repo
 * REGISTRY_URL - Docker registry URL (can be empty)
 * REGISTRY_CREDENTIALS_ID - Docker hub credentials id
 *
**/

def common = new com.mirantis.mk.Common()
def gerrit = new com.mirantis.mk.Gerrit()
def git = new com.mirantis.mk.Git()
def dockerLib = new com.mirantis.mk.Docker()
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
    docker.withRegistry(REGISTRY_URL, REGISTRY_CREDENTIALS_ID) {
      stage("checkout") {
         git.checkoutGitRepository('.', IMAGE_GIT_URL, IMAGE_BRANCH, IMAGE_CREDENTIALS_ID)
      }
      stage("build") {
        common.infoMsg("Building docker image ${IMAGE_NAME}")
        dockerApp = dockerLib.buildDockerImage(IMAGE_NAME, "", "${workspace}/${DOCKERFILE_PATH}", imageTagsList[0], buildArgs)
        if(!dockerApp){
          throw new Exception("Docker build image failed")
        }
      }
      stage("upload to docker hub"){
        for(int i=0;i<imageTagsList.size();i++){
          common.infoMsg("Uploading image ${IMAGE_NAME} with tag ${imageTagsList[i]}")
          dockerApp.push(imageTagsList[i])
        }
      }
    }
  } catch (Throwable e) {
     // If there was an error or exception thrown, the build failed
     currentBuild.result = "FAILURE"
     currentBuild.description = e.message
     throw e
  } finally {
     common.sendNotification(currentBuild.result,"",["slack"])
  }
}
