/**
 * Docker image build pipeline with push to JFrog
 * IMAGE_NAME - Image name
 * IMAGE_TAGS - Tag list for image, separated by space
 * CREDENTIALS_ID - gerrit credentials id
 * DOCKERFILE_PATH - path to dockerfile in repository
 * DOCKER_REGISTRY - url to registry
 * PROJECT_NAMESPACE - in which namespace will be stored
**/
def artifactory = new com.mirantis.mcp.MCPArtifactory()
def common = new com.mirantis.mk.Common()
def gerrit = new com.mirantis.mk.Gerrit()


node("docker") {
  def artifactoryServer = Artifactory.server("mcp-ci")
  def buildInfo = Artifactory.newBuildInfo()

  def projectNamespace = "mirantis/${PROJECT_NAMESPACE}"

  def dockerRepository = DOCKER_REGISTRY
  def docker_dev_repo = "docker-dev-local"
  def docker_prod_repo = "docker-prod-local"

  def imageTagsList = IMAGE_TAGS.tokenize(" ")
  def workspace = common.getWorkspace()

  gerritChange = gerrit.getGerritChange(GERRIT_NAME, GERRIT_HOST, GERRIT_CHANGE_NUMBER, CREDENTIALS_ID)

  try{
    stage("checkout") {
      gerrit.gerritPatchsetCheckout([
        credentialsId : CREDENTIALS_ID,
        withWipeOut : true,
      ])
    }
    stage("build image"){
      containerId = sh(
        script: "docker build -f ${DOCKERFILE_PATH}/Dockerfile -q --rm . | awk -F':' '{print \$2}'",
        returnStdout: true
      ).trim().take(12)
      imageTagsList << "${GERRIT_CHANGE_NUMBER}_${GERRIT_PATCHSET_NUMBER}"
      for (imageTag in imageTagsList) {
        sh "docker tag ${containerId} ${dockerRepository}/${projectNamespace}/${IMAGE_NAME}:${imageTag}"
      }
    }
    stage("publish image"){
      if (gerritChange.status != "MERGED"){
        for (imageTag in imageTagsList) {
          artifactory.uploadImageToArtifactory(artifactoryServer,
                                               dockerRepository,
                                               "${projectNamespace}/${IMAGE_NAME}",
                                               imageTag,
                                               docker_dev_repo,
                                               buildInfo)
          currentBuild.description = "image: ${IMAGE_NAME}:${imageTag}<br>"
        }
      } else {
        def properties = [
          'com.mirantis.gerritChangeId': "${GERRIT_CHANGE_ID}",
          'com.mirantis.gerritPatchsetNumber': "${GERRIT_PATCHSET_NUMBER}",
          'com.mirantis.gerritChangeNumber' : "${GERRIT_CHANGE_NUMBER}"
        ]
        // Search for an artifact with required properties
        def artifactURI = artifactory.uriByProperties(artifactoryServer.getUrl(),
                                                      properties)
        // Get build info: build id and job name
        if ( artifactURI ) {
          def buildProperties = artifactory.getPropertiesForArtifact(artifactURI)
          //promote docker image
          artifactory.promoteDockerArtifact(artifactoryServer.getUrl(),
                                            docker_dev_repo,
                                            docker_prod_repo,
                                            "${projectNamespace}/${IMAGE_NAME}",
                                            buildProperties.get('com.mirantis.targetTag').join(','),
                                            'latest')
        } else {
          throw new RuntimeException("Artifacts were not found, nothing to promote")
        }
      }
    }
  } catch (Throwable e) {
      currentBuild.result = 'FAILURE'
      common.errorMsg("Build failed due to error: ${e}")
      throw e
  } finally {
      common.sendNotification(currentBuild.result, "",["slack"])
  }
}
